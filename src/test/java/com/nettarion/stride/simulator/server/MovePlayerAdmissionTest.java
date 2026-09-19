package com.nettarion.stride.simulator.server;

import static com.nettarion.stride.simulator.server.ServerTestWorlds.EXTRA;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.HALF_WIDTH;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.IDLE;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.STONE;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.airborneAt;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.assertRaw;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.emptyWorld;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.floor;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.floorWorld;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.packet;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.region;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.view;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.wallWorld;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.CorrectionReason;
import com.nettarion.stride.simulator.DamageWrite;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.MovementPacket;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.Publisher;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.ServerWrite;
import com.nettarion.stride.simulator.StateDigest;
import com.nettarion.stride.simulator.UnpredictedServerWriteException;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.Suffocation;
import com.nettarion.stride.simulator.world.WorldView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The listener's decision on one movement packet, rule by rule from {@code handleMovePlayer} and the server-side
 * {@code Entity.move}: each test sends the packet kind the client's publisher selects and checks what the server's
 * copy and the report hold afterwards.
 */
final class MovePlayerAdmissionTest {
	@Test
	void theComposedPhasesWriteEveryHitOnceAndAgreeWithTheWholeTick() {
		for (boolean sendPosition : new boolean[] {false, true}) {
			for (double fall : new double[] {0.0, 3.5}) {
				PlayerState before = airborneAt(0.5, 0.5, 0.5);
				before.fallDistance = fall;
				PlayerState after = before.copy();
				after.placeAt(0.5, 0.0, 0.5);
				after.onGround = true;
				MovementPacket kind = sendPosition ? packet(before, after) : MovementPacket.NONE;
				SnapshotView world = floorWorld();
				ServerPlayerState reported = ServerPlayerState.atBoundary(before);
				ServerPlayerState composed = reported.copy();
				ServerTick.Transaction transaction = new ServerTick().transact(after, kind, reported, IDLE, world);

				ServerTick tick = new ServerTick();
				List<ServerWrite> writes = new ArrayList<>();
				tick.beginTick(after, composed);
				HurtCause packetHurt =
				    tick.handlePackets(after, kind, composed, IDLE, world, true, true, false, 7, writes::add);
				HurtCause tickHurt = tick.advanceTicks(composed, world, 7, writes::add);

				assertEquals(transaction.hurt().orElse(null), packetHurt != null ? packetHurt : tickHurt);
				assertEquals(transaction.damage().stream().map(hit -> new DamageWrite(7, hit)).toList(), writes);
				assertEquals(StateDigest.server(reported), StateDigest.server(composed));
				if (sendPosition && fall > 0.0) {
					assertFalse(writes.isEmpty(), "the damage case must actually write a hit");
				}
			}
		}
	}

	@Test
	void theComposedStepRefusesACorrectionAndWritesNothingForIt() {
		SnapshotView world = wallWorld();
		PlayerState before = airborneAt(-HALF_WIDTH, 0.0, 0.5);
		before.onGround = true;
		PlayerState after = before.copy();
		after.placeAt(1.0 + HALF_WIDTH, 0.0, 0.5);
		ServerPlayerState reported = ServerPlayerState.atBoundary(before);
		ServerPlayerState composed = reported.copy();
		ServerTick.Corrected corrected = assertInstanceOf(
		    ServerTick.Corrected.class, new ServerTick().transact(after, packet(before, after), reported, IDLE, world));

		ServerTick tick = new ServerTick();
		List<ServerWrite> writes = new ArrayList<>();
		tick.beginTick(after, composed);
		MovementPacket kind = packet(before, after);
		UnpredictedServerWriteException refusal = assertThrows(UnpredictedServerWriteException.class,
		    () -> tick.handlePackets(after, kind, composed, IDLE, world, true, true, false, 7, writes::add));

		assertEquals(RefusalCause.CORRECTED_MOVEMENT, refusal.cause());
		assertEquals(corrected.correction(), refusal.correction());
		assertTrue(writes.isEmpty());
		assertTrue(composed.correctionPending);
	}

