package com.nettarion.stride.simulator;

import java.util.Objects;

/**
 * One hurt-marked velocity publication the server will make, predicted from
 * the input that caused the hit and completed after the client tick whose
 * packets the sampling server tick handled: the causing tick itself when the
 * movement packet's landing dealt the hit, the following one when the
 * server's own tick dealt it after the sample.
 *
 * <p>The event is bound to the exact causal movement state. Its stored vector is
 * the decoded server payload at the declared publication boundary. Applying it to another trajectory
 * fails instead of silently moving the event to a different path.
 */
public final class HurtMotion {
	private static final double MINIMUM_PACKET_SCALE = 3.051944088384301E-5;
	private static final double PACKED_MAX = 32766.0;

	private final HurtCause cause;
	private final int causeAction;
	private final int writeAfterAction;
	private final long expectedStateDigest;
	private final double writeX;
	private final double writeY;
	private final double writeZ;
	private final double beforeX;
	private final double beforeY;
	private final double beforeZ;

	private HurtMotion(final HurtCause cause, final int causeAction, final int writeAfterAction,
	    final long expectedStateDigest, final double writeX, final double writeY, final double writeZ,
	    final double beforeX, final double beforeY, final double beforeZ) {
		this.cause = cause;
		this.causeAction = causeAction;
		this.writeAfterAction = writeAfterAction;
		this.expectedStateDigest = expectedStateDigest;
		this.writeX = writeX;
		this.writeY = writeY;
		this.writeZ = writeZ;
		this.beforeX = beforeX;
		this.beforeY = beforeY;
		this.beforeZ = beforeZ;
	}

	/**
	 * Bind a server velocity to the client state at its delivery boundary and
	 * round it through the 26.2 entity-motion packet before it can enter
	 * planning; the write completes after the tick following the cause.
	 */
	public static HurtMotion decoded(final HurtCause cause, final int causeAction, final PlayerState expectedState,
	    final double serverVelocityX, final double serverVelocityY, final double serverVelocityZ) {
		requireCauseTick(causeAction);
		return decoded(cause, causeAction, Math.addExact(causeAction, 1), expectedState, serverVelocityX, serverVelocityY,
		    serverVelocityZ);
	}

	/**
	 * {@link #decoded(HurtCause, int, PlayerState, double, double, double)}
	 * with the completing tick named: the causing tick for a hit the
	 * movement packet dealt before the tracker sampled, the following one
	 * for a hit the server's own tick dealt after it, later when the
	 * transport was scheduled to hold the packet.
	 */
	public static HurtMotion decoded(final HurtCause cause, final int causeAction, final int writeAfterAction,
	    final PlayerState expectedState, final double serverVelocityX, final double serverVelocityY,
	    final double serverVelocityZ) {
		Objects.requireNonNull(cause, "cause");
		requireCauseTick(causeAction);
		if (writeAfterAction < causeAction) {
			throw new IllegalArgumentException("the write cannot complete before its cause");
		}
		Objects.requireNonNull(expectedState, "expectedState");
		requireFinite(serverVelocityX, "serverVelocityX");
		requireFinite(serverVelocityY, "serverVelocityY");
		requireFinite(serverVelocityZ, "serverVelocityZ");
		double scale = wireScale(serverVelocityX, serverVelocityY, serverVelocityZ);
		return new HurtMotion(cause, causeAction, writeAfterAction, StateDigest.state(expectedState),
		    quantize(serverVelocityX, scale), quantize(serverVelocityY, scale), quantize(serverVelocityZ, scale),
		    expectedState.deltaMovementX, expectedState.deltaMovementY, expectedState.deltaMovementZ);
	}

	/** The damage source whose hit marked the player. */
	public HurtCause cause() {
		return this.cause;
	}

	/** Exact delivery-boundary digest persisted in scheduled evidence. */
	public long expectedStateDigest() {
		return this.expectedStateDigest;
	}

	/**
	 * The zero-based input at which the server marked the player hurt, or
	 * {@code -1} when the hit belongs to an input before the run that
	 * delivers it.
	 */
	public int causeAction() {
		return this.causeAction;
	}

