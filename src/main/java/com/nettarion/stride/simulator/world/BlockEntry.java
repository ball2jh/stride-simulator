package com.nettarion.stride.simulator.world;

import java.util.List;
import java.util.Objects;

/**
 * One distinct block state: its collision shape, surface coefficients and the
 * behavior facts the simulator reads from it.
 *
 * <p>Build one with {@link #builder}, which starts from an ordinary block's
 * vanilla defaults and names each fact. Immutable; the box list is copied.
 *
 * @param blockStateId vanilla's block-state registry id
 * @param name vanilla's block registry name, such as {@code minecraft:stone}
 * @param friction {@code Block.getFriction()}; 0.6 for an ordinary block
 * @param speedFactor {@code Block.getSpeedFactor()}; 1 for an ordinary block
 * @param jumpFactor {@code Block.getJumpFactor()}; 1 for an ordinary block
 * @param movingPiston whether the state is a moving piston, which the simulator refuses to compile
 * @param suppressesSupportingSpeedFactor whether the block's own speed factor replaces the supporting block's
 * @param bubbleColumnMode the bubble-column effect, if any
 * @param fallDistanceResetting whether the state is in {@code BlockTags.FALL_DAMAGE_RESETTING}
 * @param collisionBehavior how the collision shape depends on the player, if it does
 * @param suffocation {@code BlockState.isSuffocating}, or unknown when the capture did not record it
 * @param insideEffect the client-visible {@code entityInside} body, if any
 * @param bounceRestitution {@code Block.getBounceRestitution()}; 0 for no bounce
 * @param suppressesBounce whether the state is in {@code BlockTags.SUPPRESSES_BOUNCE}
 * @param stepOn the client-visible {@code stepOn} body, if any
 * @param retainsSupportPos whether {@code Entity.getOnPos} keeps this block's own Y
 * @param climbability the {@code onClimbable} role
 * @param shapeProvenance whether {@code boxes} is vanilla's canonical full cube by identity
 * @param contact the server-only contact body, if any
 * @param landing the {@code fallOn} body
 * @param boxes the collision shape in block-local coordinates, in vanilla's box order
 */
