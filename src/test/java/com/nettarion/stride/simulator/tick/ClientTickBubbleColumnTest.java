package com.nettarion.stride.simulator.tick;

import static com.nettarion.stride.simulator.tick.RawBits.assertRaw;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.block.BlockBehavior;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.world.CompleteWorldView;
import com.nettarion.stride.simulator.world.WorldView;

import org.junit.jupiter.api.Test;

final class ClientTickBubbleColumnTest {
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);

	@Test
	void interiorPushUpRunsAfterWaterTravelAndResetsFallDistance() {
		PlayerState state = stateAt(0.5, 0.1, 0.5);
		state.fallDistance = 4.0;

		new ClientTick().tick(state, IDLE, new BubbleWorld(WorldView.BubbleColumnMode.PUSH_UP, true, false));

		assertRaw(0.1, state.y, "travel completes before the bubble callback changes velocity");
		assertRaw(-0.005 + 0.06, state.deltaMovementY);
		assertEquals(0.0, state.fallDistance);
	}

	@Test
	void interiorDragDownUsesInteriorCapAndReset() {
		PlayerState state = stateAt(0.5, 0.1, 0.5);
		state.deltaMovementY = -0.5;
		state.fallDistance = 4.0;

		new ClientTick().tick(state, IDLE, new BubbleWorld(WorldView.BubbleColumnMode.DRAG_DOWN, true, false));

		assertRaw(-0.3, state.deltaMovementY);
		assertEquals(0.0, state.fallDistance);
	}

	@Test
	void surfaceBranchRequiresBothEmptyCollisionAndEmptyFluidAbove() {
		PlayerState surface = stateAt(0.5, 0.1, 0.5);
		PlayerState waterAbove = surface.copy();
		PlayerState solidAbove = surface.copy();

		new ClientTick().tick(surface, IDLE, new BubbleWorld(WorldView.BubbleColumnMode.PUSH_UP, false, false));
		new ClientTick().tick(waterAbove, IDLE, new BubbleWorld(WorldView.BubbleColumnMode.PUSH_UP, true, false));
		new ClientTick().tick(solidAbove, IDLE, new BubbleWorld(WorldView.BubbleColumnMode.PUSH_UP, false, true));

		assertRaw(-0.005 + 0.1, surface.deltaMovementY);
		assertRaw(-0.005 + 0.06, waterAbove.deltaMovementY);
		assertRaw(-0.005 + 0.06, solidAbove.deltaMovementY);
	}

	@Test
	void surfaceBranchAsksTheContextFreeShapeOfAContextSensitiveBlockAbove() {
		// BubbleColumnBlock asks stateAbove.getCollisionShape(level, pos) with the
		// empty context. Scaffolding answers its stable shape there whatever the
		// player-context capture recorded; powder snow answers empty.
		PlayerState scaffoldingAbove = stateAt(0.5, 0.1, 0.5);
		PlayerState unstableScaffoldingAbove = stateAt(0.5, 0.1, 0.5);
		PlayerState powderAbove = stateAt(0.5, 0.1, 0.5);

		new ClientTick().tick(scaffoldingAbove, IDLE,
		    new BubbleWorld(WorldView.BubbleColumnMode.PUSH_UP, false, false, false,
		        WorldView.CollisionBehavior.SCAFFOLDING_SUPPORTED));
		new ClientTick().tick(unstableScaffoldingAbove, IDLE,
		    new BubbleWorld(WorldView.BubbleColumnMode.PUSH_UP, false, false, false,
		        WorldView.CollisionBehavior.SCAFFOLDING_UNSTABLE_BOTTOM));
		new ClientTick().tick(powderAbove, IDLE,
		    new BubbleWorld(WorldView.BubbleColumnMode.PUSH_UP, false, true, false,
		        WorldView.CollisionBehavior.POWDER_SNOW_NO_BOOTS));

		assertRaw(
		    -0.005 + 0.06, scaffoldingAbove.deltaMovementY, "captured empty, context-free stable: inside the column");
		assertRaw(-0.005 + 0.06, unstableScaffoldingAbove.deltaMovementY);
		assertRaw(-0.005 + 0.1, powderAbove.deltaMovementY, "captured full, context-free empty: above the column");
	}

	@Test
	void interiorUsesExactUpAndDownCaps() {
		PlayerState interiorUp = stateAt(0.5, 0.1, 0.5);
		interiorUp.deltaMovementY = 0.81;
		PlayerState interiorDown = stateAt(0.5, 0.6, 0.5);
		interiorDown.deltaMovementY = -0.5;

		new ClientTick().tick(interiorUp, IDLE, new BubbleWorld(WorldView.BubbleColumnMode.PUSH_UP, true, false));
		new ClientTick().tick(interiorDown, IDLE, new BubbleWorld(WorldView.BubbleColumnMode.DRAG_DOWN, true, false));

		assertRaw(0.7, interiorUp.deltaMovementY);
		assertRaw(-0.3, interiorDown.deltaMovementY);
	}

	@Test
	void movementBeyondMovedFarThresholdRunsPreciseTraversal() {
		// The column this world reports is far below the player's box; a
		// bubble callback would clamp deltaMovementY, so agreement with plain gravity
		// also shows the traversal did not spuriously reach it.
		PlayerState state = stateAt(0.5, 2.0, 0.5);
		state.deltaMovementX = 1.1;

		new ClientTick().tick(state, IDLE, new BubbleWorld(WorldView.BubbleColumnMode.PUSH_UP, false, false));

		assertRaw(Double.longBitsToDouble(0x3ff999999999999aL), state.x);
		assertRaw(Double.longBitsToDouble(0x4000000000000000L), state.y);
		assertRaw(Double.longBitsToDouble(0x3fe0000000000000L), state.z);
		assertRaw(Double.longBitsToDouble(0x3ff004189b333334L), state.deltaMovementX);
		assertRaw(Double.longBitsToDouble(0xbfb41205c28f5c29L), state.deltaMovementY);
		assertRaw(0.0, state.deltaMovementZ);
	}

	@Test
	void fallingVisitsExposedTopBeforeOverlappingInteriorColumn() {
		PlayerState state = stateAt(0.5, 1.0, 0.5);
		state.deltaMovementY = -0.5;

		new ClientTick().tick(state, IDLE, new BubbleWorld(WorldView.BubbleColumnMode.DRAG_DOWN, true, false, true));

		// The exposed y=1 callback subtracts first; the y=0 interior callback
		// then clamps back to -0.3. Ascending endpoint order would leave -0.33.
		assertRaw(-0.3, state.deltaMovementY);
	}

	private static PlayerState stateAt(final double x, final double y, final double z) {
		PlayerState state = new PlayerState();
		state.x = x;
		state.y = y;
		state.z = z;
		state.setBox(AABB.around(x, y, z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));
		state.onGround = false;
		return state;
	}

	private static final class BubbleWorld implements CompleteWorldView {
		private final BubbleColumnMode mode;
		private final boolean waterAbove;
		private final boolean collisionAbove;
		private final boolean stacked;
		private final CollisionBehavior aboveBehavior;

		private BubbleWorld(final BubbleColumnMode mode, final boolean waterAbove, final boolean collisionAbove) {
			this(mode, waterAbove, collisionAbove, false);
		}

		private BubbleWorld(final BubbleColumnMode mode, final boolean waterAbove, final boolean collisionAbove,
		    final boolean stacked) {
			this(mode, waterAbove, collisionAbove, stacked, CollisionBehavior.ORDINARY);
		}

		private BubbleWorld(final BubbleColumnMode mode, final boolean waterAbove, final boolean collisionAbove,
		    final boolean stacked, final CollisionBehavior aboveBehavior) {
			this.mode = mode;
			this.waterAbove = waterAbove;
			this.collisionAbove = collisionAbove;
			this.stacked = stacked;
			this.aboveBehavior = aboveBehavior;
		}

		@Override
		public CollisionBehavior collisionBehaviorAt(final int x, final int y, final int z) {
			return x == 0 && y == 1 && z == 0 ? this.aboveBehavior : CollisionBehavior.ORDINARY;
		}

		@Override
		public boolean hasFluids() {
			return this.mode != BubbleColumnMode.NONE;
		}
		@Override
		public boolean hasBubbleColumns() {
			return this.mode != BubbleColumnMode.NONE;
		}

		@Override
		public void fluidAt(final int x, final int y, final int z, final FluidSample target) {
			if (x == 0 && z == 0 && (y == 0 || y == 1 && (this.waterAbove || this.stacked))) {
				target.set(FluidSample.Kind.WATER, 1.0, 0.0, 0.0, 0.0, true);
			} else {
				target.clear();
			}
		}

		@Override
		public BubbleColumnMode bubbleColumnModeAt(final int x, final int y, final int z) {
			return x == 0 && z == 0 && (y == 0 || y == 1 && this.stacked) ? this.mode : BubbleColumnMode.NONE;
		}

		@Override
		public BlockBehavior behaviorAt(final int x, final int y, final int z) {
			return BlockBehavior.bubbleColumn(bubbleColumnModeAt(x, y, z));
		}

		@Override
		public boolean hasCollisionShapeAt(final int x, final int y, final int z) {
			return this.collisionAbove && x == 0 && y == 1 && z == 0;
		}

		@Override
		public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final CollisionBuffer target) {
			target.clear();
		}
	}
}
