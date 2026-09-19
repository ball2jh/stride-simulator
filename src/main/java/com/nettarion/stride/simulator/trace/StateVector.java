package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.PlayerState;

import java.util.Arrays;
import java.util.Objects;

/**
 * The recorded player state at one instant, as raw bits per {@link StateField}.
 *
 * <p>Every producer that writes a trace, a live game client or this simulator, fills one of
 * these per tick, so two producers can be compared field for field. Values are stored as raw
 * bits so that equality is exact by construction: a producer hands over a {@code double} and this
 * class keeps its bit pattern; nothing widens, narrows, rounds or normalizes on the way in or out.
 * Instances are immutable.
 */
public final class StateVector {
	private final long[] raw;

	/**
	 * Human-readable labels for {@link StateField.Kind#ENUM} fields, parallel to
	 * {@link StateField#ALL}. Deliberately outside the equality relation: a label is a diagnostic
	 * aid, and the numeric id in {@link #raw} is the fact.
	 */
	private final String[] labels;

	private StateVector(final long[] raw, final String[] labels) {
		this.raw = raw;
		this.labels = labels;
	}

	/** Returns an empty builder for assigning a complete recorded state vector. */
	public static Builder builder() {
		return new Builder();
	}

	/** Returns the stored raw encoding of a field without converting its scalar type. */
	public long rawBits(final StateField field) {
		return this.raw[field.ordinal()];
	}

	/** Decodes a double field, refusing a field of another kind. */
	public double getDouble(final StateField field) {
		require(field, StateField.Kind.DOUBLE);
		return Double.longBitsToDouble(this.raw[field.ordinal()]);
	}

	/** Decodes a float field, refusing a field of another kind. */
	public float getFloat(final StateField field) {
		require(field, StateField.Kind.FLOAT);
		return Float.intBitsToFloat((int) this.raw[field.ordinal()]);
	}

	/** Decodes a boolean field, refusing a field of another kind. */
	public boolean getBoolean(final StateField field) {
		require(field, StateField.Kind.BOOLEAN);
		return this.raw[field.ordinal()] != 0L;
	}

	/** Decodes an integer field, refusing a field of another kind. */
	public int getInt(final StateField field) {
		require(field, StateField.Kind.INT);
		return (int) this.raw[field.ordinal()];
	}

	/** Decodes a packed-byte field, refusing a field of another kind. */
	public byte getFlags(final StateField field) {
		require(field, StateField.Kind.BYTE_FLAGS);
		return (byte) this.raw[field.ordinal()];
	}

	/** The vanilla numeric id of an enum field, refusing a field of another kind. */
	public int getEnumId(final StateField field) {
		require(field, StateField.Kind.ENUM);
		return (int) this.raw[field.ordinal()];
	}

	/** The label recorded for an enum field, or its numeric id if none was given. */
	public String label(final StateField field) {
		String label = this.labels[field.ordinal()];
		return label != null ? label : Long.toString(this.raw[field.ordinal()]);
	}

	private static void require(final StateField field, final StateField.Kind expected) {
		if (field.kind() != expected) {
			throw new IllegalArgumentException(field + " is " + field.kind() + ", not " + expected);
		}
	}

	@Override
	public boolean equals(final Object other) {
		return other instanceof StateVector state && Arrays.equals(this.raw, state.raw);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(this.raw);
	}

