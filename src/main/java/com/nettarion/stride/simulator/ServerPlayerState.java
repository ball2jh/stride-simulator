package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.server.ServerEntity;
import com.nettarion.stride.simulator.server.ServerMovementListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The server's copy of the player: the movement half it inherits from {@link PlayerState}, the survival
 * facts only the server tracks ({@code LivingEntity}'s health, absorption, hit cooldown, fire, air and
 * tick count with {@code FoodData}), and the connection listener's facts between movement packets.
 *
 * <p>The admitted domain is a player with no armor, enchantment damage protection, status effects,
 * blocking item, altered damage attributes or invulnerable ability, on a non-peaceful server with
 * declared regeneration and damage rules, the default world border and no oxygen bonus. Absorption
 * above zero is admitted as the amount alone: a hit only lowers it; whatever granted it is not ticked.
 *
 * <p>Instances are mutable and belong to one thread. Direct assignment to a field with a setter
 * ({@link #setSprinting}, {@link #setSwimming}, {@link #setPose}, {@link #setTicksFrozen},
 * {@link #setSharedFlag}) bypasses the entity-data dirty tracking the tracker sample reads; use the
 * setter when the change should reach the client.
 */
public class ServerPlayerState extends PlayerState {
	/** Vanilla's default maximum health, in health points. */
	public static final float DEFAULT_MAXIMUM_HEALTH = 20.0F;

	/** Vanilla's default maximum air supply, in ticks. */
	public static final int DEFAULT_MAXIMUM_AIR = 300;

	/** Vanilla's {@code Entity.getTicksRequiredToFreeze} for a player, the cap on {@code ticksFrozen}. */
	public static final int DEFAULT_TICKS_REQUIRED_TO_FREEZE = 140;

	/** {@code Entity.getFireImmuneTicks} for a player: the dry steady fire state is its negation. */
	public static final int FIRE_IMMUNE_TICKS = 20;

	/** The entity tick count of a constructed boundary, which no capture supplies. */
	public static final long UNKNOWN_TICK_COUNT = Long.MIN_VALUE;

	/** Vanilla's {@code FoodData.MAX_EXHAUSTION}: the cap {@code addExhaustion} applies. */
	public static final float MAXIMUM_EXHAUSTION = 40.0F;

	/** Vanilla's {@code FoodData} regeneration period, in ticks; the timer never reaches it. */
	public static final int FOOD_TICK_PERIOD = 80;

	/** {@code Entity.addMovementThisTick} keeps at most this many movements, merging the oldest two beyond it. */
	public static final int MAXIMUM_MOVEMENTS_THIS_TICK = 100;

	/** {@code LivingEntity.hurtServer} refuses an equal or smaller hit while {@code invulnerableTime} exceeds this. */
	public static final int HIT_COOLDOWN_REFUSING_TICKS = 10;

	/** The grace {@code LivingEntity.setIgnoreFallDamageFromCurrentImpulse} grants, in ticks. */
	public static final int IMPULSE_CONTEXT_RESET_GRACE_TICKS = 40;

	/** Vanilla's floating tick cap, after which the listener kicks; here the transition refuses. */
	public static final int MAXIMUM_FLOATING_TICKS = 80;

	/** {@code ServerGamePacketListenerImpl.tickCount}, separate from the entity tick count. */
	public int connectionTickCount;

	/** {@code ServerGamePacketListenerImpl.receivedMovePacketCount}. */
	public int receivedMovePacketCount;

	/** {@code ServerGamePacketListenerImpl.knownMovePacketCount}. */
	public int knownMovePacketCount;

	/** {@code ServerGamePacketListenerImpl.awaitingTeleportTime}: the connection tick of the last correction. */
	public int awaitingTeleportTime;

	/** {@code ServerGamePacketListenerImpl.firstGoodX}: the movement-check anchor, in blocks. */
	public double firstGoodX;

	/** {@code ServerGamePacketListenerImpl.firstGoodY}, in blocks. */
	public double firstGoodY;

	/** {@code ServerGamePacketListenerImpl.firstGoodZ}, in blocks. */
	public double firstGoodZ;

	/** {@code ServerGamePacketListenerImpl.lastGoodX}: the last accepted position, in blocks. */
	public double lastGoodX;

	/** {@code ServerGamePacketListenerImpl.lastGoodY}, in blocks. */
	public double lastGoodY;

	/** {@code ServerGamePacketListenerImpl.lastGoodZ}, in blocks. */
	public double lastGoodZ;

	/** {@code ServerGamePacketListenerImpl.awaitingTeleport}: the id of the pending correction. */
	public int awaitingTeleport;

	/** {@code awaitingPositionFromClient.x}: the X the pending correction sent, in blocks. */
	public double awaitingPositionX;

	/** {@code awaitingPositionFromClient.y}: the feet height the pending correction sent, in blocks. */
	public double awaitingPositionY;

	/** {@code awaitingPositionFromClient.z}: the Z the pending correction sent, in blocks. */
	public double awaitingPositionZ;

	/** Whether {@code LivingEntity.currentImpulseImpactPos} is present; it caps fall damage. */
	public boolean currentImpulseImpactPosPresent;

	/** {@code currentImpulseImpactPos.x}, in blocks; meaningful only when present. */
	public double currentImpulseImpactPosX;

	/** {@code currentImpulseImpactPos.y}, in blocks; meaningful only when present. */
	public double currentImpulseImpactPosY;

	/** {@code currentImpulseImpactPos.z}, in blocks; meaningful only when present. */
	public double currentImpulseImpactPosZ;

	/** {@code Entity.movementThisTick}'s first entry is held in the scalar fields below. */
	public boolean movementAxisDependent = true;

	/** Only a batched schedule allocates tail entries; records are immutable across forks. */
	ArrayList<Movement> additionalMovements;

	/** Whether any movement was recorded this server tick. */
	public boolean movementThisTickPresent;

	/** X position at the start of the first movement retained in this server tick, in blocks. */
	public double movementFromX;

	/** Feet height at the start of the first movement retained in this server tick, in blocks. */
	public double movementFromY;

	/** Z position at the start of the first movement retained in this server tick, in blocks. */
	public double movementFromZ;

	/** X position after the first retained movement, in blocks. */
	public double movementToX;

	/** Feet height after the first retained movement, in blocks. */
	public double movementToY;

	/** Z position after the first retained movement, in blocks. */
	public double movementToZ;

	/** Requested X displacement before collision resolution for the first retained movement, in blocks. */
	public double movementRequestedX;

	/** Requested Z displacement before collision resolution for the first retained movement, in blocks. */
	public double movementRequestedZ;

	/** {@code SynchedEntityData} dirty bits ({@link EntityDataWrite#FLAGS} and siblings) pending the tracker sample. */
	public int entityDataDirty;

	/** {@code ServerPlayer.lastSentHealth}: the health of the last health packet, in health points. */
	public float lastSentHealth = 20.0F;

	/** {@code ServerPlayer.lastSentFood}: the food level of the last health packet. */
	public int lastSentFood = 20;

	/** {@code ServerPlayer.lastFoodSaturationZero}: whether saturation was zero at the last health packet. */
	public boolean lastFoodSaturationZero;

	/** Whether the transport schedule is known; an unknown one refuses at the first tracker sample. */
	public boolean publicationScheduleKnown = true;

	/** {@code Entity.baseTick}'s shared fire bit; block contact can change {@code remainingFireTicks} afterward. */
	public boolean sharedFlagOnFire;

	/** The shared flags the tracker last published; see {@link SharedFlag}. */
	public int lastSentSharedFlags;

	/** The pose the tracker last published. */
	public Pose lastSentPose = Pose.STANDING;

	/** The frozen tick count the tracker last published, in ticks. */
	public int lastSentTicksFrozen;

	/** The frost speed modifier the tracker last published, in ticks. */
	public int lastSentFrostSpeedTicks;

	/** {@code AttributeMap} dirty state after {@code removeFrost} or {@code tryAddFrost}. */
	public boolean movementSpeedAttributeDirty;

	/** The dedicated server's player movement check setting; the default is on. */
	public boolean playerMovementCheck = true;

	/** Whether the server applies the movement-speed check while gliding. */
	public boolean elytraMovementCheck = true;

	/** Whether movement validation grants the integrated server owner's exemption. */
	public boolean singleplayerOwner;

	/** {@code LivingEntity.currentImpulseContextResetGraceTime}, in ticks; nonzero is not admitted. */
	public int currentImpulseContextResetGraceTime;

	/** {@code FoodData.saturationLevel}; {@code setSaturation} and save loading do not cap it to the food level. */
	public float saturationLevel = 5.0F;

	/** {@code FoodData.exhaustionLevel}, at most {@link #MAXIMUM_EXHAUSTION}. */
	public float exhaustionLevel;

	/** {@code FoodData.tickTimer}: ticks toward the next regeneration or starvation; see {@link #FOOD_TICK_PERIOD}. */
	public int tickTimer;

	/** The non-peaceful server's {@code naturalHealthRegeneration} rule. */
	public boolean naturalRegeneration = true;

	/**
	 * The level's {@code fallDamage} rule, which {@code Player.isInvulnerableTo} reads by damage type tag
	 * before any hit lands: fall and stalagmite hits. A disabled rule drops the hit without touching the
	 * cooldown. Vanilla's default is {@code true}.
	 */
	public boolean fallDamage = true;

	/** The level's {@code fireDamage} rule: in-fire, campfire, on-fire, lava and hot-floor hits. */
	public boolean fireDamage = true;

	/** The level's {@code drowningDamage} rule. */
	public boolean drowningDamage = true;

	/** The level's {@code freezeDamage} rule. */
	public boolean freezeDamage = true;

	/** Non-peaceful level difficulty governing starvation thresholds. */
	public Difficulty difficulty = Difficulty.NORMAL;

	/** {@code LivingEntity.health}, in health points; see {@link #dead()}. */
	public float health = DEFAULT_MAXIMUM_HEALTH;

	/** Declared maximum health, in health points. */
	public float maximumHealth = DEFAULT_MAXIMUM_HEALTH;

	/** Remaining absorption, in health points, consumed before ordinary health. */
	public float absorption;

	/** {@code LivingEntity.invulnerableTime}: twenty after a full hit, decremented every level tick. */
	public int invulnerableTime;

	/** {@code LivingEntity.lastHurt}: the amount of the hit that owns the cooldown, in health points. */
	public float lastHurt;

	/** {@code Entity.remainingFireTicks}: positive while burning, negative through the immune window. */
	public int remainingFireTicks = -FIRE_IMMUNE_TICKS;

	/** {@code LivingEntity.airSupply}, in ticks. */
	public int airSupply = DEFAULT_MAXIMUM_AIR;

	/** {@code Entity.tickCount} at this boundary, or {@link #UNKNOWN_TICK_COUNT}. */
	public long tickCount = UNKNOWN_TICK_COUNT;

	/** {@code clientIsFloating}: the last accepted packet looked airborne with no blocks around. */
	public boolean clientIsFloating;

	/**
	 * Whether the last accepted packet could not decide the floating latch because its air query
	 * reached space the capture does not declare. The latch is then held false and
	 * {@link #aboveGroundTickCount} counts an upper bound, so the kick is refused as undetermined.
	 */
	public boolean floatingUnknown;

	/** {@code aboveGroundTickCount}: connection ticks spent floating; {@link #MAXIMUM_FLOATING_TICKS} kicks. */
	public int aboveGroundTickCount;

	/** {@code ServerPlayer.lastKnownClientMovement.x}: the last accepted packet's displacement, in blocks. */
	public double lastKnownClientMovementX;

	/** {@code ServerPlayer.lastKnownClientMovement.y}, in blocks. */
	public double lastKnownClientMovementY;

	/** {@code ServerPlayer.lastKnownClientMovement.z}, in blocks. */
	public double lastKnownClientMovementZ;

	/** Whether a correction was sent and not yet acknowledged, so movement packets only apply rotation. */
	public boolean correctionPending;

	/**
	 * {@code MinecraftServer.allowFlight}: a server setting, not an observable player fact. The dedicated
	 * default is {@code false}; it only suppresses the floating latch.
	 */
	public boolean allowFlight;

	/**
	 * {@code Entity.isShiftKeyDown} on the server's copy: the shift bit of the last input packet, which
	 * the client sends before the movement packet of the same tick. Assigning it directly bypasses dirty
	 * tracking; see {@link #setSharedFlag}.
	 */
	public boolean shiftKeyDown;

	/**
	 * Firework rockets the client sees attached to the player. Each replaces a gliding player's velocity
	 * on every actor tick for a server-private lifetime; the server tick refuses the first action at
	 * which one would write.
	 */
	public int attachedRockets;

	/** A server player with fresh survival and listener facts; prefer {@link #atBoundary}. */
	public ServerPlayerState() {}

	/**
	 * The server's copy at a boundary holding {@code movement}: the same position, rotation and
	 * contacts with zero controls, full health, the dry steady fire state, full air and default
	 * {@code FoodData} (20 food, 5 saturation, zero exhaustion and timer). Nothing is floating or
	 * pending and the entity tick count is unknown, so a hit whose timing depends on it refuses.
	 * Only the movement half of {@code movement} is read, even when it is itself a server copy.
	 */
	public static ServerPlayerState atBoundary(final PlayerState movement) {
		ServerPlayerState state = new ServerPlayerState();
		movement.copyMovementInto(state);
		ServerMovementListener.resetPosition(state);
		state.lastSentHealth = state.health;
		state.lastSentFood = state.foodLevel;
		state.lastFoodSaturationZero = state.saturationLevel == 0.0F;
		state.lastSentSharedFlags = ServerEntity.sharedFlags(state);
		state.lastSentPose = state.pose;
		state.lastSentTicksFrozen = state.ticksFrozen;
		state.lastSentFrostSpeedTicks = state.frostSpeedTicks;
		// ServerPlayer has no LocalPlayer keyboard or crouching field. Its
		// LivingEntity input starts at zero, independently of the client.
		state.xxa = 0.0F;
		state.yya = 0.0F;
		state.zza = 0.0F;
		state.jumping = false;
		state.crouching = false;
		state.inputKeyPresses = 0;
		state.inputMoveVectorX = 0.0F;
		state.inputMoveVectorY = 0.0F;
		state.sprintTriggerTime = 0;
		state.jumpTriggerTime = 0;
		state.noJumpDelay = 0;
		state.autoJumpTime = 0;
		return state;
	}

	/**
	 * The server's copy as a client can derive it at a boundary, so a consumer on a live connection can
	 * start the simulator's prediction from what it can see.
	 *
	 * <p>Movement is client-authoritative, so the server's position, rotation and contact flags are the
	 * publisher's last published values and the listener's anchors start there. The survival facts are
	 * {@code observed}'s, taken as sent. What no client can see (hunger phase, connection and entity
	 * tick counters, movement records) keeps the constructed boundary's values. The result is validated
	 * as any server state is.
	 *
	 * @throws IllegalStateException when the observed facts are outside the admitted domain
	 */
	public static ServerPlayerState derivedFrom(
	    final PlayerState movement, final Publisher publisher, final Observed observed) {
		Objects.requireNonNull(publisher, "publisher");
		Objects.requireNonNull(observed, "observed");
		ServerPlayerState server = atBoundary(movement);
		server.placeAt(publisher.xLast, publisher.yLast, publisher.zLast);
		server.yRot = publisher.yRotLast;
		server.xRot = publisher.xRotLast;
		server.onGround = publisher.lastOnGround;
		server.horizontalCollision = publisher.lastHorizontalCollision;
		server.firstGoodX = server.lastGoodX = server.x;
		server.firstGoodY = server.lastGoodY = server.y;
		server.firstGoodZ = server.lastGoodZ = server.z;
		// Entity.movementThisTick is cleared when a tick's block effects run.
		server.movementThisTickPresent = false;
		server.restoreAdditionalMovements(List.of());
		server.health = observed.health();
		server.maximumHealth = observed.maximumHealth();
		server.absorption = observed.absorption();
		server.airSupply = observed.airSupply();
		server.foodLevel = observed.foodLevel();
		server.saturationLevel = observed.saturationLevel();
		server.lastSentHealth = server.health;
		server.lastSentFood = server.foodLevel;
		server.lastFoodSaturationZero = server.saturationLevel == 0.0F;
		server.invulnerableTime = observed.invulnerableTime();
		server.shiftKeyDown = observed.shiftKeyDown();
		server.attachedRockets = observed.attachedRockets();
		server.awaitingTeleport = observed.awaitingTeleport();
		// A ServerPlayer has no client options; the integrated server allows
		// flight and exempts its owner from the movement checks.
		server.sprintWindowTicks = 0;
		server.singleplayerOwner = observed.integratedOwner();
		server.allowFlight = observed.integratedOwner();
		server.difficulty = observed.difficulty();
		server.lastSentSharedFlags = ServerEntity.sharedFlags(server);
		server.lastSentPose = server.pose;
		server.lastSentTicksFrozen = server.ticksFrozen;
		server.lastSentFrostSpeedTicks = server.frostSpeedTicks;
		server.requireValid();
		return server;
	}

	/** Raw-bit equality of every field, movement included. */
	public static boolean rawEquals(final ServerPlayerState a, final ServerPlayerState b) {
		return PlayerState.rawEquals(a, b) && StateFields.rawEqualsOwn(a, b);
	}

	/** Whether the retained movement tails agree, treating empty storage alike. */
	static boolean sameMovements(final ServerPlayerState a, final ServerPlayerState b) {
		int n = a.additionalMovements == null ? 0 : a.additionalMovements.size();
		int m = b.additionalMovements == null ? 0 : b.additionalMovements.size();
		return n == m && (n == 0 || a.additionalMovements.equals(b.additionalMovements));
	}

	/** {@code LivingEntity.setIgnoreFallDamageFromCurrentImpulse} with a declared impact position, in blocks. */
	public void setIgnoreFallDamageFromCurrentImpulse(
	    final boolean ignore, final double x, final double y, final double z) {
		if (ignore) {
			if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
				throw new IllegalArgumentException("non-finite impulse impact position");
			}
			this.currentImpulseContextResetGraceTime =
			    Math.max(this.currentImpulseContextResetGraceTime, IMPULSE_CONTEXT_RESET_GRACE_TICKS);
			this.currentImpulseImpactPosPresent = true;
			this.currentImpulseImpactPosX = x;
			this.currentImpulseImpactPosY = y;
			this.currentImpulseImpactPosZ = z;
		} else {
			this.currentImpulseContextResetGraceTime = 0;
		}
	}

	/** {@code LivingEntity.tryResetCurrentImpulseContext}: preserves the context while grace remains. */
	public void tryResetCurrentImpulseContext() {
		if (this.currentImpulseContextResetGraceTime == 0) {
			resetCurrentImpulseContext();
		}
	}

	/** {@code LivingEntity.resetCurrentImpulseContext}: clears fall protection and its grace timer. */
	public void resetCurrentImpulseContext() {
		this.currentImpulseContextResetGraceTime = 0;
		this.currentImpulseImpactPosPresent = false;
		this.currentImpulseImpactPosX = this.currentImpulseImpactPosY = this.currentImpulseImpactPosZ = 0;
	}

	/** Retained movements this tick beyond the first, counted without copying. */
	public int additionalMovementCount() {
		return this.additionalMovements == null ? 0 : this.additionalMovements.size();
	}

	/** One retained movement beyond the first, by index, read without copying. */
	public Movement additionalMovement(final int index) {
		return this.additionalMovements.get(index);
	}

	/** An immutable copy of the retained movements beyond the first. */
	public List<Movement> additionalMovements() {
		return this.additionalMovements == null ? List.of() : List.copyOf(this.additionalMovements);
	}

	/**
	 * Restores a captured movement tail.
	 *
	 * @throws IllegalArgumentException when the tail exceeds vanilla's cap
	 */
	public void restoreAdditionalMovements(final List<Movement> movements) {
		if (movements.size() > MAXIMUM_MOVEMENTS_THIS_TICK - 1) {
			throw new IllegalArgumentException("movement tail exceeds vanilla's cap");
		}
		this.additionalMovements = movements.isEmpty() ? null : new ArrayList<>(movements);
	}

	/**
	 * Records one movement segment, vanilla {@code Entity.addMovementThisTick}, retaining the bounded
	 * history the server replays for block contact. Coordinates are in blocks.
	 */
	public void addMovementThisTick(final double fromX, final double fromY, final double fromZ, final double x,
	    final double y, final double z, final double requestedX, final double requestedZ) {
		if (!this.movementThisTickPresent) {
			this.movementThisTickPresent = true;
			this.movementAxisDependent = true;
			this.movementFromX = fromX;
			this.movementFromY = fromY;
			this.movementFromZ = fromZ;
			this.movementToX = x;
			this.movementToY = y;
			this.movementToZ = z;
			this.movementRequestedX = requestedX;
			this.movementRequestedZ = requestedZ;
		} else {
			if (this.additionalMovements == null) {
				this.additionalMovements = new ArrayList<>();
			}
			if (this.additionalMovements.size() >= MAXIMUM_MOVEMENTS_THIS_TICK - 1) {
				Movement second = this.additionalMovements.removeFirst();
				this.movementToX = second.toX();
				this.movementToY = second.toY();
				this.movementToZ = second.toZ();
				this.movementAxisDependent = false;
			}
			this.additionalMovements.add(new Movement(fromX, fromY, fromZ, x, y, z, requestedX, requestedZ));
		}
	}

	/** Removes the most recently recorded segment, clearing the scalar slot when no tail remains. */
	public void removeLatestMovementRecording() {
		if (this.additionalMovements != null && !this.additionalMovements.isEmpty()) {
			this.additionalMovements.removeLast();
		} else {
			clearMovementThisTick();
		}
	}

	/** Clears every retained movement segment and canonicalizes the empty scalar representation. */
	public void clearMovementThisTick() {
		// Empty vanilla movement storage has no segment values. Canonicalize
		// the flattened representation so observation, equality and hashing
		// agree.
		this.movementAxisDependent = true;
		this.movementFromX = this.movementFromY = this.movementFromZ = 0.0;
		this.movementToX = this.movementToY = this.movementToZ = 0.0;
		this.movementRequestedX = this.movementRequestedZ = 0.0;
		this.movementThisTickPresent = false;
		if (this.additionalMovements != null) {
			this.additionalMovements.clear();
		}
	}

	/** Sets the swimming flag and marks the entity data dirty when it changed. */
	@Override
	public void setSwimming(final boolean value) {
		if (this.swimming != value) {
			this.entityDataDirty |= EntityDataWrite.FLAGS;
		}
		super.setSwimming(value);
	}

	/** Sets the sprint flag and marks the entity data and speed attribute dirty as vanilla does. */
	@Override
	public void setSprinting(final boolean value) {
		if (this.sprinting != value) {
			this.entityDataDirty |= EntityDataWrite.FLAGS;
		}
		this.movementSpeedAttributeDirty |= this.sprintingAttribute || value;
		super.setSprinting(value);
	}

	/** Sets the pose and marks the entity data dirty when it changed. */
	@Override
	public void setPose(final Pose value) {
		if (this.pose != value) {
			this.entityDataDirty |= EntityDataWrite.POSE;
		}
		super.setPose(value);
	}

	/** Sets the frozen tick count and marks the entity data dirty when it changed. */
	@Override
	public void setTicksFrozen(final int value) {
		if (this.ticksFrozen != value) {
			this.entityDataDirty |= EntityDataWrite.FROZEN;
		}
		super.setTicksFrozen(value);
	}

	/** Sets a shared flag, including the server-only fire and shift bits, and marks the byte dirty when it changed. */
	@Override
	public void setSharedFlag(final int flag, final boolean value) {
		int before = ServerEntity.sharedFlags(this);
		switch (flag) {
			case SharedFlag.ON_FIRE -> this.sharedFlagOnFire = value;
			case SharedFlag.SHIFT_KEY_DOWN -> this.shiftKeyDown = value;
			default -> super.setSharedFlag(flag, value);
		}
		if (before != ServerEntity.sharedFlags(this)) {
			this.entityDataDirty |= EntityDataWrite.FLAGS;
		}
	}

	/** An independent copy of every field, movement included. */
	@Override
	public ServerPlayerState copy() {
		ServerPlayerState copy = new ServerPlayerState();
		copyInto(copy);
		return copy;
	}

	/**
	 * Copies the movement half into {@code copy}, and the server half as well when {@code copy} is a
	 * {@link ServerPlayerState}. A plain {@code PlayerState} target receives movement only.
	 */
	@Override
	public void copyInto(final PlayerState copy) {
		if (copy == this) {
			return;
		}
		super.copyInto(copy);
		if (copy instanceof ServerPlayerState server) {
			StateFields.copy(server, this);
		}
	}

	/**
	 * Takes from {@code observed}, a copy derived from what a client can read, the facts the server
	 * publishes to the client: health, maximum health, absorption, air, food level, the hit cooldown the
	 * client sets on a damage event, the fire flag, the shift key, attached rockets and the pending
	 * teleport id. Saturation travels only inside a health packet, so it is taken only when
	 * {@code healthPublication} says one just arrived. Everything a client cannot see keeps this copy's
	 * values.
	 *
	 * @return how many fields changed
	 */
	public int reanchorFrom(final ServerPlayerState observed, final boolean healthPublication) {
		int changed = 0;
		if (this.health != observed.health) {
			this.health = observed.health;
			changed++;
		}
		if (this.maximumHealth != observed.maximumHealth) {
			this.maximumHealth = observed.maximumHealth;
			changed++;
		}
		if (this.absorption != observed.absorption) {
			this.absorption = observed.absorption;
			changed++;
		}
		if (this.airSupply != observed.airSupply) {
			this.airSupply = observed.airSupply;
			changed++;
		}
		if (this.foodLevel != observed.foodLevel) {
			this.foodLevel = observed.foodLevel;
			changed++;
		}
		if (healthPublication && this.saturationLevel != observed.saturationLevel) {
			this.saturationLevel = observed.saturationLevel;
			changed++;
		}
		if (this.invulnerableTime != observed.invulnerableTime) {
			this.invulnerableTime = observed.invulnerableTime;
			changed++;
		}
		if (this.sharedFlagOnFire != observed.sharedFlagOnFire) {
			this.sharedFlagOnFire = observed.sharedFlagOnFire;
			changed++;
		}
		if (this.shiftKeyDown != observed.shiftKeyDown) {
			this.shiftKeyDown = observed.shiftKeyDown;
			changed++;
		}
		if (this.attachedRockets != observed.attachedRockets) {
			this.attachedRockets = observed.attachedRockets;
			changed++;
		}
		if (this.awaitingTeleport != observed.awaitingTeleport) {
			this.awaitingTeleport = observed.awaitingTeleport;
			changed++;
		}
		return changed;
	}

	/** Whether health has reached zero, at which the server's tick stops the player. */
	public boolean dead() {
		return this.health <= 0.0F;
	}

	/** Whether the hit cooldown still refuses an equal or smaller hit. */
	public boolean inHitCooldown() {
		return this.invulnerableTime > HIT_COOLDOWN_REFUSING_TICKS;
	}

	/**
	 * Rejects out-of-range survival facts, non-finite known movement, or a negative grace, floating or
	 * rocket count. The movement half is validated by {@link #requireValidForTransition()}.
	 *
	 * @throws IllegalStateException when a field is outside the admitted domain
	 */
	public void requireValid() {
		if (this.currentImpulseContextResetGraceTime < 0) {
			throw new IllegalStateException("negative post-impulse movement grace");
		}
		if (this.difficulty == null) {
			throw new IllegalStateException("unknown level difficulty");
		}
		if (this.foodLevel < 0 || this.foodLevel > 20 || !Float.isFinite(this.saturationLevel)
		    || this.saturationLevel < 0.0F || !Float.isFinite(this.exhaustionLevel) || this.exhaustionLevel < 0.0F
		    || this.exhaustionLevel > MAXIMUM_EXHAUSTION || this.tickTimer < 0 || this.tickTimer >= FOOD_TICK_PERIOD) {
			throw new IllegalStateException("food values are out of range");
		}
		if (!Float.isFinite(this.health) || !Float.isFinite(this.maximumHealth) || !Float.isFinite(this.absorption)
		    || !Float.isFinite(this.lastHurt)) {
			throw new IllegalStateException("survival values must be finite");
		}
		if (this.maximumHealth <= 0.0F || this.health < 0.0F || this.health > this.maximumHealth
		    || this.absorption < 0.0F || this.invulnerableTime < 0 || this.lastHurt < 0.0F
		    || this.tickCount != UNKNOWN_TICK_COUNT
		        && (this.tickCount < Integer.MIN_VALUE || this.tickCount > Integer.MAX_VALUE)) {
			throw new IllegalStateException("survival values are out of range");
		}
		if (!Double.isFinite(this.lastKnownClientMovementX) || !Double.isFinite(this.lastKnownClientMovementY)
		    || !Double.isFinite(this.lastKnownClientMovementZ)) {
			throw new IllegalStateException("known movement must be finite");
		}
		if (this.aboveGroundTickCount < 0) {
			throw new IllegalStateException("floating tick count is out of range");
		}
		if (this.attachedRockets < 0) {
			throw new IllegalStateException("attached rocket count is out of range");
		}
	}

	/** The admitted non-peaceful vanilla level difficulties. */
	public enum Difficulty {
		/** Easy: starvation stops at 10 health. */
		EASY,
		/** Normal: starvation stops at 1 health. */
		NORMAL,
		/** Hard: starvation can kill. */
		HARD
	}

	/**
	 * One {@code Entity.Movement} with scalar {@code Vec3} components, in blocks.
	 *
	 * @param fromX the X position before the movement
	 * @param fromY the feet height before the movement
	 * @param fromZ the Z position before the movement
	 * @param toX the X position after the movement
	 * @param toY the feet height after the movement
	 * @param toZ the Z position after the movement
	 * @param requestedX the requested X displacement before collision resolution
	 * @param requestedZ the requested Z displacement before collision resolution
	 */
	public record Movement(double fromX, double fromY, double fromZ, double toX, double toY, double toZ,
	    double requestedX, double requestedZ) {}

	/**
	 * The facts a client can read about the server's copy of its player, as {@link #derivedFrom} admits
	 * them: what the server last published (health, maximum health, absorption, air, food, saturation),
	 * the hit cooldown the client set on the damage event, the shift key, the rockets the client sees
	 * attached, the pending teleport id, the level's difficulty, and whether the server is the client's
	 * own integrated one, which allows flight and exempts its owner from the movement checks.
	 *
	 * @param health the published health, in health points
	 * @param maximumHealth the published maximum health, in health points
	 * @param absorption the published absorption, in health points
	 * @param airSupply the published air supply, in ticks
	 * @param foodLevel the published food level
	 * @param saturationLevel the published saturation
	 * @param invulnerableTime the hit cooldown the client set, in ticks
	 * @param shiftKeyDown the shift key of the last input packet
	 * @param attachedRockets the rockets attached to the player
	 * @param awaitingTeleport the pending teleport id
	 * @param difficulty the level's difficulty
	 * @param integratedOwner whether the server is the client's own integrated one
	 */
	public record Observed(float health, float maximumHealth, float absorption, int airSupply, int foodLevel,
	    float saturationLevel, int invulnerableTime, boolean shiftKeyDown, int attachedRockets, int awaitingTeleport,
	    Difficulty difficulty, boolean integratedOwner) {
		/** Rejects a null difficulty. */
		public Observed {
			Objects.requireNonNull(difficulty, "difficulty");
		}
	}
}
