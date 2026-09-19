package com.nettarion.stride.simulator;

/** Why the server corrected a movement packet instead of accepting it. */
public enum CorrectionReason {
	/** The displacement since the connection tick began exceeded the packet-scaled movement budget. */
	MOVED_TOO_QUICKLY,
	/** A movement packet arrived more than twenty connection ticks into a pending correction, which resends it. */
	TELEPORT_RETRY,
	/** The horizontal residual after collision exceeded the strict quarter block. */
	MOVED_WRONGLY,
	/** The target box meets a block shape the pre-packet box did not. */
	NEW_COLLISION
}
