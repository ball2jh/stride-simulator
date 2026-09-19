package com.nettarion.stride.simulator;

import java.util.Objects;

/**
 * The client's retained record of what it last sent the server:
 * {@code LocalPlayer}'s {@code xLast}, {@code yLast}, {@code zLast},
 * {@code yRotLast}, {@code xRotLast}, {@code lastOnGround},
 * {@code lastHorizontalCollision}, and {@code positionReminder}.
 *
 * <p>{@code sendPosition} compares each tick's result against the last published position, rotation
 * and contacts, not the previous tick, so two moves under the threshold still publish their sum. The
 * baselines are part of the composed step but not movement: a standing player's reminder advances
 * every tick while its {@link PlayerState} stays the same. Mutable and copyable like
 * {@code PlayerState}; one thread owns an instance.
 */
public final class Publisher {
	/** A publisher with no packet history; prefer {@link #atBoundary}. */
	public Publisher() {}

	/**
	 * {@code Mth.square(2.0E-4)}: the strict squared-distance threshold above
	 * which the publisher sends a position, computed as vanilla computes it.
	 */
	private static final double MOVE_THRESHOLD_SQUARED = 2.0E-4 * 2.0E-4;
	/** The silent call count at which a position is sent regardless. */
	private static final int REMINDER_LIMIT = 20;

	/** X position most recently sent in a position-bearing movement packet. */
	public double xLast;

	/** Feet height most recently sent in a position-bearing movement packet. */
	public double yLast;

	/** Z position most recently sent in a position-bearing movement packet. */
	public double zLast;

	/** Yaw most recently sent in a rotation-bearing movement packet, in degrees. */
	public float yRotLast;

	/** Pitch most recently sent in a rotation-bearing movement packet, in degrees. */
	public float xRotLast;

	/** Ground-contact flag in the most recent movement publication. */
	public boolean lastOnGround;

	/** Horizontal-collision flag in the most recent movement publication. */
	public boolean lastHorizontalCollision;

	/** Ticks since the last published position; the twentieth forces one. */
	public int positionReminder;

	/** {@code LocalPlayer.lastSentInput}, flattened to the input packet's seven key bits. */
	public int lastSentInput;

	/** {@code LocalPlayer.wasSprinting}, independent of the latest entity data from the server. */
	public boolean wasSprinting;

	/** Whether the last {@link #publish} found the input keys changed, so an input packet is sent. */
	boolean inputChanged;

	/** Whether the last {@link #publish} found the sprint flag changed, so a sprint command is sent. */
	boolean sprintingChanged;

	/**
	 * The publisher at a packet boundary: the server holds exactly this
	 * position, rotation, and contacts, and the reminder restarts. This is
	 * the meaning of starting a composed transition from a constructed state,
	 * not an inference from it; a live client reports its real publisher.
	 */
	public static Publisher atBoundary(final PlayerState state) {
		Objects.requireNonNull(state, "state");
		Publisher publisher = new Publisher();
		publisher.xLast = state.x;
		publisher.yLast = state.y;
		publisher.zLast = state.z;
		publisher.yRotLast = state.yRot;
		publisher.xRotLast = state.xRot;
		publisher.lastOnGround = state.onGround;
		publisher.lastHorizontalCollision = state.horizontalCollision;
		publisher.positionReminder = 0;
		publisher.lastSentInput = state.inputKeyPresses;
		publisher.wasSprinting = state.sprinting;
		return publisher;
	}

	/** Returns an independent copy of all retained publication state. */
	public Publisher copy() {
		Publisher copy = new Publisher();
		copyInto(copy);
		return copy;
	}

	/** Copy every field into retained caller-owned storage. */
	public void copyInto(final Publisher copy) {
		StateFields.copy(copy, this);
	}

	/** Raw-bit equality of every field. */
	public static boolean rawEquals(final Publisher a, final Publisher b) {
		return StateFields.rawEquals(a, b);
	}

	/**
	 * {@code LocalPlayer.sendPosition}: select the movement packet this tick
	 * sends for {@code state} and advance the retained baselines.
	 *
	 * <p>Runs after the whole client movement tick, where vanilla runs it.
	 * The reminder forces a position on every twentieth silent call. Position
	 * baselines advance only when a position is sent, rotation baselines only
	 * when a rotation is sent, and both contact baselines advance on every
	 * call whether or not anything was sent. The bare player always controls
	 * its camera, which is the branch this models.
	 *
	 * @return the packet form sent, which {@link #packetFor} reports without
	 *     advancing
	 */
	public MovementPacket publish(final PlayerState state) {
		MovementPacket form = packetFor(state);
		this.inputChanged = this.lastSentInput != state.inputKeyPresses;
		this.sprintingChanged = this.wasSprinting != state.sprinting;
		this.lastSentInput = state.inputKeyPresses;
		this.wasSprinting = state.sprinting;
		this.positionReminder++;
		if (form.hasPosition()) {
			this.xLast = state.x;
			this.yLast = state.y;
			this.zLast = state.z;
			this.positionReminder = 0;
		}
		if (form.hasRotation()) {
			this.yRotLast = state.yRot;
			this.xRotLast = state.xRot;
		}
		this.lastOnGround = state.onGround;
		this.lastHorizontalCollision = state.horizontalCollision;
		return form;
	}

	/** The packet form {@link #publish} would select for {@code state}, without advancing. */
	public MovementPacket packetFor(final PlayerState state) {
		double deltaMovementX = state.x - this.xLast;
		double deltaMovementY = state.y - this.yLast;
		double deltaMovementZ = state.z - this.zLast;
		double deltaYRot = state.yRot - this.yRotLast;
		double deltaXRot = state.xRot - this.xRotLast;
		boolean move =
		    deltaMovementX * deltaMovementX + deltaMovementY * deltaMovementY + deltaMovementZ * deltaMovementZ
		        > MOVE_THRESHOLD_SQUARED
		    || this.positionReminder + 1 >= REMINDER_LIMIT;
		boolean rot = deltaYRot != 0.0 || deltaXRot != 0.0;
		if (move && rot) {
			return MovementPacket.POS_ROT;
		}
		if (move) {
			return MovementPacket.POS;
		}
		if (rot) {
			return MovementPacket.ROT;
		}
		if (this.lastOnGround != state.onGround || this.lastHorizontalCollision != state.horizontalCollision) {
			return MovementPacket.STATUS_ONLY;
		}
		return MovementPacket.NONE;
	}

	/**
	 * Rejects non-finite baselines or a negative reminder.
	 *
	 * @throws IllegalStateException when a baseline is not finite or the reminder is negative
	 */
	public void requireValid() {
		if (!Double.isFinite(this.xLast) || !Double.isFinite(this.yLast) || !Double.isFinite(this.zLast)) {
			throw new IllegalStateException("published position must be finite");
		}
		if (!Float.isFinite(this.yRotLast) || !Float.isFinite(this.xRotLast)) {
			throw new IllegalStateException("published rotation must be finite");
		}
		if (this.positionReminder < 0) {
			throw new IllegalStateException("position reminder is out of range");
		}
	}
}
