package com.nettarion.stride.simulator.world;

/**
 * The player facts a context-sensitive collision shape depends on: scaffolding
 * and powder snow resolve their shape from the player rather than from the
 * block state alone.
 *
 * <p>Immutable. {@code entityBottom} is the player's feet Y in blocks;
 * {@code descending} is vanilla {@code isShiftKeyDown()}; {@code fallDistance}
 * is in blocks; {@code canWalkOnPowderSnow} is whether the player wears leather
 * boots. A world with no context-sensitive block ignores every component.
 *
 * @param entityBottom the player's feet Y, in blocks
 * @param descending whether the sneak key is held, vanilla {@code isShiftKeyDown()}
 * @param fallDistance the player's accumulated fall distance, in blocks
 * @param canWalkOnPowderSnow whether the player may stand on powder snow
 */
public record
    CollisionContext(double entityBottom, boolean descending, double fallDistance, boolean canWalkOnPowderSnow) {
	/**
	 * The context a query carries when it has none: feet below every block, not
	 * sneaking, no fall, no boots. A context-sensitive world refuses a query
	 * asked without context rather than answering from these values.
	 */
	public static final CollisionContext NONE = new CollisionContext(Double.NEGATIVE_INFINITY, false, 0.0, false);
}
