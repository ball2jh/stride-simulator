package com.nettarion.stride.simulator.world;

/** Provenance for the broadphase distinction geometry alone cannot recover. */
public enum ShapeProvenance {
	/** Captured shape was not Minecraft's canonical singleton full block. */
	GENERAL,
	/** Capture observed object identity with Minecraft's canonical full block. */
	CANONICAL_FULL,
	/**
	 * Source identity was not recorded. Replay the geometry classifier used by
	 * snapshot formats through 13 without presenting its answer as source fact.
	 */
	LEGACY_GEOMETRY
}
