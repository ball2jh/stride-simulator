package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * Fire and soul fire: {@code BaseFireBlock.entityInside}, which collects
 * CLEAR_FREEZE on both sides, applied to each side's own frozen count by
 * {@code applyAndClear}, and, on the server, the ignition and the hit (one
 * point, two for soul fire) for the step, in {@code InsideBlockEffectType}
 * order.
 */
final class BaseFireBlock extends BlockBehaviour {
	static final BaseFireBlock FIRE = new BaseFireBlock(false);
	static final BaseFireBlock SOUL_FIRE = new BaseFireBlock(true);

	private final boolean soul;

	private BaseFireBlock(final boolean soul) {
		this.soul = soul;
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

	/** {@code apply(CLEAR_FREEZE)}, which both sides collect and apply. */
	@Override
	void entityInside(final PlayerState state, final int x, final int y, final int z, final boolean isPrecise,
	    final WorldView world, final Scratch scratch) {
		scratch.insideEffects.collectClearFreeze();
		if (scratch.authority.isServer()) {
			scratch.insideEffects.collectFireContact(this.soul ? 2.0F : 1.0F);
		}
	}

	@Override
	public String toString() {
		return this.soul ? "soul fire" : "fire";
	}
}
