package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.WorldView;

/** A cactus: {@code CactusBlock.entityInside}, one point on every visit of the server's copy. */
final class CactusBlock extends BlockBehavior {
	static final CactusBlock INSTANCE = new CactusBlock();

	private CactusBlock() {}

	@Override
	int families() {
		return CONTACT;
	}

	@Override
	boolean hasContact() {
		return true;
	}

	@Override
	void entityInside(final PlayerState state, final int x, final int y, final int z, final boolean isPrecise,
	    final WorldView world, final Scratch scratch) {
		if (!scratch.authority.isServer()) return;
		scratch.authority.survival().hurtServer(HurtCause.CACTUS, 1.0F);
	}

	@Override
	public String toString() {
		return "cactus";
	}
}
