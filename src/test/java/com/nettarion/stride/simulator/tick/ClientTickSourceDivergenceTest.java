package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.world.CompleteWorldView;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.SupportCell;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Source-derived regressions for mixed branches that isolated fixtures missed. */
class ClientTickSourceDivergenceTest {
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);
	private static final PlayerInput FORWARD =
	    new PlayerInput(true, false, false, false, false, false, false, 0.0F, 0.0F);
	private static final PlayerInput JUMP = new PlayerInput(false, false, false, false, true, false, false, 0.0F, 0.0F);

	@Test
	void partialWorldViewIsRefusedBeforeStateMutation() {
		PlayerState state = new PlayerState();
		state.yRot = 12.0F;
		PlayerInput turning = PlayerInput.idle(90.0F, 30.0F);

		assertThrows(
		    UnimplementedMechanicException.class, () -> new ClientTick().tick(state, turning, new IncompleteWorld()));

		assertEquals(Float.floatToRawIntBits(12.0F), Float.floatToRawIntBits(state.yRot));
	}

	@Test
	void declaredFluidWithoutAResolverFailsClosed() {
		PlayerState state = new PlayerState();
		state.placeAt(0.5, 1.0, 0.5);

		assertThrows(
		    UnimplementedMechanicException.class, () -> new ClientTick().tick(state, IDLE, new MissingFluidWorld()));
	}

	@Test
	void retainedSupportingBlockDrivesNextTicksGroundFriction() {
		SupportWorld world = new SupportWorld(0.98F, 1.0F, 1.0F, 1.0F);
		PlayerState state = landAcrossCellBoundary(world);

		new ClientTick().tick(state, FORWARD, world);

		float speed = 0.1F * (0.21600002F / (0.98F * 0.98F * 0.98F));
		double expectedMove = (double) 0.98F * speed;
		assertRaw(0.5 + expectedMove, state.z);
	}

	@Test
	void retainedSupportingBlockDrivesNextTicksJumpFactor() {
		SupportWorld world = new SupportWorld(0.6F, 1.0F, 0.5F, 1.0F);
		PlayerState state = landAcrossCellBoundary(world);

		new ClientTick().tick(state, JUMP, world);

		assertRaw((double) (0.42F * 0.5F), state.y);
	}

	@Test
	void currentCellJumpFactorOverridesRetainedSupportFactor() {
		SupportWorld world = new SupportWorld(0.6F, 1.0F, 0.75F, 0.5F);
		PlayerState state = landAcrossCellBoundary(world);

		new ClientTick().tick(state, JUMP, world);

		assertRaw((double) (0.42F * 0.5F), state.y);
	}

	@Test
	void wallLikeSupportKeepsItsRetainedYForMovementLookups() {
		PlayerState state = stateAt(0.5, 0.0, 0.5);
		state.onGround = true;
		state.mainSupportingBlockPosPresent = true;
		state.mainSupportingBlockPosX = 0;
		state.mainSupportingBlockPosY = -2;
		state.mainSupportingBlockPosZ = 0;

		new ClientTick().tick(state, JUMP, new AirWorld() {
			@Override
			public int propertiesIn(
			    final int wanted, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
				return wanted & WorldView.PROPERTY_JUMP_FACTOR;
			}
			@Override
			public boolean retainsSupportPosAt(final int x, final int y, final int z) {
				return y == -2;
			}
			@Override
			public float jumpFactor(final int x, final int y, final int z) {
				return y == -2 ? 0.5F : 1.0F;
			}
		});

		assertRaw((double) (0.42F * 0.5F), state.y);
	}

	@Test
	void newlySelectedSupportingBlockDrivesPostMoveSpeedFactor() {
		SupportWorld world = new SupportWorld(0.6F, 0.4F, 1.0F, 1.0F);
		PlayerState state = landAcrossCellBoundary(world);
		state.deltaMovementX = 0.2;

		new ClientTick().tick(state, IDLE, world);

		assertRaw(0.2 * (double) 0.4F * (double) (0.6F * 0.91F), state.deltaMovementX);
	}

	@Test
	void airborneMoveClearsAndCanonicalizesRetainedSupport() {
		PlayerState state = stateAt(0.5, 10.0, 0.5);
		state.mainSupportingBlockPosPresent = true;
		state.mainSupportingBlockPosX = -3;
		state.mainSupportingBlockPosY = 9;
		state.mainSupportingBlockPosZ = 4;

		new ClientTick().tick(state, IDLE, new AirWorld());

		assertFalse(state.mainSupportingBlockPosPresent);
		assertEquals(0, state.mainSupportingBlockPosX);
		assertEquals(0, state.mainSupportingBlockPosY);
		assertEquals(0, state.mainSupportingBlockPosZ);
	}

	@Test
	void creativeFlightIgnoresBlockSpeedFactor() {
		PlayerState state = flyingState();
		state.deltaMovementX = 0.5;

		new ClientTick().tick(state, IDLE, new AirWorld() {
			@Override
			public boolean hasNonDefaultSpeedFactor() {
				return true;
			}
			@Override
			public int propertiesIn(
			    final int wanted, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
				return wanted & WorldView.PROPERTY_SPEED_FACTOR;
			}
			@Override
			public float speedFactor(final int x, final int y, final int z) {
				return 0.4F;
			}
		});

		assertRaw(0.5 * (double) 0.91F, state.deltaMovementX);
	}

	@Test
	void theHorizontalPositionIsClampedAUnitInsideTheCoordinateLimitEveryTick() {
		// Player.tick after super.tick(): X and Z clamp to +/-2.9999999E7.
		PlayerState state = stateAt(29999999.75, 10.0, -29999999.75);
		new ClientTick().tick(state, IDLE, new AirWorld());
		assertRaw(2.9999999E7, state.x);
		assertRaw(-2.9999999E7, state.z);
		assertRaw(2.9999999E7 - (double) (0.6F / 2.0F), state.boundingBoxMinX);
	}

	@Test
	void statesVanillasPlayerNeverHoldsRefuseInsteadOfSimulating() {
		java.util.List<java.util.function.Consumer<PlayerState>> corruptions = java.util.List.of(s
		    -> s.noJumpDelay = -1,
		    s
		    -> s.jumpTriggerTime = -1,
		    s
		    -> s.fallFlyTicks = -1,
		    s -> s.ticksFrozen = 141, s -> s.foodLevel = 21, s -> s.yya = 0.5F, s -> s.inputMoveVectorY = Float.NaN);
		for (var corruption : corruptions) {
			PlayerState state = stateAt(0.5, 10.0, 0.5);
			corruption.accept(state);
			org.junit.jupiter.api.Assertions.assertThrows(
			    com.nettarion.stride.simulator.UnimplementedMechanicException.class,
			    () -> new ClientTick().tick(state, IDLE, new AirWorld()));
		}
	}

	@Test
	void creativeFlightIgnoresClimbables() {
		// Player.onClimbable answers false while abilities.flying, so the climb
		// clamp and its fall reset never reach a flying player.
		PlayerState state = flyingState();
		state.deltaMovementX = 0.5;
		state.fallDistance = 0.5;

		new ClientTick().tick(state, IDLE, new AirWorld() {
			@Override
			public boolean hasClimbables() {
				return true;
			}
			@Override
			public int propertiesIn(
			    final int wanted, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
				return wanted & WorldView.PROPERTY_CLIMBABLE;
			}
			@Override
			public boolean onClimbableAt(final int x, final int y, final int z, final boolean fallFlying) {
				return true;
			}
		});

		assertRaw(0.5, state.x - 0.5);
	}

	@Test
	void creativeCapabilityDoesNotPreventFirstJumpEdgeFromStartingGlide() {
		PlayerState state = stateAt(0.5, 10.0, 0.5);
		state.mayfly = true;
		state.gliderUsable = true;

		new ClientTick().tick(state, JUMP, new AirWorld());

		assertTrue(state.fallFlying);
		assertFalse(state.flying);
		assertEquals(6, state.jumpTriggerTime);
	}

	@Test
	void lavaHeightSelectsFluidJumpThresholdWhenBothFluidTrackersArePositive() {
		PlayerState state = stateAt(0.95, 0.0, 0.5);
		state.onGround = true;

		new ClientTick().tick(state, JUMP, new MixedFluidWorld());

		assertRaw((double) 0.04F * (double) 0.8F - 0.08 / 16.0, state.deltaMovementY);
		assertEquals(0, state.noJumpDelay);
	}

	@Test
	void capturedSprintWindowControlsTheDoubleTapTimer() {
		PlayerState state = stateAt(0.5, 10.0, 0.5);
		state.sprintWindowTicks = 3;

		new ClientTick().tick(state, FORWARD, new AirWorld());

		assertEquals(3, state.sprintTriggerTime);
	}

	@Test
	void enabledAutoJumpFailsBeforeMovement() {
		PlayerState state = stateAt(0.5, 10.0, 0.5);
		state.autoJumpEnabled = true;
		state.yRot = 17.0F;
		state.xRot = -4.0F;

		assertThrows(UnimplementedMechanicException.class, () -> new ClientTick().tick(state, FORWARD, new AirWorld()));
		assertRaw(0.5, state.x);
		assertRaw(10.0, state.y);
		assertRaw(17.0F, state.yRot);
		assertRaw(-4.0F, state.xRot);
	}

	@Test
	void pendingAutoJumpFailsBeforeAnyStateMutation() {
		PlayerState state = stateAt(0.5, 10.0, 0.5);
		state.autoJumpTime = 1;
		PlayerState before = state.copy();

		assertThrows(UnimplementedMechanicException.class, () -> new ClientTick().tick(state, FORWARD, new AirWorld()));

		assertRaw(before.x, state.x);
		assertRaw(before.y, state.y);
		assertRaw(before.z, state.z);
		assertRaw(before.deltaMovementX, state.deltaMovementX);
		assertRaw(before.deltaMovementY, state.deltaMovementY);
		assertRaw(before.deltaMovementZ, state.deltaMovementZ);
		assertRaw(before.yRot, state.yRot);
		assertRaw(before.xRot, state.xRot);
	}

	@Test
	void fastLavaDimensionUsesTheLargerCurrentScale() {
		PlayerState slow = stateAt(0.5, 0.1, 0.5);
		PlayerState fast = slow.copy();
		fast.fastLava = true;
		ClientTick kernel = new ClientTick();

		kernel.tick(slow, IDLE, new FlowingLavaWorld());
		kernel.tick(fast, IDLE, new FlowingLavaWorld());

		assertTrue(fast.deltaMovementX > slow.deltaMovementX);
	}

	@Test
	void fallFlyingIgnoresBlockSpeedFactor() {
		PlayerState ordinary = fallFlyingState();
		PlayerState slowed = ordinary.copy();
		ClientTick kernel = new ClientTick();

		kernel.tick(ordinary, IDLE, new AirWorld());
		kernel.tick(slowed, IDLE, new AirWorld() {
			@Override
			public boolean hasNonDefaultSpeedFactor() {
				return true;
			}
			@Override
			public int propertiesIn(
			    final int wanted, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
				return wanted & WorldView.PROPERTY_SPEED_FACTOR;
			}
			@Override
			public float speedFactor(final int x, final int y, final int z) {
				return 0.4F;
			}
		});

		assertRaw(ordinary.deltaMovementX, slowed.deltaMovementX);
		assertRaw(ordinary.deltaMovementZ, slowed.deltaMovementZ);
	}

	@Test
	void fallFlyingContinuesThroughGlideThroughClimbable() {
		PlayerState ordinary = fallFlyingState();
		PlayerState throughVine = ordinary.copy();
		ClientTick kernel = new ClientTick();

		kernel.tick(ordinary, IDLE, new AirWorld());
		kernel.tick(throughVine, IDLE, new AirWorld() {
			@Override
			public boolean hasClimbables() {
				return true;
			}
			@Override
			public int propertiesIn(
			    final int wanted, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
				return wanted & WorldView.PROPERTY_CLIMBABLE;
			}
			@Override
			public boolean onClimbableAt(final int x, final int y, final int z, final boolean fallFlying) {
				return !fallFlying;
			}
		});

		assertTrue(throughVine.fallFlying);
		assertRaw(ordinary.deltaMovementX, throughVine.deltaMovementX);
		assertRaw(ordinary.deltaMovementY, throughVine.deltaMovementY);
		assertRaw(ordinary.deltaMovementZ, throughVine.deltaMovementZ);
	}

	@Test
	void alignedOpenTrapdoorAboveLadderIsClimbable() {
		WorldSnapshot.BlockEntry air = block(0, WorldView.Climbability.NONE);
		WorldSnapshot.BlockEntry ladder = block(1, WorldView.Climbability.LADDER_NORTH);
		WorldSnapshot.BlockEntry trapdoor = block(2, WorldView.Climbability.OPEN_TRAPDOOR_NORTH);
		int size = 5;
		int[] cells = new int[size * size * size];
		Arrays.fill(cells, 0);
		cells[index(size, 2, 1, 2)] = 1;
		cells[index(size, 2, 2, 2)] = 2;
		SnapshotView world = new SnapshotView(new WorldSnapshot(-2, -2, -2, size, size, size,
		    WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING, List.of(air, ladder, trapdoor), cells));
		PlayerState state = stateAt(0.5, 0.0, 0.5);
		state.deltaMovementX = 0.5;

		new ClientTick().tick(state, IDLE, world);

		assertRaw((double) 0.15F, state.x - 0.5);
	}

	@Test
	void waterCollisionWithClimbableAppliesLiftBeforeDamping() {
		PlayerState state = stateAt(0.5, 0.1, 0.7);
		state.deltaMovementZ = 0.4;

		new ClientTick().tick(state, IDLE, new WaterClimbableWallWorld());

		assertTrue(state.horizontalCollision);
		assertRaw(0.2 * (double) 0.8F - 0.08 / 16.0, state.deltaMovementY);
	}

	@Test
	void longMoveResetsFallDistanceWhenTheResolvedSegmentHits() {
		PlayerState state = stateAt(0.5, 10.0, 0.5);
		state.deltaMovementX = 2.0;
		state.fallDistance = 3.0;
		ResettingSegmentWorld world = new ResettingSegmentWorld();

		new ClientTick().tick(state, IDLE, world);

		assertEquals(1, world.queries);
		assertRaw(0.0, state.fallDistance);
	}

	private static PlayerState landAcrossCellBoundary(final SupportWorld world) {
		PlayerState state = stateAt(1.05, 0.1, 0.5);
		state.deltaMovementY = -0.2;
		new ClientTick().tick(state, IDLE, world);
		assertTrue(state.onGround, "the setup must select the support at x=0");
		return state;
	}

	private static PlayerState stateAt(final double x, final double y, final double z) {
		PlayerState state = new PlayerState();
		state.placeAt(x, y, z);
		return state;
	}

	private static PlayerState flyingState() {
		PlayerState state = stateAt(0.5, 10.0, 0.5);
		state.mayfly = true;
		state.flying = true;
		return state;
	}

	private static PlayerState fallFlyingState() {
		PlayerState state = stateAt(0.5, 20.0, 0.5);
		state.pose = PlayerState.Pose.FALL_FLYING;
		state.placeAt(state.x, state.y, state.z);
		state.fallFlying = true;
		state.gliderUsable = true;
		state.deltaMovementX = 0.25;
		state.deltaMovementY = -0.2;
		state.deltaMovementZ = 0.8;
		return state;
	}

	private static void assertRaw(final double expected, final double actual) {
		assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
	}

	private static int index(final int size, final int x, final int y, final int z) {
		return (y * size + z) * size + x;
	}

	private static WorldSnapshot.BlockEntry block(final int id, final WorldView.Climbability climbability) {
		return new WorldSnapshot.BlockEntry(id, "test:" + id, 0.6F, 1.0F, 1.0F, false, false,
		    WorldView.BubbleColumnMode.NONE, climbability != WorldView.Climbability.NONE,
		    WorldView.CollisionBehavior.ORDINARY, WorldSnapshot.Suffocation.NO, WorldView.InsideEffect.NONE, 0.0F,
		    false, WorldView.StepOn.NONE, false, climbability, List.of());
	}

	private static class AirWorld implements CompleteWorldView {
		@Override
		public boolean suffocatesAt(
		    final int cellX, final int cellZ, final double boundingBoxMinY, final double boundingBoxMaxY) {
			return false;
		}
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

	private static final class IncompleteWorld extends AirWorld {
		@Override
		public boolean movementFactsComplete() {
			return false;
		}
	}

	private static final class MissingFluidWorld extends AirWorld {
		@Override
		public boolean hasFluids() {
			return true;
		}
	}

	private static final class ResettingSegmentWorld extends AirWorld {
		private int queries;

		@Override
		public boolean resetsFallDistanceAlong(final double fromX, final double fromY, final double fromZ,
		    final double toX, final double toY, final double toZ) {
			this.queries++;
			return true;
		}
	}

	private static final class SupportWorld extends AirWorld {
		private final float supportFriction;
		private final float supportSpeed;
		private final float supportJump;
		private final float currentJump;

		private SupportWorld(
		    final float supportFriction, final float supportSpeed, final float supportJump, final float currentJump) {
			this.supportFriction = supportFriction;
			this.supportSpeed = supportSpeed;
			this.supportJump = supportJump;
			this.currentJump = currentJump;
		}

		@Override
		public int propertiesIn(
		    final int wanted, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
			return wanted
			    & (WorldView.PROPERTY_FRICTION | WorldView.PROPERTY_SPEED_FACTOR | WorldView.PROPERTY_JUMP_FACTOR);
		}

		@Override
		public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final CollisionBuffer target) {
			target.clear();
			if (minX < 1.0 && maxX > 0.0 && minY < 0.0 && maxY > -1.0 && minZ < 1.0 && maxZ > 0.0) {
				target.add(0.0, -1.0, 0.0, 1.0, 0.0, 1.0);
			}
		}

		@Override
		public void findSupportingBlock(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final double atX, final double atY, final double atZ,
		    final SupportCell out) {
			out.clear();
			if (minX < 1.0 && maxX > 0.0 && minY < 0.0 && maxY >= 0.0 && minZ < 1.0 && maxZ > 0.0) {
				out.set(0, -1, 0);
			}
		}

		@Override
		public float friction(final int x, final int y, final int z) {
			return x == 0 && y == -1 && z == 0 ? this.supportFriction : 0.6F;
		}

		@Override
		public float speedFactor(final int x, final int y, final int z) {
			return x == 0 && y == -1 && z == 0 ? this.supportSpeed : 1.0F;
		}

		@Override
		public float jumpFactor(final int x, final int y, final int z) {
			if (x == 1 && y == 0 && z == 0) return this.currentJump;
			return x == 0 && y == -1 && z == 0 ? this.supportJump : 1.0F;
		}
	}

	private static final class WaterClimbableWallWorld extends AirWorld {
		@Override
		public boolean hasFluids() {
			return true;
		}
		@Override
		public boolean hasClimbables() {
			return true;
		}

		@Override
		public int propertiesIn(
		    final int wanted, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
			return wanted & (WorldView.PROPERTY_FLUID | WorldView.PROPERTY_CLIMBABLE);
		}

		@Override
		public void fluidAt(final int x, final int y, final int z, final FluidSample target) {
			if (y >= 0 && y <= 2) {
				target.set(FluidSample.Kind.WATER, 1.0, 0.0, 0.0, 0.0, true);
			} else {
				target.clear();
			}
		}

		@Override
		public boolean onClimbableAt(final int x, final int y, final int z, final boolean fallFlying) {
			return x == 0 && y == 0 && z == 0;
		}

		@Override
		public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final CollisionBuffer target) {
			target.clear();
			if (minZ < 1.0 && maxZ > 1.0) {
				target.add(0.0, 0.0, 1.0, 1.0, 3.0, 2.0);
			}
			if (minY < 2.0 && maxY > 2.0) {
				target.add(-1.0, 2.0, -1.0, 2.0, 3.0, 2.0);
			}
		}
	}

	private static final class MixedFluidWorld extends AirWorld {
		@Override
		public boolean hasFluids() {
			return true;
		}

		@Override
		public int propertiesIn(
		    final int wanted, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
			return wanted & WorldView.PROPERTY_FLUID;
		}

		@Override
		public void fluidAt(final int x, final int y, final int z, final FluidSample target) {
			if (y != 0) {
				target.clear();
			} else if (x == 0) {
				target.set(FluidSample.Kind.WATER, 0.2, 0.0, 0.0, 0.0, true);
			} else if (x == 1) {
				target.set(FluidSample.Kind.LAVA, 1.0, 0.0, 0.0, 0.0, true);
			} else {
				target.clear();
			}
		}
	}

	private static final class FlowingLavaWorld extends AirWorld {
		@Override
		public boolean hasFluids() {
			return true;
		}

		@Override
		public int propertiesIn(
		    final int wanted, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
			return wanted & WorldView.PROPERTY_FLUID;
		}

		@Override
		public void fluidAt(final int x, final int y, final int z, final FluidSample target) {
			if (y == 0) {
				target.set(FluidSample.Kind.LAVA, 1.0, 1.0, 0.0, 0.0, true);
			} else {
				target.clear();
			}
		}
	}
}
