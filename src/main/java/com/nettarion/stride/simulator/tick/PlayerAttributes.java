package com.nettarion.stride.simulator.tick;

/**
 * Vanilla's default attribute values for the bare player, read by the tick as constants.
 *
 * <p>The admitted player carries default attributes except the sprint and frost modifiers, so
 * every attribute read in the tick is one of these literals. Each mirrors the vanilla member named
 * in its doc; the float or double width is vanilla's and is part of the arithmetic.
 */
final class PlayerAttributes {
	/** {@code Attributes.GRAVITY} default, in blocks per tick per tick. */
	static final double GRAVITY = 0.08;

	/** {@code Attributes.STEP_HEIGHT} default, in blocks, as {@code Entity.maxUpStep()} narrows it. */
	static final float MAX_UP_STEP = 0.6F;

	/** {@code Attributes.JUMP_STRENGTH} default, in blocks per tick. */
	static final float JUMP_STRENGTH = 0.42F;

	/** {@code Player.createAttributes}: the {@code MOVEMENT_SPEED} base for a player. */
	static final float BASE_MOVEMENT_SPEED = 0.1F;

	/** {@code LivingEntity.SPEED_MODIFIER_SPRINTING} amount, applied as {@code ADD_MULTIPLIED_TOTAL}. */
	static final float SPRINT_SPEED_BONUS = 0.3F;

	/** {@code Attributes.SNEAKING_SPEED} default, the factor a crouching input is scaled by. */
	static final float SNEAKING_SPEED = 0.3F;

	private PlayerAttributes() {}
}
