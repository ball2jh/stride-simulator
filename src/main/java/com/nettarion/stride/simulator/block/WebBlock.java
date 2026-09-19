package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * A cobweb: {@code WebBlock.entityInside}, which for a player is one {@code Player.makeStuckInBlock} with the
 * web's vector.
 */
final class WebBlock extends BlockBehavior {
	static final WebBlock INSTANCE = new WebBlock();

	private WebBlock() {}

	@Override
	int families() {
		return INSIDE;
	}

	@Override
	boolean hasEntityInside() {
		return true;
	}

	/**
	 * {@code WebBlock.entityInside}: the shared {@link #makeStuckInBlock} with the web's vector, on both sides.
	 *
	 * @implNote {@code new Vec3(0.25, 0.05F, 0.25)} widens a float into the Y component, so it is not an exact
	 *     decimal double; writing 0.05 here disagrees on the first stuck tick.
	 */
	@Override
	void entityInside(final PlayerState state, final int x, final int y, final int z, final boolean isPrecise,
	    final WorldView world, final Scratch scratch) {
		makeStuckInBlock(state, 0.25, (double) 0.05F, 0.25, scratch);
	}

	@Override
	public String toString() {
		return "cobweb";
	}
}
