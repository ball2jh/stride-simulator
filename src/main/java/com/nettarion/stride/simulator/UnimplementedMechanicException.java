package com.nettarion.stride.simulator;

import java.util.function.Supplier;

/**
 * Thrown when the tick reaches a mechanic the simulator does not yet model; it
 * refuses rather than guesses.
 *
 * <p>Unknown behaviour fails closed. An unclassified block state, an unhandled
 * movement hook, or an unsupported context must stop or invalidate a rollout
 * with a diagnosable reason, and must never silently become ordinary movement.
 * This is that stop.
 *
 * <p>This is a supported caller-visible refusal outcome, not an assertion that
 * the requested movement is impossible in Minecraft. Callers may try a broader
 * implementation or invalidate the rollout, but must not substitute a neutral
 * fact and continue an exactness claim. The state supplied to the failed
 * operation may already be partially mutated and must be discarded.
 *
 * <p>The {@link #cause()} names the boundary; the message explains the call.
 * The one-argument constructor is for callers outside the simulator that
 * relay a refusal they cannot classify.
 */
public class UnimplementedMechanicException extends RuntimeException implements Refuses {
	private static final long serialVersionUID = 1L;
	private final Refusal cause;
	/** The message not yet built, or null once {@link #getMessage} built it or the constructor was given one. */
	private transient Supplier<String> deferred;
	private String message;

	public UnimplementedMechanicException(final Refusal cause, final String reason) {
		super(reason);
		this.cause = java.util.Objects.requireNonNull(cause, "cause");
		this.message = reason;
	}

	/** A relayed refusal whose boundary the thrower cannot name. */
	public UnimplementedMechanicException(final String reason) {
		this(Refusal.UNCLASSIFIED, reason);
	}

	private UnimplementedMechanicException(final Refusal cause, final Supplier<String> deferred) {
		super((String) null);
		this.cause = java.util.Objects.requireNonNull(cause, "cause");
		this.deferred = java.util.Objects.requireNonNull(deferred, "deferred");
	}

	/**
	 * A refusal whose message is built only when something reads it. A
	 * refusal is a signal on the search's hot path, and its cause is what a
	 * consumer counts; the coordinates and values a message names are
	 * formatted on the first {@link #getMessage}, not on the throw. The
	 * supplier is a closure over a few primitives, one small allocation in
	 * place of the string.
	 */
	public static UnimplementedMechanicException deferred(final Refusal cause, final Supplier<String> message) {
		return new UnimplementedMechanicException(cause, message);
	}

	/**
	 * {@link #deferred(Refusal, Supplier)} for the common refusal that names one
	 * cell: {@code prefix + x + "," + y + "," + z + suffix}, the coordinates
	 * copied here so a loop variable can be named without a capture.
	 */
	public static UnimplementedMechanicException at(
	    final Refusal cause, final String prefix, final int x, final int y, final int z, final String suffix) {
		return new UnimplementedMechanicException(cause, () -> prefix + x + "," + y + "," + z + suffix);
	}

	@Override
	public String getMessage() {
		String built = this.message;
		if (built == null && this.deferred != null) {
			built = this.deferred.get();
			this.message = built;
			this.deferred = null;
		}
		return built;
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
