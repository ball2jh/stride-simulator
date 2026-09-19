package com.nettarion.stride.simulator;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Refusal thrown when a transition reaches a mechanic, block, or world fact the simulator does
 * not model, or a state outside the admitted domain.
 *
 * <p>Unknown behavior fails closed: an unclassified block state, an unhandled movement hook, or
 * an unsupported context stops the transition with a diagnosable {@link #cause()} and never
 * silently becomes ordinary movement. This is not a claim that the movement is impossible in
 * Minecraft, only that this simulator does not compute it.
 *
 * <p>The message may be built lazily; see {@link #deferred(RefusalCause, Supplier)}. A deferred
 * message is built by a transient supplier: serializing the exception before anything read the
 * message drops it, and the deserialized copy reports a {@code null} message beside its cause.
 */
public final class UnimplementedMechanicException extends RefusalException {
	private static final long serialVersionUID = 1L;

	/** The message not yet built, or null once {@link #getMessage} built it or the constructor was given one. */
	private transient Supplier<String> deferred;

	/** The built or eagerly given message, or null while a deferred one is unread. */
	private String message;

	/** A refusal with the given stable cause and an eagerly built message. */
	public UnimplementedMechanicException(final RefusalCause cause, final String message) {
		super(cause, message);
		this.message = message;
	}

	/** A refusal whose message {@code deferred} builds on the first read. */
	private UnimplementedMechanicException(final RefusalCause cause, final Supplier<String> deferred) {
		super(cause, null);
		this.deferred = Objects.requireNonNull(deferred, "deferred");
	}

	/**
	 * A refusal whose message is built only when something reads it. Refusals are thrown on hot
	 * paths where callers usually count the cause and never read the message, so the coordinates
	 * and values a message names are formatted on the first {@link #getMessage} call rather than
	 * on the throw. The supplier should close over a few primitives.
	 */
	public static UnimplementedMechanicException deferred(final RefusalCause cause, final Supplier<String> message) {
		return new UnimplementedMechanicException(cause, message);
	}

	/**
	 * {@link #deferred(RefusalCause, Supplier)} for the common refusal that names one cell:
	 * {@code prefix + x + "," + y + "," + z + suffix}. The coordinates are copied here so a loop
	 * variable can be named without a capture.
	 */
	public static UnimplementedMechanicException at(
	    final RefusalCause cause, final String prefix, final int x, final int y, final int z, final String suffix) {
		return new UnimplementedMechanicException(cause, () -> prefix + x + "," + y + "," + z + suffix);
	}

	/** The message, building a deferred one on the first call. */
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
}
