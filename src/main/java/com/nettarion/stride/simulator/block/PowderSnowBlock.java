package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.server.ServerDamage;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * Powder snow: {@code PowderSnowBlock.entityInside}, the stuck vector and the collected FREEZE, behind a shape
 * that depends on the fall distance.
 *
 * <p>On the server the EXTINGUISH follows the FREEZE, with the melt a burning player causes before it.
 * {@code getEntityInsideCollisionShape} is the full cube until the fall distance passes two and a half blocks and
 * a 0.9-high box after; {@code fallOn} plays a sound and deals nothing.
 *
 * <p>The palette carries powder snow as its collision behavior, since the shape a bootless player collides with
 * is what the capture classifies; the boots themselves are {@link PlayerState#canWalkOnPowderSnow} and are read
 * by the collision, not here. The whole hook, its server half included, is owned by that fact, so the block's
 * contact fact is {@code NONE} the way a liquid's is the fluid entry's.
 */
final class PowderSnowBlock extends BlockBehavior {
	static final PowderSnowBlock INSTANCE = new PowderSnowBlock();

	/** {@code PowderSnowBlock.FALLING_COLLISION_SHAPE}, as one box. */
	private static final double[] FALLING_COLLISION_SHAPE = {0.0, 0.0, 0.0, 1.0, (double) 0.9F, 1.0};

	/** The fall distance, in blocks, above which {@code getEntityInsideCollisionShape} is the falling shape. */
	private static final double FALLING_SHAPE_FALL_DISTANCE = 2.5;

	private PowderSnowBlock() {}

	@Override
	int families() {
		return POWDER | LANDING;
	}

	@Override
	boolean hasEntityInside() {
		return true;
	}

	/**
	 * {@code getEntityInsideCollisionShape} returns the block's own collision shape, which for a bootless player
	 * is empty (and so the default cube, short-circuited) while the fall distance stays at or below 2.5, and the
	 * falling shape above it, which vanilla then has to sweep for. A box that came to rest on top of the shape
	 * did not reach it, so nothing applies and the cell is not even marked visited.
	 */
	@Override
	boolean entityInsideShapeReached(
	    final PlayerState state, final int x, final int y, final int z, final Scratch scratch) {
		// Written as the negation of vanilla's `fallDistance > 2.5F` so that a NaN
		// fall distance takes the full-cube branch exactly as vanilla's comparison does.
		return !(state.fallDistance > FALLING_SHAPE_FALL_DISTANCE)
		    || scratch.insideTraversal.collidedWithShapeMovingFrom(state, x, y, z, FALLING_COLLISION_SHAPE);
	}

	/**
	 * {@code PowderSnowBlock.entityInside}: the stuck vector behind the feet-cell gate, then FREEZE for the
	 * current step, de-duplicated per step by the collector, and on the server the melt-then-EXTINGUISH.
	 */
	@Override
	void entityInside(final PlayerState state, final int x, final int y, final int z, final boolean isPrecise,
	    final WorldView world, final Scratch scratch) {
		// PowderSnowBlock.entityInside gates the whole makeStuckInBlock call on the
		// player's own in-block state, the feet cell, rather than the cell being
		// visited, so a visit to any powder cell sets it and a tick that visits
		// none leaves it alone. It runs here, inside the traversal, because
		// resetFallDistance is one of its effects and the next cell's
		// entity-inside shape is chosen by fallDistance.
		//
		// The call is Player.makeStuckInBlock, the shared helper: the vector and
		// the fall reset behind the flying gate, then the impulse-context reset
		// whatever the gate said. FREEZE below is deliberately outside it:
		// PowderSnowBlock.entityInside applies that after the branch closes, so a
		// flying player still freezes.
		if (world.collisionBehaviorAt(Mth.floor(state.x), Mth.floor(state.y), Mth.floor(state.z))
		    == WorldView.CollisionBehavior.POWDER_SNOW_NO_BOOTS) {
			makeStuckInBlock(state, (double) 0.9F, 1.5, (double) 0.9F, scratch);
		}
		scratch.insideEffects.collectFreeze();
		if (scratch.authority.isServer()) {
			scratch.insideEffects.collectPowderSnowContact();
		}
	}

	/** {@code PowderSnowBlock.fallOn}: a sound and no hit. */
	@Override
	public void fallOn(final double fallDistance, final int x, final int y, final int z, final ServerDamage damage) {}

	@Override
	public String toString() {
		return "powder snow";
	}
}
