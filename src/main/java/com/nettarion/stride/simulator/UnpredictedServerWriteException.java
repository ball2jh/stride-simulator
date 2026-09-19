package com.nettarion.stride.simulator;

import java.util.Objects;

/**
 * Refusal thrown when the server would correct the client's reported movement instead of
 * accepting it, so the successor is not the one the client computed.
 *
 * <p>The correction is a server authority decision, not movement physics, and the simulator does
 * not predict where a corrected client ends up. {@link #correction()} carries the decoded
 * transaction: the packet kind, the reason, the target the client reported, the position the
 * server's collision resolved to, the residual it measured, and where it sent the client back
 * to. The {@link #cause()} is always {@link RefusalCause#CORRECTED_MOVEMENT}.
 */
public final class UnpredictedServerWriteException extends RefusalException {
	private static final long serialVersionUID = 1L;

	private final MovementCorrection correction;

	/** A refusal of the step after action {@code action}, because the server issued {@code correction}. */
	public UnpredictedServerWriteException(final int action, final MovementCorrection correction) {
		super(RefusalCause.CORRECTED_MOVEMENT,
		    "the server corrects the movement packet after input " + action + ": " + correction);
		this.correction = Objects.requireNonNull(correction, "correction");
	}

	/** The decoded correction that refused this step. */
	public MovementCorrection correction() {
		return this.correction;
	}
}
