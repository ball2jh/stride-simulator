package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.server.ServerEntity;
import com.nettarion.stride.simulator.server.ServerMovementListener;
import java.util.ArrayList;

/**
 * The server's copy of the player: its movement vector, the survival facts
 * only the server tracks, and the listener's retained facts between movement
 * packets.
 *
 * <p>The movement half is the {@link PlayerState} this class extends, ticked
 * by the same kernel as the client's copy. The survival half is
 * {@code LivingEntity}'s health, absorption, hit cooldown, fire, air, and
 * the entity tick count; {@link com.nettarion.stride.simulator.server.Survival} deals every hit on it. The
 * listener half is vanilla {@code ServerGamePacketListenerImpl}'s floating latch
 * and counter, {@code ServerPlayer.lastKnownClientMovement}, the pending
 * correction acknowledgement, the server's flight setting, the shift key
 * the last input packet installed, and the external actors the client last
 * saw attached to the player.
 *
 * <p>The current exact slice is a player with no armor, enchantment damage
 * protection, status effects, blocking item, altered damage attributes, or
 * invulnerable ability, on a non-peaceful server, with declared natural
 * regeneration and damage gamerules, the default world border, and no oxygen bonus. Those omitted facts are conditions of this type, not
 * values inferred from an incomplete capture. An absorption above zero is
 * admitted as the amount alone: vanilla keeps it at or under the
 * max-absorption attribute, so a hit only lowers it and its clamp is at zero;
 * whatever granted it is neither ticked nor expired here.
 *
 * <p>{@link #atBoundary} is the constructed packet boundary: the server holds
 * the supplied movement with independent zero controls, at full health with
 * the dry steady fire
 * state, full air, and default FoodData (20 food, 5 saturation, zero
 * exhaustion and timer). Server controls are zero, nothing is floating or
 * pending, and the entity tick count is unknown, so a hit whose timing depends on it refuses. Mutable and
 * copied into retained storage like {@link PlayerState}, so an arena holds
 * it in the same shape.
 */
public class ServerPlayerState extends PlayerState {
	/** A server player with fresh survival and listener facts; prefer {@link #atBoundary}. */
	public ServerPlayerState() {}

	/** Retained vanilla {@code ServerGamePacketListenerImpl} counters (separate from entity tickCount). */
	public int connectionTickCount, receivedMovePacketCount, knownMovePacketCount, awaitingTeleportTime;
	/** Listener movement-validation anchors. */
	public double firstGoodX, firstGoodY, firstGoodZ, lastGoodX, lastGoodY, lastGoodZ;
	/** Listener teleport identifier and the absolute position awaiting acknowledgement. */
	public int awaitingTeleport;
	public double awaitingPositionX, awaitingPositionY, awaitingPositionZ;
	/** LivingEntity's optional currentImpulseImpactPos, used to cap fall damage. */
	public boolean currentImpulseImpactPosPresent;
	public double currentImpulseImpactPosX, currentImpulseImpactPosY, currentImpulseImpactPosZ;

	/** LivingEntity.setIgnoreFallDamageFromCurrentImpulse with a declared impact position. */
	public void setIgnoreFallDamageFromCurrentImpulse(
	    final boolean ignore, final double x, final double y, final double z) {
		if (ignore) {
			if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
				throw new IllegalArgumentException("non-finite impulse impact position");
			this.currentImpulseContextResetGraceTime = Math.max(this.currentImpulseContextResetGraceTime, 40);
			this.currentImpulseImpactPosPresent = true;
			this.currentImpulseImpactPosX = x;
			this.currentImpulseImpactPosY = y;
			this.currentImpulseImpactPosZ = z;
		} else {
			this.currentImpulseContextResetGraceTime = 0;
		}
	}

	/** LivingEntity.tryResetCurrentImpulseContext preserves a context while grace remains. */
	public void tryResetCurrentImpulseContext() {
		if (this.currentImpulseContextResetGraceTime == 0) resetCurrentImpulseContext();
	}