	/**
	 * A new player state carrying exactly these values, without validating them.
	 *
	 * <p>The pose is restored from its vanilla numeric id, never from the diagnostic label. Refuses
	 * with {@link IllegalArgumentException} when the id names no admitted pose.
	 */
	public PlayerState toPlayerState() {
		PlayerState state = new PlayerState();
		state.x = getDouble(StateField.POS_X);
		state.y = getDouble(StateField.POS_Y);
		state.z = getDouble(StateField.POS_Z);
		state.deltaMovementX = getDouble(StateField.DELTA_X);
		state.deltaMovementY = getDouble(StateField.DELTA_Y);
		state.deltaMovementZ = getDouble(StateField.DELTA_Z);
		state.yRot = getFloat(StateField.Y_ROT);
		state.xRot = getFloat(StateField.X_ROT);
		state.setBox(
		    new AABB(getDouble(StateField.BB_MIN_X), getDouble(StateField.BB_MIN_Y), getDouble(StateField.BB_MIN_Z),
		        getDouble(StateField.BB_MAX_X), getDouble(StateField.BB_MAX_Y), getDouble(StateField.BB_MAX_Z)));
		state.onGround = getBoolean(StateField.ON_GROUND);
		state.horizontalCollision = getBoolean(StateField.HORIZONTAL_COLLISION);
		state.verticalCollision = getBoolean(StateField.VERTICAL_COLLISION);
		state.verticalCollisionBelow = getBoolean(StateField.VERTICAL_COLLISION_BELOW);
		state.minorHorizontalCollision = getBoolean(StateField.MINOR_HORIZONTAL_COLLISION);
		state.mainSupportingBlockPosPresent = getBoolean(StateField.SUPPORTING_BLOCK_PRESENT);
		state.mainSupportingBlockPosX = getInt(StateField.SUPPORTING_BLOCK_X);
		state.mainSupportingBlockPosY = getInt(StateField.SUPPORTING_BLOCK_Y);
		state.mainSupportingBlockPosZ = getInt(StateField.SUPPORTING_BLOCK_Z);
		state.onGroundNoBlocks = getBoolean(StateField.ON_GROUND_NO_BLOCKS);
		state.fallDistance = getDouble(StateField.FALL_DISTANCE);
		state.sprinting = getBoolean(StateField.SPRINTING);
		state.sprintingAttribute = getBoolean(StateField.SPRINTING_ATTRIBUTE);
		state.foodLevel = getInt(StateField.FOOD_LEVEL);
		state.swimming = getBoolean(StateField.SWIMMING);
		state.fallFlying = getBoolean(StateField.FALL_FLYING);
		state.sharedFlagsResidual = getFlags(StateField.SHARED_FLAGS_RESIDUAL);
		state.pose = PlayerState.Pose.fromVanillaId(getEnumId(StateField.POSE));
		state.xxa = getFloat(StateField.XXA);
		state.yya = getFloat(StateField.YYA);
		state.zza = getFloat(StateField.ZZA);
		state.jumping = getBoolean(StateField.JUMPING);
		state.sprintTriggerTime = getInt(StateField.SPRINT_TRIGGER_TIME);
		state.jumpTriggerTime = getInt(StateField.JUMP_TRIGGER_TIME);
		state.autoJumpTime = getInt(StateField.AUTO_JUMP_TIME);
		state.noJumpDelay = getInt(StateField.NO_JUMP_DELAY);
		state.crouching = getBoolean(StateField.CROUCHING);
		state.inputKeyPresses = getFlags(StateField.INPUT_KEY_PRESSES);
		state.inputMoveVectorX = getFloat(StateField.INPUT_MOVE_VECTOR_X);
		state.inputMoveVectorY = getFloat(StateField.INPUT_MOVE_VECTOR_Y);
		state.waterHeight = getDouble(StateField.WATER_HEIGHT);
		state.lavaHeight = getDouble(StateField.LAVA_HEIGHT);
		state.eyeInWater = getBoolean(StateField.EYE_IN_WATER);
		state.mayfly = getBoolean(StateField.MAY_FLY);
		state.flying = getBoolean(StateField.FLYING);
		state.flyingSpeed = getFloat(StateField.FLYING_SPEED);
		state.fallFlyTicks = getInt(StateField.FALL_FLY_TICKS);
		state.gliderUsable = getBoolean(StateField.GLIDER_USABLE);
		state.stuckSpeedMultiplierX = getDouble(StateField.STUCK_SPEED_X);
		state.stuckSpeedMultiplierY = getDouble(StateField.STUCK_SPEED_Y);
		state.stuckSpeedMultiplierZ = getDouble(StateField.STUCK_SPEED_Z);
		state.isInPowderSnow = getBoolean(StateField.IN_POWDER_SNOW);
		state.wasInPowderSnow = getBoolean(StateField.WAS_IN_POWDER_SNOW);
		state.ticksFrozen = getInt(StateField.TICKS_FROZEN);
		state.canFreeze = getBoolean(StateField.CAN_FREEZE);
		state.frostSpeedTicks = getInt(StateField.FROST_SPEED_TICKS);
		state.canWalkOnPowderSnow = getBoolean(StateField.CAN_WALK_ON_POWDER_SNOW);
		state.sprintWindowTicks = getInt(StateField.SPRINT_WINDOW_TICKS);
		state.autoJumpEnabled = getBoolean(StateField.AUTO_JUMP_ENABLED);
		state.fastLava = getBoolean(StateField.FAST_LAVA);
		state.movementSpeedMultiplier = getDouble(StateField.MOVEMENT_SPEED_MULTIPLIER);
		state.jumpBoostPower = getFloat(StateField.JUMP_BOOST_POWER);
		return state;
	}

