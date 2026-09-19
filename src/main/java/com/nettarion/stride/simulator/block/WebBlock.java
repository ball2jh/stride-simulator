package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * A cobweb: {@code WebBlock.entityInside}, which for a player is one
 * {@code Player.makeStuckInBlock} with the web's vector.
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
	 * {@code WebBlock.entityInside} and {@code SweetBerryBushBlock.entityInside},
	 * both of which reduce to one {@code Entity.makeStuckInBlock} for a client
	 * player, through {@code Player}'s override.
	 *
	 * <p>Neither reads {@code isPrecise}, unlike the bubble callbacks, and neither
	 * has anything else this state vector carries. The bush's body has a second
	 * half — the scratch damage — but it is inside
	 * {@code level instanceof ServerLevel} and this is the client; its
	 * {@code LivingEntity} and not-a-fox-or-bee test is a constant for a player.
	 *
	 * <p>The vectors are the pinned literals and not rounded transcriptions of
	 * them. {@code new Vec3(0.25, 0.05F, 0.25)} widens a float into the Y
	 * component and {@code new Vec3(0.8F, 0.75, 0.8F)} widens one into X and Z, so
	 * four of the six are exact decimal doubles and two are not; writing 0.05 or
	 * 0.8 here disagrees on the first stuck tick.
	 *
	 * <p>The Weaving effect's larger vector, {@code (0.5, 0.25, 0.5)}, is outside
	 * the admitted effects and never proposed here.
	 *
	 * <p>{@code Player.makeStuckInBlock} also calls
	 * {@code tryResetCurrentImpulseContext}, which the shared body runs on the
	 * server's copy: an imported boundary can carry a wind-charge impulse
	 * context, and {@code Survival.causeFallDamage} caps the next fall by it.
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