	/** LivingEntity.resetCurrentImpulseContext clears fall protection and its grace timer. */
	public void resetCurrentImpulseContext() {
		this.currentImpulseContextResetGraceTime = 0;
		this.currentImpulseImpactPosPresent = false;
		this.currentImpulseImpactPosX = this.currentImpulseImpactPosY = this.currentImpulseImpactPosZ = 0;
	}

	/** Entity.movementThisTick: the first movement stays in scalar storage. */
	public boolean movementAxisDependent = true;
	// Only a batched schedule allocates tail entries; records are immutable across forks.
	ArrayList<Movement> additionalMovements;

	/** One Entity.Movement with scalar Vec3 components. */
	public record Movement(double fromX, double fromY, double fromZ, double toX, double toY, double toZ,
	    double requestedX, double requestedZ) {}

	/** Retained movements this tick beyond the first, counted without copying. */
	public int additionalMovementCount() {
		return this.additionalMovements == null ? 0 : this.additionalMovements.size();
	}

	/** One retained movement by position, read without copying. */
	public Movement additionalMovement(final int index) {
		return this.additionalMovements.get(index);
	}

	/** Immutable tail of the retained movement list, for exact boundary serialization. */
	public java.util.List<Movement> additionalMovements() {
		return this.additionalMovements == null ? java.util.List.of() : java.util.List.copyOf(this.additionalMovements);
	}

	/** Restore an explicitly captured retained movement tail. */
	public void restoreAdditionalMovements(final java.util.List<Movement> movements) {
		if (movements.size() > 99) throw new IllegalArgumentException("movement tail exceeds source cap");
		this.additionalMovements = movements.isEmpty() ? null : new ArrayList<>(movements);
	}

	/**
	 * Records one movement segment, vanilla {@code Entity.addMovementThisTick}, retaining the bounded
	 * history the server replays for block contact.
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
			if (this.additionalMovements == null) this.additionalMovements = new ArrayList<>();
			if (this.additionalMovements.size() >= 99) {
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
		} else
			clearMovementThisTick();
	}

	/** Clears all retained movement segments and canonicalizes their empty scalar representation. */
	public void clearMovementThisTick() {
		// Empty native movement storage has no segment values. Canonicalize
		// the flattened representation so observation, equality and hashing
		// agree.
		this.movementAxisDependent = true;
		this.movementFromX = this.movementFromY = this.movementFromZ = 0.0;
		this.movementToX = this.movementToY = this.movementToZ = 0.0;
		this.movementRequestedX = this.movementRequestedZ = 0.0;
		this.movementThisTickPresent = false;
		if (this.additionalMovements != null) this.additionalMovements.clear();
	}

	/** Whether the retained movement tails agree, treating empty storage alike. */
	static boolean sameMovements(final ServerPlayerState a, final ServerPlayerState b) {
		int n = a.additionalMovements == null ? 0 : a.additionalMovements.size();
		int m = b.additionalMovements == null ? 0 : b.additionalMovements.size();
		return n == m && (n == 0 || a.additionalMovements.equals(b.additionalMovements));
	}

	public boolean movementThisTickPresent;
	/** X position at the start of the first movement retained in this server tick. */
	public double movementFromX;
	/** Feet height at the start of the first movement retained in this server tick. */
	public double movementFromY;
	/** Z position at the start of the first movement retained in this server tick. */
	public double movementFromZ;
	/** X position after the first retained movement. */
	public double movementToX;
	/** Feet height after the first retained movement. */
	public double movementToY;
	/** Z position after the first retained movement. */
	public double movementToZ;
	/** Requested X displacement before collision resolution for the first retained movement. */
	public double movementRequestedX;
	/** Requested Z displacement before collision resolution for the first retained movement. */
	public double movementRequestedZ;

	/** SynchedEntityData dirty entries retained until ServerEntity packs them. */
	public int entityDataDirty;
	/** ServerPlayer.doTick's health packet publication baselines at the supplied boundary. */
	public float lastSentHealth = 20.0F;
	/** Food level in the most recent health publication. */
	public int lastSentFood = 20;
	/** Whether saturation was zero at the most recent health publication. */
	public boolean lastFoodSaturationZero;

