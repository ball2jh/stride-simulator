package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * A lit campfire or soul campfire: {@code CampfireBlock.entityInside}, one
 * point on every visit, two for the soul campfire, dealt by
 * {@link ServerTick} from the visit the server's copy logs. An unlit
 * campfire has no body and is captured as none.
 */
final class CampfireBlock extends BlockBehaviour {
	static final CampfireBlock CAMPFIRE = new CampfireBlock(false);
	static final CampfireBlock SOUL_CAMPFIRE = new CampfireBlock(true);

	private final boolean soul;

	private CampfireBlock(final boolean soul) {
		this.soul = soul;
	}

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
		scratch.authority.survival().hurtServer(HurtCause.CAMPFIRE, this.soul ? 2.0F : 1.0F);
	}

	@Override
	public String toString() {
		return this.soul ? "soul campfire" : "campfire";
	}
}
