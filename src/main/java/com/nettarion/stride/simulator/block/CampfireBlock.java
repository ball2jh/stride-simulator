package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * A lit campfire or soul campfire: {@code CampfireBlock.entityInside}, a hit on every visit of the server's copy.
 *
 * <p>The hit is dealt at the visit through the server's {@code ServerDamage}: one point for a campfire, two for a
 * soul campfire. An unlit campfire has no hook and is captured as none; a capture that predates the lit fact
 * resolves to {@link RefusingBlock}.
 */
final class CampfireBlock extends BlockBehavior {
	static final CampfireBlock CAMPFIRE = new CampfireBlock("campfire", 1.0F);

	static final CampfireBlock SOUL_CAMPFIRE = new CampfireBlock("soul campfire", 2.0F);

	private final String name;

	/** {@code CampfireBlock.fireDamage}: one point for a campfire, two for a soul campfire. */
	private final float fireDamage;

	private CampfireBlock(final String name, final float fireDamage) {
		this.name = name;
		this.fireDamage = fireDamage;
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
		if (!scratch.authority.isServer()) {
			return;
		}
		scratch.authority.damage().hurtServer(HurtCause.CAMPFIRE, this.fireDamage);
	}

	@Override
	public String toString() {
		return this.name;
	}
}