	@Override
	public void setSwimming(final boolean value) {
		if (this.swimming != value) this.entityDataDirty |= EntityDataWrite.FLAGS;
		super.setSwimming(value);
	}

	@Override
	public void setSprinting(final boolean value) {
		if (this.sprinting != value) this.entityDataDirty |= EntityDataWrite.FLAGS;
		this.movementSpeedAttributeDirty |= this.sprintingAttribute || value;
		super.setSprinting(value);
	}

	@Override
	public void setPose(final Pose value) {
		if (this.pose != value) this.entityDataDirty |= EntityDataWrite.POSE;
		super.setPose(value);
	}

	@Override
	public void setTicksFrozen(final int value) {
		if (this.ticksFrozen != value) this.entityDataDirty |= EntityDataWrite.FROZEN;
		super.setTicksFrozen(value);
	}

	@Override
	public void setSharedFlag(final int flag, final boolean value) {
		int before = ServerEntity.sharedFlags(this);
		switch (flag) {
			case 0 -> this.sharedFlagOnFire = value;
			case 1 -> this.shiftKeyDown = value;
			default -> super.setSharedFlag(flag, value);
		}
		if (before != ServerEntity.sharedFlags(this)) this.entityDataDirty |= EntityDataWrite.FLAGS;
	}

	public static final float DEFAULT_MAXIMUM_HEALTH = 20.0F;
	public static final int DEFAULT_MAXIMUM_AIR = 300;
	public static final int DEFAULT_TICKS_REQUIRED_TO_FREEZE = 140;
	/** {@code Entity.getFireImmuneTicks} for a player: the dry steady fire state is its negation. */
	public static final int FIRE_IMMUNE_TICKS = 20;
	/** The entity tick count of a constructed boundary, which no capture supplies. */
	public static final long UNKNOWN_TICK_COUNT = Long.MIN_VALUE;

	/** Constructed one-client/one-server tick schedule; unknown live timing refuses. */
	public boolean publicationScheduleKnown = true;
	/** Entity baseTick's shared fire bit; contact can change remainingFireTicks afterward. */
	public boolean sharedFlagOnFire;
	/** ServerEntity's last published shared flags, pose, frozen ticks and frost modifier. */
	public int lastSentSharedFlags;
	/** Pose most recently published by the tracker. */
	public Pose lastSentPose = Pose.STANDING;
	/** Frozen tick count most recently published by the tracker. */
	public int lastSentTicksFrozen;
	/** Frozen-speed attribute value most recently published by the tracker. */
	public int lastSentFrostSpeedTicks;
	/** AttributeMap dirty state after removeFrost/tryAddFrost. */
	public boolean movementSpeedAttributeDirty;
	/** Constructed dedicated-server movement-check gamerules and owner exemption. */
	public boolean playerMovementCheck = true;
	/** Whether the server applies the movement-speed check while gliding. */
	public boolean elytraMovementCheck = true;
	/** Whether movement validation grants the local single-player owner exemption. */
	public boolean singleplayerOwner;
	/** Nonzero external-impulse context is outside the constructed movement slice. */
	public int currentImpulseContextResetGraceTime;

	/*
	 * Survival: LivingEntity and Player fields the server alone tracks.
	 */

	/** FoodData.setSaturation and save loading do not cap this to foodLevel. */
	public float saturationLevel = 5.0F;
	/** Accumulated food exhaustion awaiting FoodData processing. */
	public float exhaustionLevel;
	public int tickTimer;
	/** The non-peaceful server's naturalHealthRegeneration gamerule. */
	public boolean naturalRegeneration = true;
	/**
	 * The level's damage gamerules, which {@code Player.isInvulnerableTo} reads
	 * by damage type tag before any hit lands: {@code fallDamage} for the fall
	 * and stalagmite types, {@code fireDamage} for in-fire, campfire, on-fire,
	 * lava and hot-floor, {@code drowningDamage} for drowning and
	 * {@code freezeDamage} for freezing. A disabled rule drops the hit without
	 * touching the cooldown. Vanilla defaults are all {@code true}.
	 */
	public boolean fallDamage = true;
	/** Whether the declared server rules permit fire damage. */
	public boolean fireDamage = true;
	/** Whether the declared server rules permit drowning damage. */
	public boolean drowningDamage = true;
	/** Whether the declared server rules permit freezing damage. */
	public boolean freezeDamage = true;
	/** Non-peaceful level difficulty governing starvation thresholds. */
	public Difficulty difficulty = Difficulty.NORMAL;
	/** The admitted non-peaceful vanilla level difficulties. */
	public enum Difficulty { EASY, NORMAL, HARD }

