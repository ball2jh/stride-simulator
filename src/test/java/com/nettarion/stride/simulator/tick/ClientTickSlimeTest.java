package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@code SlimeBlock.stepOn} on the client, paired against the same floor with
 * the body removed. The damping's shift gate is {@code isSteppingCarefully()},
 * which is {@code LocalPlayer.isShiftKeyDown()}: the input as of the block
 * effects, which run after {@code KeyboardInput.tick()} and therefore read
 * this tick's action, not the one retained from the previous tick.
 */
class ClientTickSlimeTest {
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);
	private static final PlayerInput SHIFT =
	    new PlayerInput(false, false, false, false, false, true, false, 0.0F, 0.0F);

	/** Resting on a floor after a tick: the gravity step against a zeroed fall. */
	private static final double RESTING_DELTA_Y = (0.0 - 0.08) * (double) 0.98F;

	@Test
	void walkingOnSlimeDampsHorizontalVelocityByTheVerticalScale() {
		PlayerState slime = walkingState((byte) 0);
		PlayerState control = walkingState((byte) 0);

		new ClientTick().tick(slime, IDLE, floor(true));
		new ClientTick().tick(control, IDLE, floor(false));

		double scale = 0.4 + Math.abs(control.deltaMovementY) * 0.2;
		assertTrue(Math.abs(control.deltaMovementY) < 0.1);
		assertNotEquals(0.0, control.deltaMovementX);
		assertRaw(control.deltaMovementX * scale, slime.deltaMovementX);
		assertRaw(control.deltaMovementZ * scale, slime.deltaMovementZ);
		assertRaw(control.deltaMovementY, slime.deltaMovementY);
	}

	@Test
	void shiftPressedThisTickSuppressesDampingEvenWhenPreviousInputWasIdle() {
		PlayerState slime = walkingState((byte) 0);
		PlayerState control = walkingState((byte) 0);

		new ClientTick().tick(slime, SHIFT, floor(true));
		new ClientTick().tick(control, SHIFT, floor(false));

		assertNotEquals(0.0, control.deltaMovementX);
		assertRaw(control.deltaMovementX, slime.deltaMovementX);
		assertRaw(control.deltaMovementZ, slime.deltaMovementZ);
		assertEquals(SHIFT.packedInput(), slime.inputKeyPresses);
	}

	@Test
	void shiftReleasedThisTickDampsEvenWhenPreviousInputWasShift() {
		PlayerState slime = walkingState((byte) PlayerInput.FLAG_SHIFT);
		PlayerState control = walkingState((byte) PlayerInput.FLAG_SHIFT);

		new ClientTick().tick(slime, IDLE, floor(true));
		new ClientTick().tick(control, IDLE, floor(false));

		double scale = 0.4 + Math.abs(control.deltaMovementY) * 0.2;
		assertNotEquals(0.0, control.deltaMovementX);
		assertRaw(control.deltaMovementX * scale, slime.deltaMovementX);
		assertRaw(control.deltaMovementZ * scale, slime.deltaMovementZ);
		assertEquals(0, slime.inputKeyPresses);
	}

	@Test
	void shiftHeldAcrossTicksSuppressesDamping() {
		PlayerState slime = walkingState((byte) PlayerInput.FLAG_SHIFT);
		PlayerState control = walkingState((byte) PlayerInput.FLAG_SHIFT);

		new ClientTick().tick(slime, SHIFT, floor(true));
		new ClientTick().tick(control, SHIFT, floor(false));

		assertRaw(control.deltaMovementX, slime.deltaMovementX);
		assertRaw(control.deltaMovementZ, slime.deltaMovementZ);
	}

	/** Standing on the floor's top face at y = 0, carrying horizontal velocity. */
	private static PlayerState walkingState(final byte previousInput) {
		PlayerState state = new PlayerState();
		state.x = 0.5;
		state.y = 0.0;
		state.z = 0.5;
		state.deltaMovementX = 0.2;
		state.deltaMovementY = RESTING_DELTA_Y;
		state.deltaMovementZ = -0.15;
		state.onGround = true;
		state.verticalCollision = true;
		state.verticalCollisionBelow = true;
		state.inputKeyPresses = previousInput;
		state.setBox(
		    AABB.around(state.x, state.y, state.z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));
		return state;
	}

	/**
	 * A floor of slime, or of a block with slime's friction and bounce but no
	 * {@code stepOn} body — the stress course's STEP_ON fault — so the only
	 * difference between the paired ticks is the body under test.
	 */
	private static SnapshotView floor(final boolean slime) {
		WorldSnapshot.BlockEntry.Builder floor =
		    WorldSnapshot.BlockEntry.builder(1, slime ? "minecraft:slime_block" : "test:slime_without_step_on")
		        .fullCube()
		        .friction(0.8F)
		        .bounceRestitution(1.0F)
		        .landing(slime ? WorldView.Landing.SLIME : WorldView.Landing.ORDINARY)
		        .suffocation(WorldSnapshot.Suffocation.NO);
		if (slime) {
			floor.stepOn(WorldView.StepOn.SLIME);
		}
		WorldSnapshot.Builder grid =
		    WorldSnapshot.builder(WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING, -2, -2, -2, 5, 5, 5)
		        .palette(WorldSnapshot.BlockEntry.builder(0, "minecraft:air").build(), floor.build());
		for (int z = -2; z <= 2; z++) {
			for (int x = -2; x <= 2; x++) {
				grid.set(x, -1, z, 1);
			}
		}
		return new SnapshotView(grid.build());
	}

	private static void assertRaw(final double expected, final double actual) {
		assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
	}
}
