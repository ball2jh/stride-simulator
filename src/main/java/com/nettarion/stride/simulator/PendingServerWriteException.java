package com.nettarion.stride.simulator;

/**
 * Refusal thrown when the admitted facts do not determine one exact server outcome, or when a
 * run ends while a caused server write is still pending delivery.
 *
 * <p>No partial prediction is returned. The {@link #cause()} names which fact was missing.
 */
public final class PendingServerWriteException extends RefusalException {
	private static final long serialVersionUID = 1L;

	/** The refusal {@link Simulator#run} throws when a sequence ends with a hurt write still pending. */
	PendingServerWriteException() {
		this(RefusalCause.PENDING_WRITE,
		    "the action sequence ends before the hurt-marked velocity write the last server tick caused arrives");
	}

	/** A refusal with the given stable cause and a message naming the missing fact. */
	public PendingServerWriteException(final RefusalCause cause, final String message) {
		super(cause, message);
	}
}
