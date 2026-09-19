package com.nettarion.stride.simulator;

/**
 * Whether the server may change the world when the player interacts with a block, declared once for a
 * whole {@link ScheduledSimulation}.
 *
 * <p>Vanilla checks a per-player permission before a cauldron or similar block changes state under a
 * player. This library does not model the permission itself; the caller declares it. A world change
 * reached under {@link #UNDECLARED} refuses with {@link RefusalCause#UNMODELED_WORLD_WRITE}.
 */
public enum Interaction {
	/** The server applies every world change the player's contact would cause. */
	ALLOWED,
	/** The server applies no world change; contact leaves the world as it is. */
	DENIED,
	/** The caller did not say; the first world change refuses. */
	UNDECLARED
}
