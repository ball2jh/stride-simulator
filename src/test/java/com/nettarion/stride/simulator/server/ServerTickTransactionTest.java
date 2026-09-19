package com.nettarion.stride.simulator.server;

import com.nettarion.stride.simulator.DamageWrite;
import com.nettarion.stride.simulator.EntityDataWrite;
import com.nettarion.stride.simulator.ServerWrite;
import com.nettarion.stride.simulator.CorrectionReason;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.MovementPacket;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.Publisher;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.StateDigest;
import com.nettarion.stride.simulator.UnpredictedServerWriteException;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.Suffocation;
import com.nettarion.stride.simulator.world.FluidKind;
import com.nettarion.stride.simulator.world.FluidEntry;

/**
 * The listener's transaction on the server's copy, rule by rule from
 * {@code ServerMovementListener.handleMovePlayer} and the server-side
 * {@code Entity.move}.
 *
 * <p>Each test sends the packet form the client's publisher selects and
 * checks what the server's copy and listener hold afterwards. {@code ListenerTransactionParityVanillaTest}
 * runs the same transaction beside a real listener for a whole plan.
 */
class ServerTickTransactionTest {
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);
	private static final double HALF_WIDTH = (double) 0.6F / 2.0;

	@Test
	void effectEmissionMatchesReportedSuccessIncludingDamageAndNoPacket() {
		for (boolean sendPosition : new boolean[] {false, true}) {
			for (double fall : new double[] {0.0, 3.5}) {
				PlayerState before = airborneAt(0.5, 0.5, 0.5);
				before.fallDistance = fall;
				PlayerState after = before.copy();
				after.placeAt(0.5, 0.0, 0.5);
				after.onGround = true;
				MovementPacket packet = sendPosition ? packet(before, after) : MovementPacket.NONE;
				SnapshotView world = new SnapshotView(floorWorld());
				ServerPlayerState reported = ServerPlayerState.atBoundary(before);
				ServerPlayerState emitted = reported.copy();
				ServerTick.Transaction transaction =
				    new ServerTick().transact(after, packet, reported, IDLE, world, false);
				List<ServerWrite> writes = new ArrayList<>();
				HurtCause hurt =
				    new ServerTick().advanceEffects(after, packet, emitted, IDLE, world, true, true, 7, writes::add);
				assertEquals(transaction.hurt().orElse(null), hurt);
				assertEquals(transaction.damage().stream().map(hit -> new DamageWrite(7, hit)).toList(), writes);
				assertEquals(StateDigest.server(reported), StateDigest.server(emitted));
				if (sendPosition && fall > 0.0) {
					assertFalse(writes.isEmpty(), "the damage case must actually emit a hit");
				}
			}
		}
	}

	@Test
	void effectEmissionPreservesCorrectionAndEmitsNothingAfterRefusal() {
		SnapshotView world = new SnapshotView(wallWorld());
		PlayerState before = airborneAt(-HALF_WIDTH, 0.0, 0.5);
		before.onGround = true;
		PlayerState after = before.copy();
		after.placeAt(1.0 + HALF_WIDTH, 0.0, 0.5);
		ServerPlayerState reported = ServerPlayerState.atBoundary(before);
		ServerPlayerState emitted = reported.copy();
		assertInstanceOf(ServerTick.Corrected.class,
		    new ServerTick().transact(after, packet(before, after), reported, IDLE, world, false));
		List<ServerWrite> writes = new ArrayList<>();
		assertThrows(UnpredictedServerWriteException.class,
		    ()
		        -> new ServerTick().advanceEffects(
		            after, packet(before, after), emitted, IDLE, world, true, true, 7, writes::add));
		assertTrue(writes.isEmpty());
		assertEquals(StateDigest.server(reported), StateDigest.server(emitted));
	}

	@Test
	void noPacketRunsOnlyTheConnectionTickAndRestoresTheAnchor() {
		PlayerState client = airborneAt(0.5, 5.0, 0.5);
		ServerPlayerState server = ServerPlayerState.atBoundary(client);

		ServerTick.Transaction transaction =
		    new ServerTick().transact(client, MovementPacket.NONE, server, IDLE, emptyWorld(), false);

		assertInstanceOf(ServerTick.Unpublished.class, transaction);
		assertRaw(5.0, server.y, "the connection tick restores the position anchor");
		assertTrue(server.deltaMovementY < 0.0, "the server's own velocity still advanced");
	}

	@Test
	void finiteVerticalResidualIsErasedAndTheSnappedDescentFeedsFall() {
		SnapshotView world = new SnapshotView(verticalResidualFallWorld());
		PlayerState before = airborneAt(0.5, 1.0, 0.5);
		before.fallDistance = 10.0;
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		PlayerState after = before.copy();
		after.placeAt(0.5, -2.0, 0.5);
		after.onGround = true;

		ServerTick.Transaction transaction =
		    new ServerTick().transact(after, packet(before, after), server, IDLE, world, false);

		ServerTick.Accepted accepted = assertInstanceOf(ServerTick.Accepted.class, transaction,
		    "a residual of three blocks straight down is erased before the quarter-block test");
		assertRaw(0.0, accepted.residualSquared(), "");
		assertRaw(-2.0, server.y, "the accepted packet snaps to its target");
		assertTrue(server.verticalCollision, "the upper block clipped the collision-resolved move");
		assertTrue(server.verticalCollisionBelow);
		assertTrue(server.onGround, "the packet's ground bit replaces the collision result");
		assertTrue(server.mainSupportingBlockPosPresent && server.mainSupportingBlockPosY == -3,
		    "support is recomputed at the snapped lower target");
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

			ServerTick.Transaction transaction = new ServerTick().transact(
			    after, publisher.publish(after), server, IDLE, new SnapshotView(floorWorld()), false);

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
		    new ServerTick().handleMovePlayer(after, packet(before, after), handled, new SnapshotView(floorWorld()));
		assertInstanceOf(ServerTick.Accepted.class, packetOnly);
		assertTrue(handled.onGround, "the packet's ground bit is installed");
		assertTrue(handled.horizontalCollision, "and its contact bit");

		ServerTick.Transaction transaction = new ServerTick().transact(
		    after, packet(before, after), server, IDLE, new SnapshotView(floorWorld()), false);

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

		new ServerTick().transact(after, packet(before, after), server, IDLE, new SnapshotView(floorWorld()), false);

		assertEquals(-170.0F, server.yRot, "Mth.wrapDegrees");
		assertEquals(-15.0F, server.xRot);
		assertRaw(0.5, server.z, "");
	}

	@Test
	void movedWronglyIsCorrectedFromAFreeStartAndForgivenFromAnOccupiedOne() {
		SnapshotView world = new SnapshotView(wallWorld());
		PlayerState before = airborneAt(-HALF_WIDTH, 0.0, 0.5);
		before.onGround = true;
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		server.deltaMovementZ = 0.2;
		PlayerState after = before.copy();
		after.placeAt(1.0 + HALF_WIDTH, 0.0, 0.5);

		ServerTick.Transaction corrected =
		    new ServerTick().transact(after, packet(before, after), server, IDLE, world, false);

		ServerTick.Corrected correction = assertInstanceOf(ServerTick.Corrected.class, corrected);
		assertEquals(CorrectionReason.MOVED_WRONGLY, correction.reason());
		assertRaw(-HALF_WIDTH, correction.resolvedX(), "the wall clipped the resolved move at its face");
		assertTrue(correction.residualSquared() > 0.0625);
		assertRaw(-HALF_WIDTH, server.x, "the server sent the client back to its pre-packet position");
		assertRaw(0.0, server.deltaMovementZ, "a correction is an absolute destination with zero velocity");
		assertTrue(server.correctionPending);
		assertThrows(PendingServerWriteException.class,
		    ()
		        -> new ServerTick().transact(after, packet(before, after), server, IDLE, world, false),
		    "movement packets only apply rotation until the client acknowledges");

		// Embedded in one block with a second block ahead: the server's move is
		// clipped by the second block, the residual exceeds the quarter block,
		// but the pre-packet box was already occupied and the target beyond the
		// wall meets no block at all, so vanilla accepts the through-wall target.
		PlayerState occupied = airborneAt(0.5, 5.0, 1.5);
		ServerPlayerState occupiedServer = ServerPlayerState.atBoundary(occupied);
		PlayerState beyondTheWall = occupied.copy();
		beyondTheWall.placeAt(0.5, 5.0, 3.5);

		ServerTick.Transaction forgiven = new ServerTick().transact(beyondTheWall, packet(occupied, beyondTheWall),
		    occupiedServer, IDLE, new SnapshotView(embeddedStartWorld()), false);

		ServerTick.Accepted accepted = assertInstanceOf(
		    ServerTick.Accepted.class, forgiven, "a start box already inside a block forgives the residual");
		assertTrue(accepted.forgivenByOccupiedStart());
		assertTrue(accepted.residualSquared() > 0.0625);
		assertRaw(3.5, occupiedServer.z, "");
	}

	@Test
	void aTargetMeetingANewBlockIsCorrectedAtTheFloatDeflatedEdge() {
		SnapshotView world = new SnapshotView(wallWorld());
		double exactTargetX = -HALF_WIDTH + (double) 1.0E-5F;
		PlayerState before = airborneAt(-1.0, 0.0, 0.5);

		ServerTick.Transaction exact = transactTo(before, exactTargetX, world);
		assertInstanceOf(ServerTick.Accepted.class, exact, "the deflated target box stops exactly at the wall face");

		ServerTick.Transaction above = transactTo(before, Math.nextUp(exactTargetX), world);
		ServerTick.Corrected corrected = assertInstanceOf(ServerTick.Corrected.class, above);
		assertEquals(CorrectionReason.NEW_COLLISION, corrected.reason());
		assertTrue(
		    corrected.residualSquared() < 0.0625, "the residual alone would have been accepted; the new block decides");
	}

	@Test
	void aShapeTheOldBoxAlreadyMeetsIsNotNewWhateverItsOtherBoxesDo() {
		// Shapes.joinIsNotEmpty(shape, oldShape, AND) joins the whole stair
		// shape: the feet inside its lower slab meet it before and after a
		// sideways move, and the upper step the box never reaches does not
		// make it new.
		SnapshotView world = new SnapshotView(stairsWorld());
		PlayerState before = airborneAt(0.5, 0.4, 0.15);
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		PlayerState after = before.copy();
		after.placeAt(0.55, 0.4, 0.15);

		ServerTick.Transaction inside =
		    new ServerTick().transact(after, packet(before, after), server, IDLE, world, false);

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
		    new ServerTick().transact(ontoTheSlab, packet(above, ontoTheSlab), aboveServer, IDLE, world, false));
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
			SnapshotView world = new SnapshotView(scaffolding ? scaffoldingWorld() : powderSnowWorld());
			PlayerState before = airborneAt(0.5, 1.0, 0.5);
			before.onGround = true;
			before.canWalkOnPowderSnow = !scaffolding;
			ServerPlayerState server = ServerPlayerState.atBoundary(before);
			PlayerState after = before.copy();
			after.placeAt(0.5, 0.92, 0.5);
			after.onGround = false;

			ServerTick.Transaction sunk =
			    new ServerTick().transact(after, packet(before, after), server, IDLE, world, false);

			ServerTick.Accepted accepted =
			    assertInstanceOf(ServerTick.Accepted.class, sunk, scaffolding ? "scaffolding" : "powder snow");
			assertRaw(0.0, accepted.residualSquared(),
			    "the server's own move was clipped at the top of the block, and a"
			        + " vertical residual is erased before the moved-wrongly test");
			assertRaw(0.92, server.y, "the accepted target is inside the block");
		}
	}

	@Test
	void startFallFlyingOnAGlidingCopyEndsTheGlide() {
		// Player.tryToStartFallFlying refuses while a glide is under way, so
		// handlePlayerCommand's START_FALL_FLYING falls through to
		// stopFallFlying's pulse, which is a dirty flags entry.
		ServerPlayerState gliding = ServerPlayerState.atBoundary(gliding(0.5, 10.0, 0.5, 1.0));
		gliding.entityDataDirty = 0;
		new ServerTick().handleStartFallFlying(gliding);
		assertFalse(gliding.fallFlying);
		assertEquals(EntityDataWrite.FLAGS, gliding.entityDataDirty & EntityDataWrite.FLAGS);

		ServerPlayerState airborne = ServerPlayerState.atBoundary(airborneAt(0.5, 10.0, 0.5));
		airborne.gliderUsable = true;
		new ServerTick().handleStartFallFlying(airborne);
		assertTrue(airborne.fallFlying, "a copy that may glide starts");
	}

	@Test
	void floatingNeedsAirAroundAndTheCounterAdvancesEveryConnectionTick() {
		SnapshotView world = emptyWorld();
		PlayerState before = airborneAt(0.5, 10.0, 0.5);
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		PlayerState after = before.copy();
		after.placeAt(0.5, 10.0 - 0.03125, 0.5);

		ServerTick tick = new ServerTick();
		ServerTick.Accepted accepted = assertInstanceOf(
		    ServerTick.Accepted.class, tick.transact(after, packet(before, after), server, IDLE, world, false));
		assertTrue(accepted.clientIsFloating(), "-1/32 requested is inclusive");

		PlayerState falling = before.copy();
		falling.placeAt(0.5, Math.nextDown(10.0 - 0.03125), 0.5);
		ServerPlayerState fallingServer = ServerPlayerState.atBoundary(before);
		assertFalse(assertInstanceOf(ServerTick.Accepted.class,
		    tick.transact(falling, packet(before, falling), fallingServer, IDLE, world, false))
		        .clientIsFloating());

		// The connection tick of the server tick that accepted the packet is
		// the first floating tick. Standing still: the publisher is silent
		// except for the reminder's forced Pos every twentieth tick, which is
		// accepted and floating again.
		assertEquals(1, server.aboveGroundTickCount);
		Publisher publisher = Publisher.atBoundary(after);
		for (int connectionTick = 2; connectionTick <= 80; connectionTick++) {
			tick.transact(after, publisher.publish(after), server, IDLE, world, false);
			assertEquals(connectionTick, server.aboveGroundTickCount);
		}
		assertThrows(PendingServerWriteException.class,
		    ()
		        -> tick.transact(after, publisher.publish(after), server, IDLE, world, false),
		    "the eighty-first floating connection tick is the kick");
	}

	@Test
	void anUndecidableFloatingLatchIsHeldFalseAndOnlyTheKickIsRefused() {
		// The player's box stays inside the region (x from -3.98) but the air
		// box inflated by 0.0625 reaches x=-4.0425, one cell past its edge, so
		// floating cannot be decided; the latch stays false, the counter bounds
		// it from above, and only the eighty-first tick refuses, as undetermined
		// rather than as a kick.
		SnapshotView world = emptyWorld();
		PlayerState before = airborneAt(-3.68, 10.0, 0.5);
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		PlayerState after = before.copy();
		after.placeAt(-3.68, 10.0 - 0.03125, 0.5);

		ServerTick tick = new ServerTick();
		ServerTick.Accepted accepted = assertInstanceOf(
		    ServerTick.Accepted.class, tick.transact(after, packet(before, after), server, IDLE, world, false));
		assertFalse(accepted.clientIsFloating());
		assertTrue(accepted.floatingUnknown());
		assertTrue(server.floatingUnknown);

		assertEquals(1, server.aboveGroundTickCount);
		Publisher publisher = Publisher.atBoundary(after);
		for (int connectionTick = 2; connectionTick <= 80; connectionTick++) {
			tick.transact(after, publisher.publish(after), server, IDLE, world, false);
			assertEquals(connectionTick, server.aboveGroundTickCount);
		}
		PendingServerWriteException refusal = assertThrows(PendingServerWriteException.class,
		    () -> tick.transact(after, publisher.publish(after), server, IDLE, world, false));
		assertTrue(refusal.getMessage().contains("cannot be decided"), refusal.getMessage());

		// A known non-air cell decides NOT_AIR whatever else is unknown.
		// Feet 0.3 above the floor: the box extended 0.55 down reaches the stone.
		PlayerState nearStone = airborneAt(3.9, 0.3, 0.5);
		ServerPlayerState nearStoneServer = ServerPlayerState.atBoundary(nearStone);
		PlayerState nearStoneAfter = nearStone.copy();
		nearStoneAfter.placeAt(3.9, 0.3 - 0.03125, 0.5);
		ServerTick.Accepted decided = assertInstanceOf(ServerTick.Accepted.class,
		    tick.transact(nearStoneAfter, packet(nearStone, nearStoneAfter), nearStoneServer, IDLE,
		        new SnapshotView(floorWorld()), false));
		assertFalse(decided.clientIsFloating());
		assertFalse(decided.floatingUnknown(), "the floor under the box is known not to be air");
	}

	@Test
	void theRisingGroundJumpIsReappliedWithTheServersSprintFlagAndYaw() {
		SnapshotView world = new SnapshotView(floorWorld());
		PlayerState before = airborneAt(0.5, 0.0, 0.5);
		before.onGround = true;
		before.yRot = 90.0F;
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		PlayerState after = before.copy();
		after.placeAt(0.5, 0.42, 0.5);
		after.onGround = false;
		after.setSprinting(true);
		after.yRot = 0.0F;

		new ServerTick().transact(after, packet(before, after), server, IDLE, world, false);

		assertTrue(server.deltaMovementY > 0.0, "the server re-applied the jump before resolving the packet");
		assertTrue(server.sprinting, "the sprint command precedes the movement packet");
		assertTrue(Math.abs(server.deltaMovementX) > 0.0 && server.deltaMovementX < 0.0,
		    "the sprint boost used the server's pre-packet yaw of 90, which faces -X");
		assertEquals(0.0F, server.yRot, "and the packet's rotation was installed afterwards");
	}

	private static ServerTick.Transaction transactTo(
	    final PlayerState before, final double targetX, final SnapshotView world) {
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		PlayerState after = before.copy();
		after.placeAt(targetX, before.y, before.z);
		after.onGround = true;
		after.horizontalCollision = true;
		return new ServerTick().transact(after, packet(before, after), server, IDLE, world, false);
	}

	/** The packet the client tick would send for {@code after} from a boundary at {@code before}. */
	private static MovementPacket packet(final PlayerState before, final PlayerState after) {
		return Publisher.atBoundary(before).publish(after);
	}

	@Test
	void aLandingHurtsFromAFullBlockPastTheSafeDistanceNotBefore() {
		// LivingEntity.calculateFallDamage: floor(fall + 1e-6 - 3) > 0, on the
		// fall the packet's own descent completes, in creative never.
		assertEquals(
		    Optional.empty(), landingHurt(3.4, 0.5, false), "3.9 blocks is under a full block past the safe distance");
		assertEquals(
		    Optional.of(HurtCause.FALL), landingHurt(3.5, 0.5, false), "4.0 blocks is the first damaging fall");
		assertEquals(Optional.empty(), landingHurt(3.5, 0.5, true),
		    "Player.causeFallDamage returns before hurting a player who may fly");
	}

	@Test
	void aDisabledFallDamageGameruleDropsTheLandingHitBeforeTheCooldown() {
		// Player.isInvulnerableTo answers the fall gamerule before any other gate.
		assertEquals(Optional.empty(), landingHurt(3.5, 0.5, false, false), "the fall gamerule drops the hit");
		assertEquals(Optional.of(HurtCause.FALL), landingHurt(3.5, 0.5, false, true));
	}

	@Test
	void aDescentEndingInWaterRefreshesTheTrackerBeforeTheLandingIsJudged() {
		// LivingEntity.checkFallDamage refreshes the fluid tracker when the copy
		// was dry, so a fall that ends on the floor of a pool resets first.
		PlayerState before = airborneAt(0.5, 8.0, 0.5);
		before.fallDistance = 4.0;
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		PlayerState after = before.copy();
		after.placeAt(0.5, 0.0, 0.5);
		after.onGround = true;

		ServerTick.Transaction transaction =
		    new ServerTick().transact(after, packet(before, after), server, IDLE, new SnapshotView(poolWorld()), false);

		assertInstanceOf(ServerTick.Accepted.class, transaction);
		assertTrue(server.waterHeight > 0.0, "the landed copy stands in the pool");
		assertEquals(Optional.empty(), transaction.hurt(), "water resets the fall before the ground test");
		assertRaw(0.0, server.fallDistance, "");
		assertEquals(20.0F, server.health);
	}

	@Test
	void aCorrectionSearchesSupportWithThePacketsGroundBitNotTheServers() {
		// The teleport path passes packet.isOnGround() to checkSupportingBlock
		// without installing it; the server's own ground flag stays true here.
		SnapshotView world = new SnapshotView(wallWorld());
		PlayerState before = airborneAt(-HALF_WIDTH, 0.0, 0.5);
		before.onGround = true;
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		server.mainSupportingBlockPosPresent = true;
		server.mainSupportingBlockPosY = -1;
		PlayerState after = before.copy();
		after.placeAt(1.0 + HALF_WIDTH, 0.0, 0.5);
		after.onGround = false;

		ServerTick.Transaction corrected =
		    new ServerTick().transact(after, packet(before, after), server, IDLE, world, false);

		assertInstanceOf(ServerTick.Corrected.class, corrected);
		assertTrue(server.onGround, "the server's own flag is untouched");
		assertFalse(server.mainSupportingBlockPosPresent, "the packet's airborne bit clears the support");
		assertFalse(server.onGroundNoBlocks);
	}

	@Test
	void aSlimeLandingSettlesTheImpulseContextUnlessSneaking() {
		// SlimeBlock.fallOn calls causeFallDamage at multiplier zero unless the
		// player suppresses the bounce; the call never hits but a landing above
		// the impact height resets the wind-charge context outright, where the
		// accepted packet's own reset still defers to the grace period.
		ServerPlayerState landed = landOnSlime(false);
		assertFalse(landed.currentImpulseImpactPosPresent, "the context is settled by the landing");
		assertEquals(20.0F, landed.health);
		ServerPlayerState sneaking = landOnSlime(true);
		assertTrue(sneaking.currentImpulseImpactPosPresent, "sneaking skips the slime's fall handling");
	}

	@Test
	void aCobwebVisitSettlesTheImpulseContextEvenWhileFlying() {
		// Player.makeStuckInBlock resets the context after its flying gate, so a
		// flying player keeps its speed but still loses the context.
		for (boolean flying : new boolean[] {false, true}) {
			PlayerState before = airborneAt(0.5, 0.0, 0.5);
			before.mayfly = flying;
			before.flying = flying;
			ServerPlayerState server = ServerPlayerState.atBoundary(before);
			server.setIgnoreFallDamageFromCurrentImpulse(true, 0.5, 6.0, 0.5);
			server.currentImpulseContextResetGraceTime = 0;
			assertTrue(server.currentImpulseImpactPosPresent);
			new ServerTick().transact(
			    before.copy(), MovementPacket.NONE, server, IDLE, new SnapshotView(cobwebWorld()), false);
			assertFalse(server.currentImpulseImpactPosPresent, "flying " + flying);
			assertEquals(flying, server.stuckSpeedMultiplierX == 0.0, "the vector stays behind the flying gate");
		}
	}

	@Test
	void aPowderSnowVisitSettlesTheImpulseContextEvenWhileFlying() {
		// PowderSnowBlock.entityInside reaches the same Player.makeStuckInBlock
		// as the cobweb once the feet cell is powder snow: the vector and the
		// fall reset stay behind the flying gate, the impulse-context reset does
		// not. A bootless player in powder snow never lands, so no fall handling
		// would otherwise settle the context.
		for (boolean flying : new boolean[] {false, true}) {
			PlayerState before = airborneAt(0.5, 0.0, 0.5);
			before.mayfly = flying;
			before.flying = flying;
			before.fallDistance = 1.0;
			ServerPlayerState server = ServerPlayerState.atBoundary(before);
			server.setIgnoreFallDamageFromCurrentImpulse(true, 0.5, 6.0, 0.5);
			server.currentImpulseContextResetGraceTime = 0;
			server.tickCount = 1L;
			assertTrue(server.currentImpulseImpactPosPresent);
			new ServerTick().transact(
			    before.copy(), MovementPacket.NONE, server, IDLE, new SnapshotView(powderSnowWorld()), false);
			assertFalse(server.currentImpulseImpactPosPresent, "flying " + flying);
			assertEquals(flying, server.stuckSpeedMultiplierX == 0.0, "the vector stays behind the flying gate");
			assertEquals(1, server.ticksFrozen, "FREEZE is outside the gate");
		}
	}

	@Test
	void farmlandRefusesATrampleOnlyForABodyLargeEnoughToTrampleIt() {
		// FarmlandBlock.fallOn tramples at random only when width^2 * height
		// exceeds 0.512; a glider's 0.6-cube box is 0.216 and lands ordinarily.
		assertThrows(PendingServerWriteException.class,
		    () -> landOnFarmland(false), "a standing body past half a block is a random trample");
		ServerPlayerState glider = landOnFarmland(true);
		assertRaw(0.0, glider.fallDistance, "");
	}

	private static ServerPlayerState landOnFarmland(final boolean gliding) {
		PlayerState before = new PlayerState();
		if (gliding) {
			before.pose = PlayerState.Pose.FALL_FLYING;
			before.fallFlying = true;
			before.gliderUsable = true;
			before.fallFlyTicks = 5;
		}
		before.placeAt(0.5, 2.0, 0.5);
		before.fallDistance = 1.0;
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		PlayerState after = before.copy();
		after.placeAt(0.5, 0.0, 0.5);
		after.onGround = true;
		// The landing is the movement handler's; the glider's own connection
		// tick afterwards would refuse the stopped glide, which is not the
		// trample question.
		ServerTick.Transaction transaction =
		    new ServerTick().handleMovePlayer(after, packet(before, after), server, new SnapshotView(farmlandWorld()));
		assertInstanceOf(ServerTick.Accepted.class, transaction);
		return server;
	}

	private static ServerPlayerState landOnSlime(final boolean sneaking) {
		PlayerState before = airborneAt(0.5, 3.0, 0.5);
		before.fallDistance = 2.0;
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		server.setIgnoreFallDamageFromCurrentImpulse(true, 0.5, -2.0, 0.5);
		server.currentImpulseContextResetGraceTime = 5;
		// The input packet of the same tick installs the server's shift flag.
		PlayerInput action = sneaking ? PlayerInput.ofPacked((byte) PlayerInput.FLAG_SNEAK, 0.0F, 0.0F) : IDLE;
		PlayerState after = before.copy();
		after.placeAt(0.5, 0.0, 0.5);
		after.onGround = true;
		ServerTick.Transaction transaction = new ServerTick().transact(
		    after, packet(before, after), server, action, new SnapshotView(slimeWorld()), false);
		assertEquals(sneaking, server.shiftKeyDown);
		assertInstanceOf(ServerTick.Accepted.class, transaction);
		assertEquals(Optional.empty(), transaction.hurt(), "a slime landing never hits");
		return server;
	}

	private static Optional<HurtCause> landingHurt(
	    final double fallBefore, final double descent, final boolean mayfly) {
		return landingHurt(fallBefore, descent, mayfly, true);
	}

	private static Optional<HurtCause> landingHurt(
	    final double fallBefore, final double descent, final boolean mayfly, final boolean fallDamage) {
		PlayerState before = airborneAt(0.5, descent, 0.5);
		before.fallDistance = fallBefore;
		before.mayfly = mayfly;
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		server.fallDamage = fallDamage;
		PlayerState after = before.copy();
		after.placeAt(0.5, 0.0, 0.5);
		after.onGround = true;
		ServerTick.Transaction transaction = new ServerTick().transact(
		    after, packet(before, after), server, IDLE, new SnapshotView(floorWorld()), false);
		assertInstanceOf(ServerTick.Accepted.class, transaction);
		assertRaw(0.0, server.fallDistance, "the packet's ground bit resets the fall");
		return transaction.hurt();
	}

	@Test
	void theServersGlideTravelIntoAWallHurtsAtTheConnectionTick() {
		ServerPlayerState server = ServerPlayerState.atBoundary(gliding(-1.0, 0.5, 0.5, 1.0));
		PlayerState client = server.copy();

		ServerTick.Transaction transaction =
		    new ServerTick().transact(client, MovementPacket.NONE, server, IDLE, new SnapshotView(wallWorld()), false);

		assertInstanceOf(ServerTick.Unpublished.class, transaction);
		assertEquals(Optional.of(HurtCause.FLY_INTO_WALL), transaction.hurt(),
		    "handleFallFlyingCollisions: (1.0 - 0.0) * 10 - 3 > 0 on a horizontal collision");
		assertTrue(server.horizontalCollision);
		assertTrue(server.deltaMovementX == 0.0, "the wall stopped the server's copy");
		assertRaw(-1.0, server.x, "the connection tick restores the anchor");
		assertTrue(server.fallFlying, "the hit does not end the glide");
	}

	@Test
	void aGlideThatShedsTooLittleSpeedDoesNotHurt() {
		// Only 0.2 of speed is lost against a wall reached at a crawl.
		ServerPlayerState server = ServerPlayerState.atBoundary(gliding(-0.35, 0.5, 0.5, 0.25));
		PlayerState client = server.copy();

		ServerTick.Transaction transaction =
		    new ServerTick().transact(client, MovementPacket.NONE, server, IDLE, new SnapshotView(wallWorld()), false);

		assertTrue(server.horizontalCollision);
		assertEquals(Optional.empty(), transaction.hurt(), "(0.25 - 0.0) * 10 - 3 is not positive");
	}

	@Test
	void anAttachedRocketRefusesTheFirstInputInWhichItWouldWrite() {
		ServerPlayerState server = ServerPlayerState.atBoundary(gliding(-3.0, 3.0, 0.5, 0.5));
		PlayerState client = server.copy();
		server.attachedRockets = 1;

		PendingServerWriteException refusal = assertThrows(PendingServerWriteException.class,
		    () -> new ServerTick().transact(client, MovementPacket.NONE, server, IDLE, emptyWorld(), false));
		assertTrue(refusal.getMessage().contains("attached firework rocket"), refusal.getMessage());

		PlayerState walking = airborneAt(0.5, 0.0, 0.5);
		walking.onGround = true;
		ServerPlayerState walkingServer = ServerPlayerState.atBoundary(walking);
		walkingServer.attachedRockets = 1;
		assertEquals(Optional.empty(),
		    new ServerTick()
		        .transact(walking, packet(walking, walking), walkingServer, IDLE, new SnapshotView(floorWorld()), false)
		        .hurt(),
		    "a rocket attached to a player who is not gliding writes nothing");
	}

	@Test
	void theServerStoppingAGlideClearsTheFlagForTheNextSampleAndTravelsInAir() {
		// LivingEntity.updateFallFlying on a copy that cannot glide clears the
		// shared flag and returns, so LivingEntity.travel selects the air in
		// the same tick; the dirty flag waits for the next tracker sample.
		ServerPlayerState server = ServerPlayerState.atBoundary(gliding(0.5, 0.0, 0.5, 0.5));
		server.onGround = true;
		PlayerState client = server.copy();

		assertEquals(Optional.empty(),
		    new ServerTick()
		        .transact(client, MovementPacket.NONE, server, IDLE, new SnapshotView(floorWorld()), false)
		        .hurt());
		assertFalse(server.fallFlying, "the server's copy stops gliding in its own tick");
		assertTrue((server.entityDataDirty & EntityDataWrite.FLAGS) != 0,
		    "the cleared flag is dirty for the next tracker sample");
		assertEquals(0, server.fallFlyTicks, "LivingEntity.tick resets the glide count");
		assertRaw(0.5 * (double) (0.6F * 0.91F), server.deltaMovementX,
		    "the same tick's travel is travelInAir on the ground");
	}

	@Test
	void theTwentiethGlideTickRefusesBecauseTheGliderWriteIsUncaptured() {
		ServerPlayerState server = ServerPlayerState.atBoundary(gliding(-3.0, 3.0, 0.5, 0.5));
		server.fallFlyTicks = 19;
		PlayerState client = server.copy();

		PendingServerWriteException refusal = assertThrows(PendingServerWriteException.class,
		    () -> new ServerTick().transact(client, MovementPacket.NONE, server, IDLE, emptyWorld(), false));
		assertTrue(refusal.getMessage().contains("damages the glider"), refusal.getMessage());

		ServerPlayerState tenth = ServerPlayerState.atBoundary(gliding(-3.0, 3.0, 0.5, 0.5));
		tenth.fallFlyTicks = 9;
		assertEquals(Optional.empty(),
		    new ServerTick().transact(tenth.copy(), MovementPacket.NONE, tenth, IDLE, emptyWorld(), false).hurt(),
		    "the tenth tick emits only the glide event");
	}

	@Test
	void theServersCopyAdoptsAClientLaunchItCanContinueAndRefusesOneItCannot() {
		PlayerState before = airborneAt(0.5, 3.0, 0.5);
		before.gliderUsable = true;
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		PlayerState after = before.copy();
		after.placeAt(0.5, 2.9, 0.5);
		after.fallFlying = true;

		new ServerTick().transact(after, packet(before, after), server, IDLE, emptyWorld(), false);
		assertTrue(server.fallFlying, "START_FALL_FLYING precedes the movement packet");

		// Player.tryToStartFallFlying fails without a glider, so the handler
		// runs stopFallFlying: the flag pulses on and off, which dirties it for
		// the tracker sample that echoes the stop to the client.
		ServerPlayerState bare = ServerPlayerState.atBoundary(before);
		bare.gliderUsable = false;
		new ServerTick().transact(after, packet(before, after), bare, IDLE, emptyWorld(), false);
		assertFalse(bare.fallFlying, "the server's copy does not glide");
		assertTrue((bare.entityDataDirty & EntityDataWrite.FLAGS) != 0,
		    "stopFallFlying's pulse dirties the shared flags for the echo");

		// The same command on a copy that already glides is a stop as well.
		ServerPlayerState already = ServerPlayerState.atBoundary(before);
		already.setSharedFlag(7, true);
		already.entityDataDirty = 0;
		new ServerTick().handleStartFallFlying(already);
		assertFalse(already.fallFlying, "tryToStartFallFlying refuses an already gliding copy");
		assertTrue((already.entityDataDirty & EntityDataWrite.FLAGS) != 0);
	}

	/** An established glide toward +x at {@code speed} blocks per tick. */
	private static PlayerState gliding(final double x, final double y, final double z, final double speed) {
		PlayerState state = new PlayerState();
		state.pose = PlayerState.Pose.FALL_FLYING;
		state.placeAt(x, y, z);
		state.fallFlying = true;
		state.gliderUsable = true;
		state.fallFlyTicks = 5;
		state.deltaMovementX = speed;
		state.yRot = -90.0F;
		return state;
	}

	private static PlayerState airborneAt(final double x, final double y, final double z) {
		PlayerState state = new PlayerState();
		state.placeAt(x, y, z);
		return state;
	}

	private static void assertRaw(final double expected, final double actual, final String message) {
		assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual),
		    () -> message + " expected " + expected + " but was " + actual);
	}

	private static BlockEntry air() {
		return BlockEntry.builder(0, "minecraft:air").suffocation(Suffocation.NO).build();
	}

	private static BlockEntry stone() {
		return BlockEntry.builder(1, "minecraft:stone")
		    .suffocation(Suffocation.YES)
		    .fullCube()
		    .build();
	}

	private static WorldSnapshot.Builder region() {
		return WorldSnapshot.builder(OutsidePolicy.REFUSING, -4, -6, -4, 9, 24, 9)
		    .palette(air(), stone());
	}

	private static SnapshotView emptyWorld() {
		return new SnapshotView(region().build());
	}

	/** Stone with its top at y=0 everywhere. */
	private static WorldSnapshot floorWorld() {
		WorldSnapshot.Builder world = region();
		for (int z = -4; z <= 4; z++) {
			for (int x = -4; x <= 4; x++) {
				world.set(x, -1, z, 1);
			}
		}
		return world.build();
	}

	/** One stone block at (0,0,0) above a stone floor whose top is at y=-2. */
	private static WorldSnapshot verticalResidualFallWorld() {
		WorldSnapshot.Builder world = region();
		for (int z = -4; z <= 4; z++) {
			for (int x = -4; x <= 4; x++) {
				world.set(x, -3, z, 1);
			}
		}
		world.set(0, 0, 0, 1);
		return world.build();
	}

	/** A farmland floor with its top at y=0 everywhere. */
	private static WorldSnapshot farmlandWorld() {
		BlockEntry farmland = BlockEntry.builder(2, "minecraft:farmland")
		                                        .fullCube()
		                                        .landing(WorldView.Landing.FARMLAND)
		                                        .suffocation(Suffocation.NO)
		                                        .build();
		WorldSnapshot.Builder world =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, -4, -6, -4, 9, 24, 9)
		        .palette(air(), stone(), farmland);
		for (int z = -4; z <= 4; z++) {
			for (int x = -4; x <= 4; x++) {
				world.set(x, -1, z, 2);
			}
		}
		return world.build();
	}

	/** A slime floor with its top at y=0 everywhere. */
	private static WorldSnapshot slimeWorld() {
		BlockEntry slime = BlockEntry.builder(2, "minecraft:slime_block")
		                                     .fullCube()
		                                     .friction(0.8F)
		                                     .bounceRestitution(1.0F)
		                                     .landing(WorldView.Landing.SLIME)
		                                     .stepOn(WorldView.StepOn.SLIME)
		                                     .suffocation(Suffocation.NO)
		                                     .build();
		WorldSnapshot.Builder world =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, -4, -6, -4, 9, 24, 9)
		        .palette(air(), stone(), slime);
		for (int z = -4; z <= 4; z++) {
			for (int x = -4; x <= 4; x++) {
				world.set(x, -1, z, 2);
			}
		}
		return world.build();
	}

	/** The stone floor with a cobweb in the cell above the origin. */
	private static WorldSnapshot cobwebWorld() {
		BlockEntry cobweb = BlockEntry.builder(2, "minecraft:cobweb")
		                                      .insideEffect(WorldView.InsideEffect.COBWEB)
		                                      .contact(WorldView.Contact.NONE)
		                                      .suffocation(Suffocation.NO)
		                                      .build();
		WorldSnapshot.Builder world =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, -4, -6, -4, 9, 24, 9)
		        .palette(air(), stone(), cobweb);
		for (int z = -4; z <= 4; z++) {
			for (int x = -4; x <= 4; x++) {
				world.set(x, -1, z, 1);
			}
		}
		world.set(0, 0, 0, 2);
		return world.build();
	}

	/** The stone floor with powder snow in the cell above the origin. */
	private static WorldSnapshot powderSnowWorld() {
		BlockEntry powderSnow = BlockEntry.builder(2, "minecraft:powder_snow")
		                                          .collisionBehavior(WorldView.CollisionBehavior.POWDER_SNOW_NO_BOOTS)
		                                          .landing(WorldView.Landing.POWDER_SNOW)
		                                          .suffocation(Suffocation.NO)
		                                          .build();
		WorldSnapshot.Builder world =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, -4, -6, -4, 9, 24, 9)
		        .palette(air(), stone(), powderSnow);
		for (int z = -4; z <= 4; z++) {
			for (int x = -4; x <= 4; x++) {
				world.set(x, -1, z, 1);
			}
		}
		world.set(0, 0, 0, 2);
		return world.build();
	}

	/** The stone floor under one block of water everywhere. */
	private static WorldSnapshot poolWorld() {
		WorldSnapshot.Builder world = region().fluidPalette(FluidEntry.EMPTY,
		    new FluidEntry(
		        1, "minecraft:water", FluidKind.WATER, 8.0 / 9.0, 0, 0, 0, true));
		for (int z = -4; z <= 4; z++) {
			for (int x = -4; x <= 4; x++) {
				world.set(x, -1, z, 1);
				world.setFluid(x, 0, z, 1);
			}
		}
		return world.build();
	}

	/** Stone floor at y=-1 and a two-high stone wall filling x=0. */
	private static WorldSnapshot wallWorld() {
		WorldSnapshot.Builder world = region();
		for (int z = -4; z <= 4; z++) {
			for (int x = -4; x <= 4; x++) {
				world.set(x, -1, z, 1);
			}
			world.set(0, 0, z, 1);
			world.set(0, 1, z, 1);
		}
		return world.build();
	}

	/**
	 * One stair at (0,0,0) over a stone floor whose top is at y=-2: a lower
	 * slab across the cell and an upper step over its z in [0.5, 1].
	 */
	private static WorldSnapshot stairsWorld() {
		BlockEntry stairs = BlockEntry.builder(2, "minecraft:stone_stairs")
		                                      .suffocation(Suffocation.NO)
		                                      .boxes(new ShapeBox(0.0, 0.0, 0.0, 1.0, 0.5, 1.0),
		                                          new ShapeBox(0.0, 0.5, 0.5, 1.0, 1.0, 1.0))
		                                      .build();
		WorldSnapshot.Builder world =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, -4, -6, -4, 9, 24, 9)
		        .palette(air(), stone(), stairs);
		for (int z = -4; z <= 4; z++) {
			for (int x = -4; x <= 4; x++) {
				world.set(x, -3, z, 1);
			}
		}
		world.set(0, 0, 0, 2);
		return world.build();
	}

	/** One supported scaffolding at (0,0,0) over a stone floor whose top is at y=0. */
	private static WorldSnapshot scaffoldingWorld() {
		BlockEntry scaffolding = BlockEntry.builder(2, "minecraft:scaffolding")
		                                           .fallDistanceResetting(true)
		                                           .collisionBehavior(WorldView.CollisionBehavior.SCAFFOLDING_SUPPORTED)
		                                           .suffocation(Suffocation.NO)
		                                           .climbability(WorldView.Climbability.CLIMBABLE)
		                                           .boxes(new ShapeBox(0, 0.875, 0, 1, 1, 1),
		                                               new ShapeBox(0, 0, 0, 0.125, 1, 0.125),
		                                               new ShapeBox(0.875, 0, 0, 1, 1, 0.125),
		                                               new ShapeBox(0, 0, 0.875, 0.125, 1, 1),
		                                               new ShapeBox(0.875, 0, 0.875, 1, 1, 1))
		                                           .build();
		WorldSnapshot.Builder world =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, -4, -6, -4, 9, 24, 9)
		        .palette(air(), stone(), scaffolding);
		for (int z = -4; z <= 4; z++) {
			for (int x = -4; x <= 4; x++) {
				world.set(x, -1, z, 1);
			}
		}
		world.set(0, 0, 0, 2);
		return world.build();
	}

	/** No floor; stone at z=1 and z=2, y=5 and y=6, for a player embedded at z=1.5. */
	private static WorldSnapshot embeddedStartWorld() {
		WorldSnapshot.Builder world = region();
		for (int x = -4; x <= 4; x++) {
			world.set(x, 5, 1, 1);
			world.set(x, 6, 1, 1);
			world.set(x, 5, 2, 1);
			world.set(x, 6, 2, 1);
		}
		return world.build();
	}
}
