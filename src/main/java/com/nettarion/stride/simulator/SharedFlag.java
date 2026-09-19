package com.nettarion.stride.simulator;

/**
 * The bit indices and masks of vanilla's {@code Entity} shared-flags byte, as {@code Entity.setSharedFlag}
 * numbers them.
 *
 * <p>{@link PlayerState#setSharedFlag} takes an index; entity-data writes and the tracker sample carry
 * the whole byte, tested with a mask.
 */
public final class SharedFlag {
	/** Bit index of the on-fire flag. */
	public static final int ON_FIRE = 0;

	/** Bit index of the shift-key flag. */
	public static final int SHIFT_KEY_DOWN = 1;

	/** Bit index of the sprinting flag. */
	public static final int SPRINTING = 3;

	/** Bit index of the swimming flag. */
	public static final int SWIMMING = 4;

	/** Bit index of the glowing flag. */
	public static final int GLOWING = 6;

	/** Bit index of the gliding flag. */
	public static final int FALL_FLYING = 7;

	/** Mask of the on-fire flag. */
	public static final int ON_FIRE_MASK = 1 << ON_FIRE;

	/** Mask of the shift-key flag. */
	public static final int SHIFT_KEY_DOWN_MASK = 1 << SHIFT_KEY_DOWN;

	/** Mask of the sprinting flag. */
	public static final int SPRINTING_MASK = 1 << SPRINTING;

	/** Mask of the swimming flag. */
	public static final int SWIMMING_MASK = 1 << SWIMMING;

	/** Mask of the glowing flag. */
	public static final int GLOWING_MASK = 1 << GLOWING;

	/** Mask of the gliding flag. */
	public static final int FALL_FLYING_MASK = 1 << FALL_FLYING;

	private SharedFlag() {}

	/** Whether the flag at {@code bit} is set in {@code flags}. */
	public static boolean isSet(final int flags, final int bit) {
		return (flags & (1 << bit)) != 0;
	}
}
