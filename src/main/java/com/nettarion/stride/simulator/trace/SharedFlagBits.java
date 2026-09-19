package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.SharedFlag;
/**
 * The bit layout of vanilla {@code Entity}'s synchronized shared-flags byte, and the one place
 * that splits it into recorded fields.
 *
 * <p>Bit numbers are those of the {@code Entity.setSharedFlag} call sites in vanilla 26.2.
 *
 * <p>A producer must read the raw byte, never the accessors. For {@code shiftKeyDown} the
 * accessor is wrong: {@code LocalPlayer.isShiftKeyDown()} returns
 * {@code input.keyPresses.sneak()}, which answers a different question than the flag does, and
 * the two disagree by exactly the round-trip latency the field is excluded for. Deriving every
 * bit from the byte keeps all producers recording the same fact.
 */
public final class SharedFlagBits {
	/** Bit 0: the entity is on fire. Residual; no named field. */
	public static final int ON_FIRE = SharedFlag.ON_FIRE;

	/** Bit 1: the server-owned sneak flag, recorded as {@link StateField#SHIFT_KEY_DOWN}. */
	public static final int SHIFT_KEY_DOWN = SharedFlag.SHIFT_KEY_DOWN;

	/** Bit 3: sprinting, recorded as {@link StateField#SPRINTING}. */
	public static final int SPRINTING = SharedFlag.SPRINTING;

	/** Bit 4: swimming, recorded as {@link StateField#SWIMMING}. */
	public static final int SWIMMING = SharedFlag.SWIMMING;

	/** Bit 6: glowing. Residual; no named field. */
	public static final int GLOWING = SharedFlag.GLOWING;

	/** Bit 7: fall flying, recorded as {@link StateField#FALL_FLYING}. */
	public static final int FALL_FLYING = SharedFlag.FALL_FLYING;

	/**
	 * The bits without a named field: 0 (on fire), 2, 5 (invisible) and 6 (glowing). They are
	 * carried together as {@link StateField#SHARED_FLAGS_RESIDUAL}.
	 */
	public static final int RESIDUAL_MASK =
	    0xFF & ~((1 << SHIFT_KEY_DOWN) | (1 << SPRINTING) | (1 << SWIMMING) | (1 << FALL_FLYING));

	private SharedFlagBits() {}

	/** Whether {@code bit} (0 through 7) is set in {@code flags}. */
	public static boolean isSet(final byte flags, final int bit) {
		return (flags & (1 << bit)) != 0;
	}

	/** The bits of {@code flags} that have no named field, per {@link #RESIDUAL_MASK}. */
	public static byte residual(final byte flags) {
		return (byte) (flags & RESIDUAL_MASK);
	}

	/** Writes the named bits and the residual of one shared-flags byte into {@code builder}. */
	public static void decompose(final StateVector.Builder builder, final byte flags) {
		builder.set(StateField.SPRINTING, isSet(flags, SPRINTING));
		builder.set(StateField.SWIMMING, isSet(flags, SWIMMING));
		builder.set(StateField.FALL_FLYING, isSet(flags, FALL_FLYING));
		builder.set(StateField.SHIFT_KEY_DOWN, isSet(flags, SHIFT_KEY_DOWN));
		builder.setFlags(StateField.SHARED_FLAGS_RESIDUAL, residual(flags));
	}

	/** Rebuilds the shared-flags byte from the named fields and the residual of {@code state}. */
	public static byte compose(final StateVector state) {
		int flags = Byte.toUnsignedInt(state.getFlags(StateField.SHARED_FLAGS_RESIDUAL));
		flags |= state.getBoolean(StateField.SPRINTING) ? 1 << SPRINTING : 0;
		flags |= state.getBoolean(StateField.SWIMMING) ? 1 << SWIMMING : 0;
		flags |= state.getBoolean(StateField.FALL_FLYING) ? 1 << FALL_FLYING : 0;
		flags |= state.getBoolean(StateField.SHIFT_KEY_DOWN) ? 1 << SHIFT_KEY_DOWN : 0;
		return (byte) flags;
	}
}
