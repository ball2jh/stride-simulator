package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * A cauldron full of lava: {@code LavaCauldronBlock.entityInside}, behind the filled bowl's inside shape.
 *
 * <p>Collects CLEAR_FREEZE on both sides, applied to each side's own frozen count by {@code applyAndClear}, and
 * on the server ignites and hurts as lava does. {@code getEntityInsideCollisionShape} is the filled bowl rather
 * than the cube, so the sweep has to reach it. Layered water and powder-snow cauldrons are
 * {@code LayeredCauldronBlock} when a {@code BlockStateCatalog} supplies their shapes and successor states;
 * empty cauldrons have no hook.
 */
final class LavaCauldronBlock extends BlockBehavior {
	static final LavaCauldronBlock INSTANCE = new LavaCauldronBlock();

	/** {@code LavaCauldronBlock.FILLED_SHAPE}, as non-overlapping boxes, one per line. */
	// clang-format off
	private static final double[] FILLED_SHAPE = {
	    // The bowl floor plus lava contents fill the whole cell through 15/16.
	    0.0, 3.0 / 16.0, 0.0, 1.0, 15.0 / 16.0, 1.0,
	    // The rim above the contents.
	    0.0, 15.0 / 16.0, 0.0, 0.125, 1.0, 1.0,
	    0.875, 15.0 / 16.0, 0.0, 1.0, 1.0, 1.0,
	    0.125, 15.0 / 16.0, 0.0, 0.875, 1.0, 0.125,
	    0.125, 15.0 / 16.0, 0.875, 0.875, 1.0, 1.0,
	    // AbstractCauldronBlock's four L-shaped feet below the bowl floor.
	    0.0, 0.0, 0.0, 0.125, 3.0 / 16.0, 0.25,
	    0.125, 0.0, 0.0, 0.25, 3.0 / 16.0, 0.125,
	    0.875, 0.0, 0.0, 1.0, 3.0 / 16.0, 0.25,
	    0.75, 0.0, 0.0, 0.875, 3.0 / 16.0, 0.125,
	    0.0, 0.0, 0.75, 0.125, 3.0 / 16.0, 1.0,
	    0.125, 0.0, 0.875, 0.25, 3.0 / 16.0, 1.0,
	    0.875, 0.0, 0.75, 1.0, 3.0 / 16.0, 1.0,
	    0.75, 0.0, 0.875, 0.875, 3.0 / 16.0, 1.0,
	};
	// clang-format on

	private LavaCauldronBlock() {}

	@Override
	int families() {
		return INSIDE | CONTACT;
	}

	@Override
	boolean hasEntityInside() {
		return true;
	}

	@Override
	boolean hasContact() {
		return true;
	}

	@Override
	boolean entityInsideShapeReached(
	    final PlayerState state, final int x, final int y, final int z, final Scratch scratch) {
		return scratch.insideTraversal.collidedWithShapeMovingFrom(state, x, y, z, FILLED_SHAPE);
	}

	/** CLEAR_FREEZE is a collected effect on both sides, applied by {@code applyAndClear} on both. */
	@Override
	void entityInside(final PlayerState state, final int x, final int y, final int z, final boolean isPrecise,
	    final WorldView world, final Scratch scratch) {
		scratch.insideEffects.collectClearFreeze();
		if (scratch.authority.isServer()) {
			scratch.insideEffects.collectLavaContact();
		}
	}

	@Override
	public String toString() {
		return "lava cauldron";
	}
}
