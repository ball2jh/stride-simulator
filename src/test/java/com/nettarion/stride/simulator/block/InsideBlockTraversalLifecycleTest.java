package com.nettarion.stride.simulator.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.CompleteWorldView;
import com.nettarion.stride.simulator.world.WorldView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Contact acceptance, traversal limits, and reuse across complete block-effect phases. */
final class InsideBlockTraversalLifecycleTest {
	@Test
	void aShapeMissOnTheXArmCanContactOnTheZArmAndAppliesOnlyOnce() {
		ProbeBlock block = new ProbeBlock(new double[] {0, 0, 0.85, 1, 1, 1});
		ProbeWorld world = new ProbeWorld(1, block);
		Scratch scratch = new Scratch();
		scratch.hasMovementSegment = true;
		scratch.segmentFromX = 0.5;
		scratch.segmentFromZ = 0.5;
		scratch.segmentRequestedX = 1.0;
		scratch.segmentRequestedZ = 1.0;
		PlayerState state = at(1.5, 1.5);

		BlockEffects.applyEffectsFromBlocks(state, world, scratch);

		assertFalse(block.shapeHits.getFirst(), "the X arm misses the back of the cell");
		assertTrue(block.shapeHits.contains(true), "the Z arm must get another contact test");
		assertEquals(1, block.applications, "origin, swept, and destination visits share one contact");
		assertEquals(1, state.ticksFrozen);
	}

	@Test
	void refusalDoesNotRetainVisitedCellsOrPendingEffectsForTheNextPhase() {
		ProbeBlock block = new ProbeBlock(null);
		block.refuseOnce = true;
		ProbeWorld world = new ProbeWorld(0, block);
		Scratch scratch = new Scratch();
		scratch.oldX = 0.5;
		scratch.oldZ = 0.5;
		assertThrows(UnimplementedMechanicException.class,
		    () -> BlockEffects.applyEffectsFromBlocks(at(0.5, 0.5), world, scratch));

		PlayerState next = at(0.5, 0.5);
		BlockEffects.applyEffectsFromBlocks(next, world, scratch);
		assertEquals(2, block.applications, "the refused tick must not suppress the next contact");
		assertEquals(1, next.ticksFrozen, "only the new traversal's freeze survives");

		PlayerState again = at(0.5, 0.5);
		BlockEffects.applyEffectsFromBlocks(again, world, scratch);
		assertEquals(3, block.applications, "successful traversals reset too");
		assertEquals(1, again.ticksFrozen);
	}

	@Test
	void exhaustedSweepStopsWorldQueriesAndStillVisitsTheDestination() {
		ProbeBlock block = new ProbeBlock(null);
		ProbeWorld world = new ProbeWorld(40, block);
		Scratch scratch = new Scratch();
		scratch.oldX = 0.5;
		scratch.oldZ = 0.5;

		BlockEffects.applyEffectsFromBlocks(at(40.5, 0.5), world, scratch);

		assertFalse(world.queriedX.contains(20), "the budget stops queries along the long sweep");
		assertTrue(world.queriedX.contains(40), "the stationary destination fallback still runs");
		assertEquals(1, block.applications);
	}

	private static PlayerState at(final double x, final double z) {
		PlayerState state = new PlayerState();
		state.placeAt(x, 0.0, z);
		return state;
	}

	/** An observable contact hook with optional partial geometry and a one-shot refusal. */
	private static final class ProbeBlock extends BlockBehavior {
		private final double[] shape;

		private final List<Boolean> shapeHits = new ArrayList<>();

		private int applications;

		private boolean refuseOnce;

		private ProbeBlock(final double[] shape) {
			this.shape = shape;
		}

		@Override
		boolean hasEntityInside() {
			return true;
		}

		@Override
		boolean entityInsideShapeReached(
		    final PlayerState state, final int x, final int y, final int z, final Scratch scratch) {
			boolean hit =
			    this.shape == null || scratch.insideTraversal.collidedWithShapeMovingFrom(state, x, y, z, this.shape);
			this.shapeHits.add(hit);
			return hit;
		}

		@Override
		void entityInside(final PlayerState state, final int x, final int y, final int z, final boolean isPrecise,
		    final WorldView world, final Scratch scratch) {
			this.applications++;
			scratch.insideEffects.collectFreeze();
			if (this.refuseOnce) {
				this.refuseOnce = false;
				throw new UnimplementedMechanicException(
				    RefusalCause.UNMODELED_BLOCK, "test contact refuses after collection");
			}
		}
	}

	/** Empty geometry with one instrumented inside-effect hook at (cellX, 0, 0). */
	private static final class ProbeWorld implements CompleteWorldView {
		private final int cellX;

		private final ProbeBlock block;

		private final List<Integer> queriedX = new ArrayList<>();

		private ProbeWorld(final int cellX, final ProbeBlock block) {
			this.cellX = cellX;
			this.block = block;
		}

		@Override
		public long collisionVersion() {
			return 0L;
		}

		@Override
		public BlockBehavior behaviorAt(final int x, final int y, final int z) {
			this.queriedX.add(x);
			return x == this.cellX && y == 0 && z == 0 ? this.block : BlockBehavior.INERT;
		}

		@Override
		public int propertiesIn(
		    final int mask, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
			return mask & PROPERTY_INSIDE_EFFECT;
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