	public float health = DEFAULT_MAXIMUM_HEALTH;
	/** Declared maximum health in health points. */
	public float maximumHealth = DEFAULT_MAXIMUM_HEALTH;
	/** Remaining absorption health points, consumed before ordinary health. */
	public float absorption;
	/** {@code LivingEntity.invulnerableTime}: twenty after a full hit, decremented every level tick. */
	public int invulnerableTime;
	/** {@code LivingEntity.lastHurt}: the amount of the hit that owns the cooldown. */
	public float lastHurt;
	/** {@code Entity.remainingFireTicks}: positive while burning, negative through the immune window. */
	public int remainingFireTicks = -FIRE_IMMUNE_TICKS;
	/** Remaining underwater air supply, in the upstream tick-based counter. */
	public int airSupply = DEFAULT_MAXIMUM_AIR;
	/** {@code Entity.tickCount} at this boundary, or {@link #UNKNOWN_TICK_COUNT}. */
	public long tickCount = UNKNOWN_TICK_COUNT;

	/*
	 * Listener: vanilla {@code ServerGamePacketListenerImpl} and {@code ServerPlayer} facts between packets.
	 */

	/** {@code clientIsFloating}: the last accepted packet looked airborne with no blocks around. */
	public boolean clientIsFloating;
	/**
	 * The last accepted packet could not decide the floating latch because
	 * its air query reached space the capture does not declare. The latch is
	 * then held false and {@link #aboveGroundTickCount} counts an upper bound,
	 * so the kick is refused as undetermined instead of missed.
	 */
	public boolean floatingUnknown;
	/** {@code aboveGroundTickCount}: connection ticks spent floating; 80 is the kick. */
	public int aboveGroundTickCount;
	/** {@code ServerPlayer.lastKnownClientMovement}: the last accepted packet's displacement. */
	public double lastKnownClientMovementX;
	public double lastKnownClientMovementY;
	public double lastKnownClientMovementZ;
	/**
	 * {@code awaitingPositionFromClient}: a correction was sent and not yet
	 * acknowledged, so movement packets only apply rotation until it is.
	 */
	public boolean correctionPending;
	/**
	 * {@code MinecraftServer.allowFlight}: a server setting, not an observable
	 * player fact. The dedicated default is {@code false}; a caller that knows
	 * the server allows flight declares it, since it only suppresses the
	 * floating latch.
	 */
	public boolean allowFlight;
	/**
	 * {@code Entity.isShiftKeyDown} on the server's copy: the shift bit of the
	 * last input packet, which the client sends before the movement packet of
	 * the same tick. The server's own tick reads it for careful stepping.
	 */
	public boolean shiftKeyDown;
	/**
	 * Firework rockets the client sees attached to the player
	 * ({@code FireworkRocketEntity.DATA_ATTACHED_TO_TARGET}). Each one
	 * replaces a gliding player's velocity on every actor tick, on both
	 * sides, for a server-private lifetime; the server tick refuses the first
	 * input at which one would write.
	 */
	public int attachedRockets;

