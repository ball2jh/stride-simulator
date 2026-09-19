package com.nettarion.stride.simulator.tick;

import static com.nettarion.stride.simulator.tick.RawBits.assertRaw;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.world.CompleteWorldView;

import org.junit.jupiter.api.Test;

final class ClientTickLavaTest {
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);
	private static final PlayerInput JUMP = new PlayerInput(false, false, false, false, true, false, false, 0.0F, 0.0F);

	@Test
	void shallowAndDeepLavaUseDifferentVerticalDamping() {
		PlayerState shallow = stateAt(0.5, 0.1, 0.5);
		shallow.onGround = false;
		shallow.deltaMovementX = 0.1;
		PlayerState deep = shallow.copy();

		new ClientTick().tick(shallow, IDLE, new LavaWorld(0.3, 0.0, false));
		new ClientTick().tick(deep, IDLE, new LavaWorld(1.0, 0.0, false));

		assertRaw(0.6, shallow.x);
		assertRaw(0.05, shallow.deltaMovementX);
		assertRaw(-0.005 - 0.08 / 4.0, shallow.deltaMovementY);
		assertRaw(0.6, deep.x);
		assertRaw(0.05, deep.deltaMovementX);
		assertRaw(-0.08 / 4.0, deep.deltaMovementY);
	}

	@Test
	void airborneLavaJumpAddsPointZeroFourBeforeLavaTravel() {
		PlayerState shallow = stateAt(0.5, 0.1, 0.5);
		shallow.onGround = false;
		PlayerState deep = shallow.copy();

		new ClientTick().tick(shallow, JUMP, new LavaWorld(0.3, 0.0, false));
		new ClientTick().tick(deep, JUMP, new LavaWorld(1.0, 0.0, false));

		assertRaw(0.1 + (double) 0.04F, shallow.y);
		assertRaw((double) 0.04F * (double) 0.8F - 0.08 / 16.0 - 0.08 / 4.0, shallow.deltaMovementY);
		assertRaw(0.1 + (double) 0.04F, deep.y);
		assertRaw((double) 0.04F * 0.5 - 0.08 / 4.0, deep.deltaMovementY);
	}

	@Test
	void shallowGroundedLavaUsesOrdinaryGroundJump() {
		PlayerState state = stateAt(0.5, 0.1, 0.5);
		state.onGround = true;

		new ClientTick().tick(state, JUMP, new LavaWorld(0.3, 0.0, false));

		assertRaw(0.1 + (double) 0.42F, state.y);
		assertRaw((double) 0.42F * (double) 0.8F - 0.08 / 16.0 - 0.08 / 4.0, state.deltaMovementY);
		assertEquals(10, state.noJumpDelay);
	}

	@Test
	void lavaHalvesExistingFallDistanceThenStillAccumulatesDescent() {
		PlayerState state = stateAt(0.5, 0.5, 0.5);
		state.onGround = false;
		state.deltaMovementY = -0.1;
		state.fallDistance = 8.0;

		new ClientTick().tick(state, IDLE, new LavaWorld(1.0, 0.0, false));

		assertRaw(4.0 - (float) -0.1, state.fallDistance);
	}

	@Test
	void lavaJumpOutUsesFreeBankProbe() {
		PlayerState state = stateAt(0.5, 0.8, 0.65);
		state.onGround = false;
		state.deltaMovementZ = 0.2;

		new ClientTick().tick(state, IDLE, new LavaWorld(1.0, 0.0, true));

		assertEquals(Double.doubleToRawLongBits((double) 0.3F), Double.doubleToRawLongBits(state.deltaMovementY));
	}

	@Test
	void overworldLavaCurrentUsesFastLavaFalseScale() {
		PlayerState state = stateAt(0.5, 0.1, 0.5);
		state.onGround = false;
		state.deltaMovementX = 0.003;

		new ClientTick().tick(state, IDLE, new LavaWorld(1.0, 1.0, false));

		assertRaw(0.5 + 0.003 + 0.0023333333333333335, state.x,
		    "the first current application occurs before movement; lava refresh applies again after it");
	}

	private static PlayerState stateAt(final double x, final double y, final double z) {
		PlayerState state = new PlayerState();
		state.x = x;
		state.y = y;
		state.z = z;
		state.setBox(AABB.around(x, y, z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));
		return state;
	}

	private static final class LavaWorld implements CompleteWorldView {
		private final double height;
		private final double flowX;
		private final boolean bankWall;

		private LavaWorld(final double height, final double flowX, final boolean bankWall) {
			this.height = height;
			this.flowX = flowX;
			this.bankWall = bankWall;
		}

		@Override
		public boolean hasFluids() {
			return true;
		}

		@Override
		public void fluidAt(final int x, final int y, final int z, final FluidSample target) {
			if (y == 0 && z == 0) {
				target.set(FluidSample.Kind.LAVA, this.height, this.flowX, 0.0, 0.0, true);
			} else {
				target.clear();
			}
		}

		@Override
		public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final CollisionBuffer target) {
			target.clear();
			if (this.bankWall && minX < 2.0 && maxX > -1.0 && minY < 1.0 && maxY > 0.0 && minZ < 2.0 && maxZ > 1.0) {
				target.add(-1.0, 0.0, 1.0, 2.0, 1.0, 2.0);
			}
		}
	}
}