	/** Returns the action index after which this velocity publication is applied. */
	public int writeAfterAction() {
		return this.writeAfterAction;
	}

	/** Returns the decoded server payload's X velocity, in blocks per tick. */
	public double writeX() {
		return this.writeX;
	}

	/** Returns the decoded server payload's Y velocity, in blocks per tick. */
	public double writeY() {
		return this.writeY;
	}

	/** Returns the decoded server payload's Z velocity, in blocks per tick. */
	public double writeZ() {
		return this.writeZ;
	}

	/**
	 * The client's own velocity at the boundary the write is bound to, which
	 * the write replaces: what a client that has not applied the packet yet
	 * carries there.
	 */
	public double beforeX() {
		return this.beforeX;
	}

	/** Returns the client's Y velocity immediately before the bound publication, in blocks per tick. */
	public double beforeY() {
		return this.beforeY;
	}

	/** Returns the client's Z velocity immediately before the bound publication, in blocks per tick. */
	public double beforeZ() {
		return this.beforeZ;
	}

	/** Apply this packet after the client tick in which it arrives. */
	public void applyAfterTick(final int completedTick, final PlayerState state) {
		Objects.requireNonNull(state, "state");
		if (completedTick != this.writeAfterAction) {
			throw new IllegalArgumentException(
			    "authority write belongs after tick " + this.writeAfterAction + ", not " + completedTick);
		}
		long actualDigest = StateDigest.state(state);
		if (actualDigest != this.expectedStateDigest) {
			throw new StaleServerWriteException(this.expectedStateDigest, actualDigest);
		}
		applyToDigestedState(state);
	}

	/** Apply to the very state the expected digest was taken from, which needs no second digest. */
	public void applyToDigestedState(final PlayerState state) {
		state.deltaMovementX = this.writeX;
		state.deltaMovementY = this.writeY;
		state.deltaMovementZ = this.writeZ;
	}

	/** Equality includes the causal state binding as well as the delivered packet. */
	@Override
	public boolean equals(final Object other) {
		return this == other
		    || other instanceof HurtMotion event && this.cause == event.cause && this.causeAction == event.causeAction
		    && this.writeAfterAction == event.writeAfterAction && this.expectedStateDigest == event.expectedStateDigest
		    && Double.doubleToRawLongBits(this.writeX) == Double.doubleToRawLongBits(event.writeX)
		    && Double.doubleToRawLongBits(this.writeY) == Double.doubleToRawLongBits(event.writeY)
		    && Double.doubleToRawLongBits(this.writeZ) == Double.doubleToRawLongBits(event.writeZ);
	}

	@Override
	public int hashCode() {
		int result = this.cause.hashCode();
		result = 31 * result + this.causeAction;
		result = 31 * result + this.writeAfterAction;
		result = 31 * result + Long.hashCode(this.expectedStateDigest);
		result = 31 * result + Long.hashCode(Double.doubleToRawLongBits(this.writeX));
		result = 31 * result + Long.hashCode(Double.doubleToRawLongBits(this.writeY));
		return 31 * result + Long.hashCode(Double.doubleToRawLongBits(this.writeZ));
	}

	@Override
	public String toString() {
		return "HurtMotion[" + this.cause + " at input " + this.causeAction + ", write (" + this.writeX + ", "
		    + this.writeY + ", " + this.writeZ + ") after input " + this.writeAfterAction + "]";
	}

	private static void requireCauseTick(final int causeAction) {
		if (causeAction < -1 || causeAction == Integer.MAX_VALUE) {
			throw new IllegalArgumentException("cause tick is out of range");
		}
	}

	private static double wireScale(final double x, final double y, final double z) {
		double maximum = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
		return maximum < MINIMUM_PACKET_SCALE ? 0.0 : Math.ceil(maximum);
	}

	private static double quantize(final double value, final double scale) {
		if (scale == 0.0) return 0.0;
		long packed = Math.round((value / scale * 0.5 + 0.5) * PACKED_MAX);
		return (Math.min(packed & 32767L, 32766L) * 2.0 / PACKED_MAX - 1.0) * scale;
	}

	private static void requireFinite(final double value, final String name) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException(name + " must be finite");
		}
	}
}
