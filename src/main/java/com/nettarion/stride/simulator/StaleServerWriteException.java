package com.nettarion.stride.simulator;

/**
 * Refusal thrown when a predicted server write is applied to a player state other than the exact
 * one it was derived from. The {@link #cause()} is always {@link RefusalCause#STALE_WRITE}.
 */
public final class StaleServerWriteException extends RefusalException {
	private static final long serialVersionUID = 1L;

	/** Refuses applying a write derived from {@code expectedDigest} to a state whose digest is {@code actualDigest}. */
	public StaleServerWriteException(final long expectedDigest, final long actualDigest) {
		super(RefusalCause.STALE_WRITE,
		    "predicted server state changed: expected digest " + Long.toUnsignedString(expectedDigest) + ", found "
		        + Long.toUnsignedString(actualDigest));
	}
}
