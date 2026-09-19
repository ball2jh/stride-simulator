package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.PlayerState;

import java.util.List;

/**
 * One field of the recorded player state vector.
 *
 * <p>Declaration order is the column order of a trace and the order divergences are reported
 * in. Appending a field is a trace format change ({@link Trace#FORMAT_VERSION}).
 *
 * <p>Every field is stored as raw bits in a {@code long} whatever its source type, so equality
 * is plain {@code long} comparison. No floating-point comparison happens anywhere, which keeps
 * {@code -0.0} distinct from {@code 0.0} and preserves NaN payloads; both are reachable in
 * vanilla collision code and both are invisible to {@code ==}.
 */
public enum StateField {
	/** {@link PlayerState#x}, in blocks. */
	POS_X(Kind.DOUBLE),
	/** {@link PlayerState#y}, in blocks. */
	POS_Y(Kind.DOUBLE),
	/** {@link PlayerState#z}, in blocks. */
	POS_Z(Kind.DOUBLE),

	/** {@link PlayerState#deltaMovementX}, in blocks per tick. */
	DELTA_X(Kind.DOUBLE),
	/** {@link PlayerState#deltaMovementY}, in blocks per tick. */
	DELTA_Y(Kind.DOUBLE),
	/** {@link PlayerState#deltaMovementZ}, in blocks per tick. */
	DELTA_Z(Kind.DOUBLE),

	/** {@link PlayerState#yRot}, yaw in degrees. */
	Y_ROT(Kind.FLOAT),
	/** {@link PlayerState#xRot}, pitch in degrees. */
	X_ROT(Kind.FLOAT),

	/**
	 * {@link PlayerState#boundingBoxMinX}. Vanilla mutates {@code Entity.bb} directly in
	 * {@code move()} and {@code setPos()}, so the box is carried rather than derived from position
	 * and dimensions.
	 */
	BB_MIN_X(Kind.DOUBLE),
	/** {@link PlayerState#boundingBoxMinY}. */
	BB_MIN_Y(Kind.DOUBLE),
	/** {@link PlayerState#boundingBoxMinZ}. */
	BB_MIN_Z(Kind.DOUBLE),
	/** {@link PlayerState#boundingBoxMaxX}. */
	BB_MAX_X(Kind.DOUBLE),
	/** {@link PlayerState#boundingBoxMaxY}. */
	BB_MAX_Y(Kind.DOUBLE),
	/** {@link PlayerState#boundingBoxMaxZ}. */
	BB_MAX_Z(Kind.DOUBLE),

	/** {@link PlayerState#onGround}. */
	ON_GROUND(Kind.BOOLEAN),
	/** {@link PlayerState#horizontalCollision}. */
	HORIZONTAL_COLLISION(Kind.BOOLEAN),
	/** {@link PlayerState#verticalCollision}. */
	VERTICAL_COLLISION(Kind.BOOLEAN),
	/** {@link PlayerState#verticalCollisionBelow}. */
	VERTICAL_COLLISION_BELOW(Kind.BOOLEAN),
	/** {@link PlayerState#minorHorizontalCollision}. */
	MINOR_HORIZONTAL_COLLISION(Kind.BOOLEAN),

	/** {@link PlayerState#fallDistance}, in blocks; a double in 26.2 ({@code Entity.fallDistance}). */
	FALL_DISTANCE(Kind.DOUBLE),

	/**
	 * {@link PlayerState#sprinting}: bit 3 of the vanilla shared-flags byte, set by
	 * {@code LocalPlayer.aiStep}.
	 *
	 * <p>The shared-flags byte is split into named fields by who computes each bit. Recording the
	 * byte as one value would let one server-owned bit force the whole byte out of comparison,
	 * taking the client-computed sprint state with it. Bit numbers are those of the
	 * {@code Entity.setSharedFlag} call sites; see {@link SharedFlagBits}.
	 */
	SPRINTING(Kind.BOOLEAN),
	/** {@link PlayerState#swimming}: shared-flags bit 4, set by {@code Entity.updateSwimming}. */
	SWIMMING(Kind.BOOLEAN),
	/** {@link PlayerState#fallFlying}: shared-flags bit 7, set by {@code LivingEntity} and {@code Player}. */
	FALL_FLYING(Kind.BOOLEAN),

