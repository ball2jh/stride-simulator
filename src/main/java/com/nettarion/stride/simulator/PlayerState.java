package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.world.WorldView;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * The client's copy of everything about the player that affects movement, as a flat mutable structure
 * the simulator steps in place.
 *
 * <p>The fields are the movement state plus the hidden fields a transition needs, notably the previous
 * tick's input, which {@code LocalPlayer.aiStep} reads before {@code input.tick()} overwrites it. What
 * the client last sent lives in {@link Publisher}; what the server alone tracks lives in
 * {@link ServerPlayerState}. Positions are in blocks, velocities in blocks per tick, rotations in
 * degrees, counters in ticks; {@code xxa}, {@code yya}, {@code zza}, {@code yRot} and {@code xRot} are
 * vanilla's names for sideways, vertical and forward input, yaw and pitch.
 *
 * <p>Instances are mutable and belong to one thread; {@link #copy()} and {@link #copyInto} are raw-bit
 * exact. A caller-assembled state is admitted after {@link #requireValidForTransition()}: structural
 * failures throw {@link IllegalStateException}, a center outside the admitted coordinate domain
 * refuses with {@link UnimplementedMechanicException}.
 */
public sealed class PlayerState permits ServerPlayerState {
	private static final double MAX_HORIZONTAL_CENTER = 30_000_000.0;

	private static final double MAX_VERTICAL_CENTER = 20_000_000.0;

	/**
	 * The pose-fit cache: the memoized collision result for one pose at one position in one world.
	 * Optimization metadata only, absent from the digest and the trace state. Every use rechecks the
	 * recorded key and the raw position bits, so a stale entry can only become a miss. The entry is keyed
	 * on the world's identity number and the collision version of the cells the box can touch, and never
	 * holds the world; after {@link #carryPoseFitCache} it is also keyed on the span revision of those
	 * cells. Zero names no world, so a state never checked, or checked in a view without an identity,
	 * holds no entry.
	 */
	private long poseFitWorld;

	/** The collision version of the span at the time of the entry, or zero in an immutable view. */
	private long poseFitCollisionVersion;

	/** The span revision the entry stands under once carried, and zero until then. */
	private long poseFitSpanRevision;

	private long poseFitXBits;

	private long poseFitYBits;

	private long poseFitZBits;

	private int poseFitWidthBits;

	/** The height of the pose that fit; negative once cleared, which fails every comparison. */
	private float poseFitHeight;

	/** The inflation vanilla's collision query applies to a box before scanning cells, in blocks. */
	private static final double POSE_FIT_EPSILON = 1.0E-7;

	/** Player center on the east-west axis, in blocks; use {@link #placeAt} to keep the box consistent. */
	public double x;

	/** Player feet height, in blocks; use {@link #placeAt} to keep the box consistent. */
	public double y;

	/** Player center on the north-south axis, in blocks; positive Z points south. */
	public double z;

	/** Retained X velocity in blocks per tick, before the next tick applies forces. */
	public double deltaMovementX;

	/** Retained vertical velocity in blocks per tick; positive values move upward. */
	public double deltaMovementY;

	/** Retained Z velocity in blocks per tick, before the next tick applies forces. */
	public double deltaMovementZ;

	/** Yaw in degrees: zero faces positive Z and positive ninety faces negative X. */
	public float yRot;

	/** Pitch in degrees, from negative ninety (up) to positive ninety (down). */
	public float xRot;

	/** Minimum X face of the retained collision box, in blocks. */
	public double boundingBoxMinX;

	/** Minimum Y face of the retained collision box, in blocks. */
	public double boundingBoxMinY;

	/** Minimum Z face of the retained collision box, in blocks. */
	public double boundingBoxMinZ;

	/** Maximum X face of the retained collision box, in blocks. */
	public double boundingBoxMaxX;

	/** Maximum Y face of the retained collision box, in blocks. */
	public double boundingBoxMaxY;

	/** Maximum Z face of the retained collision box, in blocks. */
	public double boundingBoxMaxZ;

	/** Whether the previous movement reported supporting vertical contact. */
	public boolean onGround;

	/** Whether the previous movement clipped either horizontal component. */
	public boolean horizontalCollision;

	/** Whether the previous movement clipped its vertical component. */
	public boolean verticalCollision;

	/** Whether the vertical collision was below the body. */
	public boolean verticalCollisionBelow;

	/** Whether the previous horizontal collision met vanilla's minor-collision rule. */
	public boolean minorHorizontalCollision;

	/** Whether vanilla's {@code Entity.mainSupportingBlockPos} is present. */
	public boolean mainSupportingBlockPosPresent;

	/** X coordinate of the retained supporting cell; meaningful only when its presence flag is set. */
	public int mainSupportingBlockPosX;

	/** Y coordinate of the retained supporting cell; meaningful only when its presence flag is set. */
	public int mainSupportingBlockPosY;

	/** Z coordinate of the retained supporting cell; meaningful only when its presence flag is set. */
	public int mainSupportingBlockPosZ;

	/** Retained fallback latch used by {@code Entity.checkSupportingBlock}. */
	public boolean onGroundNoBlocks;

	/** Accumulated downward travel in blocks used by landing and damage rules. */
	public double fallDistance;

	/** Water immersion height in blocks at this boundary, from the fluid tracker. */
	public double waterHeight;

	/** Lava immersion height in blocks at this boundary, from the fluid tracker. */
	public double lavaHeight;

	/** Whether the eye was in water; {@code baseTick} snapshots this before refreshing fluids. */
	public boolean eyeInWater;

	/** Delayed X movement multiplier installed by block contact and consumed by movement. */
	public double stuckSpeedMultiplierX;

	/** Delayed Y movement multiplier installed by block contact and consumed by movement. */
	public double stuckSpeedMultiplierY;

	/** Delayed Z movement multiplier installed by block contact and consumed by movement. */
	public double stuckSpeedMultiplierZ;

	/** Whether the most recent block traversal contacted powder snow. */
	public boolean isInPowderSnow;

	/** Powder-snow contact retained from the preceding base tick. */
	public boolean wasInPowderSnow;

	/**
	 * {@code Entity.ticksFrozen}, in ticks. On the client it is what the last entity-data write
	 * delivered; the server owns the count and {@link ServerPlayerState#setTicksFrozen} tracks its dirtiness.
	 */
	public int ticksFrozen;

	/** Whether this player admits freezing; immunity must be supplied explicitly. */
	public boolean canFreeze = true;

	/** Synchronized frozen-tick value used to derive the movement-speed penalty, in ticks. */
	public int frostSpeedTicks;

	/**
	 * Product of the synchronized non-sprint Movement Speed total multipliers.
	 *
	 * <p>The supported inputs are vanilla's Speed and Slowness effects, whose amounts are the float
	 * {@code 0.2F} and {@code -0.15F} widened to double and then multiplied by {@code amplifier + 1} in
	 * double, as {@code MobEffect.AttributeTemplate.create} does; for amplifiers of two and up that
	 * differs from the float product. Any other finite value is arithmetic the tick will perform, not a
	 * supported modifier.
	 */
	public double movementSpeedMultiplier = 1.0;

	/**
	 * Direct jump power added by Jump Boost, {@code 0.1F * (amplifier + 1.0F)}, the only supported
	 * additive jump input. Jump Boost's Safe Fall Distance modifier is not movement and is not carried.
	 */
	public float jumpBoostPower;

	/** Equipment-derived capability read by powder-snow collision and climb lift. */
	public boolean canWalkOnPowderSnow;

	/** Client option read by {@code LocalPlayer}'s sprint trigger, in ticks. */
	public int sprintWindowTicks = 7;

	/** Retained {@code LocalPlayer} auto-jump setting used by {@code updateAutoJump}. */
	public boolean autoJumpEnabled;

	/** Dimension-owned {@code EnvironmentAttributes.FAST_LAVA} value. */
	public boolean fastLava;

	/** Whether the abilities permit creative flight; this is separate from active flight. */
	public boolean mayfly;

	/** Whether creative flight travel is currently active. */
	public boolean flying;

	/** Ability-owned creative flight speed, normally {@code 0.05F}. */
	public float flyingSpeed = 0.05F;

	/** Current sprint flag; use {@link #setSprinting} when also changing its speed attribute. */
	public boolean sprinting;

	/** Whether {@code MOVEMENT_SPEED} currently has {@code LivingEntity.SPEED_MODIFIER_SPRINTING}. */
	public boolean sprintingAttribute;

	/** {@code FoodData.getFoodLevel} on this side, which gates sprinting and other exhausting moves. */
	public int foodLevel = 20;

	/** Synchronized swimming flag, distinct from the current pose. */
	public boolean swimming;

	/** Synchronized gliding flag; distinct from creative flight. */
	public boolean fallFlying;

	/** Consecutive ticks retained by the fall-flying state machine. */
	public int fallFlyTicks;

	/** Whether the declared equipment can start or sustain gliding at this boundary. */
	public boolean gliderUsable;

	/** {@code Entity} shared-flag bits not represented by named movement flags; see {@link SharedFlag}. */
	public byte sharedFlagsResidual;

	/** Current player pose, which controls collision-box dimensions. */
	public Pose pose = Pose.STANDING;

	/** Vanilla's {@code xxa}: sideways movement input consumed by travel; positive steers left. */
	public float xxa;

	/** Vanilla's {@code yya}: vertical movement input consumed by travel. */
	public float yya;

	/** Vanilla's {@code zza}: forward movement input consumed by travel. */
	public float zza;

	/** Whether the current movement phase requests a jump. */
	public boolean jumping;

	/** Remaining ticks in the double-tap sprint trigger window. */
	public int sprintTriggerTime;

	/** Remaining ticks in the double-tap flight trigger window. */
	public int jumpTriggerTime;

	/** Retained auto-jump countdown, in ticks. */
	public int autoJumpTime;

	/** Remaining ticks before another held jump can fire. */
	public int noJumpDelay;

	/** Client crouch state after input and pose-clearance checks. */
	public boolean crouching;

	/**
	 * Raw last-tick {@code ClientInput} key bits. {@code aiStep} samples shift and forward before
	 * {@code input.tick()} overwrites them, so omitting these fields would make an arbitrary captured
	 * state impossible to fork exactly.
	 */
	public byte inputKeyPresses;

	/** Previous tick lateral input vector, retained before the next input refresh. */
	public float inputMoveVectorX;

	/** Previous tick forward input vector, retained before the next input refresh. */
	public float inputMoveVectorY;

	/**
	 * A client player at the origin with no box, no velocity, standing pose, and every flag clear; set
	 * the position with {@link #placeAt} and validate with {@link #requireValidForTransition}.
	 */
	public PlayerState() {}

	/** Raw-bit equality of every movement field. */
	public static boolean rawEquals(final PlayerState a, final PlayerState b) {
		return StateFields.rawEquals(a, b);
	}

	/**
	 * Whether an ordinary movement center is inside the admitted coordinate domain.
	 *
	 * <p>The admitted closed intervals are [-30,000,000, 30,000,000] for X and Z and
	 * [-20,000,000, 20,000,000] for Y, in blocks. Both the initial center and every committed successor
	 * center must satisfy this predicate; the collision box may extend beyond it.
	 */
	public static boolean supportsCenterPosition(final double x, final double y, final double z) {
		return supportsHorizontalPosition(x, z) && Double.isFinite(y) && y >= -MAX_VERTICAL_CENTER
		    && y <= MAX_VERTICAL_CENTER;
	}

	/** Whether finite X and Z lie in the closed [-30,000,000, 30,000,000] admitted center interval. */
	public static boolean supportsHorizontalPosition(final double x, final double z) {
		return Double.isFinite(x) && Double.isFinite(z) && x >= -MAX_HORIZONTAL_CENTER && x <= MAX_HORIZONTAL_CENTER
		    && z >= -MAX_HORIZONTAL_CENTER && z <= MAX_HORIZONTAL_CENTER;
	}

	/**
	 * Refuses a center outside the admitted coordinate domain; does not validate the remaining state.
	 *
	 * @throws UnimplementedMechanicException with {@link RefusalCause#INADMISSIBLE_COORDINATE}
	 */
	public static void requireSupportedCenter(final double x, final double y, final double z) {
		if (!supportsCenterPosition(x, y, z)) {
			throw UnimplementedMechanicException.deferred(RefusalCause.INADMISSIBLE_COORDINATE,
			    () -> "movement center lies outside the admitted coordinate domain: " + x + "," + y + "," + z);
		}
	}

	private static void requireFinite(final String label, final double... values) {
		for (double value : values) {
			if (!Double.isFinite(value)) {
				throw new IllegalStateException(label + " must be finite");
			}
		}
	}

	private static void requireRaw(final String field, final double expected, final double actual) {
		if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual)) {
			throw new IllegalStateException(field + " does not match position and pose");
		}
	}

	/** Sets {@code Entity}'s synchronized swimming flag. */
	public void setSwimming(final boolean value) {
		this.swimming = value;
	}

	/** Sets {@code LivingEntity}'s sprint state and its speed attribute together. */
	public void setSprinting(final boolean value) {
		this.sprinting = value;
		this.sprintingAttribute = value;
	}

	/** Sets {@code Entity}'s synchronized pose; the caller refreshes the box with {@link #placeAt}. */
	public void setPose(final Pose value) {
		this.pose = value;
	}

	/** Sets {@code Entity}'s synchronized frozen tick count. */
	public void setTicksFrozen(final int value) {
		this.ticksFrozen = value;
	}

	/**
	 * Sets one modeled {@code Entity} shared flag by its vanilla bit index; see {@link SharedFlag}.
	 *
	 * @throws IllegalArgumentException for a flag this copy does not model
	 */
	public void setSharedFlag(final int flag, final boolean value) {
		switch (flag) {
			case SharedFlag.SPRINTING -> this.sprinting = value;
			case SharedFlag.SWIMMING -> this.swimming = value;
			case SharedFlag.FALL_FLYING -> this.fallFlying = value;
			default -> throw new IllegalArgumentException("unsupported shared flag: " + flag);
		}
	}

	/** An independent copy of every movement field and the pose-fit cache. */
	public PlayerState copy() {
		PlayerState copy = new PlayerState();
		copyInto(copy);
		return copy;
	}

	/**
	 * Copies every movement field and the pose-fit cache into caller-owned storage, which may be this
	 * object. A {@link ServerPlayerState} overrides this to copy its server fields as well when the
	 * target is a server copy; use it instead of allocating one object per fork.
	 */
	public void copyInto(final PlayerState copy) {
		copyMovementInto(copy);
	}

	/** {@link #copyInto} without the server override: the movement half and the pose-fit cache only. */
	final void copyMovementInto(final PlayerState copy) {
		StateFields.copy(copy, this);
		copy.poseFitWorld = this.poseFitWorld;
		copy.poseFitCollisionVersion = this.poseFitCollisionVersion;
		copy.poseFitSpanRevision = this.poseFitSpanRevision;
		copy.poseFitXBits = this.poseFitXBits;
		copy.poseFitYBits = this.poseFitYBits;
		copy.poseFitZBits = this.poseFitZBits;
		copy.poseFitWidthBits = this.poseFitWidthBits;
		copy.poseFitHeight = this.poseFitHeight;
	}

	/** An immutable copy of the retained collision box. */
	public AABB box() {
		return new AABB(this.boundingBoxMinX, this.boundingBoxMinY, this.boundingBoxMinZ, this.boundingBoxMaxX,
		    this.boundingBoxMaxY, this.boundingBoxMaxZ);
	}

	/** Replaces the collision box and clears the pose-fit cache; position and pose are unchanged. */
	public void setBox(final AABB box) {
		clearPoseFitCache();
		this.boundingBoxMinX = box.minX();
		this.boundingBoxMinY = box.minY();
		this.boundingBoxMinZ = box.minZ();
		this.boundingBoxMaxX = box.maxX();
		this.boundingBoxMaxY = box.maxY();
		this.boundingBoxMaxZ = box.maxZ();
	}

	/**
	 * Copies the position and collision box, nine correlated fields, from {@code source} and clears the
	 * pose-fit cache. Velocity, contact flags and pose are unchanged. Call
	 * {@link #requireValidForTransition()} after combining state from independent sources.
	 */
	public void copyLocationFrom(final PlayerState source) {
		if (source == null) {
			throw new NullPointerException("source");
		}
		clearPoseFitCache();
		this.x = source.x;
		this.y = source.y;
		this.z = source.z;
		this.boundingBoxMinX = source.boundingBoxMinX;
		this.boundingBoxMinY = source.boundingBoxMinY;
		this.boundingBoxMinZ = source.boundingBoxMinZ;
		this.boundingBoxMaxX = source.boundingBoxMaxX;
		this.boundingBoxMaxY = source.boundingBoxMaxY;
		this.boundingBoxMaxZ = source.boundingBoxMaxZ;
	}

	/**
	 * Places the player at a center and feet height in blocks and rebuilds the box from the current
	 * pose. This is state initialization or an external authority event, not a movement tick.
	 */
	public void placeAt(final double x, final double y, final double z) {
		clearPoseFitCache();
		this.x = x;
		this.y = y;
		this.z = z;
		double halfWidth = this.pose.width / 2.0F;
		this.boundingBoxMinX = x - halfWidth;
		this.boundingBoxMinY = y;
		this.boundingBoxMinZ = z - halfWidth;
		this.boundingBoxMaxX = x + halfWidth;
		this.boundingBoxMaxY = y + this.pose.height;
		this.boundingBoxMaxZ = z + halfWidth;
	}

	/**
	 * Applies an observed velocity packet as the client applies one: the three components, in blocks
	 * per tick, replace the velocity and nothing else moves. This is the same application a predicted
	 * {@link HurtMotion} makes, so a consumer re-anchoring on an arrived packet reaches the state the
	 * simulator would have.
	 *
	 * @throws IllegalStateException when a component is not finite
	 */
	public void applyObservedVelocity(final double x, final double y, final double z) {
		requireFinite("velocity", x, y, z);
		this.deltaMovementX = x;
		this.deltaMovementY = y;
		this.deltaMovementZ = z;
	}

	/**
	 * Applies an observed entity-data packet as the client applies one: the sprint, swimming and gliding
	 * bits of the shared flags when present, the pose with its box when present and modeled, and the
	 * frozen ticks when present. This is the same application {@link EntityDataWrite#applyUnchecked}
	 * makes for a predicted write. The sprint attribute is untouched: the client changes it only through
	 * {@code LivingEntity.setSprinting} or the server's attribute packet. An unmodeled pose leaves the
	 * copy as it was.
	 */
	public void applyObservedEntityData(
	    final OptionalInt sharedFlags, final Optional<String> pose, final OptionalInt ticksFrozen) {
		if (sharedFlags.isPresent()) {
			int flags = sharedFlags.getAsInt();
			this.sprinting = (flags & SharedFlag.SPRINTING_MASK) != 0;
			this.swimming = (flags & SharedFlag.SWIMMING_MASK) != 0;
			this.fallFlying = (flags & SharedFlag.FALL_FLYING_MASK) != 0;
		}
		if (pose.isPresent()) {
			for (Pose known : Pose.values()) {
				if (known.name().equals(pose.get())) {
					this.pose = known;
					placeAt(this.x, this.y, this.z);
				}
			}
		}
		if (ticksFrozen.isPresent()) {
			this.ticksFrozen = ticksFrozen.getAsInt();
		}
	}

	/**
	 * Rejects malformed state before it enters a transition.
	 *
	 * <p>Copies the simulator makes are trusted after this check, so it is not called on every tick;
	 * call it once after assembling a state from a capture or an observation. Raw equality matters here:
	 * the tick reads position and box independently, and a one-bit disagreement can change collision or
	 * traversal.
	 *
	 * @throws IllegalStateException when fields are non-finite, missing, inverted, or inconsistent with
	 *     position and pose
	 * @throws UnimplementedMechanicException when the center is structurally valid but outside the
	 *     admitted coordinate domain
	 */
	public void requireValidForTransition() {
		if (this.pose == null) {
			throw new IllegalStateException("movement state has no pose");
		}
		requireFinite("position", this.x, this.y, this.z);
		requireSupportedCenter(this.x, this.y, this.z);
		requireFinite("velocity", this.deltaMovementX, this.deltaMovementY, this.deltaMovementZ);
		requireFinite("box minimum", this.boundingBoxMinX, this.boundingBoxMinY, this.boundingBoxMinZ);
		requireFinite("box maximum", this.boundingBoxMaxX, this.boundingBoxMaxY, this.boundingBoxMaxZ);
		if (this.boundingBoxMinX > this.boundingBoxMaxX || this.boundingBoxMinY > this.boundingBoxMaxY
		    || this.boundingBoxMinZ > this.boundingBoxMaxZ) {
			throw new IllegalStateException("movement box has inverted bounds");
		}
		double halfWidth = this.pose.width / 2.0F;
		requireRaw("boundingBoxMinX", this.x - halfWidth, this.boundingBoxMinX);
		requireRaw("boundingBoxMinY", this.y, this.boundingBoxMinY);
		requireRaw("boundingBoxMinZ", this.z - halfWidth, this.boundingBoxMinZ);
		requireRaw("boundingBoxMaxX", this.x + halfWidth, this.boundingBoxMaxX);
		requireRaw("boundingBoxMaxY", this.y + this.pose.height, this.boundingBoxMaxY);
		requireRaw("boundingBoxMaxZ", this.z + halfWidth, this.boundingBoxMaxZ);
		requireFinite("fluid height", this.waterHeight, this.lavaHeight);
		requireFinite(
		    "stuck speed", this.stuckSpeedMultiplierX, this.stuckSpeedMultiplierY, this.stuckSpeedMultiplierZ);
		if (!Float.isFinite(this.yRot) || !Float.isFinite(this.xRot) || !Float.isFinite(this.flyingSpeed)
		    || !Float.isFinite(this.jumpBoostPower) || !Double.isFinite(this.movementSpeedMultiplier)) {
			throw new IllegalStateException("movement coefficients must be finite");
		}
	}

	/**
	 * Clears the pose-fit cache. The height is what every check compares against a pose, so a negative
	 * one fails them all; the world identity is kept, since the next entry is almost always in the same
	 * world.
	 */
	void clearPoseFitCache() {
		this.poseFitHeight = -1.0F;
	}

	/** Whether the pose-fit cache covers {@code pose} at this position in {@code world}. */
	public boolean hasCachedPoseFit(final WorldView world, final Pose pose) {
		return this.poseFitWorld != 0L && this.poseFitXBits == Double.doubleToRawLongBits(this.x)
		    && this.poseFitYBits == Double.doubleToRawLongBits(this.y)
		    && this.poseFitZBits == Double.doubleToRawLongBits(this.z)
		    && this.poseFitWidthBits == Float.floatToRawIntBits(pose.width) && this.poseFitHeight >= pose.height
		    && poseFitHolds(world);
	}

	/**
	 * Whether the entry holds in {@code world}: in the view it was taken in, by the span's collision
	 * version unless the view is immutable; in another view, by span revision when the entry carries
	 * one and the world answers the same.
	 */
	private boolean poseFitHolds(final WorldView world) {
		long identity = world.identity();
		if (identity != 0L && this.poseFitWorld == identity) {
			// The very view the entry was taken in: immutable, nothing to read;
			// mutable, the span's version says whether an edit reached it.
			return world.movementFactsImmutable() || this.poseFitCollisionVersion == poseFitSpan(world, false);
		}
		// Another view: the entry holds there when it answers the same span
		// revision, which a republication that kept the sections does.
		return this.poseFitSpanRevision != 0L && this.poseFitSpanRevision == poseFitSpan(world, true);
	}

	/**
	 * Carries the pose-fit cache across a world republication: records, from the view it was taken in,
	 * the span revision it stands under, so a later check in another view can honor it. Nothing is read
	 * when the entry is absent, already carried, or stale in its own view. A consumer calls this on the
	 * states it keeps across a republication; the tick never does.
	 */
	public void carryPoseFitCache(final WorldView world) {
		if (this.poseFitSpanRevision != 0L || this.poseFitWorld == 0L || this.poseFitHeight < 0.0F) {
			return;
		}
		long identity = world.identity();
		if (identity == 0L || this.poseFitWorld != identity) {
			return;
		}
		if (!world.movementFactsImmutable() && this.poseFitCollisionVersion != poseFitSpan(world, false)) {
			return;
		}
		this.poseFitSpanRevision = poseFitSpan(world, true);
	}

	/** Records a pose fit the caller established; no collision query runs here. */
	public void cachePoseFit(final WorldView world, final Pose pose) {
		long xBits = Double.doubleToRawLongBits(this.x);
		long yBits = Double.doubleToRawLongBits(this.y);
		long zBits = Double.doubleToRawLongBits(this.z);
		int widthBits = Float.floatToRawIntBits(pose.width);
		// A view without an identity cannot be keyed on: nothing to record,
		// and every later check is a miss.
		long identity = world.identity();
		if (identity == 0L) {
			clearPoseFitCache();
			return;
		}
		// A fresh shorter entry cannot renew a still-valid taller one.
		if (this.poseFitWorld != 0L && this.poseFitXBits == xBits && this.poseFitYBits == yBits
		    && this.poseFitZBits == zBits && this.poseFitWidthBits == widthBits && this.poseFitHeight >= pose.height
		    && poseFitHolds(world)) {
			return;
		}
		this.poseFitWorld = identity;
		this.poseFitXBits = xBits;
		this.poseFitYBits = yBits;
		this.poseFitZBits = zBits;
		this.poseFitWidthBits = widthBits;
		this.poseFitHeight = pose.height;
		// Read last, over the span the fields above now describe. The span
		// revision is not read here: see carryPoseFitCache.
		this.poseFitSpanRevision = 0L;
		this.poseFitCollisionVersion = world.movementFactsImmutable() ? 0L : poseFitSpan(world, false);
	}

	/**
	 * The {@link WorldView#spanRevision} ({@code revision}) or {@link WorldView#collisionVersionIn} of the
	 * cells the recorded box can touch. The span is rebuilt from the entry's own record:
	 * {@code Float.intBitsToFloat} inverts {@code floatToRawIntBits} exactly and the height is held as a
	 * float already, so this reproduces the probe's box bit for bit. It uses the recorded height, which
	 * {@link #hasCachedPoseFit} allows to exceed the pose being asked about, so the span can only be too
	 * large. Shapes are cell-bounded wherever an entry can be recorded, so no cell outside this span can
	 * contribute a box that reaches the recorded one.
	 */
	private long poseFitSpan(final WorldView world, final boolean revision) {
		float halfWidth = Float.intBitsToFloat(this.poseFitWidthBits) / 2.0F;
		double cachedX = Double.longBitsToDouble(this.poseFitXBits);
		double cachedY = Double.longBitsToDouble(this.poseFitYBits);
		double cachedZ = Double.longBitsToDouble(this.poseFitZBits);
		double minX = cachedX - halfWidth + POSE_FIT_EPSILON;
		double minY = cachedY + POSE_FIT_EPSILON;
		double minZ = cachedZ - halfWidth + POSE_FIT_EPSILON;
		double maxX = cachedX + halfWidth - POSE_FIT_EPSILON;
		double maxY = cachedY + this.poseFitHeight - POSE_FIT_EPSILON;
		double maxZ = cachedZ + halfWidth - POSE_FIT_EPSILON;
		// The span collectCollisions scans for that box, derived the same way.
		int fromX = Mth.floor(minX - POSE_FIT_EPSILON) - 1;
		int fromY = Mth.floor(minY - POSE_FIT_EPSILON) - 1;
		int fromZ = Mth.floor(minZ - POSE_FIT_EPSILON) - 1;
		int toX = Mth.floor(maxX + POSE_FIT_EPSILON) + 1;
		int toY = Mth.floor(maxY + POSE_FIT_EPSILON) + 1;
		int toZ = Mth.floor(maxZ + POSE_FIT_EPSILON) + 1;
		return revision ? world.spanRevision(fromX, fromY, fromZ, toX, toY, toZ)
		                : world.collisionVersionIn(fromX, fromY, fromZ, toX, toY, toZ);
	}

	/** Records a collision-clear current pose only when the retained box exactly matches that pose. */
	public void cacheCurrentPoseAfterCollision(final WorldView world) {
		double halfWidth = this.pose.width / 2.0F;
		if (Double.doubleToRawLongBits(this.boundingBoxMinX) != Double.doubleToRawLongBits(this.x - halfWidth)
		    || Double.doubleToRawLongBits(this.boundingBoxMinY) != Double.doubleToRawLongBits(this.y)
		    || Double.doubleToRawLongBits(this.boundingBoxMinZ) != Double.doubleToRawLongBits(this.z - halfWidth)
		    || Double.doubleToRawLongBits(this.boundingBoxMaxX) != Double.doubleToRawLongBits(this.x + halfWidth)
		    || Double.doubleToRawLongBits(this.boundingBoxMaxY) != Double.doubleToRawLongBits(this.y + this.pose.height)
		    || Double.doubleToRawLongBits(this.boundingBoxMaxZ) != Double.doubleToRawLongBits(this.z + halfWidth)) {
			return;
		}
		cachePoseFit(world, this.pose);
	}

	/**
	 * Vanilla's player poses the simulator can reach.
	 *
	 * <p>{@link #id} is vanilla's own pose id, carried explicitly rather than taken from this enum's
	 * declaration order, so a trace names the vanilla pose and not a local ordinal.
	 */
	public enum Pose {
		/** Standing, 0.6 by 1.8 blocks. */
		STANDING(0, 0.6F, 1.8F, 1.62F),
		/** Gliding with an elytra, 0.6 by 0.6 blocks. */
		FALL_FLYING(1, 0.6F, 0.6F, 0.4F),
		/** Swimming, 0.6 by 0.6 blocks. */
		SWIMMING(3, 0.6F, 0.6F, 0.4F),
		/** Sneaking, 0.6 by 1.5 blocks. */
		CROUCHING(5, 0.6F, 1.5F, 1.27F);

		/** Vanilla's pose id. */
		public final int id;

		/** Collision box width, in blocks. */
		public final float width;

		/** Collision box height, in blocks. */
		public final float height;

		/** Eye height above the feet, in blocks. */
		public final float eyeHeight;

		Pose(final int id, final float width, final float height, final float eyeHeight) {
			this.id = id;
			this.width = width;
			this.height = height;
			this.eyeHeight = eyeHeight;
		}

		/**
		 * The modeled pose with vanilla's id.
		 *
		 * @throws IllegalArgumentException for an id the simulator does not model
		 */
		public static Pose fromVanillaId(final int id) {
			for (Pose pose : values()) {
				if (pose.id == id) {
					return pose;
				}
			}
			throw new IllegalArgumentException("unsupported vanilla pose id " + id);
		}
	}
}
