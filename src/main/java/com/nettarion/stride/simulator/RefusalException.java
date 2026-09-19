package com.nettarion.stride.simulator;

import java.util.Objects;

/**
 * Base of every refusal: a transition the simulator declines to compute because the supplied
 * state, world, or schedule lies outside the admitted domain.
 *
 * <p>A refusal is an expected outcome, not a fault. It carries one stable {@link RefusalCause}
 * that callers classify and count, beside a message that explains the particular call. Do not
 * parse the message. Because refusals are thrown on hot paths, no refusal records a stack trace
 * unless the JVM was started with {@code -Dstride.refusal.trace=true}.
 *
 * <p>A refused transition has no usable successor. State passed to the refused operation may
 * already be partially mutated and must be discarded; reload a known boundary before
 * continuing. Structurally invalid arguments are programming errors and throw the ordinary
 * {@link IllegalArgumentException} or {@link IllegalStateException} instead.
 */
public abstract sealed class RefusalException extends RuntimeException
    permits UnimplementedMechanicException, PendingServerWriteException, UnpredictedServerWriteException,
        StaleServerWriteException {
	private static final long serialVersionUID = 1L;
	/** Whether refusals record a stack trace; read once per JVM. */
	static final boolean TRACE = Boolean.getBoolean("stride.refusal.trace");

	private final RefusalCause cause;

	RefusalException(final RefusalCause cause, final String message) {
		super(message);
		this.cause = Objects.requireNonNull(cause, "cause");
	}

	/** The stable classification of this refusal. */
	public final RefusalCause cause() {
		return this.cause;
	}

	@Override
	public final synchronized Throwable fillInStackTrace() {
		return TRACE ? super.fillInStackTrace() : this;
	}
}
