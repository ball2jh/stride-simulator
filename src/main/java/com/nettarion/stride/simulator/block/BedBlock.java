package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.server.Survival;

/** A bed: {@code BedBlock.fallOn}, half the fall distance at multiplier one. */
final class BedBlock extends BlockBehavior {
	static final BedBlock INSTANCE = new BedBlock();

	private BedBlock() {}

	@Override
	int families() {
		return LANDING;
	}

	@Override
	public void fallOn(final double fallDistance, final int x, final int y, final int z, final Survival survival) {
		survival.causeFallDamage(fallDistance * 0.5, 1.0F, HurtCause.FALL);
	}

	@Override
	public String toString() {
		return "bed";
	}
}
