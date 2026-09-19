package com.nettarion.stride.simulator;

/** Why the server corrected a movement packet instead of accepting it. */
public enum CorrectionReason {
	/** The displacement exceeded the connection interval's packet-scaled movement budget. */
	MOVED_TOO_QUICKLY,
	/** A movement packet triggered the pending teleport's twenty-tick retry. */
	TELEPORT_RETRY,
	/** The horizontal residual after collision exceeded the strict quarter block. */
	MOVED_WRONGLY,
	/** The target box meets a block shape the pre-packet box did not. */
	NEW_COLLISION
}