	/**
	 * Shared-flags bit 1, the server-owned sneak flag, kept apart from local sneak input.
	 *
	 * <p>Only the vanilla server sets this bit, and it reaches the client through the entity-data
	 * echo, so no implementation without a server can reproduce it. It is inert for client
	 * movement: {@code LocalPlayer.isShiftKeyDown()} reads {@code input.keyPresses.sneak()} and
	 * never consults the bit. It exists so other clients can render the crouch.
	 */
	SHIFT_KEY_DOWN(Kind.BOOLEAN),

	/**
	 * {@link PlayerState#sharedFlagsResidual}: every shared-flags bit not named above (0 on fire,
	 * 2, 5 invisible, 6 glowing), masked by {@link SharedFlagBits#RESIDUAL_MASK}. Carried so an
	 * unclassified bit still diverges loudly instead of vanishing when the byte was split.
	 */
	SHARED_FLAGS_RESIDUAL(Kind.BYTE_FLAGS),

	/** {@link PlayerState#pose}, stored as the vanilla numeric pose id. */
	POSE(Kind.ENUM),

	/** {@link PlayerState#xxa}: vanilla {@code LivingEntity.xxa}, written by input handling before travel. */
	XXA(Kind.FLOAT),
	/** {@link PlayerState#yya}: vanilla {@code LivingEntity.yya}. */
	YYA(Kind.FLOAT),
	/** {@link PlayerState#zza}: vanilla {@code LivingEntity.zza}. */
	ZZA(Kind.FLOAT),
	/** {@link PlayerState#jumping}: vanilla {@code LivingEntity.jumping}. */
	JUMPING(Kind.BOOLEAN),

	/**
	 * {@link PlayerState#sprintTriggerTime}, in ticks. The tick-based trigger state machines are
	 * why a transition is not a pure function of position and velocity.
	 */
	SPRINT_TRIGGER_TIME(Kind.INT),
	/** {@link PlayerState#jumpTriggerTime}, in ticks. */
	JUMP_TRIGGER_TIME(Kind.INT),
	/** {@link PlayerState#autoJumpTime}, in ticks. */
	AUTO_JUMP_TIME(Kind.INT),
	/** {@link PlayerState#noJumpDelay}, in ticks. */
	NO_JUMP_DELAY(Kind.INT),
	/** {@link PlayerState#crouching}. */
	CROUCHING(Kind.BOOLEAN),

	/**
	 * {@link PlayerState#inputKeyPresses}: the vanilla {@code ClientInput} sampled at the start
	 * of {@code LocalPlayer.aiStep}, before {@code KeyboardInput.tick} overwrites it with this
	 * tick's action. Without these fields a mid-trace state cannot be forked exactly.
	 */
	INPUT_KEY_PRESSES(Kind.BYTE_FLAGS),
	/** {@link PlayerState#inputMoveVectorX}. */
	INPUT_MOVE_VECTOR_X(Kind.FLOAT),
	/** {@link PlayerState#inputMoveVectorY}. */
	INPUT_MOVE_VECTOR_Y(Kind.FLOAT),

	/**
	 * {@link PlayerState#waterHeight}, in blocks: the vanilla {@code EntityFluidInteraction}
	 * tracker at the end of the tick. The next {@code baseTick} samples {@code EYE_IN_WATER} before
	 * rebuilding the tracker, so this is fork state rather than a derived observation.
	 */
	WATER_HEIGHT(Kind.DOUBLE),
	/** {@link PlayerState#lavaHeight}, in blocks. */
	LAVA_HEIGHT(Kind.DOUBLE),
	/** {@link PlayerState#eyeInWater}. */
	EYE_IN_WATER(Kind.BOOLEAN),

	/**
	 * {@link PlayerState#mayfly}: vanilla {@code Player.Abilities.mayfly}, which owns the
	 * double-tap flight state machine.
	 */
	MAY_FLY(Kind.BOOLEAN),
	/** {@link PlayerState#flying}: vanilla {@code Player.Abilities.flying}, which changes travel and pose. */
	FLYING(Kind.BOOLEAN),
	/** {@link PlayerState#flyingSpeed}: vanilla {@code Player.Abilities.flyingSpeed}; mutable, so carried. */
	FLYING_SPEED(Kind.FLOAT),

	/** {@link PlayerState#fallFlyTicks}, in ticks: the client's fall-flying counter. */
	FALL_FLY_TICKS(Kind.INT),
	/**
	 * {@link PlayerState#gliderUsable}: whether an equipped item is a usable glider. Carrying
	 * the capability keeps custom gliders possible without recording inventory.
	 */
	GLIDER_USABLE(Kind.BOOLEAN),

