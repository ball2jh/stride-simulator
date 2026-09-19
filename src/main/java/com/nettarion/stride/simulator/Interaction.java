package com.nettarion.stride.simulator;

/**
 * Whether the simulated player may change blocks in the world.
 *
 * <p>A live capture records this permission when the server declared it. A caller that does not
 * know it supplies {@link #UNDECLARED}; the server tick then refuses any transition that would
 * need the answer instead of guessing.
 */
public enum Interaction {
	/** The player may change blocks. */
	ALLOWED,
	/** The player may not change blocks. */
	DENIED,
	/** The caller did not supply the permission; a transition that needs it refuses. */
	UNDECLARED
}
