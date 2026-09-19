package com.nettarion.stride.simulator.block;

/**
 * The collected inside-block effects, in vanilla's {@code InsideBlockEffectType} declaration order.
 *
 * <p>That order is the order a step's flush applies them: the freeze pair first, then the ignitions with the hits
 * that follow each, then the extinguish. The collector de-duplicates each type within one step.
 */
enum InsideBlockEffectType {
	/** {@code PowderSnowBlock.entityInside}: one increment of the frozen count per step. */
	FREEZE,
	/** Fire, lava and the lava cauldron: the frozen count reset to zero. */
	CLEAR_FREEZE,
	/** {@code BaseFireBlock.entityInside} on the server: {@code fireIgnite}, then the in-fire hits. */
	FIRE_IGNITE,
	/** Lava and the lava cauldron on the server: {@code lavaIgnite}, then the lava hits. */
	LAVA_IGNITE,
	/** Water, powder snow and the layered cauldrons on the server: {@code clearFire}. */
	EXTINGUISH
}
