package com.nettarion.stride.simulator.trace;

/**
 * A field of the audited player state vector.
 *
 * <p>The declaration order here is the order fields appear in a trace and the
 * order divergences are reported in. Appending a field requires a matching
 * trace format change.
 *
 * <p>Every field is stored as raw bits in a {@code long} regardless of its
 * source type, so the equality relation is plain
 * {@code long} comparison. No floating-point comparison happens anywhere,
 * which is what keeps {@code -0.0} distinct from {@code 0.0} and preserves NaN
 * payloads — both reachable in vanilla collision code and both invisible to
 * {@code ==}.
 */
public enum StateField {
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#x}. */
	POS_X(Kind.DOUBLE),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#y}. */
	POS_Y(Kind.DOUBLE),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#z}. */
	POS_Z(Kind.DOUBLE),

	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#deltaMovementX}. */
	DELTA_X(Kind.DOUBLE),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#deltaMovementY}. */
	DELTA_Y(Kind.DOUBLE),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#deltaMovementZ}. */
	DELTA_Z(Kind.DOUBLE),

	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#yRot}. */
	Y_ROT(Kind.FLOAT),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#xRot}. */
	X_ROT(Kind.FLOAT),

	// Entity.bb is mutated directly by move()/setPos(), so it is carried rather
	// than derived from position and dimensions.
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#boundingBoxMinX}. */
	BB_MIN_X(Kind.DOUBLE),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#boundingBoxMinY}. */
	BB_MIN_Y(Kind.DOUBLE),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#boundingBoxMinZ}. */
	BB_MIN_Z(Kind.DOUBLE),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#boundingBoxMaxX}. */
	BB_MAX_X(Kind.DOUBLE),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#boundingBoxMaxY}. */
	BB_MAX_Y(Kind.DOUBLE),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#boundingBoxMaxZ}. */
	BB_MAX_Z(Kind.DOUBLE),

	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#onGround}. */
	ON_GROUND(Kind.BOOLEAN),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#horizontalCollision}. */
	HORIZONTAL_COLLISION(Kind.BOOLEAN),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#verticalCollision}. */
	VERTICAL_COLLISION(Kind.BOOLEAN),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#verticalCollisionBelow}. */
	VERTICAL_COLLISION_BELOW(Kind.BOOLEAN),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#minorHorizontalCollision}. */
	MINOR_HORIZONTAL_COLLISION(Kind.BOOLEAN),

	// double in 26.2, float in older versions. Read from Entity.fallDistance.
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#fallDistance}. */
	FALL_DISTANCE(Kind.DOUBLE),

	// Entity.entityData shared flags, split by who computes each bit rather
	// than carried as one byte. The bits have different owners, and auditing
	// the storage location instead of the facts meant one server-owned bit
	// forced the whole byte out of the equality relation, taking client-computed
	// sprint state with it. The protocol therefore names each semantic bit.
	//
	// Bit numbers are from the Entity.setSharedFlag call sites.
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#sprinting}. */
	SPRINTING(Kind.BOOLEAN), // bit 3, LocalPlayer.aiStep
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#swimming}. */
	SWIMMING(Kind.BOOLEAN), // bit 4, Entity.updateSwimming
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#fallFlying}. */
	FALL_FLYING(Kind.BOOLEAN), // bit 7, LivingEntity / Player

	// Bit 1. Set only by ServerGamePacketListenerImpl and echoed back through
	// entity-data sync, so it can never be reproduced by a implementation without a
	// server. Inert for client movement: LocalPlayer overrides isShiftKeyDown()
	// to read input.keyPresses.shift() directly and never consults this bit.
	// It exists so other players' clients can render the crouch.
	/** The server-owned shared sneak flag, preserved separately from local sneak input. */
	SHIFT_KEY_DOWN(Kind.BOOLEAN),

	// Every bit not named above (0 on-fire, 2, 5 invisible, 6 glowing), masked.
	// Carried so a bit nobody has classified yet still diverges loudly instead
	// of becoming invisible the moment the byte was split up.
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#sharedFlagsResidual}. */
	SHARED_FLAGS_RESIDUAL(Kind.BYTE_FLAGS),

	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#pose}. */
	POSE(Kind.ENUM),

	// LivingEntity movement input, written by applyInput/modifyInput before
	// travel reads it.
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#xxa}. */
	XXA(Kind.FLOAT),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#yya}. */
	YYA(Kind.FLOAT),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#zza}. */
	ZZA(Kind.FLOAT),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#jumping}. */
	JUMPING(Kind.BOOLEAN),

	// Tick-based trigger state machines. These are the reason a transition is
	// not a pure function of position and velocity.
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#sprintTriggerTime}. */
	SPRINT_TRIGGER_TIME(Kind.INT),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#jumpTriggerTime}. */
	JUMP_TRIGGER_TIME(Kind.INT),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#autoJumpTime}. */
	AUTO_JUMP_TIME(Kind.INT),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#noJumpDelay}. */
	NO_JUMP_DELAY(Kind.INT),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#crouching}. */
	CROUCHING(Kind.BOOLEAN),

