package com.nettarion.stride.simulator.world;

import java.util.List;
import java.util.Objects;

/** One distinct block state, with everything the kernel needs from it. */
public record BlockEntry(int blockStateId, String name, float friction, float speedFactor, float jumpFactor,
    boolean movingPiston, boolean suppressesSupportingSpeedFactor, WorldView.BubbleColumnMode bubbleColumnMode,
    boolean fallDistanceResetting, WorldView.CollisionBehavior collisionBehavior, Suffocation suffocation,
    WorldView.InsideEffect insideEffect, float bounceRestitution, boolean suppressesBounce, WorldView.StepOn stepOn,
    boolean retainsSupportPos, WorldView.Climbability climbability, ShapeProvenance shapeProvenance,
    WorldView.Contact contact, WorldView.Landing landing, List<ShapeBox> boxes) {
	/**
	 * Equal entries hash equally, and cheaply: the state id and name decide
	 * the bucket, so merging the palettes of hundreds of sections into one
	 * publication does not hash every shape box of every entry each time.
	 * Distinct states share a bucket only when they share an id and a name,
	 * which is when the deep equality has to run anyway.
	 */
	@Override
	public int hashCode() {
		return 31 * this.blockStateId + this.name.hashCode();
	}

	public BlockEntry {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(bubbleColumnMode, "bubbleColumnMode");
		Objects.requireNonNull(collisionBehavior, "collisionBehavior");
		Objects.requireNonNull(suffocation, "suffocation");
		Objects.requireNonNull(insideEffect, "insideEffect");
		Objects.requireNonNull(stepOn, "stepOn");
		Objects.requireNonNull(climbability, "climbability");
		Objects.requireNonNull(shapeProvenance, "shapeProvenance");
		Objects.requireNonNull(contact, "contact");
		// Older captures marked this server hook inert. The block name proves
		// that omission; retain client replay but refuse server contact.
		if (name.equals("minecraft:water_cauldron") || name.equals("minecraft:powder_snow_cauldron")) {
			contact = WorldView.Contact.UNMODELED;
		}
		Objects.requireNonNull(landing, "landing");
		if (!Float.isFinite(friction) || !Float.isFinite(speedFactor) || !Float.isFinite(jumpFactor)
		    || !Float.isFinite(bounceRestitution)) {
			throw new IllegalArgumentException("block coefficients must be finite");
		}
		boxes = List.copyOf(boxes);
		if (shapeProvenance == ShapeProvenance.CANONICAL_FULL
		    && !boxes.equals(List.of(ShapeBox.FULL_CUBE))) {
			throw new IllegalArgumentException("canonical full collision shape must be exactly one unit cube");
		}
	}

	/**
	 * Compatibility constructor for entries created before the contact and
	 * landing bodies were captured: both are read from the block's name
	 * where the name decides them, and are {@code UNKNOWN} where the
	 * block state does, so a visit or landing there refuses instead of
	 * guessing. New capture code supplies the bodies from the implementing
	 * class and state.
	 */
	public BlockEntry(final int blockStateId, final String name, final float friction, final float speedFactor,
	    final float jumpFactor, final boolean movingPiston, final boolean suppressesSupportingSpeedFactor,
	    final WorldView.BubbleColumnMode bubbleColumnMode, final boolean fallDistanceResetting,
	    final WorldView.CollisionBehavior collisionBehavior, final Suffocation suffocation,
	    final WorldView.InsideEffect insideEffect, final float bounceRestitution, final boolean suppressesBounce,
	    final WorldView.StepOn stepOn, final boolean retainsSupportPos, final WorldView.Climbability climbability,
	    final ShapeProvenance shapeProvenance, final List<ShapeBox> boxes) {
		this(blockStateId, name, friction, speedFactor, jumpFactor, movingPiston, suppressesSupportingSpeedFactor,
		    bubbleColumnMode, fallDistanceResetting, collisionBehavior, suffocation, insideEffect,
		    bounceRestitution, suppressesBounce, stepOn, retainsSupportPos, climbability, shapeProvenance,
		    legacyContact(name), legacyLanding(name), boxes);
	}

	/**
	 * The contact body a block name alone decides, for entries captured
	 * before the fact existed. Campfires and berry bushes depend on their
	 * state and are unknown; the classes the classification lists as
	 * hazardous or undecided and the slice does not model are unmodeled.
	 */
	public static WorldView.Contact legacyContact(final String name) {
		return switch (name) {
			case "minecraft:cactus" -> WorldView.Contact.CACTUS;
			case "minecraft:magma_block" -> WorldView.Contact.HOT_FLOOR;
			case "minecraft:fire" -> WorldView.Contact.FIRE;
			case "minecraft:soul_fire" -> WorldView.Contact.SOUL_FIRE;
			case "minecraft:lava_cauldron" -> WorldView.Contact.LAVA_CAULDRON;
			case "minecraft:campfire", "minecraft:soul_campfire", "minecraft:sweet_berry_bush" ->
				WorldView.Contact.UNKNOWN;
			case "minecraft:water_cauldron", "minecraft:powder_snow_cauldron", "minecraft:wither_rose",
			    "minecraft:sculk_sensor", "minecraft:calibrated_sculk_sensor", "minecraft:sculk_shrieker",
			    "minecraft:turtle_egg", "minecraft:big_dripleaf", "minecraft:end_gateway", "minecraft:end_portal",
			    "minecraft:nether_portal", "minecraft:moving_piston" ->
				WorldView.Contact.UNMODELED;
			default -> WorldView.Contact.NONE;
		};
	}

	/** The landing body a block name alone decides; a dripstone's tip is state and unknown. */
	public static WorldView.Landing legacyLanding(final String name) {
		if (name.endsWith("_bed")) {
			return WorldView.Landing.BED;
		}
		return switch (name) {
			case "minecraft:hay_block" -> WorldView.Landing.HAY;
			case "minecraft:honey_block" -> WorldView.Landing.HONEY;
			case "minecraft:slime_block" -> WorldView.Landing.SLIME;
			case "minecraft:farmland" -> WorldView.Landing.FARMLAND;
			case "minecraft:powder_snow" -> WorldView.Landing.POWDER_SNOW;
			case "minecraft:pointed_dripstone" -> WorldView.Landing.UNKNOWN;
			case "minecraft:turtle_egg" -> WorldView.Landing.UNMODELED;
			default -> WorldView.Landing.ORDINARY;
		};
	}

	/**
	 * Compatibility constructor for snapshots created before source shape
	 * identity was represented. New capture code supplies explicit provenance.
	 */
	public BlockEntry(final int blockStateId, final String name, final float friction, final float speedFactor,
	    final float jumpFactor, final boolean movingPiston, final boolean suppressesSupportingSpeedFactor,
	    final WorldView.BubbleColumnMode bubbleColumnMode, final boolean fallDistanceResetting,
	    final WorldView.CollisionBehavior collisionBehavior, final Suffocation suffocation,
	    final WorldView.InsideEffect insideEffect, final float bounceRestitution, final boolean suppressesBounce,
	    final WorldView.StepOn stepOn, final boolean retainsSupportPos, final WorldView.Climbability climbability,
	    final List<ShapeBox> boxes) {
		this(blockStateId, name, friction, speedFactor, jumpFactor, movingPiston, suppressesSupportingSpeedFactor,
		    bubbleColumnMode, fallDistanceResetting, collisionBehavior, suffocation, insideEffect,
		    bounceRestitution, suppressesBounce, stepOn, retainsSupportPos, climbability,
		    ShapeProvenance.LEGACY_GEOMETRY, boxes);
	}

	/** Creates an ordinary block whose suffocation fact has not been captured. */
	public BlockEntry(final int blockStateId, final String name, final float friction, final float speedFactor,
	    final float jumpFactor, final List<ShapeBox> boxes) {
		this(blockStateId, name, friction, speedFactor, jumpFactor, false, false, WorldView.BubbleColumnMode.NONE,
		    false, WorldView.CollisionBehavior.ORDINARY, Suffocation.UNKNOWN, WorldView.InsideEffect.NONE, 0.0F,
		    false, WorldView.StepOn.NONE, false, WorldView.Climbability.NONE, boxes);
	}

	public boolean isEmpty() {
		return this.boxes.isEmpty();
	}

	/** Whether capture proved canonical singleton identity rather than inferring it. */
	public boolean canonicalFullCollisionShape() {
		return this.shapeProvenance == ShapeProvenance.CANONICAL_FULL;
	}

	/**
	 * Names every movement fact of one block state, starting from the
	 * vanilla defaults an ordinary block carries.
	 *
	 * <p>The canonical constructor takes nineteen positional components
	 * whose booleans and enums are easy to transpose silently. Fixtures
	 * state only the facts that distinguish the block they mean.
	 */
	public static Builder builder(final int blockStateId, final String name) {
		return new Builder(blockStateId, name);
	}

	/** Accumulates one {@link BlockEntry} by name rather than by position. */
	public static final class Builder {
		private final int blockStateId;
		private final String name;
		private float friction = 0.6F;
		private float speedFactor = 1.0F;
		private float jumpFactor = 1.0F;
		private boolean movingPiston;
		private boolean suppressesSupportingSpeedFactor;
		private WorldView.BubbleColumnMode bubbleColumnMode = WorldView.BubbleColumnMode.NONE;
		private boolean fallDistanceResetting;
		private WorldView.CollisionBehavior collisionBehavior = WorldView.CollisionBehavior.ORDINARY;
		private Suffocation suffocation = Suffocation.UNKNOWN;
		private WorldView.InsideEffect insideEffect = WorldView.InsideEffect.NONE;
		private float bounceRestitution;
		private boolean suppressesBounce;
		private WorldView.StepOn stepOn = WorldView.StepOn.NONE;
		private WorldView.Contact contact = WorldView.Contact.NONE;
		private WorldView.Landing landing = WorldView.Landing.ORDINARY;
		private boolean retainsSupportPos;
		private WorldView.Climbability climbability = WorldView.Climbability.NONE;
		private ShapeProvenance shapeProvenance = ShapeProvenance.GENERAL;
		private List<ShapeBox> boxes = List.of();

		private Builder(final int blockStateId, final String name) {
			this.blockStateId = blockStateId;
			this.name = Objects.requireNonNull(name, "name");
			this.contact = legacyContact(name);
			this.landing = legacyLanding(name);
		}

		/** Sets the captured friction coefficient used by movement on this block. Returns this builder. */
		public Builder friction(final float value) {
			this.friction = value;
			return this;
		}

		/** Sets the captured movement-speed multiplier of the block. Returns this builder. */
		public Builder speedFactor(final float value) {
			this.speedFactor = value;
			return this;
		}

		/** Sets the captured multiplier applied when jumping from this block. Returns this builder. */
		public Builder jumpFactor(final float value) {
			this.jumpFactor = value;
			return this;
		}

		/** Declares the moving-piston collision fact for this palette entry. Returns this builder. */
		public Builder movingPiston(final boolean value) {
			this.movingPiston = value;
			return this;
		}

		/** Declares whether this entry suppresses the supporting block's speed factor. Returns this builder. */
		public Builder suppressesSupportingSpeedFactor(final boolean value) {
			this.suppressesSupportingSpeedFactor = value;
			return this;
		}

		/** Declares the captured bubble-column push or drag mode. Returns this builder. */
		public Builder bubbleColumnMode(final WorldView.BubbleColumnMode value) {
			this.bubbleColumnMode = value;
			return this;
		}

		/** Declares whether this block resets fall distance along the admitted fall-reset query. Returns this builder. */
		public Builder fallDistanceResetting(final boolean value) {
			this.fallDistanceResetting = value;
			return this;
		}

		/** Declares the context-dependent collision behavior for this block state. Returns this builder. */
		public Builder collisionBehavior(final WorldView.CollisionBehavior value) {
			this.collisionBehavior = value;
			return this;
		}

		/** Declares the captured suffocation answer for this block state. Returns this builder. */
		public Builder suffocation(final Suffocation value) {
			this.suffocation = value;
			return this;
		}

		/** Declares the client-visible entity-inside behavior. Returns this builder. */
		public Builder insideEffect(final WorldView.InsideEffect value) {
			this.insideEffect = value;
			return this;
		}

		/** Declares the velocity restitution applied by this block's bounce rule. Returns this builder. */
		public Builder bounceRestitution(final float value) {
			this.bounceRestitution = value;
			return this;
		}

		/** Declares whether landing on this entry suppresses the normal bounce behavior. Returns this builder. */
		public Builder suppressesBounce(final boolean value) {
			this.suppressesBounce = value;
			return this;
		}

		/** The server-only contact body, {@code entityInside} or {@code stepOn}. */
		public Builder contact(final WorldView.Contact value) {
			this.contact = Objects.requireNonNull(value, "contact");
			return this;
		}

		/** The {@code fallOn} body. */
		public Builder landing(final WorldView.Landing value) {
			this.landing = Objects.requireNonNull(value, "landing");
			return this;
		}

		/** Declares the client movement behavior applied to a player stepping on this block. Returns this builder. */
		public Builder stepOn(final WorldView.StepOn value) {
			this.stepOn = value;
			return this;
		}

		/** Declares whether the entry participates in retained support-position queries. Returns this builder. */
		public Builder retainsSupportPos(final boolean value) {
			this.retainsSupportPos = value;
			return this;
		}

		/** Declares the block's climbable role, including facing-dependent ladder or trapdoor facts. Returns this builder. */
		public Builder climbability(final WorldView.Climbability value) {
			this.climbability = value;
			return this;
		}

		/** The collision shape of an ordinary solid block. */
		public Builder fullCube() {
			this.shapeProvenance = ShapeProvenance.CANONICAL_FULL;
			this.boxes = List.of(ShapeBox.FULL_CUBE);
			return this;
		}

		/** Declares collision boxes in local block coordinates; list order is preserved. Returns this builder. */
		public Builder boxes(final List<ShapeBox> value) {
			this.shapeProvenance = ShapeProvenance.GENERAL;
			this.boxes = List.copyOf(value);
			return this;
		}

		public Builder boxes(final ShapeBox... value) {
			return boxes(List.of(value));
		}

		/** Builds a block entry from the declared geometry, coefficients, and behavior facts. */
		public BlockEntry build() {
			return new BlockEntry(this.blockStateId, this.name, this.friction, this.speedFactor, this.jumpFactor,
			    this.movingPiston, this.suppressesSupportingSpeedFactor, this.bubbleColumnMode,
			    this.fallDistanceResetting, this.collisionBehavior, this.suffocation, this.insideEffect,
			    this.bounceRestitution, this.suppressesBounce, this.stepOn, this.retainsSupportPos,
			    this.climbability, this.shapeProvenance, this.contact, this.landing, this.boxes);
		}
	}
}