	/**
	 * {@link PlayerState#stuckSpeedMultiplierX}: powder-snow contact is a delayed effect;
	 * {@code entityInside} installs the multiplier after travel and the next {@code Entity.move}
	 * consumes it.
	 */
	STUCK_SPEED_X(Kind.DOUBLE),
	/** {@link PlayerState#stuckSpeedMultiplierY}. */
	STUCK_SPEED_Y(Kind.DOUBLE),
	/** {@link PlayerState#stuckSpeedMultiplierZ}. */
	STUCK_SPEED_Z(Kind.DOUBLE),
	/** {@link PlayerState#isInPowderSnow}. */
	IN_POWDER_SNOW(Kind.BOOLEAN),
	/** {@link PlayerState#wasInPowderSnow}. */
	WAS_IN_POWDER_SNOW(Kind.BOOLEAN),
	/** {@link PlayerState#ticksFrozen}, in ticks. */
	TICKS_FROZEN(Kind.INT),
	/** {@link PlayerState#canFreeze}. */
	CAN_FREEZE(Kind.BOOLEAN),
	/**
	 * {@link PlayerState#frostSpeedTicks}, in ticks: server-written attribute state that can lag
	 * {@link #TICKS_FROZEN}.
	 */
	FROST_SPEED_TICKS(Kind.INT),

	/**
	 * {@link PlayerState#mainSupportingBlockPosPresent}: vanilla {@code Entity.checkSupportingBlock}
	 * retains the supporting block across ticks; jump and ground friction read the previous tick's
	 * identity before the next move.
	 */
	SUPPORTING_BLOCK_PRESENT(Kind.BOOLEAN),
	/** {@link PlayerState#mainSupportingBlockPosX}, a block coordinate. */
	SUPPORTING_BLOCK_X(Kind.INT),
	/** {@link PlayerState#mainSupportingBlockPosY}, a block coordinate. */
	SUPPORTING_BLOCK_Y(Kind.INT),
	/** {@link PlayerState#mainSupportingBlockPosZ}, a block coordinate. */
	SUPPORTING_BLOCK_Z(Kind.INT),
	/** {@link PlayerState#onGroundNoBlocks}. */
	ON_GROUND_NO_BLOCKS(Kind.BOOLEAN),

	/** {@link PlayerState#canWalkOnPowderSnow}: equipment-derived powder-snow collision capability. */
	CAN_WALK_ON_POWDER_SNOW(Kind.BOOLEAN),
	/** {@link PlayerState#sprintWindowTicks}, in ticks. */
	SPRINT_WINDOW_TICKS(Kind.INT),
	/** {@link PlayerState#autoJumpEnabled}. */
	AUTO_JUMP_ENABLED(Kind.BOOLEAN),
	/** {@link PlayerState#fastLava}. */
	FAST_LAVA(Kind.BOOLEAN),

	/**
	 * {@link PlayerState#movementSpeedMultiplier}: the effective movement-speed attribute
	 * multiplier from effects and equipment. Sprinting stays a separate field rather than part of
	 * this factor.
	 */
	MOVEMENT_SPEED_MULTIPLIER(Kind.DOUBLE),
	/** {@link PlayerState#jumpBoostPower}: the effective jump-boost contribution. */
	JUMP_BOOST_POWER(Kind.FLOAT),
	/** {@link PlayerState#sprintingAttribute}: whether the sprint speed modifier is installed. */
	SPRINTING_ATTRIBUTE(Kind.BOOLEAN),
	/** {@link PlayerState#foodLevel}. */
	FOOD_LEVEL(Kind.INT);

	/** The scalar encoding used to store one state field in a raw-bit trace. */
	public enum Kind {
		/** Raw IEEE 754 double bits, all 64. */
		DOUBLE,
		/** Raw IEEE 754 float bits in the low 32 bits. */
		FLOAT,
		/** Zero or one. */
		BOOLEAN,
		/** A signed 32-bit integer in the low 32 bits. */
		INT,
		/** Eight packed flag bits in the low byte. */
		BYTE_FLAGS,
		/** A stable numeric id with an optional diagnostic label. */
		ENUM
	}

	/** Every field in declaration order, which is trace column order. */
	public static final List<StateField> ALL = List.of(values());

	private final Kind kind;

	StateField(final Kind kind) {
		this.kind = kind;
	}

	/** The scalar encoding of this field. */
	public Kind kind() {
		return this.kind;
	}
}