	/**
	 * A new player state carrying these values, checked with
	 * {@link PlayerState#requireValidForTransition()}; refuses with {@link IllegalStateException}.
	 */
	public PlayerState toPlayerStateForTransition() {
		PlayerState state = toPlayerState();
		state.requireValidForTransition();
		return state;
	}

	/** The recorded vector of one player state; the state is not modified. */
	public static StateVector of(final PlayerState state) {
		StateVector.Builder builder = StateVector.builder()
		                                  .set(StateField.POS_X, state.x)
		                                  .set(StateField.POS_Y, state.y)
		                                  .set(StateField.POS_Z, state.z)
		                                  .set(StateField.DELTA_X, state.deltaMovementX)
		                                  .set(StateField.DELTA_Y, state.deltaMovementY)
		                                  .set(StateField.DELTA_Z, state.deltaMovementZ)
		                                  .set(StateField.Y_ROT, state.yRot)
		                                  .set(StateField.X_ROT, state.xRot)
		                                  .set(StateField.BB_MIN_X, state.boundingBoxMinX)
		                                  .set(StateField.BB_MIN_Y, state.boundingBoxMinY)
		                                  .set(StateField.BB_MIN_Z, state.boundingBoxMinZ)
		                                  .set(StateField.BB_MAX_X, state.boundingBoxMaxX)
		                                  .set(StateField.BB_MAX_Y, state.boundingBoxMaxY)
		                                  .set(StateField.BB_MAX_Z, state.boundingBoxMaxZ)
		                                  .set(StateField.ON_GROUND, state.onGround)
		                                  .set(StateField.HORIZONTAL_COLLISION, state.horizontalCollision)
		                                  .set(StateField.VERTICAL_COLLISION, state.verticalCollision)
		                                  .set(StateField.VERTICAL_COLLISION_BELOW, state.verticalCollisionBelow)
		                                  .set(StateField.MINOR_HORIZONTAL_COLLISION, state.minorHorizontalCollision)
		                                  .set(StateField.SUPPORTING_BLOCK_PRESENT, state.mainSupportingBlockPosPresent)
		                                  .set(StateField.SUPPORTING_BLOCK_X, state.mainSupportingBlockPosX)
		                                  .set(StateField.SUPPORTING_BLOCK_Y, state.mainSupportingBlockPosY)
		                                  .set(StateField.SUPPORTING_BLOCK_Z, state.mainSupportingBlockPosZ)
		                                  .set(StateField.ON_GROUND_NO_BLOCKS, state.onGroundNoBlocks)
		                                  .set(StateField.FALL_DISTANCE, state.fallDistance)
		                                  .setEnum(StateField.POSE, state.pose.id, state.pose.name())
		                                  .set(StateField.XXA, state.xxa)
		                                  .set(StateField.YYA, state.yya)
		                                  .set(StateField.ZZA, state.zza)
		                                  .set(StateField.JUMPING, state.jumping)
		                                  .set(StateField.SPRINT_TRIGGER_TIME, state.sprintTriggerTime)
		                                  .set(StateField.JUMP_TRIGGER_TIME, state.jumpTriggerTime)
		                                  .set(StateField.AUTO_JUMP_TIME, state.autoJumpTime)
		                                  .set(StateField.NO_JUMP_DELAY, state.noJumpDelay)
		                                  .set(StateField.CROUCHING, state.crouching)
		                                  .setFlags(StateField.INPUT_KEY_PRESSES, state.inputKeyPresses)
		                                  .set(StateField.INPUT_MOVE_VECTOR_X, state.inputMoveVectorX)
		                                  .set(StateField.INPUT_MOVE_VECTOR_Y, state.inputMoveVectorY)
		                                  .set(StateField.WATER_HEIGHT, state.waterHeight)
		                                  .set(StateField.LAVA_HEIGHT, state.lavaHeight)
		                                  .set(StateField.EYE_IN_WATER, state.eyeInWater)
		                                  .set(StateField.MAY_FLY, state.mayfly)
		                                  .set(StateField.FLYING, state.flying)
		                                  .set(StateField.FLYING_SPEED, state.flyingSpeed)
		                                  .set(StateField.FALL_FLY_TICKS, state.fallFlyTicks)
		                                  .set(StateField.GLIDER_USABLE, state.gliderUsable)
		                                  .set(StateField.STUCK_SPEED_X, state.stuckSpeedMultiplierX)
		                                  .set(StateField.STUCK_SPEED_Y, state.stuckSpeedMultiplierY)
		                                  .set(StateField.STUCK_SPEED_Z, state.stuckSpeedMultiplierZ)
		                                  .set(StateField.IN_POWDER_SNOW, state.isInPowderSnow)
		                                  .set(StateField.WAS_IN_POWDER_SNOW, state.wasInPowderSnow)
		                                  .set(StateField.TICKS_FROZEN, state.ticksFrozen)
		                                  .set(StateField.CAN_FREEZE, state.canFreeze)
		                                  .set(StateField.FROST_SPEED_TICKS, state.frostSpeedTicks)
		                                  .set(StateField.CAN_WALK_ON_POWDER_SNOW, state.canWalkOnPowderSnow);
		builder.set(StateField.SPRINT_WINDOW_TICKS, state.sprintWindowTicks)
		    .set(StateField.AUTO_JUMP_ENABLED, state.autoJumpEnabled)
		    .set(StateField.FAST_LAVA, state.fastLava)
		    .set(StateField.MOVEMENT_SPEED_MULTIPLIER, state.movementSpeedMultiplier)
		    .set(StateField.JUMP_BOOST_POWER, state.jumpBoostPower)
		    .set(StateField.SPRINTING_ATTRIBUTE, state.sprintingAttribute)
		    .set(StateField.FOOD_LEVEL, state.foodLevel);
		int flags = Byte.toUnsignedInt(state.sharedFlagsResidual);
		flags |= state.sprinting ? 1 << SharedFlagBits.SPRINTING : 0;
		flags |= state.swimming ? 1 << SharedFlagBits.SWIMMING : 0;
		flags |= state.fallFlying ? 1 << SharedFlagBits.FALL_FLYING : 0;
		SharedFlagBits.decompose(builder, (byte) flags);
		return builder.build();
	}

