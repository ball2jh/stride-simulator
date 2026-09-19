package com.nettarion.stride.simulator.tick;

import static com.nettarion.stride.simulator.tick.RawBits.assertRaw;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.block.BlockBehavior;
import com.nettarion.stride.simulator.block.BubbleColumnBlock;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.world.CompleteWorldView;
import com.nettarion.stride.simulator.world.FlatFloorView;
import com.nettarion.stride.simulator.world.WorldView;

import org.junit.jupiter.api.Test;

final class ClientTickCreativeFlightTest {
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);
	private static final WorldView EMPTY = new EmptyWorld();

	@Test
	void jumpAndShiftApplyCreativeVerticalImpulseBeforeTravel() {
		PlayerState up = flyingState();
		PlayerState down = flyingState();
		PlayerInput jump = action(false, true, false, false);
		PlayerInput shift = action(false, false, true, false);

		new ClientTick().tick(up, jump, EMPTY);
		new ClientTick().tick(down, shift, EMPTY);

		double impulse = (double) (0.05F * 3.0F);
		assertRaw(10.0 + impulse, up.y, "travel moves by the pre-damping creative vertical impulse");
		assertRaw(impulse * 0.6, up.deltaMovementY);
		assertRaw(10.0 - impulse, down.y);
		assertRaw(-impulse * 0.6, down.deltaMovementY);
		assertEquals(PlayerState.Pose.STANDING, down.pose, "shift descends while flying; it does not crouch");
		assertFalse(down.crouching);
	}

	@Test
	void flyingSprintUsesAbilitiesSpeedAndAirDrag() {
		PlayerState state = flyingState();

		new ClientTick().tick(state, action(true, false, false, true), EMPTY);

		assertTrue(state.sprinting);
		double acceleration = (double) 0.98F * (double) (0.05F * 2.0F);
		assertRaw(0.5 + acceleration, state.z);
		assertRaw(acceleration * 0.91F, state.deltaMovementZ);
	}

	@Test
	void secondJumpEdgeTogglesFlightAndTimerIsPostTickState() {
		PlayerState state = ordinaryAirborneState();
		state.mayfly = true;
		state.jumpTriggerTime = 5;

		new ClientTick().tick(state, action(false, true, false, false), EMPTY);

		assertTrue(state.flying);
		assertEquals(0, state.jumpTriggerTime);
		assertRaw((double) (0.05F * 3.0F) * 0.6, state.deltaMovementY);
	}

	@Test
	void firstJumpEdgeOpensWindowThenPlayerAiStepDecrementsIt() {
		PlayerState state = ordinaryAirborneState();
		state.mayfly = true;

		new ClientTick().tick(state, action(false, true, false, false), EMPTY);

		assertFalse(state.flying);
		assertEquals(6, state.jumpTriggerTime);
	}

	@Test
	void landingDisablesNonSpectatorFlightAfterTravel() {
		PlayerState state = flyingState();
		state.y = 0.1;
		state.setBox(
		    AABB.around(state.x, state.y, state.z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));
		state.deltaMovementY = -0.2;

		new ClientTick().tick(state, IDLE, FlatFloorView.ordinary(0, -64));

		assertTrue(state.onGround);
		assertFalse(state.flying);
		assertRaw(0.0, state.fallDistance);
		assertRaw(-0.2 * 0.6, state.deltaMovementY,
		    "Player.travel restores damped pre-collision Y before flight is disabled");
	}

	@Test
	void flyingSkipsFluidCurrentAndBubbleImpulseButStillTracksWater() {
		PlayerState state = flyingState();
		state.y = 0.1;
		state.setBox(
		    AABB.around(state.x, state.y, state.z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));

		new ClientTick().tick(state, IDLE, new FlowingBubbleWorld());

		assertTrue(state.waterHeight > 0.0);
		assertRaw(0.0, state.deltaMovementX);
		assertRaw(0.0, state.deltaMovementY);
		assertRaw(0.0, state.deltaMovementZ);
	}

	@Test
	void impossibleFlyingAbilityCombinationFailsClosed() {
		PlayerState state = flyingState();
		state.mayfly = false;

		assertThrows(UnimplementedMechanicException.class, () -> new ClientTick().tick(state, IDLE, EMPTY));
	}

	private static PlayerState flyingState() {
		PlayerState state = ordinaryAirborneState();
		state.mayfly = true;
		state.flying = true;
		state.flyingSpeed = 0.05F;
		return state;
	}

	private static PlayerState ordinaryAirborneState() {
		PlayerState state = new PlayerState();
		state.x = 0.5;
		state.y = 10.0;
		state.z = 0.5;
		state.setBox(
		    AABB.around(state.x, state.y, state.z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));
		return state;
	}

	private static PlayerInput action(
	    final boolean forward, final boolean jump, final boolean sneak, final boolean sprint) {
		return new PlayerInput(forward, false, false, false, jump, sneak, sprint, 0.0F, 0.0F);
	}

	private static class EmptyWorld implements CompleteWorldView {
		@Override
		public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final CollisionBuffer target) {
			target.clear();
		}
	}

	private static final class FlowingBubbleWorld extends EmptyWorld {
		@Override
		public boolean hasFluids() {
			return true;
		}
		@Override
		public boolean hasBubbleColumns() {
			return true;
		}
		@Override
		public void fluidAt(final int x, final int y, final int z, final FluidSample target) {
			if (x == 0 && y >= 0 && y <= 1 && z == 0) {
				target.set(FluidSample.Kind.WATER, 1.0, 1.0, 0.0, 0.0, true);
			} else {
				target.clear();
			}
		}
		@Override
		public BubbleColumnMode bubbleColumnModeAt(final int x, final int y, final int z) {
			return x == 0 && y == 0 && z == 0 ? BubbleColumnMode.PUSH_UP : BubbleColumnMode.NONE;
		}
		@Override
		public BlockBehavior behaviorAt(final int x, final int y, final int z) {
			return x == 0 && y == 0 && z == 0 ? BubbleColumnBlock.PUSH_UP : BlockBehavior.INERT;
		}
	}
}
