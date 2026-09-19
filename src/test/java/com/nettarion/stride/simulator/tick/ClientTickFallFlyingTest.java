package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.world.CompleteWorldView;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.world.WorldView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClientTickFallFlyingTest {
	private static final WorldView EMPTY = new EmptyWorld();

	@Test
	void risingJumpEdgeLaunchesUsableGliderAndInstallsFlightPose() {
		PlayerState state = airborne();
		state.gliderUsable = true;
		state.deltaMovementY = -0.2;

		new ClientTick().tick(state, action(true, -20.0F), EMPTY);

		assertTrue(state.fallFlying);
		assertEquals(1, state.fallFlyTicks);
		assertEquals(PlayerState.Pose.FALL_FLYING, state.pose);
		assertTrue(state.deltaMovementZ > 0.0, "glide converts descent into forward speed");
	}

	@Test
	void noUsableGliderIsAMaterialNegativeControl() {
		PlayerState positive = airborne();
		positive.gliderUsable = true;
		positive.deltaMovementY = -0.2;
		PlayerState negative = positive.copy();
		negative.gliderUsable = false;

		PlayerInput launch = action(true, -20.0F);
		ClientTick kernel = new ClientTick();
		kernel.tick(positive, launch, EMPTY);
		kernel.tick(negative, launch, EMPTY);

		assertTrue(positive.fallFlying);
		assertFalse(negative.fallFlying);
		assertNotEquals(
		    Double.doubleToRawLongBits(positive.deltaMovementY), Double.doubleToRawLongBits(negative.deltaMovementY));
	}

	@Test
	void pitchSteersAnEstablishedGlideAndCounterAdvances() {
		PlayerState dive = airborne();
		dive.fallFlying = true;
		dive.gliderUsable = true;
		dive.fallFlyTicks = 12;
		dive.pose = PlayerState.Pose.FALL_FLYING;
		dive.setBox(AABB.around(dive.x, dive.y, dive.z, dive.pose.width, dive.pose.height));
		dive.deltaMovementZ = 0.8;
		PlayerState pullUp = dive.copy();

		ClientTick kernel = new ClientTick();
		kernel.tick(dive, action(false, 25.0F), EMPTY);
		kernel.tick(pullUp, action(false, -20.0F), EMPTY);

		assertEquals(13, dive.fallFlyTicks);
		assertEquals(13, pullUp.fallFlyTicks);
		assertTrue(pullUp.deltaMovementY > dive.deltaMovementY);
		assertEquals(0x0000_0000_0000_0000L, Double.doubleToRawLongBits(pullUp.deltaMovementX));
		assertEquals(0x3f84_d631_f307_6777L, Double.doubleToRawLongBits(pullUp.deltaMovementY));
		assertEquals(0x3fe9_199c_77ac_e81aL, Double.doubleToRawLongBits(pullUp.deltaMovementZ));
	}

	private static PlayerState airborne() {
		PlayerState state = new PlayerState();
		state.x = 0.5;
		state.y = 20.0;
		state.z = 0.5;
		state.pose = PlayerState.Pose.STANDING;
		state.setBox(AABB.around(state.x, state.y, state.z, state.pose.width, state.pose.height));
		return state;
	}

	private static PlayerInput action(final boolean jump, final float pitch) {
		return new PlayerInput(false, false, false, false, jump, false, false, 0.0F, pitch);
	}

	private static final class EmptyWorld implements CompleteWorldView {
		/** Defined, not derived: no block in this fixture suffocates. */
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
