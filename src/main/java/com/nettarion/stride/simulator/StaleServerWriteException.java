package com.nettarion.stride.simulator;

/**
 * Refusal thrown when a predicted server write is applied to a player state
 * other than the exact one it was derived from. The {@link #cause()} is always
 * {@link Refusal#STALE_WRITE}.
 */
public final class StaleServerWriteException extends IllegalStateException implements Refuses {
	private static final long serialVersionUID = 1L;

	public StaleServerWriteException(final long expectedDigest, final long actualDigest) {
		super("predicted authority state changed: expected digest " + Long.toUnsignedString(expectedDigest) + ", found "
		    + Long.toUnsignedString(actualDigest));
	}

	@Override
	public Refusal cause() {
		return Refusal.STALE_WRITE;
	}

	@Override
	public synchronized Throwable fillInStackTrace() {
		return TRACE ? super.fillInStackTrace() : this;
	}
}
