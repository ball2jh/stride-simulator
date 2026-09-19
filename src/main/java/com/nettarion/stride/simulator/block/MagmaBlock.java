package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.tick.PlayerTick;
import com.nettarion.stride.simulator.tick.Scratch;

/**
 * A magma block: {@code MagmaBlock.stepOn}, one point underfoot unless stepping carefully.
 *
 * <p>The hit is dealt at the step through the server's {@code ServerDamage}. It overrides no {@code entityInside},
 * so a visit that sweeps through its cell applies nothing; the palette carries it as a contact fact because only
 * the server feels it.
 */
final class MagmaBlock extends BlockBehavior {
	static final MagmaBlock INSTANCE = new MagmaBlock();

	private MagmaBlock() {}

	@Override
	int families() {
		return CONTACT;
	}

	/**
	 * {@code MagmaBlock.stepOn}: a hit unless stepping carefully, which is the synced shift flag on the server's
	 * copy; {@code Entity.hurt} is nothing on the client.
	 */
	@Override
	void stepOn(final PlayerState state, final int x, final int y, final int z, final Scratch scratch) {
		if (scratch.authority.isServer() && !PlayerTick.isShiftKeyDown(state, scratch)) {
			scratch.authority.damage().hurtServer(HurtCause.HOT_FLOOR, 1.0F);
		}
	}

	@Override
	public String toString() {
		return "magma";
	}
}