	@Test
	void noPacketRunsOnlyTheConnectionTickAndRestoresTheAnchor() {
		PlayerState client = airborneAt(0.5, 5.0, 0.5);
		ServerPlayerState server = ServerPlayerState.atBoundary(client);

		ServerTick.Transaction transaction =
		    new ServerTick().transact(client, MovementPacket.NONE, server, IDLE, emptyWorld());

		assertInstanceOf(ServerTick.Unpublished.class, transaction);
		assertRaw(5.0, server.y, "the connection tick restores the position anchor");
		assertTrue(server.deltaMovementY < 0.0, "the server's own velocity still advanced");
	}

	@Test
	void finiteVerticalResidualIsErasedAndTheSnappedDescentFeedsFall() {
		// One stone block at (0,0,0) above a stone floor whose top is at y=-2.
		SnapshotView world = view(floor(region(), -3, STONE).set(0, 0, 0, STONE));
		PlayerState before = airborneAt(0.5, 1.0, 0.5);
		before.fallDistance = 10.0;
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		PlayerState after = before.copy();
		after.placeAt(0.5, -2.0, 0.5);
		after.onGround = true;

		ServerTick.Transaction transaction =
		    new ServerTick().transact(after, packet(before, after), server, IDLE, world);

		ServerTick.Accepted accepted = assertInstanceOf(ServerTick.Accepted.class, transaction,
		    "a residual of three blocks straight down is erased before the quarter-block test");
		assertRaw(0.0, accepted.residualSquared(), "");
		assertRaw(-2.0, server.y, "the accepted packet snaps to its target");
		assertTrue(server.verticalCollision, "the upper block clipped the collision-resolved move");
		assertTrue(server.verticalCollisionBelow);
		assertTrue(server.onGround, "the packet's ground bit replaces the collision result");
		assertTrue(server.mainSupportingBlockPosPresent);
		assertEquals(-3, server.mainSupportingBlockPosY, "support is recomputed at the snapped lower target");
		assertRaw(-3.0, server.lastKnownClientMovementY, "known movement is the snapped displacement");
		assertRaw(0.0, server.fallDistance, "fall handling consumed the accepted descent");
		assertFalse(server.clientIsFloating);
	}

	@Test
	void bothSignedZeroVerticalRequestsRetainTheServersLatchesAndKeepPositiveZero() {
		for (double targetY : new double[] {-0.0, +0.0}) {
			PlayerState before = airborneAt(0.5, +0.0, 0.5);
			before.verticalCollision = true;
			before.verticalCollisionBelow = true;
			before.fallDistance = 10.0;
			// A signed-zero move is no move to the publisher; the twentieth
			// reminder is what sends this Pos, carrying the -0.0.
			Publisher publisher = Publisher.atBoundary(before);
			publisher.positionReminder = 19;
			ServerPlayerState server = ServerPlayerState.atBoundary(before);
			PlayerState after = before.copy();
			after.placeAt(0.5, targetY, 0.5);
			after.onGround = true;

			ServerTick.Transaction transaction =
			    new ServerTick().transact(after, publisher.publish(after), server, IDLE, floorWorld());

			assertInstanceOf(ServerTick.Accepted.class, transaction);
			assertEquals(0L, Double.doubleToRawLongBits(server.y),
			    "setPosRaw's numeric comparison coalesces a target -0 to the retained +0");
			assertTrue(server.verticalCollision, "Math.abs(-0.0) > 0.0 is false, so the latch survives");
			assertTrue(server.verticalCollisionBelow);
			assertTrue(server.onGround);
			assertFalse(server.clientIsFloating, "floating reads the retained pre-move below latch");
			assertRaw(0.0, server.fallDistance, "packet ground still consumes carried fall");
		}
	}

