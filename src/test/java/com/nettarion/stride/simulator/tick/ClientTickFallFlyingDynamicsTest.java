package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.world.CompleteWorldView;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.world.WorldView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Durable consequences of the pinned fall-flying recurrence. */
class ClientTickFallFlyingDynamicsTest {
	private static final WorldView EMPTY = new EmptyWorld();

	@Test
	void verticalPitchEndpointsHaveDifferentHorizontalControlGates() {
		PlayerState up = establishedGlide();
		up.deltaMovementZ = 1.0;
		PlayerState down = up.copy();

		ClientTick kernel = new ClientTick();
		kernel.tick(up, PlayerInput.idle(90.0F, -90.0F), EMPTY);
		kernel.tick(down, PlayerInput.idle(90.0F, 90.0F), EMPTY);

		// At -90 degrees Mth.cos is exactly zero, so all three
		// look-horizontal branches are skipped and only horizontal drag applies.
		assertEquals((double) 0.99F, up.deltaMovementZ);
		// At +90 degrees the table value is tiny but nonzero. Normalization erases
		// its magnitude, so yaw alignment still removes ten percent before drag.
		assertEquals(0.9 * (double) 0.99F, down.deltaMovementZ);
		assertTrue(Math.abs(down.deltaMovementX) > 0.09,
		    "the normalized near-vertical look direction still steers horizontally");
	}

	@Test
	void levelReverseCrossesZeroOnFourteenthTick() {
		PlayerState state = establishedGlide();
		installLevelEquilibrium(state);
		double initialZ = state.z;
		double farthestZ = initialZ;

		ClientTick kernel = new ClientTick();
		for (int tick = 1; tick <= 13; tick++) {
			kernel.tick(state, PlayerInput.idle(180.0F, 0.0F), EMPTY);
			assertTrue(state.deltaMovementZ > 0.0, "still moving forward on tick " + tick);
			farthestZ = Math.max(farthestZ, state.z);
		}
		kernel.tick(state, PlayerInput.idle(180.0F, 0.0F), EMPTY);

		assertTrue(state.deltaMovementZ < 0.0);
		assertEquals(4.792123644324303, farthestZ - initialZ, 1.0E-14);
	}

	@Test
	void pitchSchedulingCanReverseThreeTicksBeforeLevelFlight() {
		PlayerState state = establishedGlide();
		installLevelEquilibrium(state);
		float[] pitches = {90.0F, 90.0F, 90.0F, 50.0F, 0.0F, 10.0F, 0.0F, 0.0F, 5.0F, 0.0F, 0.0F};

		ClientTick kernel = new ClientTick();
		for (int tick = 0; tick < pitches.length; tick++) {
			kernel.tick(state, PlayerInput.idle(180.0F, pitches[tick]), EMPTY);
			if (tick < pitches.length - 1) {
				assertTrue(state.deltaMovementZ > 0.0, "still moving forward on tick " + (tick + 1));
			}
		}

		assertTrue(state.deltaMovementZ < 0.0);
	}

	@Test
	void establishedAerodynamicVelocityIgnoresMovementAndSprintInputs() {
		PlayerState a = establishedGlide();
		a.deltaMovementX = 0.12;
		a.deltaMovementY = -0.18;
		a.deltaMovementZ = 0.73;
		PlayerState b = a.copy();

		PlayerInput first = new PlayerInput(true, false, true, false, false, false, true, 37.0F, -18.0F);
		PlayerInput second = new PlayerInput(false, true, false, true, false, false, false, 37.0F, -18.0F);
		ClientTick kernel = new ClientTick();
		kernel.tick(a, first, EMPTY);
		kernel.tick(b, second, EMPTY);

		assertEquals(Double.doubleToRawLongBits(a.x), Double.doubleToRawLongBits(b.x));
		assertEquals(Double.doubleToRawLongBits(a.y), Double.doubleToRawLongBits(b.y));
		assertEquals(Double.doubleToRawLongBits(a.z), Double.doubleToRawLongBits(b.z));
		assertEquals(Double.doubleToRawLongBits(a.deltaMovementX), Double.doubleToRawLongBits(b.deltaMovementX));
		assertEquals(Double.doubleToRawLongBits(a.deltaMovementY), Double.doubleToRawLongBits(b.deltaMovementY));
		assertEquals(Double.doubleToRawLongBits(a.deltaMovementZ), Double.doubleToRawLongBits(b.deltaMovementZ));
	}

