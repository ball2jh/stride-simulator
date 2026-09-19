package com.nettarion.stride.simulator.world;

/**
 * What lies beyond a snapshot's bounds. A synthetic world may declare itself
 * a sealed box; a captured world refuses a query that reaches its edge.
 */
public enum OutsidePolicy {
	/** Solid in every direction: the region is a sealed box of full cubes. */
	SEALED,
	/** A query that reaches it refuses with a diagnosable reason. */
	REFUSING
}
