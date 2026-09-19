package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.server.ServerTick;
import java.util.Optional;

/**
 * Refusal thrown when another server decision precedes the write this step can
 * prove, so the successor is not determined.
 *
 * <p>The observed decision is an external authority boundary, not movement
 * physics and not a prediction default. When the decision is the listener
 * correcting a movement packet, {@link #correction()} carries the decoded
 * transaction: the packet form, the cause, the target the client reported,
 * the position the server's collision resolved to, the residual it measured,
 * and where it sent the client back to. The {@link #cause()} is always
 * {@link Refusal#CORRECTED_MOVEMENT}.
 */
public final class UnpredictedServerWriteException extends IllegalStateException implements Refuses {
	private static final long serialVersionUID = 1L;
	private final transient ServerTick.Corrected correction;

	public UnpredictedServerWriteException(final int tick, final double positionDrift) {
		super("server movement diverges before fall-damage prediction at tick " + tick + " by " + positionDrift
		    + " blocks");
		this.correction = null;
	}

	public UnpredictedServerWriteException(final int tick, final ServerTick.Corrected correction) {
		super("the server corrects the movement packet after input " + tick + ": " + correction);
		this.correction = correction;
	}

	/** The decoded correction that refused this step, when that was the cause. */
	public Optional<ServerTick.Corrected> correction() {
		return Optional.ofNullable(this.correction);
	}

	@Override
	public Refusal cause() {
		return Refusal.CORRECTED_MOVEMENT;
	}

	@Override
	public synchronized Throwable fillInStackTrace() {
		return TRACE ? super.fillInStackTrace() : this;
	}
}