	/** Assigns every field once, then {@link #build() builds} an immutable vector. Not thread-safe. */
	public static final class Builder {
		private final long[] raw = new long[StateField.ALL.size()];
		private final String[] labels = new String[StateField.ALL.size()];
		private final boolean[] assigned = new boolean[StateField.ALL.size()];

		private Builder() {}

		/** Sets a {@link StateField.Kind#DOUBLE} field from its raw bits; refuses another kind. */
		public Builder set(final StateField field, final double value) {
			require(field, StateField.Kind.DOUBLE);
			return put(field, Double.doubleToRawLongBits(value));
		}

		/** Sets a {@link StateField.Kind#FLOAT} field from its raw bits; refuses another kind. */
		public Builder set(final StateField field, final float value) {
			require(field, StateField.Kind.FLOAT);
			return put(field, Integer.toUnsignedLong(Float.floatToRawIntBits(value)));
		}

		/** Sets a {@link StateField.Kind#BOOLEAN} field; refuses another kind. */
		public Builder set(final StateField field, final boolean value) {
			require(field, StateField.Kind.BOOLEAN);
			return put(field, value ? 1L : 0L);
		}

		/** Sets a {@link StateField.Kind#INT} field; refuses another kind. */
		public Builder set(final StateField field, final int value) {
			require(field, StateField.Kind.INT);
			return put(field, value);
		}

		/** Sets a {@link StateField.Kind#BYTE_FLAGS} field from its unsigned byte; refuses another kind. */
		public Builder setFlags(final StateField field, final byte value) {
			require(field, StateField.Kind.BYTE_FLAGS);
			return put(field, Byte.toUnsignedLong(value));
		}

		/**
		 * Sets a {@link StateField.Kind#ENUM} field from its vanilla numeric id and a diagnostic
		 * label; refuses another kind.
		 */
		public Builder setEnum(final StateField field, final int id, final String label) {
			require(field, StateField.Kind.ENUM);
			this.labels[field.ordinal()] = Objects.requireNonNull(label, "label");
			return put(field, id);
		}

		/** Sets a field from bits already in trace form, with an optional label, for the trace reader. */
		public Builder setRawBits(final StateField field, final long bits, final String label) {
			this.labels[field.ordinal()] = label;
			return put(field, bits);
		}

		private Builder put(final StateField field, final long bits) {
			this.raw[field.ordinal()] = bits;
			this.assigned[field.ordinal()] = true;
			return this;
		}

		/**
		 * The immutable vector; refuses with {@link IllegalStateException} when any field was never
		 * assigned, since an unassigned field would compare equal to another producer's unassigned
		 * field and hide a gap in a capture.
		 */
		public StateVector build() {
			for (StateField field : StateField.ALL) {
				if (!this.assigned[field.ordinal()]) {
					throw new IllegalStateException("state field " + field + " was never assigned");
				}
			}
			return new StateVector(this.raw.clone(), this.labels.clone());
		}
	}
}
