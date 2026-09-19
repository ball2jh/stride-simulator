package com.nettarion.stride.simulator;

/**
 * Refusal thrown when a run ends while a caused server write is still pending,
 * or the admitted facts do not determine one exact server outcome.
 *
 * <p>No partial prediction is returned. The {@link #cause()} names which fact
 * was missing; the message explains the call.
 */
public final class PendingServerWriteException extends IllegalStateException implements Refuses {
	private static final long serialVersionUID = 1L;
	private final Refusal cause;

	PendingServerWriteException() {
		this(Refusal.PENDING_WRITE,
		    "plan ends before the hurt-marked velocity write the last server tick caused arrives");
	}

	/** Refuses a server outcome that the supplied boundary cannot determine. */
	public PendingServerWriteException(final Refusal cause, final String message) {
		super(message);
		this.cause = java.util.Objects.requireNonNull(cause, "cause");
	}

	/** A relayed refusal whose boundary the thrower cannot name. */
	public PendingServerWriteException(final String message) {
		this(Refusal.UNCLASSIFIED, message);
	}

	@Override
	public Refusal cause() {
		return this.cause;
	}

	@Override
	public synchronized Throwable fillInStackTrace() {
		return TRACE ? super.fillInStackTrace() : this;
	}
}