	@Test
	void statusOnlyStillRunsTheWholeTransactionWithoutMoving() {
		PlayerState before = airborneAt(0.5, 0.0, 0.5);
		before.fallDistance = 2.5;
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		PlayerState after = before.copy();
		after.onGround = true;
		after.horizontalCollision = true;

		ServerPlayerState handled = server.copy();
		ServerTick.Transaction packetOnly =
		    new ServerTick().handleMovePlayer(after, packet(before, after), handled, floorWorld());
		assertInstanceOf(ServerTick.Accepted.class, packetOnly);
		assertTrue(handled.onGround, "the packet's ground bit is installed");
		assertTrue(handled.horizontalCollision, "and its contact bit");

		ServerTick.Transaction transaction =
		    new ServerTick().transact(after, packet(before, after), server, IDLE, floorWorld());

		ServerTick.Accepted accepted = assertInstanceOf(ServerTick.Accepted.class, transaction);
		assertRaw(0.5, accepted.targetX(), "absent coordinates decode to the server's own");
		assertTrue(server.onGround, "the ground bit survives the connection tick's own move on the floor");
		assertFalse(server.horizontalCollision,
		    "the connection tick's own move recomputes the contact bit after the packet installed it");
		assertTrue(server.mainSupportingBlockPosPresent, "support is searched under the unmoved box");
		assertRaw(0.0, server.fallDistance, "packet ground resets the carried fall");
		assertRaw(0.0, server.lastKnownClientMovementZ, "");
	}

	@Test
	void rotationOnlyPacketsReplaceRotationWrappedAndLeavePosition() {
		PlayerState before = airborneAt(0.5, 0.0, 0.5);
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		PlayerState after = before.copy();
		after.yRot = 190.0F;
		after.xRot = -15.0F;

		new ServerTick().transact(after, packet(before, after), server, IDLE, floorWorld());

		assertEquals(-170.0F, server.yRot, "Mth.wrapDegrees");
		assertEquals(-15.0F, server.xRot);
		assertRaw(0.5, server.z, "");
	}

	@Test
	void movedWronglyIsCorrectedFromAFreeStartAndForgivenFromAnOccupiedOne() {
		SnapshotView world = wallWorld();
		PlayerState before = airborneAt(-HALF_WIDTH, 0.0, 0.5);
		before.onGround = true;
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		server.deltaMovementZ = 0.2;
		PlayerState after = before.copy();
		after.placeAt(1.0 + HALF_WIDTH, 0.0, 0.5);

		ServerTick.Transaction corrected = new ServerTick().transact(after, packet(before, after), server, IDLE, world);

		ServerTick.Corrected correction = assertInstanceOf(ServerTick.Corrected.class, corrected);
		assertEquals(CorrectionReason.MOVED_WRONGLY, correction.reason());
		assertRaw(
		    -HALF_WIDTH, correction.correction().resolved().x(), "the wall clipped the resolved move at its face");
		assertTrue(correction.correction().resolved().residualSquared() > 0.0625);
		assertRaw(-HALF_WIDTH, server.x, "the server sent the client back to its pre-packet position");
		assertRaw(0.0, server.deltaMovementZ, "a correction is an absolute destination with zero velocity");
		assertTrue(server.correctionPending);
		// Movement packets only apply rotation until the client acknowledges.
		MovementPacket again = packet(before, after);
		PendingServerWriteException pending = assertThrows(
		    PendingServerWriteException.class, () -> new ServerTick().transact(after, again, server, IDLE, world));
		assertEquals(RefusalCause.PENDING_WRITE, pending.cause());

		// Embedded in one block with a second block ahead: the server's move is
		// clipped by the second block, the residual exceeds the quarter block,
		// but the pre-packet box was already occupied and the target beyond the
		// wall meets no block at all, so vanilla accepts the through-wall target.
		PlayerState occupied = airborneAt(0.5, 5.0, 1.5);
		ServerPlayerState occupiedServer = ServerPlayerState.atBoundary(occupied);
		PlayerState beyondTheWall = occupied.copy();
		beyondTheWall.placeAt(0.5, 5.0, 3.5);

		ServerTick.Transaction forgiven = new ServerTick().transact(
		    beyondTheWall, packet(occupied, beyondTheWall), occupiedServer, IDLE, embeddedStartWorld());

		ServerTick.Accepted accepted = assertInstanceOf(
		    ServerTick.Accepted.class, forgiven, "a start box already inside a block forgives the residual");
		assertTrue(accepted.forgivenByOccupiedStart());
		assertTrue(accepted.residualSquared() > 0.0625);
		assertRaw(3.5, occupiedServer.z, "");
	}

