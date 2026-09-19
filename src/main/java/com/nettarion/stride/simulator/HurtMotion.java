package com.nettarion.stride.simulator;

import java.util.Objects;

/**
 * The server's velocity as the client receives it after a hit marked the player hurt: the vector rounded through
 * the entity-motion packet, bound to the client state it replaces.
 *
 * <p>{@link #causeAction()} is the action whose packet drain or server tick dealt the marking hit;
 * {@link #writeAfterAction()} is the completed action after which the write is applied, the following action under
 * the fixed schedule. The write is bound by digest to the client state at that action, so applying it to another
 * state throws {@link StaleServerWriteException}. Velocities are in blocks per tick. Instances are immutable.
 */
public final class HurtMotion {
	/**
	 * The largest packed magnitude of one component in vanilla 26.2's {@code ClientboundSetEntityMotionPacket},
	 * which sends a per-vector scale and each component as a fifteen-bit fraction of it.
	 */
	private static final double PACKED_MAX = 32766.0;

	/** The fifteen bits the packed fraction occupies. */
	private static final long PACKED_MASK = 0x7FFFL;

	/** Below this magnitude the packet's scale is zero and every component decodes to zero. */
	private static final double MINIMUM_PACKET_SCALE = 1.0 / PACKED_MAX;

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
	 * Decode the server's velocity through the entity-motion packet and bind the result to {@code boundState}, the
	 * client state it will be applied to after {@code writeAfterAction} completed actions.
	 *
	 * @param cause the source of the marking hit
	 * @param causeAction the action that caused the hit, or {@code -1} for a hit before the first action
	 * @param writeAfterAction the completed action after which the write is applied, not before the cause
	 * @param boundState the client state the write is bound to and taken from
	 * @param serverVelocityX the server's x velocity before packing, in blocks per tick, finite
	 * @param serverVelocityY the server's y velocity before packing, in blocks per tick, finite
	 * @param serverVelocityZ the server's z velocity before packing, in blocks per tick, finite
	 * @throws IllegalArgumentException when an index is out of range or a component is not finite
	 */
	public static HurtMotion decoded(final HurtCause cause, final int causeAction, final int writeAfterAction,
	    final PlayerState boundState, final double serverVelocityX, final double serverVelocityY,
	    final double serverVelocityZ) {
		Objects.requireNonNull(cause, "cause");
		if (causeAction < -1 || causeAction == Integer.MAX_VALUE) {
			throw new IllegalArgumentException("cause action is out of range");
		}
		if (writeAfterAction < causeAction) {
			throw new IllegalArgumentException("the write cannot complete before its cause");
		}
		Objects.requireNonNull(boundState, "boundState");
		requireFinite(serverVelocityX, "serverVelocityX");
		requireFinite(serverVelocityY, "serverVelocityY");
		requireFinite(serverVelocityZ, "serverVelocityZ");
		double scale = wireScale(serverVelocityX, serverVelocityY, serverVelocityZ);
		return new HurtMotion(cause, causeAction, writeAfterAction, StateDigest.state(boundState),
		    quantize(serverVelocityX, scale), quantize(serverVelocityY, scale), quantize(serverVelocityZ, scale),
		    boundState.deltaMovementX, boundState.deltaMovementY, boundState.deltaMovementZ);
	}

	/** The damage source whose hit marked the player. */
	public HurtCause cause() {
		return this.cause;
	}

	/** The digest of the client state the write is bound to. */
	public long expectedStateDigest() {
		return this.expectedStateDigest;
	}

	/** The action that caused the marking hit, or {@code -1} when the hit belongs to an action before the run. */
	public int causeAction() {
		return this.causeAction;
	}

	/** The completed action after which this write is applied. */
	public int writeAfterAction() {
		return this.writeAfterAction;
	}

	/** The decoded x velocity the write installs, in blocks per tick. */
	public double writeX() {
		return this.writeX;
	}

	/** The decoded y velocity the write installs, in blocks per tick. */
	public double writeY() {
		return this.writeY;
	}

	/** The decoded z velocity the write installs, in blocks per tick. */
	public double writeZ() {
		return this.writeZ;
	}

	/** The bound client state's own x velocity, which the write replaces, in blocks per tick. */
	public double beforeX() {
		return this.beforeX;
	}

	/** The bound client state's own y velocity, which the write replaces, in blocks per tick. */
	public double beforeY() {
		return this.beforeY;
	}

	/** The bound client state's own z velocity, which the write replaces, in blocks per tick. */
	public double beforeZ() {
		return this.beforeZ;
	}

	/**
	 * Install the decoded velocity on {@code state} after {@code completedAction}.
	 *
	 * @throws IllegalArgumentException when {@code completedAction} is not {@link #writeAfterAction()}
	 * @throws StaleServerWriteException when {@code state} is not the state the write was bound to
	 */
	public void applyAfterAction(final int completedAction, final PlayerState state) {
		Objects.requireNonNull(state, "state");
		if (completedAction != this.writeAfterAction) {
			throw new IllegalArgumentException(
			    "hurt motion belongs after action " + this.writeAfterAction + ", not " + completedAction);
		}
		long actualDigest = StateDigest.state(state);
		if (actualDigest != this.expectedStateDigest) {
			throw new StaleServerWriteException(this.expectedStateDigest, actualDigest);
		}
		applyUnchecked(state);
	}

	/**
	 * Install the decoded velocity without checking the action or the digest. Only for the very state the write was
	 * bound to, by the code that bound it; every other caller uses {@link #applyAfterAction}.
	 */
	public void applyUnchecked(final PlayerState state) {
		state.deltaMovementX = this.writeX;
		state.deltaMovementY = this.writeY;
		state.deltaMovementZ = this.writeZ;
	}

	/**
	 * Equality is the cause, both action indices, the bound digest and the decoded vector bit for bit. The
	 * {@code before} velocities are excluded: they are implied by the digest.
	 */
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
		return "HurtMotion[" + this.cause + " at action " + this.causeAction + ", write (" + this.writeX + ", "
		    + this.writeY + ", " + this.writeZ + ") after action " + this.writeAfterAction + "]";
	}

	private static double wireScale(final double x, final double y, final double z) {
		double maximum = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
		return maximum < MINIMUM_PACKET_SCALE ? 0.0 : Math.ceil(maximum);
	}

	private static double quantize(final double value, final double scale) {
		if (scale == 0.0) {
			return 0.0;
		}
		long packed = Math.round((value / scale * 0.5 + 0.5) * PACKED_MAX);
		return (Math.min(packed & PACKED_MASK, 32766L) * 2.0 / PACKED_MAX - 1.0) * scale;
	}

	private static void requireFinite(final double value, final String name) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException(name + " must be finite");
		}
	}
}