	/** The constructed packet boundary holding this movement; see the class description. */
	public static ServerPlayerState atBoundary(final PlayerState movement) {
		ServerPlayerState state = new ServerPlayerState();
		movement.copyInto(state);
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

	@Override
	public ServerPlayerState copy() {
		ServerPlayerState copy = new ServerPlayerState();
		copyInto(copy);
		return copy;
	}

	/**
	 * The facts a client can read about the server's copy of its player at a
	 * boundary, as {@link #derivedFrom} admits them: what the server last
	 * published (health, maximum health, absorption, air, food, saturation),
	 * the hit cooldown the client set on the damage event, the shift key,
	 * the rockets the client sees attached, the pending teleport id, the
	 * level's difficulty, and whether the server is the client's own
	 * integrated one, which allows flight and exempts its owner from the
	 * movement checks.
	 */
	public record Observed(float health, float maximumHealth, float absorption, int airSupply, int foodLevel,
	    float saturationLevel, int invulnerableTime, boolean shiftKeyDown, int attachedRockets, int awaitingTeleport,
	    Difficulty difficulty, boolean integratedOwner) {
		public Observed {
			java.util.Objects.requireNonNull(difficulty, "difficulty");
		}
	}

	/**
	 * The server's copy of the player as a client can derive it at a boundary,
	 * for a pilot to carry the simulator's own prediction from.
	 *
	 * <p>Java movement is client-authoritative, so the server's position,
	 * rotation and contact flags are the publisher's last published values
	 * and the listener's acceptance anchors start there. The published
	 * survival facts are {@code observed}'s and are taken as sent. What no
	 * client can see (hunger phase, connection and entity tick counters,
	 * movement records) is left at the constructed boundary's values. The
	 * result is validated as any admitted server state is, so an out-of-range
	 * fact refuses here rather than inside a tick.
	 *
	 * @throws IllegalStateException when the observed facts are outside what
	 *     the admitted slice holds
	 */
	public static ServerPlayerState derivedFrom(
	    final PlayerState movement, final Publisher publisher, final Observed observed) {
		java.util.Objects.requireNonNull(publisher, "publisher");
		java.util.Objects.requireNonNull(observed, "observed");
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
		server.restoreAdditionalMovements(java.util.List.of());
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

	/**
	 * Takes from {@code observed}, a copy derived from what a client can read,
	 * the facts the server publishes to the client: health, maximum health,
	 * absorption, air, food level, the hit cooldown the client sets on a
	 * damage event, the fire flag, the shift key, attached rockets and the
	 * pending teleport id. Saturation travels only inside a health packet, so
	 * it is taken only when {@code healthPublication} says one just arrived;
	 * otherwise this copy's own {@code FoodData} model stands. Everything a
	 * client cannot see (exhaustion, counters, the floating latch, movement
	 * records) is left as this copy carried it.
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

	/** Copy every field, movement included, into retained caller-owned storage. */
	public void copyInto(final ServerPlayerState copy) {
		if (copy == this) return;
		super.copyInto(copy);
		StateFields.copy(copy, this);
	}

	/** Raw-bit equality of every field, movement included. */
	public static boolean rawEquals(final ServerPlayerState a, final ServerPlayerState b) {
		return PlayerState.rawEquals(a, b) && StateFields.rawEqualsOwn(a, b);
	}

	/** Whether health has reached zero, at which the server's tick stops the player. */
	public boolean dead() {
		return this.health <= 0.0F;
	}

	/** Whether the hit cooldown still refuses an equal or smaller hit. */
	public boolean inHitCooldown() {
		return this.invulnerableTime > 10;
	}

	/**
	 * RefusalException out-of-range survival facts, non-finite known movement, or a
	 * negative floating or rocket count. The movement half is validated by
	 * {@link #requireValidForTransition()}.
	 */
	public void requireValid() {
		if (this.currentImpulseContextResetGraceTime < 0) {
			throw new IllegalArgumentException("negative post-impulse movement grace");
		}
		if (this.difficulty == null) throw new IllegalArgumentException("unknown level difficulty");
		if (this.foodLevel < 0 || this.foodLevel > 20 || !Float.isFinite(this.saturationLevel)
		    || this.saturationLevel < 0.0F || !Float.isFinite(this.exhaustionLevel) || this.exhaustionLevel < 0.0F
		    || this.exhaustionLevel > 40.0F || this.tickTimer < 0 || this.tickTimer >= 80) {
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
}
