package com.nettarion.stride.simulator.tick;

import static com.nettarion.stride.simulator.tick.RawBits.assertRaw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.world.CompleteWorldView;
import com.nettarion.stride.simulator.world.FlatFloorView;
import com.nettarion.stride.simulator.world.SupportCell;

import org.junit.jupiter.api.Test;

final class ClientTickWaterTest {
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);
	private static final PlayerInput SPRINT_FORWARD =
	    new PlayerInput(true, false, false, false, false, false, true, 0.0F, 0.0F);
	private static final PlayerInput JUMP = new PlayerInput(false, false, false, false, true, false, false, 0.0F, 0.0F);
	private static final PlayerInput SHIFT =
	    new PlayerInput(false, false, false, false, false, true, false, 0.0F, 0.0F);

	@Test
	void firstContactRefreshesWaterTrackerAfterAirTravel() {
		PlayerState state = stateAt(0.5, 0.0, 0.65);
		state.deltaMovementZ = 0.2;
		state.fallDistance = 3.0;

		new ClientTick().tick(state, IDLE, new FluidWorld(FluidSample.Kind.WATER, true, 1.0, 1));

		assertTrue(
		    state.waterHeight > 0.0, "LivingEntity.checkFallDamage refreshes a previously dry tracker after movement");
		assertEquals(0.0, state.fallDistance);
		assertFalse(state.eyeInWater, "the eye column has not crossed the cell boundary even though the box has");
		assertRaw(0.5, state.x, "post-move first-contact current changes velocity, not the completed move");
		assertRaw(0.014 * (double) (0.6F * 0.91F), state.deltaMovementX);
	}

	@Test
	void sprintEligibilityUsesPreviousEyeRatherThanFreshFluidScan() {
		PlayerState state = stateAt(0.5, 0.0, 0.5);
		state.onGround = false;
		state.eyeInWater = false;
		ClientTick simulator = new ClientTick();
		FluidWorld deepWater = new FluidWorld(FluidSample.Kind.WATER, true, 0.0, Integer.MIN_VALUE);

		simulator.tick(state, SPRINT_FORWARD, deepWater);

		assertTrue(state.eyeInWater, "baseTick's fresh scan sees the submerged eye");
		assertFalse(
		    state.sprinting, "the same tick still uses the previous dry-eye latch and treats this as shallow water");

		simulator.tick(state, SPRINT_FORWARD, deepWater);
		assertTrue(state.sprinting, "the fresh eye result becomes the previous-eye latch on the following tick");
	}

	@Test
	void submergedSourceWaterUsesWaterJumpAndShiftAcceleration() {
		ClientTick simulator = new ClientTick();
		FluidWorld deepWater = new FluidWorld(FluidSample.Kind.WATER, true, 0.0, Integer.MIN_VALUE);
		// Leave space below the box so the shift impulse is not clipped by the
		// stone floor and replaced by collision restitution before water drag.
		PlayerState jumping = stateAt(0.5, 0.1, 0.5);
		jumping.onGround = false;
		PlayerState sinking = jumping.copy();

		simulator.tick(jumping, JUMP, deepWater);
		simulator.tick(sinking, SHIFT, deepWater);

		assertRaw((double) 0.04F * (double) 0.8F - 0.08 / 16.0, jumping.deltaMovementY);
		assertRaw((double) -0.04F * (double) 0.8F - 0.08 / 16.0, sinking.deltaMovementY);
		assertTrue(jumping.waterHeight > 1.0);
		assertTrue(jumping.eyeInWater);
	}

	@Test
	void currentUsesXYZScanOrderAndRunningMaximumShallowScale() {
		PlayerState shallowFirst = stateAt(0.5, 0.1, 1.0);
		shallowFirst.onGround = false;
		PlayerState deepFirst = shallowFirst.copy();

		new ClientTick().tick(shallowFirst, IDLE, new FlowWorld((x, y, z, target) -> {
			if (x == 0 && y == 0 && (z == 0 || z == 1)) {
				target.set(FluidSample.Kind.WATER, z == 0 ? 0.3 : 1.0, 1.0, 0.0, 0.0, false);
			} else {
				target.clear();
			}
		}));
		new ClientTick().tick(deepFirst, IDLE, new FlowWorld((x, y, z, target) -> {
			if (x == 0 && y == 0 && (z == 0 || z == 1)) {
				target.set(FluidSample.Kind.WATER, z == 0 ? 1.0 : 0.3, 1.0, 0.0, 0.0, false);
			} else {
				target.clear();
			}
		}));

		double shallowHeight = 0.3 - shallowFirst.y;
		double shallowFirstImpulse = (shallowHeight + 1.0) * (1.0 / 2) * 0.014;
		assertRaw(0.5 + shallowFirstImpulse, shallowFirst.x);
		assertRaw(0.5 + 0.014, deepFirst.x, "deep z=0 raises the running maximum before z=1 is accumulated");
	}

	@Test
	void fluidTopThresholdIsInclusiveAtDeflatedBoxMinimum() {
		PlayerState equal = stateAt(0.5, 0.1, 0.5);
		equal.onGround = false;
		PlayerState below = equal.copy();
		double threshold = equal.boundingBoxMinY + 0.001;

		new ClientTick().tick(equal, IDLE, new FlowWorld(singleCell(threshold, 1.0)));
		new ClientTick().tick(below, IDLE, new FlowWorld(singleCell(Math.nextDown(threshold), 1.0)));

		assertTrue(equal.waterHeight > 0.0, "fluidTop == deflated minY is included");
		assertEquals(0.0, below.waterHeight, "the immediately lower raw double is outside the tracker box");
		assertRaw(0.5, equal.x, "shallow-scaled accumulated flow remains below the tracker push threshold");
	}

	@Test
	void weakCurrentUsesMinimumPushButOldVelocityBoundaryIsStrict() {
		PlayerState resting = stateAt(0.5, 0.1, 0.5);
		resting.onGround = false;
		PlayerState atBoundary = resting.copy();
		atBoundary.deltaMovementX = 0.003;
		FlowWorld weak = new FlowWorld(singleCell(1.0, 0.1));

		new ClientTick().tick(resting, IDLE, weak);
		new ClientTick().tick(atBoundary, IDLE, weak);

		assertRaw(0.5 + 0.0045000000000000005, resting.x);
		assertRaw(0.5 + 0.003 + 0.1 * 0.014, atBoundary.x,
		    "abs(oldX)==.003 does not satisfy vanilla's strict minimum-push guard");
	}

	@Test
	void cancellationAndNormalizeThresholdCanProduceNoPush() {
		PlayerState cancelled = stateAt(0.5, 0.1, 1.0);
		cancelled.onGround = false;
		PlayerState tinyAverage = stateAt(1.0, 0.1, 1.0);
		tinyAverage.onGround = false;

		new ClientTick().tick(cancelled, IDLE, new FlowWorld((x, y, z, target) -> {
			if (x == 0 && y == 0 && (z == 0 || z == 1)) {
				target.set(FluidSample.Kind.WATER, 1.0, z == 0 ? 1.0 : -1.0, 0.0, 0.0, false);
			} else {
				target.clear();
			}
		}));
		new ClientTick().tick(tinyAverage, IDLE, new FlowWorld((x, y, z, target) -> {
			if ((x == 0 || x == 1) && (y == 0 || y == 1) && (z == 0 || z == 1)) {
				target.set(FluidSample.Kind.WATER, 1.0, x == 0 && y == 0 && z == 0 ? 0.004 : 0.0, 0.0, 0.0, false);
			} else {
				target.clear();
			}
		}));

		assertRaw(0.5, cancelled.x);
		assertRaw(1.0, tinyAverage.x, "the averaged impulse is below Vec3.normalize's 1e-5F zero threshold");
	}

	@Test
	void dryWorldClearsRestoredFluidStateAndKeepsAirTravel() {
		PlayerState state = stateAt(0.5, 2.0, 0.5);
		state.onGround = false;
		state.waterHeight = 2.0;
		state.lavaHeight = 1.0;
		state.eyeInWater = true;
		state.swimming = true;

		new ClientTick().tick(state, IDLE, FlatFloorView.ordinary(0, -64));

		assertEquals(0.0, state.waterHeight);
		assertEquals(0.0, state.lavaHeight);
		assertFalse(state.eyeInWater);
		assertFalse(state.swimming);
		assertEquals(PlayerState.Pose.STANDING, state.pose);
		assertRaw(-0.08 * 0.98F, state.deltaMovementY);
	}

	@Test
	void shiftedSwimmingStillUsesVanillaLedgeBackoff() {
		PlayerState state = stateAt(0.5, 0.0, 0.95);
		state.deltaMovementZ = 0.4;
		state.setSprinting(true);
		state.swimming = true;
		state.eyeInWater = true;

		new ClientTick().tick(state, SHIFT, new FluidLedgeWorld(FluidSample.Kind.WATER, true, 0.0, Integer.MIN_VALUE));

		assertEquals(Double.doubleToRawLongBits(1.25), Double.doubleToRawLongBits(state.z),
		    "Player.maybeBackOffFromEdge has no swimming exclusion");
	}

	@Test
	void firstDryExitTickRetainsVisuallyCrawlingInputSlowdown() {
		PlayerState state = stateAt(0.5, 2.0, 0.5);
		state.pose = PlayerState.Pose.SWIMMING;
		state.setBox(AABB.around(state.x, state.y, state.z, state.pose.width, state.pose.height));
		state.onGround = false;
		state.setSprinting(true);
		state.swimming = true;
		state.waterHeight = 1.0;
		state.eyeInWater = true;
		PlayerInput forward = new PlayerInput(true, false, false, false, false, false, false, 0.0F, 0.0F);

		new ClientTick().tick(state, forward, FlatFloorView.ordinary(0, -64));

		assertEquals(Float.floatToRawIntBits(0.29400003F), Float.floatToRawIntBits(state.zza),
		    "SWIMMING pose outside water is visually crawling until end-of-tick pose refresh");
		assertEquals(PlayerState.Pose.STANDING, state.pose);
		assertFalse(state.swimming);
		assertEquals(0.0, state.waterHeight);
	}

	private static PlayerState stateAt(final double x, final double y, final double z) {
		PlayerState state = new PlayerState();
		state.x = x;
		state.y = y;
		state.z = z;
		state.setBox(AABB.around(x, y, z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));
		state.onGround = true;
		return state;
	}

	private static FluidPattern singleCell(final double height, final double flowX) {
		return (x, y, z, target) -> {
			if (x == 0 && y == 0 && z == 0) {
				target.set(FluidSample.Kind.WATER, height, flowX, 0.0, 0.0, false);
			} else {
				target.clear();
			}
		};
	}

	@FunctionalInterface
	private interface FluidPattern {
		void sample(int x, int y, int z, FluidSample target);
	}

	private static final class FlowWorld implements CompleteWorldView {
		private final FluidPattern fluids;

		private FlowWorld(final FluidPattern fluids) {
			this.fluids = fluids;
		}

		@Override
		public boolean hasFluids() {
			return true;
		}

		@Override
		public void fluidAt(final int x, final int y, final int z, final FluidSample target) {
			this.fluids.sample(x, y, z, target);
		}

		@Override
		public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final CollisionBuffer target) {
			target.clear();
		}
	}

	/** Full source-water columns from y=0..1, optionally beginning at a Z boundary. */
	private static class FluidWorld implements CompleteWorldView {
		private final FluidSample.Kind kind;
		private final boolean source;
		private final double flowX;
		private final int waterFromZ;

		private FluidWorld(
		    final FluidSample.Kind kind, final boolean source, final double flowX, final int waterFromZ) {
			this.kind = kind;
			this.source = source;
			this.flowX = flowX;
			this.waterFromZ = waterFromZ;
		}

		@Override
		public boolean hasFluids() {
			return true;
		}

		@Override
		public void fluidAt(final int x, final int y, final int z, final FluidSample target) {
			if (y >= 0 && y <= 1 && z >= this.waterFromZ) {
				target.set(this.kind, 1.0, this.flowX, 0.0, 0.0, this.source);
			} else {
				target.clear();
			}
		}

		@Override
		public void findSupportingBlock(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final double atX, final double atY, final double atZ,
		    final SupportCell out) {
			out.clear();
			if (minY < 0.0 && maxY >= 0.0) {
				out.set(Mth.floor(atX), -1, Mth.floor(atZ));
			}
		}

		@Override
		public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final CollisionBuffer target) {
			target.clear();
			if (minY < 0.0 && maxY > -1.0) {
				target.add(Math.floor(minX), -1.0, Math.floor(minZ), Math.ceil(maxX), 0.0, Math.ceil(maxZ));
			}
		}
	}

	private static final class FluidLedgeWorld extends FluidWorld {
		private FluidLedgeWorld(
		    final FluidSample.Kind kind, final boolean source, final double flowX, final int waterFromZ) {
			super(kind, source, flowX, waterFromZ);
		}

		@Override
		public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final CollisionBuffer target) {
			target.clear();
			if (minY < 0.0 && maxY > -1.0 && minZ < 1.0) {
				target.add(Math.floor(minX), -1.0, Math.floor(minZ), Math.ceil(maxX), 0.0, 1.0);
			}
		}

		@Override
		public void findSupportingBlock(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final double atX, final double atY, final double atZ,
		    final SupportCell out) {
			out.clear();
			if (minY < 0.0 && maxY >= 0.0 && minZ < 1.0) {
				out.set(Mth.floor(atX), -1, Math.min(0, Mth.floor(atZ)));
			}
		}
	}
}
