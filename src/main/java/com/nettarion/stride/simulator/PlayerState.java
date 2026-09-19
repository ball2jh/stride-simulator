package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * The client's copy of everything about the player that affects movement, as a
 * flat mutable primitive structure the simulator steps in place.
 *
 * <p>This is a compact mutable primitive structure, forked by fixed-size
 * primitive copies. It carries the exact supported-slice state vector plus the
 * hidden fields the transition needs to be Markov-complete — notably the
 * *previous* tick's input, which {@code LocalPlayer.aiStep} reads before calling
 * {@code input.tick()}. What the client last told the server and what the server's
 * listener retains are not here: {@link Publisher} carries the client's beside
 * it and {@link ServerPlayerState} adds the server's to the server's copy in
 * {@link SimulationState}, so equality of two states is equality of movement
 * alone.
 *
 * <p>Imported instances are admitted only after {@link
 * #requireValidForTransition()} succeeds. Structural failures are malformed
 * input; a finite, structurally valid center outside the kernel's declared
 * coordinate domain is instead an {@link UnimplementedMechanicException} so a
 * caller can distinguish refusal from corrupt state.
 */
public class PlayerState {
	/** Set Entity's synchronized swimming flag. */
	public void setSwimming(final boolean value) {
		this.swimming = value;
	}

	/** Set LivingEntity's sprint state. */
	public void setSprinting(final boolean value) {
		this.sprinting = value;
		this.sprintingAttribute = value;
	}

	/** Set Entity's synchronized pose; the caller refreshes dimensions. */
	public void setPose(final Pose value) {
		this.pose = value;
	}

	/** Set Entity's synchronized frozen tick count. */
	public void setTicksFrozen(final int value) {
		this.ticksFrozen = value;
	}

	/** Set the admitted Entity shared flag by its upstream bit index. */
	public void setSharedFlag(final int flag, final boolean value) {
		switch (flag) {
			case 3 -> this.sprinting = value;
			case 4 -> this.swimming = value;
			case 7 -> this.fallFlying = value;
			default -> throw new IllegalArgumentException("unsupported shared flag: " + flag);
		}
	}

	private static final double MAX_HORIZONTAL_CENTER = 30_000_000.0;
	private static final double MAX_VERTICAL_CENTER = 20_000_000.0;
	/*
	 * Collision-resolved pose-fit certificate. Optimization metadata only: it
	 * is deliberately private, absent from StateDigest and absent from the
	 * audited trace state. Every use rechecks the proof's key and raw position
	 * bits, so a stale certificate can only become a cache miss.
	 *
	 * The proof is keyed on the {@link WorldView#spanRevision} of the cells
	 * the certified box can touch, when the world gives one: the revisions of
	 * the sections it read, which a republished corridor that kept those
	 * sections answers again, so the proof survives the publication. A world
	 * that gives none is named by its {@link WorldView#identity()} number and
	 * the span's collision version instead, and never held: a reference here
	 * once kept every superseded corridor alive through every retained
	 * state. Zero names no world, so a state never proven, or proven in a
	 * view without an identity, holds no certificate.
	 */
	private long poseFitWorld;
	private long poseFitCollisionVersion;
	/**
	 * The span revision the proof stands under, read from the view it was
	 * taken in the first time another view asks, and zero until then: a proof
	 * used only where it was taken, which is nearly every proof, reads none.
	 * The world is named and not held, so a proof first asked about elsewhere
	 * after its view is gone cannot recover a revision and is a miss there.
	 */
	private long poseFitSpanRevision;
	private long poseFitXBits;
	private long poseFitYBits;
	private long poseFitZBits;
	private int poseFitWidthBits;
	private float poseFitHeight;
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
	/** Whether the previous horizontal collision met the upstream minor-collision rule. */
	public boolean minorHorizontalCollision;
	/** Vanilla's retained {@code Entity.mainSupportingBlockPos}. */
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
	/** Current EntityFluidInteraction tracker heights at this state boundary. */
	public double waterHeight;
	/** Tracked lava immersion height in blocks at this boundary. */
	public double lavaHeight;
	/** Current tracker eye flag; baseTick snapshots this before refreshing fluids. */
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
	public int ticksFrozen;
	/** Whether this player admits freezing; immunity must be supplied explicitly. */
	public boolean canFreeze = true;
	/** Synchronized frozen-tick value used to derive the movement-speed penalty. */
	public int frostSpeedTicks;
	/**
	 * Product of the synchronized non-sprint Movement Speed total multipliers.
	 * The supported inputs are vanilla's Speed and Slowness effects, whose
	 * amounts are the float {@code 0.2F} and {@code -0.15F} widened to double
	 * and then multiplied by {@code amplifier + 1} in double, as
	 * {@code MobEffect.AttributeTemplate.create} does; for amplifiers of two and
	 * up that differs from the float product. Live admission accepts exactly
	 * those values. Any other finite value is arithmetic the tick will perform,
	 * not a supported modifier.
	 */
	public double movementSpeedMultiplier = 1.0;
	/**
	 * Direct jump power added by Jump Boost, {@code 0.1F * (amplifier + 1.0F)}, the
	 * only supported additive jump input. Jump Boost's Safe Fall Distance
	 * modifier is not movement and is not carried here.
	 */
	public float jumpBoostPower;
	/** Equipment-derived capability read by powder-snow collision and climb lift. */
	public boolean canWalkOnPowderSnow;
	/** Movement-relevant client option read by LocalPlayer's sprint trigger. */
	public int sprintWindowTicks = 7;
	/** Retained LocalPlayer auto-jump setting used by updateAutoJump. */
	public boolean autoJumpEnabled;
	/** Dimension-owned EnvironmentAttributes.FAST_LAVA value. */
	public boolean fastLava;
	/** Whether the abilities permit creative flight; this is separate from active flight. */
	public boolean mayfly;
	/** Whether creative flight travel is currently active. */
	public boolean flying;
	/** Ability-owned creative flight speed, normally {@code 0.05F}. */
	public float flyingSpeed = 0.05F;
	/** Current sprint flag; use {@link #setSprinting} when also changing its speed attribute. */

	public boolean sprinting;
	/** Whether MOVEMENT_SPEED currently has LivingEntity.SPEED_MODIFIER_SPRINTING. */
	public boolean sprintingAttribute;
	/** FoodData.getFoodLevel on this side, controlling exhaustive manoeuvre eligibility. */
	public int foodLevel = 20;
	/** Synchronized swimming flag, distinct from the current pose. */
	public boolean swimming;
	/** Synchronized gliding flag; this is distinct from creative flight. */
	public boolean fallFlying;
	/** Consecutive ticks retained by the fall-flying state machine. */
	public int fallFlyTicks;
	/** Whether the declared equipment can start or sustain gliding at this boundary. */
	public boolean gliderUsable;
	/** Entity shared-flag bits not represented by named movement flags. */
	public byte sharedFlagsResidual;
	/** Current player pose, which controls collision-box dimensions. */

	public Pose pose = Pose.STANDING;
	/** Local lateral movement input consumed by travel; positive values steer left. */

	public float xxa;
	/** Local vertical movement input consumed by travel. */
	public float yya;
	/** Local forward movement input consumed by travel. */
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
	 * Raw last-tick ClientInput. {@code aiStep} samples shift and forward before
	 * {@code input.tick()} overwrites them, so omitting these fields would make
	 * an arbitrary captured state impossible to fork exactly.
	 */
	public byte inputKeyPresses;
	/** Previous tick lateral input vector, retained before the next input refresh. */
	public float inputMoveVectorX;
	/** Previous tick forward input vector, retained before the next input refresh. */
	public float inputMoveVectorY;

	/** Returns an independent copy of the semantic state and reusable pose-fit metadata. */
	public PlayerState copy() {
		PlayerState copy = new PlayerState();
		copyInto(copy);
		return copy;
	}

	/** Raw-bit equality of every semantic field. */
	public static boolean rawEquals(final PlayerState a, final PlayerState b) {
		return StateFields.rawEquals(a, b);
	}

	/**
	 * Copy every semantic field into retained caller-owned storage.
	 *
	 * <p>Search and ML batches should preallocate their state slots and use this
	 * method instead of allocating one object per fork. Source and destination
	 * may be the same object.
	 */
	public void copyInto(final PlayerState copy) {
		StateFields.copy(copy, this);
		copyPoseFitCertificateInto(copy);
	}

	/** Returns an immutable copy of the retained collision box. */
	public AABB box() {
		return new AABB(this.boundingBoxMinX, this.boundingBoxMinY, this.boundingBoxMinZ, this.boundingBoxMaxX,
		    this.boundingBoxMaxY, this.boundingBoxMaxZ);
	}

	/** Replaces the collision box and invalidates cached pose-fit evidence; position and pose are unchanged. */
	public void setBox(final AABB box) {
		clearPoseFitCertificate();
		this.boundingBoxMinX = box.minX();
		this.boundingBoxMinY = box.minY();
		this.boundingBoxMinZ = box.minZ();
		this.boundingBoxMaxX = box.maxX();
		this.boundingBoxMaxY = box.maxY();
		this.boundingBoxMaxZ = box.maxZ();
	}

	/**
	 * Copy the exact position and player box from another state.
	 *
	 * <p>Authority and import code must move these nine correlated fields as one
	 * value. This operation deliberately leaves velocity, contact flags and pose
	 * unchanged. Call {@link #requireValidForTransition()} after combining state
	 * from independent sources.
	 */
	public void copyLocationFrom(final PlayerState source) {
		if (source == null) {
			throw new NullPointerException("source");
		}
		clearPoseFitCertificate();
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
	 * Place the player and rebuild its box from the current pose.
	 *
	 * <p>This is state initialization or an external authority event, not a
	 * movement tick. Keeping it on the state preserves the position/box
	 * invariant without widening {@link com.nettarion.stride.simulator.tick.ClientTick}'s transition interface.
	 */
	public void placeAt(final double x, final double y, final double z) {
		clearPoseFitCertificate();
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
	 * Applies an observed velocity publication to this client copy as the
	 * client applies one: the three components replace the delta movement
	 * and nothing else moves. This is the same application a predicted
	 * {@link HurtMotion} makes, so a verifier re-anchoring on an arrived
	 * packet reaches the state the simulator would have.
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
	 * Applies an observed entity-data publication to this client copy exactly
	 * as the client applies one: the sprint, swimming and gliding bits of the
	 * shared flags when present, the pose with its bounding box when present
	 * and modelled, and the frozen ticks when present. This is the same
	 * application {@link EntityDataWrite#applyToDigestedState} makes for a
	 * predicted publication, so a verifier explaining an observation with an
	 * echo reaches the state the simulator would have. The sprint attribute
	 * modifier is untouched: the client changes it only through
	 * {@code LivingEntity.setSprinting} or the server's attribute packet.
	 * A pose the simulator does not model leaves the copy as it was.
	 */
	public void applyObservedEntityData(final java.util.OptionalInt sharedFlags, final java.util.Optional<String> pose,
	    final java.util.OptionalInt ticksFrozen) {
		if (sharedFlags.isPresent()) {
			int flags = sharedFlags.getAsInt();
			this.sprinting = (flags & 8) != 0;
			this.swimming = (flags & 16) != 0;
			this.fallFlying = (flags & 128) != 0;
		}
		if (pose.isPresent()) {
			for (Pose known : Pose.values()) {
				if (known.name().equals(pose.get())) {
					this.pose = known;
					placeAt(this.x, this.y, this.z);
				}
			}
		}
		if (ticksFrozen.isPresent()) this.ticksFrozen = ticksFrozen.getAsInt();
	}

	/**
	 * Reject malformed state before it enters a transition or retained search.
	 *
	 * <p>The planner's copied states are trusted after admission, so this is not
	 * called from every hot tick. Importers and authority joins call it once after
	 * assembling a state. Raw equality matters here: the kernel reads position
	 * and box independently, and a one-bit disagreement can change collision or
	 * traversal.
	 *
	 * @throws IllegalStateException when fields are non-finite, missing,
	 *         inverted, or inconsistent with position and pose
	 * @throws UnimplementedMechanicException when the center is structurally
	 *         valid but outside the exact coordinate domain
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
	 * Whether an ordinary movement center is inside the exact kernel slice.
	 *
	 * <p>The admitted closed intervals are [-30,000,000, 30,000,000] for X and Z
	 * and [-20,000,000, 20,000,000] for Y. All coordinates must be finite. Both
	 * the initial center and every committed successor center must satisfy this
	 * predicate; the player's collision box is allowed to extend beyond it.
	 */
	public static boolean supportsCenterPosition(final double x, final double y, final double z) {
		return supportsHorizontalPosition(x, z) && Double.isFinite(y) && y >= -MAX_VERTICAL_CENTER
		    && y <= MAX_VERTICAL_CENTER;
	}

	/**
	 * Whether finite X/Z lie in the closed
	 * [-30,000,000, 30,000,000] ordinary-center slice.
	 */
	public static boolean supportsHorizontalPosition(final double x, final double z) {
		return Double.isFinite(x) && Double.isFinite(z) && x >= -MAX_HORIZONTAL_CENTER && x <= MAX_HORIZONTAL_CENTER
		    && z >= -MAX_HORIZONTAL_CENTER && z <= MAX_HORIZONTAL_CENTER;
	}

	/** Refuses a center outside the supported coordinate domain; does not validate the remaining state. */
	public static void requireSupportedCenter(final double x, final double y, final double z) {
		if (!supportsCenterPosition(x, y, z)) {
			throw UnimplementedMechanicException.deferred(Refusal.INADMISSIBLE_COORDINATE,
			    () -> "movement center lies outside the exact kernel coordinate domain: " + x + "," + y + "," + z);
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

	/**
	 * Invalidate the certificate. The height is the field every test of the
	 * certificate compares against a pose, so a negative one fails them all;
	 * the world reference is kept, since the next proof is almost always in the
	 * same world and would otherwise allocate a fresh reference every tick.
	 */
	void clearPoseFitCertificate() {
		this.poseFitHeight = -1.0F;
	}

	/** Returns whether a retained pose-fit proof still covers this position, pose, and world revision. */
	public boolean hasPoseFitCertificate(final WorldView world, final Pose pose) {
		return this.poseFitWorld != 0L && this.poseFitXBits == Double.doubleToRawLongBits(this.x)
		    && this.poseFitYBits == Double.doubleToRawLongBits(this.y)
		    && this.poseFitZBits == Double.doubleToRawLongBits(this.z)
		    && this.poseFitWidthBits == Float.floatToRawIntBits(pose.width) && this.poseFitHeight >= pose.height
		    && proofHolds(world);
	}

	/**
	 * Whether the recorded proof holds in {@code world}: by span revision when
	 * the proof has one and the world answers the same, else by the world's
	 * identity and, unless it is immutable, the span's collision version.
	 */
	private boolean proofHolds(final WorldView world) {
		long identity = world.identity();
		if (identity != 0L && this.poseFitWorld == identity) {
			// The very view the proof was taken in: immutable, nothing to read;
			// mutable, the span's version says whether an edit reached it.
			return world.movementFactsImmutable() || this.poseFitCollisionVersion == certifiedSpanVersion(world);
		}
		// Another view: the proof holds there when it answers the same span
		// revision, which a republication that kept the sections does.
		return this.poseFitSpanRevision != 0L && this.poseFitSpanRevision == certifiedSpanRevision(world);
	}

	/**
	 * Carry the proof across a publication: record, from the view it was
	 * taken in, the span revision it stands under, so a later check in
	 * another view can honour it. Nothing is read when the proof is absent,
	 * already carried, or stale in its own view. A consumer calls this on the
	 * states it keeps across a republication; the tick never does.
	 */
	public void carryPoseFitCertificate(final WorldView world) {
		if (this.poseFitSpanRevision != 0L || this.poseFitWorld == 0L || this.poseFitHeight < 0.0F) {
			return;
		}
		long identity = world.identity();
		if (identity == 0L || this.poseFitWorld != identity) {
			return;
		}
		if (!world.movementFactsImmutable() && this.poseFitCollisionVersion != certifiedSpanVersion(world)) {
			return;
		}
		this.poseFitSpanRevision = certifiedSpanRevision(world);
	}

	/** {@link WorldView#spanRevision} over the certified span, see {@link #certifiedSpanVersion}. */
	private long certifiedSpanRevision(final WorldView world) {
		float halfWidth = Float.intBitsToFloat(this.poseFitWidthBits) / 2.0F;
		double certifiedX = Double.longBitsToDouble(this.poseFitXBits);
		double certifiedY = Double.longBitsToDouble(this.poseFitYBits);
		double certifiedZ = Double.longBitsToDouble(this.poseFitZBits);
		double minX = certifiedX - halfWidth + 1.0E-7;
		double minY = certifiedY + 1.0E-7;
		double minZ = certifiedZ - halfWidth + 1.0E-7;
		double maxX = certifiedX + halfWidth - 1.0E-7;
		double maxY = certifiedY + this.poseFitHeight - 1.0E-7;
		double maxZ = certifiedZ + halfWidth - 1.0E-7;
		return world.spanRevision(Mth.floor(minX - 1.0E-7) - 1, Mth.floor(minY - 1.0E-7) - 1,
		    Mth.floor(minZ - 1.0E-7) - 1, Mth.floor(maxX + 1.0E-7) + 1, Mth.floor(maxY + 1.0E-7) + 1,
		    Mth.floor(maxZ + 1.0E-7) + 1);
	}

	/**
	 * Version of the cells the certified box can touch.
	 *
	 * <p>The span is rebuilt from the certificate's own record rather than stored
	 * beside it: {@code Float.intBitsToFloat} inverts {@code floatToRawIntBits}
	 * exactly, and the height is held as a float already, so this reproduces the
	 * probe's box bit for bit. It uses the *certified* height, which
	 * {@link #hasPoseFitCertificate} allows to exceed the pose being asked about,
	 * so the span is the taller one and can only be too large.
	 *
	 * <p>Shapes are cell-bounded wherever a certificate can be issued — issuing is
	 * gated on {@code !hasContextSensitiveCollision} — so no cell outside this
	 * span can contribute a box that reaches the certified one, and a write out
	 * there cannot make the proof false.
	 */
	private long certifiedSpanVersion(final WorldView world) {
		float halfWidth = Float.intBitsToFloat(this.poseFitWidthBits) / 2.0F;
		double certifiedX = Double.longBitsToDouble(this.poseFitXBits);
		double certifiedY = Double.longBitsToDouble(this.poseFitYBits);
		double certifiedZ = Double.longBitsToDouble(this.poseFitZBits);
		double minX = certifiedX - halfWidth + 1.0E-7;
		double minY = certifiedY + 1.0E-7;
		double minZ = certifiedZ - halfWidth + 1.0E-7;
		double maxX = certifiedX + halfWidth - 1.0E-7;
		double maxY = certifiedY + this.poseFitHeight - 1.0E-7;
		double maxZ = certifiedZ + halfWidth - 1.0E-7;
		// The span collectCollisions scans for that box, derived the same way.
		return world.collisionVersionIn(Mth.floor(minX - 1.0E-7) - 1, Mth.floor(minY - 1.0E-7) - 1,
		    Mth.floor(minZ - 1.0E-7) - 1, Mth.floor(maxX + 1.0E-7) + 1, Mth.floor(maxY + 1.0E-7) + 1,
		    Mth.floor(maxZ + 1.0E-7) + 1);
	}

	/** Records a pose-fit proof already established by the caller; this method does not perform a collision query. */
	public void certifyPoseFit(final WorldView world, final Pose pose) {
		long xBits = Double.doubleToRawLongBits(this.x);
		long yBits = Double.doubleToRawLongBits(this.y);
		long zBits = Double.doubleToRawLongBits(this.z);
		int widthBits = Float.floatToRawIntBits(pose.width);
		// A view without an identity cannot be proven against: nothing to
		// record, and every later test of the certificate is a miss.
		long identity = world.identity();
		if (identity == 0L) {
			clearPoseFitCertificate();
			return;
		}
		// A fresh shorter proof cannot renew an invalid taller certificate.
		if (this.poseFitWorld != 0L && this.poseFitXBits == xBits && this.poseFitYBits == yBits
		    && this.poseFitZBits == zBits && this.poseFitWidthBits == widthBits && this.poseFitHeight >= pose.height
		    && proofHolds(world)) {
			return;
		}
		this.poseFitWorld = identity;
		this.poseFitXBits = xBits;
		this.poseFitYBits = yBits;
		this.poseFitZBits = zBits;
		this.poseFitWidthBits = widthBits;
		this.poseFitHeight = pose.height;
		// Read last, over the span the fields above now describe. The span
		// revision is not read here: see carryPoseFitCertificate.
		this.poseFitSpanRevision = 0L;
		this.poseFitCollisionVersion = world.movementFactsImmutable() ? 0L : certifiedSpanVersion(world);
	}

	/** Records a caller-established collision-clear pose only when the retained box exactly matches that pose. */
	public void certifyCurrentPoseAfterCollision(final WorldView world) {
		double halfWidth = this.pose.width / 2.0F;
		if (Double.doubleToRawLongBits(this.boundingBoxMinX) != Double.doubleToRawLongBits(this.x - halfWidth)
		    || Double.doubleToRawLongBits(this.boundingBoxMinY) != Double.doubleToRawLongBits(this.y)
		    || Double.doubleToRawLongBits(this.boundingBoxMinZ) != Double.doubleToRawLongBits(this.z - halfWidth)
		    || Double.doubleToRawLongBits(this.boundingBoxMaxX) != Double.doubleToRawLongBits(this.x + halfWidth)
		    || Double.doubleToRawLongBits(this.boundingBoxMaxY) != Double.doubleToRawLongBits(this.y + this.pose.height)
		    || Double.doubleToRawLongBits(this.boundingBoxMaxZ) != Double.doubleToRawLongBits(this.z + halfWidth)) {
			return;
		}
		certifyPoseFit(world, this.pose);
	}

	/**
	 * The certificate travels with the copy as recorded. It cannot be
	 * revalidated here, since the world is named and not held; every use
	 * revalidates against the world it is asked about, so a copy of a stale
	 * certificate is a miss there, exactly as the original would be.
	 */
	private void copyPoseFitCertificateInto(final PlayerState copy) {
		copy.poseFitWorld = this.poseFitWorld;
		copy.poseFitCollisionVersion = this.poseFitCollisionVersion;
		copy.poseFitSpanRevision = this.poseFitSpanRevision;
		copy.poseFitXBits = this.poseFitXBits;
		copy.poseFitYBits = this.poseFitYBits;
		copy.poseFitZBits = this.poseFitZBits;
		copy.poseFitWidthBits = this.poseFitWidthBits;
		copy.poseFitHeight = this.poseFitHeight;
	}

	private static boolean raw(final double a, final double b) {
		return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
	}

	private static boolean raw(final float a, final float b) {
		return Float.floatToRawIntBits(a) == Float.floatToRawIntBits(b);
	}

	/**
	 * Vanilla's player poses that the current exact movement implementation can reach.
	 *
	 * <p>{@link #id} is vanilla's own pose id, carried explicitly rather than
	 * relying on this enum's declaration order. The audited fact is which
	 * vanilla pose the player is in, and a kernel-local ordinal that happened
	 * to differ would compare as a divergence while naming the same pose.
	 */
	public enum Pose {
		STANDING(0, 0.6F, 1.8F, 1.62F),
		FALL_FLYING(1, 0.6F, 0.6F, 0.4F),
		SWIMMING(3, 0.6F, 0.6F, 0.4F),
		CROUCHING(5, 0.6F, 1.5F, 1.27F);

		public final int id;
		public final float width;
		public final float height;
		public final float eyeHeight;

		Pose(final int id, final float width, final float height, final float eyeHeight) {
			this.id = id;
			this.width = width;
			this.height = height;
			this.eyeHeight = eyeHeight;
		}

		/** Returns the supported pose with the upstream identifier, refusing unsupported identifiers. */
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
