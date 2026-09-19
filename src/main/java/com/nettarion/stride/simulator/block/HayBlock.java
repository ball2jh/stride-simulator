package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.server.ServerDamage;

/** A hay bale: {@code HayBlock.fallOn}, the fall at multiplier one fifth. */
final class HayBlock extends BlockBehavior {
	static final HayBlock INSTANCE = new HayBlock();

	private HayBlock() {}

	@Override
	int families() {
		return LANDING;
	}

	@Override
	public void fallOn(final double fallDistance, final int x, final int y, final int z, final ServerDamage damage) {
		damage.causeFallDamage(fallDistance, 0.2F, HurtCause.FALL);
	}

	@Override
	public String toString() {
		return "hay";
	}
}
