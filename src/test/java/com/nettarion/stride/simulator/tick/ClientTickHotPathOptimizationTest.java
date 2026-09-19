package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.world.CompleteWorldView;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.FluidSample;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClientTickHotPathOptimizationTest {
	@Test
	void oneStandingProbeClassifiesSameWidthCrouchingFallbackRawExactly() {
		CountingCeilingWorld world = new CountingCeilingWorld();
		PlayerState state = stateAtOrigin();
		state.mayfly = true;
		state.flying = true;

		new ClientTick().tick(state, PlayerInput.idle(0.0F, 0.0F), world);

		assertEquals(PlayerState.Pose.CROUCHING, state.pose);
		assertEquals(1, world.standingFitQueries);
		assertEquals(
		    0, world.crouchingFitQueries, "the rejected standing box also classified its same-width crouching subset");
		assertEquals(Float.floatToRawIntBits(PlayerState.Pose.CROUCHING.height),
		    Float.floatToRawIntBits((float) (state.boundingBoxMaxY - state.boundingBoxMinY)));
	}

	@Test
	void dryWorldNeverRunsPostMoveFluidSamplingAndPreservesRawAirResult() {
		DryCountingWorld world = new DryCountingWorld();
		PlayerState state = stateAtOrigin();
		state.y = 2.0;
		state.setBox(AABB.around(state.x, state.y, state.z, state.pose.width, state.pose.height));
		state.deltaMovementY = -0.2;

		new ClientTick().tick(state, PlayerInput.idle(0.0F, 0.0F), world);

		assertEquals(0, world.fluidSamples);
		assertEquals(Double.doubleToRawLongBits((-0.2 - 0.08) * (double) 0.98F),
		    Double.doubleToRawLongBits(state.deltaMovementY));
		assertEquals(0L, Double.doubleToRawLongBits(state.waterHeight));
		assertEquals(0L, Double.doubleToRawLongBits(state.lavaHeight));
	}

	private static PlayerState stateAtOrigin() {
		PlayerState state = new PlayerState();
		state.x = 0.5;
		state.y = 0.0;
		state.z = 0.5;
		state.setBox(AABB.around(state.x, state.y, state.z, state.pose.width, state.pose.height));
		return state;
	}

	private static class DryCountingWorld implements CompleteWorldView {
		/** Defined, not derived: no block in this fixture suffocates. */
		@Override
		public boolean suffocatesAt(
		    final int cellX, final int cellZ, final double boundingBoxMinY, final double boundingBoxMaxY) {
			return false;
		}

		private int fluidSamples;

		@Override
		public long collisionVersion() {
			return 0L;
		}
		private final long identity = com.nettarion.stride.simulator.world.WorldIdentity.next();
		@Override
		public long identity() {
			return this.identity;
		}
		@Override
		public void fluidAt(final int x, final int y, final int z, final FluidSample target) {
			this.fluidSamples++;
			throw new AssertionError("dry world must not be fluid-sampled");
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

	private static final class CountingCeilingWorld extends DryCountingWorld {
		private int standingFitQueries;
		private int crouchingFitQueries;

		@Override
		public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final CollisionBuffer target) {
			target.clear();
			if (Double.doubleToRawLongBits(minY) == Double.doubleToRawLongBits(1.0E-7)) {
				double standingMax = PlayerState.Pose.STANDING.height - 1.0E-7;
				double crouchingMax = PlayerState.Pose.CROUCHING.height - 1.0E-7;
				if (Double.doubleToRawLongBits(maxY) == Double.doubleToRawLongBits(standingMax)) {
					this.standingFitQueries++;
				} else if (Double.doubleToRawLongBits(maxY) == Double.doubleToRawLongBits(crouchingMax)) {
					this.crouchingFitQueries++;
				}
			}
			if (maxY > 1.6 && minY < 1.7 && maxX > 0.0 && minX < 1.0 && maxZ > 0.0 && minZ < 1.0) {
				target.add(0.0, 1.6, 0.0, 1.0, 1.7, 1.0);
			}
		}
	}
}
