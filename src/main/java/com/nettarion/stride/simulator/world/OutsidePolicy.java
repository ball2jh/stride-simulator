package com.nettarion.stride.simulator.world;

/**
 * What lies beyond the captured region. Exact snapshots either describe a
 * deliberately sealed synthetic world or refuse a rollout at the boundary.
 */
public enum OutsidePolicy {
	/** Solid in every direction; the region is a sealed box. */
	SEALED,
	/** Touching it invalidates the rollout with a diagnosable reason. */
	REFUSING
}
