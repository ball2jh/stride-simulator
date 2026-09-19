package com.nettarion.stride.simulator;

/**
 * Whether the player may interact with blocks in the captured region, as the caller declares it.
 *
 * <p>The only admitted interaction is lowering a cauldron the server extinguishes a burning player in. A caller that
 * does not know the region's permission declares {@link #UNDECLARED}, and the first transition that would need it
 * refuses instead of guessing.
 */
public enum Interaction {
	/** The caller declares that the server lets the player change blocks in the region. */
	ALLOWED,
	/** The caller declares that the server drops every block change in the region. */
	DENIED,
	/** The caller did not supply the permission; a transition that needs it refuses. */
	UNDECLARED
}