	@Test
	void aTargetMeetingANewBlockIsCorrectedAtTheFloatDeflatedEdge() {
		SnapshotView world = wallWorld();
		double exactTargetX = -HALF_WIDTH + (double) 1.0E-5F;
		PlayerState before = airborneAt(-1.0, 0.0, 0.5);

		ServerTick.Transaction exact = transactTo(before, exactTargetX, world);
		assertInstanceOf(ServerTick.Accepted.class, exact, "the deflated target box stops exactly at the wall face");

		ServerTick.Transaction above = transactTo(before, Math.nextUp(exactTargetX), world);
		ServerTick.Corrected corrected = assertInstanceOf(ServerTick.Corrected.class, above);
		assertEquals(CorrectionReason.NEW_COLLISION, corrected.reason());
		assertTrue(corrected.correction().resolved().residualSquared() < 0.0625,
		    "the residual alone would have been accepted; the new block decides");
	}

	@Test
	void aShapeTheOldBoxAlreadyMeetsIsNotNewWhateverItsOtherBoxesDo() {
		// Shapes.joinIsNotEmpty(shape, oldShape, AND) joins the whole stair
		// shape: the feet inside its lower slab meet it before and after a
		// sideways move, and the upper step the box never reaches does not
		// make it new.
		SnapshotView world = stairsWorld();
		PlayerState before = airborneAt(0.5, 0.4, 0.15);
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		PlayerState after = before.copy();
		after.placeAt(0.55, 0.4, 0.15);

		ServerTick.Transaction inside = new ServerTick().transact(after, packet(before, after), server, IDLE, world);

		assertInstanceOf(
		    ServerTick.Accepted.class, inside, "the lower slab met the old box, so the stair shape is not new");
		assertRaw(0.55, server.x, "");

		// The same stair from above its slab: the target box reaches the slab
		// the old box did not, and the whole shape is new.
		PlayerState above = airborneAt(0.5, 0.6, 0.15);
		ServerPlayerState aboveServer = ServerPlayerState.atBoundary(above);
		PlayerState ontoTheSlab = above.copy();
		ontoTheSlab.placeAt(0.5, 0.4, 0.15);

		ServerTick.Corrected corrected = assertInstanceOf(ServerTick.Corrected.class,
		    new ServerTick().transact(ontoTheSlab, packet(above, ontoTheSlab), aboveServer, IDLE, world));
		assertEquals(CorrectionReason.NEW_COLLISION, corrected.reason());
	}

	@Test
	void powderSnowAndScaffoldingNeverCountAsNewCollisions() {
		// getPreMoveCollisions queries under CollisionContext.withPosition, a
		// placement context, and both blocks answer an empty shape to one. A
		// client that crouched into the top of the block before the server's
		// shift flag caught up is clipped by the server's own move (residual
		// under the quarter block) and then accepted at its sunken target.
		for (boolean scaffolding : new boolean[] {true, false}) {
			SnapshotView world = scaffolding ? scaffoldingWorld() : LandingTest.powderSnowWorld();
			PlayerState before = airborneAt(0.5, 1.0, 0.5);
			before.onGround = true;
			before.canWalkOnPowderSnow = !scaffolding;
			ServerPlayerState server = ServerPlayerState.atBoundary(before);
			PlayerState after = before.copy();
			after.placeAt(0.5, 0.92, 0.5);
			after.onGround = false;

			ServerTick.Transaction sunk = new ServerTick().transact(after, packet(before, after), server, IDLE, world);

			ServerTick.Accepted accepted =
			    assertInstanceOf(ServerTick.Accepted.class, sunk, scaffolding ? "scaffolding" : "powder snow");
			assertRaw(0.0, accepted.residualSquared(),
			    "the server's own move was clipped at the top of the block, and a"
			        + " vertical residual is erased before the moved-wrongly test");
			assertRaw(0.92, server.y, "the accepted target is inside the block");
		}
	}

