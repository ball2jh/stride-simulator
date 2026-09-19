package com.nettarion.stride.simulator.world;

/**
 * Whether a block entry's collision shape is vanilla's canonical full cube by
 * identity, a distinction geometry alone cannot recover: {@code Shapes.block()}
 * is one shared instance, and collision merging treats that instance
 * differently from an equal shape built elsewhere.
 */
public enum ShapeProvenance {
	/** The captured shape was not vanilla's canonical singleton full block. */
	GENERAL,
	/** The capture observed object identity with vanilla's canonical full block. */
	CANONICAL_FULL,
	/**
	 * Identity was not recorded. The geometry classifier snapshot formats
	 * through 13 relied on is replayed without presenting its answer as a
	 * captured fact.
	 */
	LEGACY_GEOMETRY
}