	@Test
	void aRetainedGlidePoseWithoutTheFlagCrawlsAtSneakingSpeedAndCannotStartSprinting() {
		// The server's stop echo clears the glide flag a tick before
		// Player.updatePlayerPose reselects the pose, and LivingEntity's
		// isVisuallySwimming counts that pose as crawling on land.
		PlayerState standing = groundedAt(PlayerState.Pose.STANDING);
		PlayerState crawling = groundedAt(PlayerState.Pose.FALL_FLYING);
		PlayerInput sprintForward = new PlayerInput(true, false, false, false, false, false, true, 0.0F, 0.0F);
		ClientTick kernel = new ClientTick();
		kernel.tick(standing, sprintForward, EMPTY);
		kernel.tick(crawling, sprintForward, EMPTY);

		assertTrue(standing.sprinting, "a standing player starts the sprint");
		assertTrue(!crawling.sprinting, "a crawling player cannot start a sprint on land");
		double standingWalk = walkSpeed(PlayerState.Pose.STANDING);
		double crawlingWalk = walkSpeed(PlayerState.Pose.FALL_FLYING);
		assertEquals(
		    0.3, crawlingWalk / standingWalk, 1.0E-6, "the crawl scales the input by the sneaking-speed attribute");
	}

	@Test
	void theEyeLatchAloneOpensTheGlidersSprintStartOnTheTickItLeavesWater() {
		// LocalPlayer.isUnderWater overrides Entity's with wasUnderwater alone,
		// the eye flag of the previous refresh. A glider that skimmed through
		// water and left it in one move still has the latch set at the next
		// tick's start while the refreshed box holds no water, and
		// canStartSprinting's glide term reads that latch.
		PlayerState latched = establishedGlide();
		latched.eyeInWater = true;
		latched.deltaMovementZ = 0.5;
		PlayerState dry = establishedGlide();
		dry.deltaMovementZ = 0.5;
		PlayerInput sprintForward = new PlayerInput(true, false, false, false, false, false, true, 0.0F, 0.0F);
		ClientTick kernel = new ClientTick();
		kernel.tick(latched, sprintForward, EMPTY);
		kernel.tick(dry, sprintForward, EMPTY);

		assertTrue(latched.sprinting, "the latched glider starts sprinting although its box is dry");
		assertTrue(!dry.sprinting, "a glider whose eyes were not under cannot start sprinting");
	}

	private static double walkSpeed(final PlayerState.Pose pose) {
		PlayerState state = groundedAt(pose);
		new ClientTick().tick(
		    state, new PlayerInput(true, false, false, false, false, false, false, 0.0F, 0.0F), EMPTY);
		return Math.hypot(state.deltaMovementX, state.deltaMovementZ);
	}

	/** A grounded player carrying the given pose with no glide flag and no water. */
	private static PlayerState groundedAt(final PlayerState.Pose pose) {
		PlayerState state = new PlayerState();
		state.pose = pose;
		state.placeAt(0.5, 40.0, 0.5);
		state.onGround = true;
		state.mainSupportingBlockPosPresent = true;
		state.mainSupportingBlockPosX = 0;
		state.mainSupportingBlockPosY = 39;
		state.mainSupportingBlockPosZ = 0;
		return state;
	}

	private static PlayerState establishedGlide() {
		PlayerState state = new PlayerState();
		state.x = 0.5;
		state.y = 40.0;
		state.z = 0.5;
		state.fallFlying = true;
		state.gliderUsable = true;
		state.pose = PlayerState.Pose.FALL_FLYING;
		state.setBox(AABB.around(state.x, state.y, state.z, state.pose.width, state.pose.height));
		return state;
	}

	private static void installLevelEquilibrium(final PlayerState state) {
		double horizontalDrag = (double) 0.99F;
		double verticalDrag = (double) 0.98F;
		double verticalFeedback = verticalDrag * 0.9;
		state.deltaMovementY = -0.02 * verticalFeedback / (1.0 - verticalFeedback);
		double descentConversion = -0.1 * (state.deltaMovementY - 0.02);
		state.deltaMovementZ = horizontalDrag * 0.9 * descentConversion / (1.0 - horizontalDrag);
	}

	private static final class EmptyWorld implements CompleteWorldView {
		@Override
		public boolean suffocatesAt(
		    final int cellX, final int cellZ, final double boundingBoxMinY, final double boundingBoxMaxY) {
			return false;
		}
		@Override
		public long collisionVersion() {
			return 0L;
		}
		@Override
		public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final CollisionBuffer target) {
			target.clear();
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
}
