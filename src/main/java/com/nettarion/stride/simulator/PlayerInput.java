package com.nettarion.stride.simulator;

/**
 * One engine action: the seven input bits vanilla itself canonicalized into
 * {@code net.minecraft.world.entity.player.Input}, plus view rotation.
 *
 * <p>The kernel adopts vanilla's action rather than inventing another input
 * vocabulary. Rotation is continuous; a consumer decides which rotations it
 * can physically produce. Opposing direction keys are allowed and resolved by
 * the tick's input rules. Positive yaw turns toward negative X from positive Z;
 * negative pitch looks up. Yaw need not be wrapped to one revolution.
 *
 * @param forward forward key state
 * @param backward backward key state
 * @param left left key state
 * @param right right key state
 * @param jump jump key state
 * @param shift sneak key state
 * @param sprint sprint key state
 * @param yRot finite yaw in degrees
 * @param xRot finite pitch in degrees, in [-90, 90]
 */
public record PlayerInput(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean shift,
    boolean sprint, float yRot, float xRot) {
	/** Validates finite rotations and pitch bounds, and canonicalizes signed-zero rotations. */
	public PlayerInput {
		// The real client discards a non-finite rotation in Entity.setYRot and
		// setXRot and clamps every pitch it holds to [-90, 90] in Entity.turn and
		// setXRot, so no delivered control can carry either; refusing them here
		// keeps a replay from predicting a state the adapter's clamping setter
		// would never produce.
		if (!Float.isFinite(yRot) || !Float.isFinite(xRot)) {
			throw new IllegalArgumentException("rotation must be finite: yaw " + yRot + ", pitch " + xRot);
		}
		if (xRot < -90.0F || xRot > 90.0F) {
			throw new IllegalArgumentException("pitch must lie in [-90, 90]: " + xRot);
		}
		// Minecraft's client rotation path does not preserve signed zero. Do the
		// same at the action boundary so a replay never predicts a raw state the
		// delivered control cannot stably produce.
		if (yRot == 0.0F) yRot = 0.0F;
		if (xRot == 0.0F) xRot = 0.0F;
	}

	// Bit layout of Input.STREAM_CODEC, so a packed action is byte-identical to
	// what ServerboundPlayerInputPacket carries.
	/** Bit mask for the forward input in the packed upstream layout. */
	public static final int FLAG_FORWARD = 1;
	/** Bit mask for the backward input in the packed upstream layout. */
	public static final int FLAG_BACKWARD = 2;
	/** Bit mask for the left input in the packed upstream layout. */
	public static final int FLAG_LEFT = 4;
	/** Bit mask for the right input in the packed upstream layout. */
	public static final int FLAG_RIGHT = 8;
	/** Bit mask for the jump input in the packed upstream layout. */
	public static final int FLAG_JUMP = 16;
	/** Bit mask for the sneak input in the packed upstream layout. */
	public static final int FLAG_SHIFT = 32;
	/** Bit mask for the sprint input in the packed upstream layout. */
	public static final int FLAG_SPRINT = 64;

	/** Encodes the seven input flags in the upstream input-packet bit order. */
	public byte packedInput() {
		int flags = 0;
		flags |= this.forward ? FLAG_FORWARD : 0;
		flags |= this.backward ? FLAG_BACKWARD : 0;
		flags |= this.left ? FLAG_LEFT : 0;
		flags |= this.right ? FLAG_RIGHT : 0;
		flags |= this.jump ? FLAG_JUMP : 0;
		flags |= this.shift ? FLAG_SHIFT : 0;
		flags |= this.sprint ? FLAG_SPRINT : 0;
		return (byte) flags;
	}

	/** Decodes the lower seven input bits with the supplied yaw and pitch; bit seven is ignored. */
	public static PlayerInput ofPacked(final byte packedInput, final float yRot, final float xRot) {
		int flags = Byte.toUnsignedInt(packedInput);
		return new PlayerInput((flags & FLAG_FORWARD) != 0, (flags & FLAG_BACKWARD) != 0, (flags & FLAG_LEFT) != 0,
		    (flags & FLAG_RIGHT) != 0, (flags & FLAG_JUMP) != 0, (flags & FLAG_SHIFT) != 0, (flags & FLAG_SPRINT) != 0,
		    yRot, xRot);
	}

	/** Returns an action with every input button released and the requested view rotation. */
	public static PlayerInput idle(final float yRot, final float xRot) {
		return new PlayerInput(false, false, false, false, false, false, false, yRot, xRot);
	}
}
