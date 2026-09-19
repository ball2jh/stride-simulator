package com.nettarion.stride.simulator;

/**
 * One client action: the seven input keys Minecraft sends in its player input packet, plus the
 * view rotation for that tick.
 *
 * <p>The record mirrors the wire shape of vanilla's {@code net.minecraft.world.entity.player.Input}
 * so a packed action is byte-identical to what {@code ServerboundPlayerInputPacket} carries.
 * Build actions with {@link #of(float, float, Key...)}, {@link #idle(float, float)}, and
 * {@link #with(Key...)} rather than the positional constructor. Opposing direction keys are
 * allowed and resolved by the tick's input rules.
 *
 * <p>Rotation is in degrees. Yaw zero faces positive Z and positive yaw turns toward negative X;
 * negative pitch looks up. Yaw need not be wrapped to one revolution. The component names
 * {@code yRot} and {@code xRot} are vanilla's names for yaw and pitch.
 *
 * @param forward forward key state
 * @param backward backward key state
 * @param left left key state
 * @param right right key state
 * @param jump jump key state
 * @param sneak sneak key state, vanilla's {@code shift}
 * @param sprint sprint key state
 * @param yRot finite yaw in degrees
 * @param xRot finite pitch in degrees, in [-90, 90]
 */
public record PlayerInput(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean sneak,
    boolean sprint, float yRot, float xRot) {
	/** Validates finite rotations and pitch bounds, and canonicalizes signed-zero rotations. */
	public PlayerInput {
		// The real client discards a non-finite rotation in Entity.setYRot and
		// setXRot and clamps every pitch it holds to [-90, 90] in Entity.turn and
		// setXRot, so no delivered control can carry either; refusing them here
		// keeps a replay from predicting a state a real client could never produce.
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

	// Bit layout of Input.STREAM_CODEC.
	/** Bit mask for the forward key in the packed input byte. */
	public static final int FLAG_FORWARD = 1;
	/** Bit mask for the backward key in the packed input byte. */
	public static final int FLAG_BACKWARD = 2;
	/** Bit mask for the left key in the packed input byte. */
	public static final int FLAG_LEFT = 4;
	/** Bit mask for the right key in the packed input byte. */
	public static final int FLAG_RIGHT = 8;
	/** Bit mask for the jump key in the packed input byte. */
	public static final int FLAG_JUMP = 16;
	/** Bit mask for the sneak key in the packed input byte. */
	public static final int FLAG_SNEAK = 32;
	/** Bit mask for the sprint key in the packed input byte. */
	public static final int FLAG_SPRINT = 64;

	/** An action with every key released and the given view rotation. */
	public static PlayerInput idle(final float yRot, final float xRot) {
		return new PlayerInput(false, false, false, false, false, false, false, yRot, xRot);
	}

	/** One of the seven input keys. */
	public enum Key {
		/** The forward key. */
		FORWARD(FLAG_FORWARD),
		/** The backward key. */
		BACKWARD(FLAG_BACKWARD),
		/** The strafe-left key. */
		LEFT(FLAG_LEFT),
		/** The strafe-right key. */
		RIGHT(FLAG_RIGHT),
		/** The jump key. */
		JUMP(FLAG_JUMP),
		/** The sneak key. */
		SNEAK(FLAG_SNEAK),
		/** The sprint key. */
		SPRINT(FLAG_SPRINT);

		private final int flag;

		Key(final int flag) {
			this.flag = flag;
		}

		/** This key's bit in the packed input byte. */
		public int flag() {
			return this.flag;
		}
	}

	/** An action holding exactly {@code keys} with the given view rotation. */
	public static PlayerInput of(final float yRot, final float xRot, final Key... keys) {
		return idle(yRot, xRot).with(keys);
	}

	/** This action with {@code keys} also held. */
	public PlayerInput with(final Key... keys) {
		int flags = Byte.toUnsignedInt(packedInput());
		for (Key key : keys) {
			flags |= key.flag;
		}
		return ofPacked((byte) flags, this.yRot, this.xRot);
	}

	/** This action with {@code keys} released. */
	public PlayerInput without(final Key... keys) {
		int flags = Byte.toUnsignedInt(packedInput());
		for (Key key : keys) {
			flags &= ~key.flag;
		}
		return ofPacked((byte) flags, this.yRot, this.xRot);
	}

	/** This action with a different view rotation. */
	public PlayerInput looking(final float yRot, final float xRot) {
		return new PlayerInput(
		    this.forward, this.backward, this.left, this.right, this.jump, this.sneak, this.sprint, yRot, xRot);
	}

	/** Encodes the seven key flags in the packet bit order. */
	public byte packedInput() {
		int flags = 0;
		flags |= this.forward ? FLAG_FORWARD : 0;
		flags |= this.backward ? FLAG_BACKWARD : 0;
		flags |= this.left ? FLAG_LEFT : 0;
		flags |= this.right ? FLAG_RIGHT : 0;
		flags |= this.jump ? FLAG_JUMP : 0;
		flags |= this.sneak ? FLAG_SNEAK : 0;
		flags |= this.sprint ? FLAG_SPRINT : 0;
		return (byte) flags;
	}

	/** Decodes the lower seven key bits with the supplied yaw and pitch; bit seven is ignored. */
	public static PlayerInput ofPacked(final byte packedInput, final float yRot, final float xRot) {
		int flags = Byte.toUnsignedInt(packedInput);
		return new PlayerInput((flags & FLAG_FORWARD) != 0, (flags & FLAG_BACKWARD) != 0, (flags & FLAG_LEFT) != 0,
		    (flags & FLAG_RIGHT) != 0, (flags & FLAG_JUMP) != 0, (flags & FLAG_SNEAK) != 0, (flags & FLAG_SPRINT) != 0,
		    yRot, xRot);
	}
}