public record BlockEntry(int blockStateId, String name, float friction, float speedFactor, float jumpFactor,
    boolean movingPiston, boolean suppressesSupportingSpeedFactor, WorldView.BubbleColumnMode bubbleColumnMode,
    boolean fallDistanceResetting, WorldView.CollisionBehavior collisionBehavior, Suffocation suffocation,
    WorldView.InsideEffect insideEffect, float bounceRestitution, boolean suppressesBounce, WorldView.StepOn stepOn,
    boolean retainsSupportPos, WorldView.Climbability climbability, ShapeProvenance shapeProvenance,
    WorldView.Contact contact, WorldView.Landing landing, List<ShapeBox> boxes) {
	/** The friction of an ordinary block, {@code Block.getFriction()}'s default. */
	static final float DEFAULT_FRICTION = 0.6F;

	/**
	 * The canonical constructor, which the record requires to be public; prefer
	 * {@link #builder}, whose named setters cannot transpose the twenty-one
	 * positional components.
	 *
	 * <p>One fact is not taken as given: a water or powder-snow cauldron's
	 * {@code contact} is recorded as {@link WorldView.Contact#UNMODELED}
	 * whatever was supplied. Captures written before the server-only contact
	 * fact existed marked that {@code entityInside} body inert, and the block
	 * name alone proves the body exists, so client replay of such a capture
	 * keeps working while a server contact refuses instead of passing. See
	 * {@link #isCauldronWithUnmodeledContact}.
	 *
	 * @throws IllegalArgumentException when a coefficient is not finite, or a canonical full shape is
	 *     not exactly one unit cube
	 */
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
		if (isCauldronWithUnmodeledContact(name)) {
			contact = WorldView.Contact.UNMODELED;
		}
		Objects.requireNonNull(landing, "landing");
		if (!Float.isFinite(friction) || !Float.isFinite(speedFactor) || !Float.isFinite(jumpFactor)
		    || !Float.isFinite(bounceRestitution)) {
			throw new IllegalArgumentException("block coefficients must be finite");
		}
		boxes = List.copyOf(boxes);
		if (shapeProvenance == ShapeProvenance.CANONICAL_FULL && !boxes.equals(List.of(ShapeBox.FULL_CUBE))) {
			throw new IllegalArgumentException("canonical full collision shape must be exactly one unit cube");
		}
	}

	/**
	 * Whether a block name is one of the two layered cauldrons whose
	 * {@code entityInside} body the simulator does not model, so that any entry
	 * of that name carries {@link WorldView.Contact#UNMODELED} however it was
	 * built.
	 */
	public static boolean isCauldronWithUnmodeledContact(final String name) {
		return name.equals("minecraft:water_cauldron") || name.equals("minecraft:powder_snow_cauldron");
	}

	/**
	 * An entry captured before the contact and landing bodies were recorded:
	 * both are read from the block's name where the name decides them, and are
	 * {@code UNKNOWN} where the block state does, so a visit or landing there
	 * refuses instead of guessing.
	 *
	 * <p>Superseded by {@link #builder}; this
	 * constructor will become non-public once callers have moved.
	 */
	public BlockEntry(final int blockStateId, final String name, final float friction, final float speedFactor,
	    final float jumpFactor, final boolean movingPiston, final boolean suppressesSupportingSpeedFactor,
	    final WorldView.BubbleColumnMode bubbleColumnMode, final boolean fallDistanceResetting,
	    final WorldView.CollisionBehavior collisionBehavior, final Suffocation suffocation,
	    final WorldView.InsideEffect insideEffect, final float bounceRestitution, final boolean suppressesBounce,
	    final WorldView.StepOn stepOn, final boolean retainsSupportPos, final WorldView.Climbability climbability,
	    final ShapeProvenance shapeProvenance, final List<ShapeBox> boxes) {
		this(blockStateId, name, friction, speedFactor, jumpFactor, movingPiston, suppressesSupportingSpeedFactor,
		    bubbleColumnMode, fallDistanceResetting, collisionBehavior, suffocation, insideEffect, bounceRestitution,
		    suppressesBounce, stepOn, retainsSupportPos, climbability, shapeProvenance, legacyContact(name),
		    legacyLanding(name), boxes);
	}

	/**
	 * An entry captured before shape provenance was recorded, which is
	 * {@link ShapeProvenance#LEGACY_GEOMETRY}.
	 *
	 * <p>Superseded by {@link #builder}; this constructor will become
	 * non-public once callers have moved.
	 */
	public BlockEntry(final int blockStateId, final String name, final float friction, final float speedFactor,
	    final float jumpFactor, final boolean movingPiston, final boolean suppressesSupportingSpeedFactor,
	    final WorldView.BubbleColumnMode bubbleColumnMode, final boolean fallDistanceResetting,
	    final WorldView.CollisionBehavior collisionBehavior, final Suffocation suffocation,
	    final WorldView.InsideEffect insideEffect, final float bounceRestitution, final boolean suppressesBounce,
	    final WorldView.StepOn stepOn, final boolean retainsSupportPos, final WorldView.Climbability climbability,
	    final List<ShapeBox> boxes) {
		this(blockStateId, name, friction, speedFactor, jumpFactor, movingPiston, suppressesSupportingSpeedFactor,
		    bubbleColumnMode, fallDistanceResetting, collisionBehavior, suffocation, insideEffect, bounceRestitution,
		    suppressesBounce, stepOn, retainsSupportPos, climbability, ShapeProvenance.LEGACY_GEOMETRY, boxes);
	}

	/**
	 * An ordinary block whose suffocation fact is {@link Suffocation#UNKNOWN},
	 * so any wall built from it refuses when the player's column touches it.
	 *
	 * <p>Superseded by {@link #builder}; this constructor will become
	 * non-public once callers have moved.
	 */
	public BlockEntry(final int blockStateId, final String name, final float friction, final float speedFactor,
	    final float jumpFactor, final List<ShapeBox> boxes) {
		this(blockStateId, name, friction, speedFactor, jumpFactor, false, false, WorldView.BubbleColumnMode.NONE,
		    false, WorldView.CollisionBehavior.ORDINARY, Suffocation.UNKNOWN, WorldView.InsideEffect.NONE, 0.0F, false,
		    WorldView.StepOn.NONE, false, WorldView.Climbability.NONE, boxes);
	}

	/**
	 * The contact body a block name alone decides, for entries captured
	 * before the fact existed. Campfires and berry bushes depend on their
	 * state and are unknown; the blocks whose bodies are hazardous or
	 * undecided and outside the admitted domain are unmodeled.
	 */
	public static WorldView.Contact legacyContact(final String name) {
		return switch (name) {
			case "minecraft:cactus" -> WorldView.Contact.CACTUS;
			case "minecraft:magma_block" -> WorldView.Contact.HOT_FLOOR;
			case "minecraft:fire" -> WorldView.Contact.FIRE;
			case "minecraft:soul_fire" -> WorldView.Contact.SOUL_FIRE;
			case "minecraft:lava_cauldron" -> WorldView.Contact.LAVA_CAULDRON;
			case "minecraft:campfire", "minecraft:soul_campfire", "minecraft:sweet_berry_bush" ->
				WorldView.Contact.UNRECORDED;
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
			case "minecraft:pointed_dripstone" -> WorldView.Landing.UNRECORDED;
			case "minecraft:turtle_egg" -> WorldView.Landing.UNMODELED;
			default -> WorldView.Landing.ORDINARY;
		};
	}

	/**
	 * Names every movement fact of one block state, starting from the
	 * vanilla defaults an ordinary block carries.
	 *
	 * <p>The default suffocation is {@link Suffocation#UNKNOWN}: a wall built
	 * without {@link Builder#suffocation} refuses the moment the player's
	 * column touches it. {@link Builder#solid()} is the normal way to make an
	 * ordinary solid block.
	 */
	public static Builder builder(final int blockStateId, final String name) {
		return new Builder(blockStateId, name);
	}

	/**
	 * Equal entries hash equally, and cheaply: the state id and name decide
	 * the bucket, so merging the palettes of hundreds of sections into one
	 * snapshot does not hash every shape box of every entry each time.
	 * Distinct states share a bucket only when they share an id and a name,
	 * which is when the deep equality has to run anyway.
	 */
	@Override
	public int hashCode() {
		return 31 * this.blockStateId + this.name.hashCode();
	}

	/** Whether the collision shape has no box. */
	public boolean isEmpty() {
		return this.boxes.isEmpty();
	}

	/** Whether the capture observed vanilla's canonical full-cube shape by identity rather than by geometry. */
	public boolean canonicalFullCollisionShape() {
		return this.shapeProvenance == ShapeProvenance.CANONICAL_FULL;
	}

	/**
	 * Accumulates one {@link BlockEntry} by name rather than by position. Each
	 * setter returns this builder. Not thread-safe.
	 *
	 * <p>Every fact starts at an ordinary block's vanilla default, except that
	 * {@code suffocation} starts {@link Suffocation#UNKNOWN}, since it cannot
	 * be derived from the shape: a block with geometry and unknown suffocation
	 * refuses the first time the player's column touches it. Set it, or call
	 * {@link #solid()} for an ordinary solid block. The contact and landing
	 * bodies start at what the block name alone decides, see
	 * {@link BlockEntry#legacyContact} and {@link BlockEntry#legacyLanding}.
	 */
	public static final class Builder {
		private final int blockStateId;

		private final String name;

		private float friction = DEFAULT_FRICTION;

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

		private WorldView.Contact contact;

		private WorldView.Landing landing;

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

		/** Sets {@code Block.getFriction()}. */
		public Builder friction(final float value) {
			this.friction = value;
			return this;
		}

		/** Sets {@code Block.getSpeedFactor()}. */
		public Builder speedFactor(final float value) {
			this.speedFactor = value;
			return this;
		}

		/** Sets {@code Block.getJumpFactor()}. */
		public Builder jumpFactor(final float value) {
			this.jumpFactor = value;
			return this;
		}

		/** Declares the state a moving piston, which the simulator refuses to compile. */
		public Builder movingPiston(final boolean value) {
			this.movingPiston = value;
			return this;
		}

		/** Declares whether the block's own speed factor replaces the supporting block's. */
		public Builder suppressesSupportingSpeedFactor(final boolean value) {
			this.suppressesSupportingSpeedFactor = value;
			return this;
		}

		/** Declares the bubble-column effect. */
		public Builder bubbleColumnMode(final WorldView.BubbleColumnMode value) {
			this.bubbleColumnMode = value;
			return this;
		}

		/** Declares whether the state is in {@code BlockTags.FALL_DAMAGE_RESETTING}. */
		public Builder fallDistanceResetting(final boolean value) {
			this.fallDistanceResetting = value;
			return this;
		}

		/** Declares how the collision shape depends on the player. */
		public Builder collisionBehavior(final WorldView.CollisionBehavior value) {
			this.collisionBehavior = value;
			return this;
		}

		/** Declares {@code BlockState.isSuffocating}; see the class description for the default. */
		public Builder suffocation(final Suffocation value) {
			this.suffocation = value;
			return this;
		}

		/** Declares the client-visible {@code entityInside} body. */
		public Builder insideEffect(final WorldView.InsideEffect value) {
			this.insideEffect = value;
			return this;
		}

		/** Sets {@code Block.getBounceRestitution()}. */
		public Builder bounceRestitution(final float value) {
			this.bounceRestitution = value;
			return this;
		}

		/** Declares whether the state is in {@code BlockTags.SUPPRESSES_BOUNCE}. */
		public Builder suppressesBounce(final boolean value) {
			this.suppressesBounce = value;
			return this;
		}

		/** Declares the server-only contact body, {@code entityInside} or {@code stepOn}. */
		public Builder contact(final WorldView.Contact value) {
			this.contact = Objects.requireNonNull(value, "contact");
			return this;
		}

		/** Declares the {@code fallOn} body. */
		public Builder landing(final WorldView.Landing value) {
			this.landing = Objects.requireNonNull(value, "landing");
			return this;
		}

		/** Declares the client-visible {@code stepOn} body. */
		public Builder stepOn(final WorldView.StepOn value) {
			this.stepOn = value;
			return this;
		}

		/** Declares whether {@code Entity.getOnPos} keeps this block's own Y. */
		public Builder retainsSupportPos(final boolean value) {
			this.retainsSupportPos = value;
			return this;
		}

		/** Declares the {@code onClimbable} role, including a ladder's or open trapdoor's facing. */
		public Builder climbability(final WorldView.Climbability value) {
			this.climbability = value;
			return this;
		}

		/** Declares vanilla's canonical full-cube collision shape, by identity. Suffocation is unchanged. */
		public Builder fullCube() {
			this.shapeProvenance = ShapeProvenance.CANONICAL_FULL;
			this.boxes = List.of(ShapeBox.FULL_CUBE);
			return this;
		}

		/**
		 * An ordinary solid block: the canonical full cube that suffocates,
		 * like stone. Suffocation is set to {@link Suffocation#YES} unless a
		 * value was already declared, so a glass-like block declares
		 * {@link #suffocation} first.
		 */
		public Builder solid() {
			fullCube();
			if (this.suffocation == Suffocation.UNKNOWN) {
				this.suffocation = Suffocation.YES;
			}
			return this;
		}

		/** Declares collision boxes in block-local coordinates; list order is preserved. */
		public Builder boxes(final List<ShapeBox> value) {
			this.shapeProvenance = ShapeProvenance.GENERAL;
			this.boxes = List.copyOf(value);
			return this;
		}

		/** Declares collision boxes in block-local coordinates; argument order is preserved. */
		public Builder boxes(final ShapeBox... value) {
			return boxes(List.of(value));
		}

		/** Builds the entry from the declared facts. */
		public BlockEntry build() {
			return new BlockEntry(this.blockStateId, this.name, this.friction, this.speedFactor, this.jumpFactor,
			    this.movingPiston, this.suppressesSupportingSpeedFactor, this.bubbleColumnMode,
			    this.fallDistanceResetting, this.collisionBehavior, this.suffocation, this.insideEffect,
			    this.bounceRestitution, this.suppressesBounce, this.stepOn, this.retainsSupportPos, this.climbability,
			    this.shapeProvenance, this.contact, this.landing, this.boxes);
		}
	}
}