	// ClientInput is sampled at the start of LocalPlayer.aiStep, before
	// KeyboardInput.tick overwrites it with this tick's action. Without these
	// fields an arbitrary mid-trace state cannot be forked exactly.
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#inputKeyPresses}. */
	INPUT_KEY_PRESSES(Kind.BYTE_FLAGS),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#inputMoveVectorX}. */
	INPUT_MOVE_VECTOR_X(Kind.FLOAT),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#inputMoveVectorY}. */
	INPUT_MOVE_VECTOR_Y(Kind.FLOAT),

	// EntityFluidInteraction tracker state at the end of the tick. The next
	// baseTick samples EYE_IN_WATER before rebuilding the tracker, so this is
	// semantic fork state rather than a world-derived observation that can be
	// discarded. Heights are also audited explicitly at the implementation boundary.
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#waterHeight}. */
	WATER_HEIGHT(Kind.DOUBLE),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#lavaHeight}. */
	LAVA_HEIGHT(Kind.DOUBLE),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#eyeInWater}. */
	EYE_IN_WATER(Kind.BOOLEAN),

	// Player.Abilities movement state. `flying` changes travel, collision-side
	// behavior and pose selection; `mayfly` owns the double-tap state machine;
	// the speed is mutable and therefore cannot be assumed to be its default.
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#mayfly}. */
	MAY_FLY(Kind.BOOLEAN),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#flying}. */
	FLYING(Kind.BOOLEAN),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#flyingSpeed}. */
	FLYING_SPEED(Kind.FLOAT),

	// Fall-flying has a client tick counter and its local start transition reads
	// whether any equipped item is currently a usable glider. Carrying the
	// capability keeps custom gliders possible without importing inventory into
	// the movement contract.
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#fallFlyTicks}. */
	FALL_FLY_TICKS(Kind.INT),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#gliderUsable}. */
	GLIDER_USABLE(Kind.BOOLEAN),

	// Powder-snow contact is a delayed movement effect: entityInside installs
	// the stuck multiplier after travel and the next Entity.move consumes it.
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#stuckSpeedMultiplierX}. */
	STUCK_SPEED_X(Kind.DOUBLE),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#stuckSpeedMultiplierY}. */
	STUCK_SPEED_Y(Kind.DOUBLE),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#stuckSpeedMultiplierZ}. */
	STUCK_SPEED_Z(Kind.DOUBLE),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#isInPowderSnow}. */
	IN_POWDER_SNOW(Kind.BOOLEAN),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#wasInPowderSnow}. */
	WAS_IN_POWDER_SNOW(Kind.BOOLEAN),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#ticksFrozen}. */
	TICKS_FROZEN(Kind.INT),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#canFreeze}. */
	CAN_FREEZE(Kind.BOOLEAN),
	// Server-installed, synchronized attribute state; it can lag TICKS_FROZEN.
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#frostSpeedTicks}. */
	FROST_SPEED_TICKS(Kind.INT),

	// Entity.checkSupportingBlock retains these across ticks. Jump and ground
	// friction read the previous tick's identity before the next move.
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#mainSupportingBlockPosPresent}. */
	SUPPORTING_BLOCK_PRESENT(Kind.BOOLEAN),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#mainSupportingBlockPosX}. */
	SUPPORTING_BLOCK_X(Kind.INT),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#mainSupportingBlockPosY}. */
	SUPPORTING_BLOCK_Y(Kind.INT),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#mainSupportingBlockPosZ}. */
	SUPPORTING_BLOCK_Z(Kind.INT),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#onGroundNoBlocks}. */
	ON_GROUND_NO_BLOCKS(Kind.BOOLEAN),

	// Equipment-derived powder-snow collision and post-collision lift capability.
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#canWalkOnPowderSnow}. */
	CAN_WALK_ON_POWDER_SNOW(Kind.BOOLEAN),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#sprintWindowTicks}. */
	SPRINT_WINDOW_TICKS(Kind.INT),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#autoJumpEnabled}. */
	AUTO_JUMP_ENABLED(Kind.BOOLEAN),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#fastLava}. */
	FAST_LAVA(Kind.BOOLEAN),

	// Effective movement contributions of the three MCPK parkour effects.
	// Sprint remains kernel-owned state rather than part of the external factor.
	MOVEMENT_SPEED_MULTIPLIER(Kind.DOUBLE),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#jumpBoostPower}. */
	JUMP_BOOST_POWER(Kind.FLOAT),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#sprintingAttribute}. */
	SPRINTING_ATTRIBUTE(Kind.BOOLEAN),
	/** Raw trace encoding of {@link com.nettarion.stride.simulator.PlayerState#foodLevel}. */
	FOOD_LEVEL(Kind.INT);

	/** The scalar encoding used to store one state field in a raw-bit trace. */
	public enum Kind {
		/** Raw IEEE 754 double bits. */
		DOUBLE,
		/** Raw IEEE 754 float bits in the low 32 bits. */
		FLOAT,
		/** Zero or one for a boolean value. */
		BOOLEAN,
		/** A signed integer scalar. */
		INT,
		/** Eight packed flag bits. */
		BYTE_FLAGS,
		/** A stable ordinal with an optional diagnostic label. */
		ENUM
	}

	private final Kind kind;

	StateField(final Kind kind) {
		this.kind = kind;
	}

	public Kind kind() {
		return this.kind;
	}

	public static final StateField[] ALL = values();
}
