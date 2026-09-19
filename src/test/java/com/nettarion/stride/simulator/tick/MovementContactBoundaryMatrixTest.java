package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.world.CompleteWorldView;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.world.FlatFloorView;
import com.nettarion.stride.simulator.world.SupportCell;
import com.nettarion.stride.simulator.world.WorldView;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Exact-kernel contact and control-boundary evidence for advanced movement math.
 *
 * <p>The collision rows mirror the strict face tests in pinned 26.2
 * {@code VoxelShape.collideX}; the bounce rows mirror
 * {@code Entity.restituteMovementAfterCollisions}. Expected numeric results are
 * compared by raw bits. The yaw-rate row is deliberately a producer-owned test
 * constraint: {@link PlayerInput} and the movement kernel admit an arbitrary
 * finite float yaw every tick and impose no actuator slew limit.
 */
final class MovementContactBoundaryMatrixTest {
	private static final double COLLISION_EPSILON = 1.0E-7;
	private static final double GRAVITY = 0.08;
	private static final double VERTICAL_DRAG = (double) 0.98F;
	private static final double FLOOR_VELOCITY = -0.0784000015258789;
	private static final double JUMP_VELOCITY = (double) 0.42F;
	private static final double ENTRY_Z_VELOCITY = 0.3602160774769785;
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);

	@Test
	void platformLandingBandPinsBothVerticalFacesAndBothHorizontalEdges() {
		double requestedY = -0.25;
		double[] lowerFaces = {Math.nextDown(requestedY), requestedY, Math.nextUp(requestedY)};
		boolean[] lowerContacts = {false, false, true};
		for (int row = 0; row < lowerFaces.length; row++) {
			PlayerState state = fallingState(0.5, 0.0, 0.5, requestedY);
			resolve(state, requestedY, PlatformWorld.stone(0.0, 1.0, lowerFaces[row], 0.0, 1.0));
			assertEquals(lowerContacts[row], state.verticalCollisionBelow, "lower traveled face row " + row);
			assertRaw(lowerContacts[row] ? lowerFaces[row] : requestedY, state.y);
			assertRaw(lowerContacts[row] ? 0.0 : requestedY, state.deltaMovementY);
		}

		double[] upperFaces = {Math.nextDown(COLLISION_EPSILON), COLLISION_EPSILON, Math.nextUp(COLLISION_EPSILON)};
		boolean[] upperContacts = {true, true, false};
		for (int row = 0; row < upperFaces.length; row++) {
			PlayerState state = fallingState(0.5, 0.0, 0.5, requestedY);
			resolve(state, requestedY, PlatformWorld.stone(0.0, 1.0, upperFaces[row], 0.0, 1.0));
			assertEquals(upperContacts[row], state.verticalCollisionBelow, "upper epsilon face row " + row);
			assertRaw(upperContacts[row] ? 0.0 : requestedY, state.y);
			assertRaw(upperContacts[row] ? 0.0 : requestedY, state.deltaMovementY);
		}

		// These are the adjacent centers whose reconstructed 0.6F-wide box
		// changes the source's strict perpendicular-overlap decision at [0,1].
		double leftEdge = Double.longBitsToDouble(0xbfd33332d4a03595L);
		double rightEdge = Double.longBitsToDouble(0x3ff4ccccb5280d66L);
		double[][] centers = {{Math.nextDown(leftEdge), leftEdge, Math.nextUp(leftEdge)},
		    {Math.nextDown(rightEdge), rightEdge, Math.nextUp(rightEdge)}};
		boolean[][] edgeContacts = {{false, true, true}, {true, false, false}};
		for (int edge = 0; edge < centers.length; edge++) {
			for (int row = 0; row < centers[edge].length; row++) {
				PlayerState state = fallingState(centers[edge][row], 0.0, 0.5, requestedY);
				resolve(state, requestedY, PlatformWorld.stone(0.0, 1.0, -0.125, 0.0, 1.0));
				assertEquals(
				    edgeContacts[edge][row], state.verticalCollisionBelow, "horizontal edge " + edge + " row " + row);
				assertRaw(edgeContacts[edge][row] ? -0.125 : requestedY, state.y);
			}
		}
	}

	@Test
	void ceilingClearancePinsStandingCrouchingCrawlingAndLateralExitOrder() {
		PlayerState.Pose[] poses = {PlayerState.Pose.STANDING, PlayerState.Pose.CROUCHING, PlayerState.Pose.SWIMMING};
		for (int poseRow = 0; poseRow < poses.length; poseRow++) {
			for (CeilingTopology topology : CeilingTopology.values()) {
				PlayerState state = risingState(poses[poseRow], 0.5);
				state.deltaMovementZ = topology.requestedZ;
				ClientTick kernel = new ClientTick();
				Move.resolve(state, 0.0, JUMP_VELOCITY, topology.requestedZ, false, topology.world(), new Scratch());

				double clearance = topology.underside - (double) poses[poseRow].height;
				boolean clipped = clearance < JUMP_VELOCITY;
				String context = "pose=" + poses[poseRow] + " topology=" + topology;
				assertRaw(clipped ? clearance : JUMP_VELOCITY, state.y, context);
				assertRaw(clipped ? -0.0 : JUMP_VELOCITY, state.deltaMovementY, context);
				assertRaw(0.5 + topology.requestedZ, state.z, context);
				assertEquals(clipped, state.verticalCollision, context);
				assertFalse(state.verticalCollisionBelow, context);
			}
		}

		PlayerState exiting = risingState(PlayerState.Pose.STANDING, 0.5);
		CeilingWorld lateral = CeilingTopology.LATERAL_FULL.world();
		ClientTick kernel = new ClientTick();
		exiting.deltaMovementZ = 0.6;
		Move.resolve(exiting, 0.0, JUMP_VELOCITY, 0.6, false, lateral, new Scratch());
		assertRaw(2.0 - (double) PlayerState.Pose.STANDING.height, exiting.y);
		assertRaw(1.1, exiting.z);
		assertTrue(exiting.verticalCollision, "Y resolves before the same movement's horizontal ceiling exit");

		exiting.deltaMovementY = JUMP_VELOCITY;
		Move.resolve(exiting, 0.0, JUMP_VELOCITY, 0.0, false, lateral, new Scratch());
		assertRaw(2.0 - (double) PlayerState.Pose.STANDING.height + JUMP_VELOCITY, exiting.y);
		assertFalse(exiting.verticalCollision, "the later movement observes that the footprint already exited");
	}

	@Test
	void fullTickBounceMatrixPinsSurfaceFractionShiftAndGravityBoundary() {
		double[] magnitudes = {Math.nextDown(GRAVITY), GRAVITY, Math.nextUp(GRAVITY)};
		double[] portions = {0.0, 0.5, Math.nextDown(1.0), 1.0};

		for (Surface surface : Surface.values()) {
			float restitution = surface.restitution;
			for (double magnitude : magnitudes) {
				for (double requestedPortion : portions) {
					for (boolean shift : new boolean[] {false, true}) {
						double startY = magnitude * requestedPortion;
						PlayerState state = fallingState(0.5, startY, 0.5, -magnitude);
						PlayerInput action =
						    new PlayerInput(false, false, false, false, false, shift, false, 0.0F, 0.0F);
						new ClientTick().tick(state, action, PlatformWorld.bouncy(restitution));

						boolean contact = requestedPortion < 1.0;
						boolean admitted = contact && magnitude >= GRAVITY && !shift;
						double expectedMovementY;
						if (admitted) {
							double movedY = -startY;
							double portion = movedY / -magnitude;
							double compensation = portion * GRAVITY;
							double effectiveDrag = 1.0 + portion * (VERTICAL_DRAG - 1.0);
							expectedMovementY = (compensation + magnitude) * effectiveDrag * (double) restitution;
						} else if (contact) {
							expectedMovementY = 0.0;
						} else {
							expectedMovementY = -magnitude;
						}
						double expectedRetained = (expectedMovementY - GRAVITY) * VERTICAL_DRAG;
						String context = "surface=" + surface + " magnitude=" + magnitude
						    + " portion=" + requestedPortion + " shift=" + shift;
						assertRaw(expectedRetained, state.deltaMovementY, context);
						assertEquals(contact, state.verticalCollisionBelow, context);
						assertEquals(contact, state.onGround, context);
						assertRaw(contact ? 0.0 : startY - magnitude, state.y, context);
					}
				}
			}
		}
	}

	@Test
	void brakingCarriesSprintAndPreviousInputEpochsAndBoundsOnlyProducerYaw() {
		PlayerInput reverseForward = forwardAt(180.0F);
		PlayerState ordinaryEpoch = brakingState(PlayerInput.FLAG_FORWARD, 0.98F);
		PlayerState shiftedEpoch = brakingState(PlayerInput.FLAG_SNEAK, 0.0F);
		ClientTick kernel = new ClientTick();
		kernel.tick(ordinaryEpoch, reverseForward, FlatFloorView.ordinary(0, -64));
		kernel.tick(shiftedEpoch, reverseForward, FlatFloorView.ordinary(0, -64));

		assertTrue(ordinaryEpoch.sprinting);
		assertTrue(shiftedEpoch.sprinting, "current forward input retains sprint even during the prior-shift epoch");
		assertTrue(shiftedEpoch.crouching, "the previous shift input still owns this tick's crouching computation");
		assertRaw(Double.longBitsToDouble(0x3fc0456399ef512bL), ordinaryEpoch.deltaMovementZ);
		assertRaw(Double.longBitsToDouble(0x3fc680f01fb72fdcL), shiftedEpoch.deltaMovementZ);
		assertEquals(reverseForward.packedInput(), ordinaryEpoch.inputKeyPresses);
		assertEquals(reverseForward.packedInput(), shiftedEpoch.inputKeyPresses,
		    "both states retain the same current input for the following epoch");

		PlayerState immediate = brakingState(PlayerInput.FLAG_FORWARD, 0.98F);
		long[][] immediateBits = trajectory(immediate, new float[] {180.0F, 180.0F, 180.0F}, Float.POSITIVE_INFINITY);
		assertTrajectory(
		    new long[][] {{0x3fe7733aad2e514cL, 0x3fc0456399ef512bL, 1L},
		        {0x3fe7733aad2e514cL, 0xbf24364019ab1b4aL, 1L}, {0x3fe35f917c54690cL, 0xbfb1ceb61ae65722L, 1L}},
		    immediateBits);

		PlayerState bounded = brakingState(PlayerInput.FLAG_FORWARD, 0.98F);
		long[][] boundedBits = trajectory(bounded, new float[] {45.0F, 90.0F, 135.0F, 180.0F}, 45.0F);
		assertTrajectory(
		    new long[][] {{0x3fee68deaae0a788L, 0x3fcf787e18ec8e1bL, 1L},
		        {0x3ff3237f188de588L, 0x3fc12ed86f9af693L, 1L}, {0x3ff3d85cc0150d5cL, 0x3f98b02d3312305aL, 1L},
		        {0x3ff23148dc7461fdL, 0xbface003a8cd92c0L, 1L}},
		    boundedBits);
		assertEquals(1, firstNegativeRetainedProjection(immediateBits));
		assertEquals(3, firstNegativeRetainedProjection(boundedBits));
		assertRaw(0.23281606506729124, maximumCommittedForward(immediateBits));
		assertRaw(0.7403228285991963, maximumCommittedForward(boundedBits));

		PlayerState localBackward = brakingState(PlayerInput.FLAG_FORWARD, 0.98F);
		long[][] backwardBits = backwardTrajectory(localBackward, 3);
		assertTrajectory(
		    new long[][] {{0x3fe86412f6d91eecL, 0x3fc25364f30d52b6L, 0L},
		        {0x3fe9d61b4c6d58faL, 0x3f994135a6259523L, 0L}, {0x3fe77d54126f6b03L, 0xbfa4806632f3fe3cL, 0L}},
		    backwardBits);
		assertEquals(2, firstNegativeRetainedProjection(backwardBits));
		assertRaw(Double.longBitsToDouble(0x3fd3ac3698dab1f4L), maximumCommittedForward(backwardBits));
	}

	private static int firstNegativeRetainedProjection(final long[][] trajectory) {
		for (int tick = 0; tick < trajectory.length; tick++) {
			if (Double.longBitsToDouble(trajectory[tick][1]) < 0.0) return tick;
		}
		return -1;
	}

	private static double maximumCommittedForward(final long[][] trajectory) {
		double furthest = 0.5;
		for (long[] tick : trajectory) {
			furthest = Math.max(furthest, Double.longBitsToDouble(tick[0]));
		}
		return furthest - 0.5;
	}

	private static long[][] backwardTrajectory(final PlayerState state, final int ticks) {
		long[][] bits = new long[ticks][3];
		ClientTick kernel = new ClientTick();
		PlayerInput backward = new PlayerInput(false, true, false, false, false, false, true, 0.0F, 0.0F);
		for (int tick = 0; tick < ticks; tick++) {
			kernel.tick(state, backward, FlatFloorView.ordinary(0, -64));
			bits[tick][0] = Double.doubleToRawLongBits(state.z);
			bits[tick][1] = Double.doubleToRawLongBits(state.deltaMovementZ);
			bits[tick][2] = state.sprinting ? 1L : 0L;
		}
		return bits;
	}

	private static void assertTrajectory(final long[][] expected, final long[][] actual) {
		assertEquals(expected.length, actual.length);
		for (int tick = 0; tick < expected.length; tick++) {
			assertArrayEquals(expected[tick], actual[tick], "tick " + tick);
		}
	}

	private static long[][] trajectory(final PlayerState state, final float[] yaws, final float producerMaxYawDelta) {
		long[][] bits = new long[yaws.length][3];
		float previousYaw = state.yRot;
		ClientTick kernel = new ClientTick();
		for (int tick = 0; tick < yaws.length; tick++) {
			float yaw = yaws[tick];
			if (Float.isFinite(producerMaxYawDelta)) {
				assertTrue(Math.abs(yaw - previousYaw) <= producerMaxYawDelta,
				    "this schedule's producer, not the kernel, owns the 45-degree slew");
			}
			kernel.tick(state, forwardAt(yaw), FlatFloorView.ordinary(0, -64));
			bits[tick][0] = Double.doubleToRawLongBits(state.z);
			bits[tick][1] = Double.doubleToRawLongBits(state.deltaMovementZ);
			bits[tick][2] = state.sprinting ? 1L : 0L;
			previousYaw = yaw;
		}
		return bits;
	}

	private static PlayerInput forwardAt(final float yaw) {
		return new PlayerInput(true, false, false, false, false, false, true, yaw, 0.0F);
	}

	private enum Surface {
		BED(0.75F),
		SLIME(1.0F);

		private final float restitution;

		Surface(final float restitution) {
			this.restitution = restitution;
		}
	}

	private enum CeilingTopology {
		FULL(2.0, 3.0, 10.0, 0.0),
		TOP_TRAPDOOR(1.8125, 2.0, 10.0, 0.0),
		LATERAL_FULL(2.0, 3.0, 0.8, 0.6);

		private final double underside;
		private final double top;
		private final double endZ;
		private final double requestedZ;

		CeilingTopology(final double underside, final double top, final double endZ, final double requestedZ) {
			this.underside = underside;
			this.top = top;
			this.endZ = endZ;
			this.requestedZ = requestedZ;
		}

		CeilingWorld world() {
			return new CeilingWorld(this.underside, this.top, this.endZ);
		}
	}

	private static PlayerState brakingState(final int previousInput, final float previousForward) {
		PlayerState state = fallingState(0.5, 0.0, 0.5, FLOOR_VELOCITY);
		state.onGround = true;
		state.mainSupportingBlockPosPresent = true;
		state.mainSupportingBlockPosX = 0;
		state.mainSupportingBlockPosY = -1;
		state.mainSupportingBlockPosZ = 0;
		state.deltaMovementZ = ENTRY_Z_VELOCITY;
		state.setSprinting(true);
		state.inputKeyPresses = (byte) previousInput;
		state.inputMoveVectorY = previousForward;
		return state;
	}

	private static PlayerState fallingState(
	    final double x, final double y, final double z, final double deltaMovementY) {
		PlayerState state = new PlayerState();
		state.placeAt(x, y, z);
		state.deltaMovementY = deltaMovementY;
		return state;
	}

	private static PlayerState risingState(final PlayerState.Pose pose, final double z) {
		PlayerState state = new PlayerState();
		state.pose = pose;
		state.placeAt(0.5, 0.0, z);
		state.deltaMovementY = JUMP_VELOCITY;
		return state;
	}

	private static void resolve(final PlayerState state, final double requestedY, final WorldView world) {
		Move.resolve(state, 0.0, requestedY, 0.0, false, world, new Scratch());
	}

	private static void assertRaw(final double expected, final double actual) {
		assertRaw(expected, actual, "");
	}

	private static void assertRaw(final double expected, final double actual, final String context) {
		assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual), context);
	}

	private abstract static class SingleBoxWorld implements CompleteWorldView {
		private final double minX;
		private final double minY;
		private final double minZ;
		private final double maxX;
		private final double maxY;
		private final double maxZ;

		SingleBoxWorld(final double minX, final double minY, final double minZ, final double maxX, final double maxY,
		    final double maxZ) {
			this.minX = minX;
			this.minY = minY;
			this.minZ = minZ;
			this.maxX = maxX;
			this.maxY = maxY;
			this.maxZ = maxZ;
		}

		@Override
		public long collisionVersion() {
			return 0L;
		}

		@Override
		public void collectCollisionBoxes(final double queryMinX, final double queryMinY, final double queryMinZ,
		    final double queryMaxX, final double queryMaxY, final double queryMaxZ, final CollisionBuffer target) {
			target.clear();
			if (queryMaxX > this.minX && queryMinX < this.maxX && queryMaxY > this.minY && queryMinY < this.maxY
			    && queryMaxZ > this.minZ && queryMinZ < this.maxZ) {
				target.add(this.minX, this.minY, this.minZ, this.maxX, this.maxY, this.maxZ);
			}
		}

		@Override
		public boolean suffocatesAt(final int x, final int z, final double minY, final double maxY) {
			return false;
		}
		@Override
		public float friction(final int x, final int y, final int z) {
			return 0.6F;
		}
		@Override
		public float speedFactor(final int x, final int y, final int z) {
			return 1.0F;
		}
		@Override
		public float jumpFactor(final int x, final int y, final int z) {
			return 1.0F;
		}
		@Override
		public boolean hasChunkAt(final int x, final int z) {
			return true;
		}
		@Override
		public int minY() {
			return -64;
		}
	}

	private static final class PlatformWorld extends SingleBoxWorld {
		private final float restitution;

		private PlatformWorld(final double minX, final double maxX, final double top, final double minZ,
		    final double maxZ, final float restitution) {
			super(minX, -1.0, minZ, maxX, top, maxZ);
			this.restitution = restitution;
		}

		static PlatformWorld stone(
		    final double minX, final double maxX, final double top, final double minZ, final double maxZ) {
			return new PlatformWorld(minX, maxX, top, minZ, maxZ, 0.0F);
		}

		static PlatformWorld bouncy(final float restitution) {
			return new PlatformWorld(-10.0, 10.0, 0.0, -10.0, 10.0, restitution);
		}

		@Override
		public int propertiesIn(
		    final int wanted, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
			return this.restitution > 0.0F ? wanted & PROPERTY_BOUNCE : 0;
		}

		@Override
		public float bounceRestitutionAt(final int x, final int y, final int z) {
			return this.restitution;
		}

		@Override
		public void findSupportingBlock(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final double atX, final double atY, final double atZ,
		    final SupportCell out) {
			out.clear();
			out.set(0, -1, 0);
		}
	}

	private static final class CeilingWorld extends SingleBoxWorld {
		private CeilingWorld(final double underside, final double top, final double endZ) {
			super(-10.0, underside, -10.0, 10.0, top, endZ);
		}
	}
}
