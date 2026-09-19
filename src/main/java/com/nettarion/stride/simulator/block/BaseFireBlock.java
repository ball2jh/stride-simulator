package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * Fire and soul fire: {@code BaseFireBlock.entityInside}.
 *
 * <p>Collects CLEAR_FREEZE on both sides, applied to each side's own frozen count by {@code applyAndClear}, and
 * on the server the ignition and the in-fire hit of this fire's damage for the step, applied in
 * {@code InsideBlockEffectType} order after the traversal.
 */
final class BaseFireBlock extends BlockBehavior {
	static final BaseFireBlock FIRE = new BaseFireBlock("fire", 1.0F);

	static final BaseFireBlock SOUL_FIRE = new BaseFireBlock("soul fire", 2.0F);

	private final String name;

	/** {@code BaseFireBlock.fireDamage}: one point for fire, two for soul fire. */
	private final float fireDamage;

	private BaseFireBlock(final String name, final float fireDamage) {
		this.name = name;
		this.fireDamage = fireDamage;
	}

	@Override
	int families() {
		return CONTACT;
	}

	@Override
	boolean hasEntityInside() {
		return true;
	}

	@Override
	boolean hasContact() {
		return true;
	}

	@Override
	void entityInside(final PlayerState state, final int x, final int y, final int z, final boolean isPrecise,
	    final WorldView world, final Scratch scratch) {
		scratch.insideEffects.collectClearFreeze();
		if (scratch.authority.isServer()) {
			scratch.insideEffects.collectFireContact(this.fireDamage);
		}
	}

	@Override
	public String toString() {
		return this.name;
	}
}
