package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.server.Survival;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * Powder snow: {@code PowderSnowBlock.entityInside}, the stuck vector, the
 * collected FREEZE, and on the server the EXTINGUISH that follows it with
 * the melt a burning player causes before it; and
 * {@code getEntityInsideCollisionShape}, which is the full cube until the
 * fall distance passes two and a half blocks and a 0.9-high box after;
 * {@code fallOn} plays a sound and deals nothing.
 *
 * <p>The palette carries powder snow as its collision behaviour, since the
 * shape a bootless player collides with is what the capture classifies;
 * the boots themselves are {@link PlayerState#canWalkOnPowderSnow} and are
 * read by the collision, not here. The whole body, its server half
 * included, is owned by that fact, so the block's contact fact is
 * {@code NONE} the way a liquid's is the fluid entry's.
 */
final class PowderSnowBlock extends BlockBehaviour {
	static final PowderSnowBlock INSTANCE = new PowderSnowBlock();

	/** {@code PowderSnowBlock.FALLING_COLLISION_SHAPE}, as one box. */
	private static final double[] FALLING_COLLISION_SHAPE = {0.0, 0.0, 0.0, 1.0, (double) 0.9F, 1.0};

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
	 * {@code getEntityInsideCollisionShape} returns the block's own collision
	 * shape, which for a bootless player is empty (and so the default cube,
	 * short-circuited) while the fall distance stays at or below 2.5, and the
	 * falling shape above it, which vanilla then has to sweep for. A box that
	 * came to rest on top of the shape did not reach it, so nothing applies
	 * and the cell is not even marked visited.
	 */
	@Override
	boolean entityInsideShapeReached(
	    final PlayerState state, final int x, final int y, final int z, final Scratch scratch) {
		return !(state.fallDistance > 2.5)
		    || scratch.insideTraversal.collidedWithShapeMovingFrom(state, x, y, z, FALLING_COLLISION_SHAPE);
	}

	/**
	 * {@code PowderSnowBlock.entityInside} collecting FREEZE for the current step.
	 *
	 * <p>{@code StepBasedCollector} de-duplicates per step index, so a tick that
	 * finds powder snow in two steps freezes twice. Two FREEZE contacts separated
	 * by a sub-movement boundary but sharing a step index are the one case where
	 * an unmodelled cell decides whether the collector flushed between them: a
	 * cell that is neither bubble column nor powder snow is invisible to this
	 * traversal, and one visited at an intermediate step would have split the
	 * group. That needs the earlier step index to be at least 2, since a
	 * sub-movement's origin pass can only re-visit cells its predecessor already
	 * covered. Refuse there rather than guess.
	 */
	@Override
	void entityInside(final PlayerState state, final int x, final int y, final int z, final boolean isPrecise,
	    final WorldView world, final Scratch scratch) {
		// PowderSnowBlock.entityInside gates the whole makeStuckInBlock call on the
		// player's own in-block state — the feet cell — rather than the cell being
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
	public void fallOn(final double fallDistance, final int x, final int y, final int z, final Survival survival) {}

	@Override
	public String toString() {
		return "powder snow";
	}
}
