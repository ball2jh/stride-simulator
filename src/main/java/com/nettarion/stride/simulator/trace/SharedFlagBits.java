package com.nettarion.stride.simulator.trace;

/**
 * The bit layout of {@code Entity}'s synchronized shared-flags byte, and the
 * one place that splits it into audited fields.
 *
 * <p>Bit numbers are read from the {@code Entity.setSharedFlag} call sites in
 * the pinned tree, not from memory.
 *
 * <p><b>Read the raw byte, never the accessors.</b> It is tempting to fill
 * these fields with {@code isSprinting()}, {@code isShiftKeyDown()} and
 * friends. For {@code shiftKeyDown} that is actively wrong:
 * {@code LocalPlayer} overrides {@code isShiftKeyDown()} to return
 * {@code input.keyPresses.sneak()}, so the accessor answers a different
 * question than the flag does, and the two disagree by exactly the round-trip
 * latency this field is excluded for. Deriving every bit from the byte keeps
 * all implementations auditing the same fact.
 */
public final class SharedFlagBits {
	public static final int ON_FIRE = 0;
	public static final int SHIFT_KEY_DOWN = 1;
	public static final int SPRINTING = 3;
	public static final int SWIMMING = 4;
	public static final int GLOWING = 6;
	public static final int FALL_FLYING = 7;

	/** Bits with no named field yet: 0, 2, 5 and 6. */
	public static final int RESIDUAL_MASK =
	    0xFF & ~((1 << SHIFT_KEY_DOWN) | (1 << SPRINTING) | (1 << SWIMMING) | (1 << FALL_FLYING));

	private SharedFlagBits() {}

	public static boolean isSet(final byte flags, final int bit) {
		return (flags & (1 << bit)) != 0;
	}

	public static byte residual(final byte flags) {
		return (byte) (flags & RESIDUAL_MASK);
	}

	/** Writes the audited bits of one shared-flags byte into a state builder. */
	public static void decompose(final StateVector.Builder builder, final byte flags) {
		builder.set(StateField.SPRINTING, isSet(flags, SPRINTING));
		builder.set(StateField.SWIMMING, isSet(flags, SWIMMING));
		builder.set(StateField.FALL_FLYING, isSet(flags, FALL_FLYING));
		builder.set(StateField.SHIFT_KEY_DOWN, isSet(flags, SHIFT_KEY_DOWN));
		builder.setFlags(StateField.SHARED_FLAGS_RESIDUAL, residual(flags));
	}

	/** Rebuilds the byte from the audited fields, for restoring a implementation. */
	public static byte compose(final StateVector state) {
		int flags = Byte.toUnsignedInt(state.getFlags(StateField.SHARED_FLAGS_RESIDUAL));
		flags |= state.getBoolean(StateField.SPRINTING) ? 1 << SPRINTING : 0;
		flags |= state.getBoolean(StateField.SWIMMING) ? 1 << SWIMMING : 0;
		flags |= state.getBoolean(StateField.FALL_FLYING) ? 1 << FALL_FLYING : 0;
		flags |= state.getBoolean(StateField.SHIFT_KEY_DOWN) ? 1 << SHIFT_KEY_DOWN : 0;
		return (byte) flags;
	}
}
