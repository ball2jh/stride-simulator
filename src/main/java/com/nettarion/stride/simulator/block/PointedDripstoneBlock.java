package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.server.Survival;

/**
 * An upward dripstone tip, a stalagmite: {@code PointedDripstoneBlock.fallOn}, the fall plus two and a half at
 * multiplier two, as its own damage source.
 *
 * <p>A dripstone that is not an upward tip lands as an ordinary block and is captured as one; a capture that
 * predates the fact resolves to {@link UnrecordedStateBlock}.
 */
final class PointedDripstoneBlock extends BlockBehavior {
	static final PointedDripstoneBlock INSTANCE = new PointedDripstoneBlock();

	private PointedDripstoneBlock() {}

	@Override
	int families() {
		return LANDING;
	}

	@Override
	public void fallOn(final double fallDistance, final int x, final int y, final int z, final Survival survival) {
		survival.causeFallDamage(fallDistance + 2.5, 2.0F, HurtCause.STALAGMITE);
	}

	@Override
	public String toString() {
		return "stalagmite";
	}
}