	@Test
	void theRisingGroundJumpIsReappliedWithTheServersSprintFlagAndYaw() {
		PlayerState before = airborneAt(0.5, 0.0, 0.5);
		before.onGround = true;
		before.yRot = 90.0F;
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		PlayerState after = before.copy();
		after.placeAt(0.5, 0.42, 0.5);
		after.onGround = false;
		after.setSprinting(true);
		after.yRot = 0.0F;

		new ServerTick().transact(after, packet(before, after), server, IDLE, floorWorld());

		assertTrue(server.deltaMovementY > 0.0, "the server re-applied the jump before resolving the packet");
		assertTrue(server.sprinting, "the sprint command precedes the movement packet");
		assertTrue(
		    server.deltaMovementX < 0.0, "the sprint boost used the server's pre-packet yaw of 90, which faces -X");
		assertEquals(0.0F, server.yRot, "and the packet's rotation was installed afterwards");
	}

	private static ServerTick.Transaction transactTo(
	    final PlayerState before, final double targetX, final SnapshotView world) {
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		PlayerState after = before.copy();
		after.placeAt(targetX, before.y, before.z);
		after.onGround = true;
		after.horizontalCollision = true;
		return new ServerTick().transact(after, packet(before, after), server, IDLE, world);
	}

	/**
	 * One stair at (0,0,0) over a stone floor whose top is at y=-2: a lower slab across the cell and an upper step
	 * over its z in [0.5, 1].
	 */
	private static SnapshotView stairsWorld() {
		BlockEntry stairs =
		    BlockEntry.builder(EXTRA, "minecraft:stone_stairs")
		        .suffocation(Suffocation.NO)
		        .boxes(new ShapeBox(0.0, 0.0, 0.0, 1.0, 0.5, 1.0), new ShapeBox(0.0, 0.5, 0.5, 1.0, 1.0, 1.0))
		        .build();
		return view(floor(region(stairs), -3, STONE).set(0, 0, 0, EXTRA));
	}

	/** One supported scaffolding at (0,0,0) over a stone floor whose top is at y=0. */
	private static SnapshotView scaffoldingWorld() {
		BlockEntry scaffolding = BlockEntry.builder(EXTRA, "minecraft:scaffolding")
		                             .fallDistanceResetting(true)
		                             .collisionBehavior(WorldView.CollisionBehavior.SCAFFOLDING_SUPPORTED)
		                             .suffocation(Suffocation.NO)
		                             .climbability(WorldView.Climbability.CLIMBABLE)
		                             .boxes(new ShapeBox(0, 0.875, 0, 1, 1, 1), new ShapeBox(0, 0, 0, 0.125, 1, 0.125),
		                                 new ShapeBox(0.875, 0, 0, 1, 1, 0.125), new ShapeBox(0, 0, 0.875, 0.125, 1, 1),
		                                 new ShapeBox(0.875, 0, 0.875, 1, 1, 1))
		                             .build();
		return view(floor(region(scaffolding), STONE).set(0, 0, 0, EXTRA));
	}

	/** No floor; stone at z=1 and z=2, y=5 and y=6, for a player embedded at z=1.5. */
	private static SnapshotView embeddedStartWorld() {
		var world = region();
		for (int x = -4; x <= 4; x++) {
			world.set(x, 5, 1, STONE);
			world.set(x, 6, 1, STONE);
			world.set(x, 5, 2, STONE);
			world.set(x, 6, 2, STONE);
		}
		return view(world);
	}
}
