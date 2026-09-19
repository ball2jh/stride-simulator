package com.nettarion.stride.simulator.world;

import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.block.BlockBehavior;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.geometry.Mth;

/**
 * The read interface the simulator calls: every world fact a tick may ask for,
 * and nothing it may change.
 *
 * <p>This is the complete movement-fact seam for one admitted transition, not
 * a general level API. Its closure includes every collision shape and source
 * identity, fluid sample, support fact, block coefficient, traversal/contact
 * effect, pose fit, and context-sensitive block answer the kernel can query
 * from the initial state through its successor. Implementations must also keep
 * those answers coherent under the collision and immutability version
 * contracts below.
 *
 * <p>A default answer means the implementation has proved that fact absent in
 * its admitted region. It must not translate unavailable cells, unknown block
 * state, or unmodeled dynamic context into a neutral answer. Such a
 * transition is refused with {@link UnimplementedMechanicException}.
 */
public interface WorldView {
	/**
	 * Explicit acknowledgement that this view can resolve every movement fact the
	 * proposed transition may reach in the kernel's supported slice. The neutral
	 * defaults below are conveniences for complete implementations that can prove
	 * a fact absent; they are not a license for a partial adapter to turn an
	 * unknown fact into vanilla's default.
	 *
	 * <p>Every implementation must make this promise explicitly after auditing
	 * its answers. {@link com.nettarion.stride.simulator.tick.ClientTick} checks it before mutating caller state.
	 * Returning {@code false} causes a typed refusal; returning {@code true} while
	 * an answer is unavailable violates this interface.
	 */
	boolean movementFactsComplete();

	/** The vertical effect applied by a captured bubble-column state. */
	enum BubbleColumnMode { NONE, DRAG_DOWN, PUSH_UP }

	/** State-dependent collision rules that cannot be represented by static boxes alone. */
	enum CollisionBehavior {
		ORDINARY,
		SCAFFOLDING_SUPPORTED,
		SCAFFOLDING_UNSTABLE_BOTTOM,
		POWDER_SNOW_NO_BOOTS,
		/** Captured geometry whose server can change after player contact. */
		SERVER_MUTABLE_SUPPORT
	}

	/** Protocol carried by collision buffers returned from this view. */
	enum CollisionShapeProtocol {
		/** Each {@link CollisionBuffer#add} is only an independent box. */
		LEGACY_BOXES,
		/** Source-shape groups and canonical-full identity are both preserved. */
		SOURCE_GROUPS_V1
	}

	/** Resolved block-state role used by {@code LivingEntity.onClimbable}. */
	enum Climbability {
		NONE,
		CLIMBABLE,
		GLIDE_THROUGH,
		LADDER_NORTH,
		LADDER_EAST,
		LADDER_SOUTH,
		LADDER_WEST,
		OPEN_TRAPDOOR_NORTH,
		OPEN_TRAPDOOR_EAST,
		OPEN_TRAPDOOR_SOUTH,
		OPEN_TRAPDOOR_WEST
	}

	/** Which {@code Block.stepOn} body this cell runs when it is underfoot. */
	enum StepOn { NONE, SLIME }

	/**
	 * Which {@code entityInside} body the inside-block traversal runs at a cell
	 * when that body changes audited client state. Bubble columns and powder snow
	 * keep their own captured facts.
	 */
	enum InsideEffect { NONE, COBWEB, SWEET_BERRY_BUSH, HONEY, LAVA_CAULDRON }

	/**
	 * Which server-only contact body a cell runs on the player: the
	 * {@code entityInside} or {@code stepOn} that hurts, ignites, or refuses.
	 * The client-visible bodies are {@link InsideEffect} and {@link StepOn};
	 * this is what only the server's copy sees. A cell with a body the slice
	 * does not model refuses on contact rather than passing as inert, which
	 * is how an unmodeled hazard fails closed inside the tick.
	 */
	enum Contact {
		NONE,
		/** {@code CactusBlock.entityInside}: one point on every visit. */
		CACTUS,
		/** A grown {@code SweetBerryBushBlock}: one point while moving horizontally. */
		SWEET_BERRY_BUSH,
		/** {@code MagmaBlock.stepOn}: one point underfoot unless stepping carefully. */
		HOT_FLOOR,
		/** A lit {@code CampfireBlock}: one point on every visit. */
		CAMPFIRE,
		/** A lit soul campfire: two points on every visit. */
		SOUL_CAMPFIRE,
		/** {@code BaseFireBlock}: clear freeze, ignite, one point. */
		FIRE,
		/** {@code SoulFireBlock}: clear freeze, ignite, two points. */
		SOUL_FIRE,
		/** {@code LavaCauldronBlock}: clear freeze, lava ignition, four points. */
		LAVA_CAULDRON,
		/** A body the slice does not model; a visit refuses. */
		UNMODELED,
		/** A capture that predates this fact for a body that depends on block state; a visit refuses. */
		UNKNOWN
	}

	/**
	 * Which {@code Block.fallOn} body the landed cell runs: how it turns the
	 * fall distance into a hit.
	 */
	enum Landing {
		/** {@code Block.fallOn}: the fall at multiplier one. */
		ORDINARY,
		/** {@code HayBlock}: multiplier one fifth. */
		HAY,
		/** {@code HoneyBlock}: multiplier one fifth. */
		HONEY,
		/** {@code SlimeBlock}: multiplier zero, so never a hit. */
		SLIME,
		/** {@code BedBlock}: half the fall distance at multiplier one. */
		BED,
		/** An upward {@code PointedDripstoneBlock} tip: the fall plus two and a half, doubled. */
		STALAGMITE,
		/** {@code FarmlandBlock}: the ordinary hit, after a random trample past half a block. */
		FARMLAND,
		/** {@code PowderSnowBlock}: a sound and no hit. */
		POWDER_SNOW,
		/** A body the slice does not model; a landing refuses. */
		UNMODELED,
		/** A capture that predates this fact for a body that depends on block state; a landing refuses. */
		UNKNOWN
	}

	/**
	 * Version of every fact that can affect {@link #collectCollisionBoxes}.
	 * Mutable worlds must change this value before publishing changed collision
	 * geometry. Immutable worlds may return a constant. Cache users also compare
	 * the {@code WorldView} object by identity; equal versions across two world
	 * objects do not make their collision answers interchangeable.
	 */
	long collisionVersion();

	/**
	 * A number that names this exact object among every view alive in this
	 * process, so retained proofs can be keyed to the world they were proven in
	 * without holding it.
	 *
	 * <p>Two views never share a non-zero identity, and a fork is a new view
	 * with a new one. Zero is the identity of a view that declines to be
	 * proven against: every retained proof keyed to it is a miss, so a view
	 * that does not implement this keeps the ordinary, unretained path. A
	 * proof compares this number, never the object, so a retained state does
	 * not pin the corridor it was last proven in and a collected view cannot
	 * turn a hit into a miss.
	 */
	default long identity() {
		return 0L;
	}

	/**
	 * Whether every movement fact exposed by this object is permanently stable.
	 *
	 * <p>This is stronger than returning a constant {@link #collisionVersion}:
	 * collision geometry, fluids, coefficients, and every other answer must remain
	 * unchanged for the lifetime of this exact object. Retained proofs may omit
	 * version reads only when this returns {@code true}.
	 */
	default boolean movementFactsImmutable() {
		return false;
	}

	/**
	 * Version of every fact that can affect {@link #collectCollisionBoxes} for a
	 * query lying inside this closed cell span. Two reads that return the same
	 * value promise the span's collision answers did not change between them.
	 *
	 * <p>Separate from {@link #collisionVersion} because a single counter answers
	 * a local question globally: an edit anywhere changes it, so every cached
	 * proof everywhere is discarded. That costs nothing in a snapshot nobody
	 * edits and everything in a streaming world where chunks arrive continuously.
	 *
	 * <p>The default is the global answer, which is correct but coarse: it may
	 * report a change the span did not see, never the reverse.
	 */
	default long collisionVersionIn(
	    final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		return collisionVersion();
	}

	/**
	 * A number naming the collision facts inside this closed cell span, across
	 * views: two views that answer the same non-zero number for the same span
	 * promise the same collision answers inside it, whichever world each was
	 * compiled from and whether or not either was edited elsewhere. A
	 * retained proof keyed on it survives a republication that kept the
	 * sections it touched. Zero means no such number is available for the
	 * span, and a proof falls back to {@link #identity} and
	 * {@link #collisionVersionIn}, which name this one view.
	 *
	 * <p>The default is zero: a view that does not page its world by section
	 * has nothing to key across views on.
	 */
	default long spanRevision(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		return 0L;
	}

	/** Whether this view contains any non-empty fluid cells. */
	default boolean hasFluids() {
		return false;
	}

	/**
	 * Resolve one cell's frozen fluid state into caller-owned storage.
	 * Unknown coordinates must fail closed rather than silently becoming air.
	 */
	default void fluidAt(final int x, final int y, final int z, final FluidSample target) {
		throw UnimplementedMechanicException.deferred(
		    RefusalCause.UNDECLARED_WORLD_FACT, () -> "this world cannot resolve fluid at " + x + "," + y + "," + z);
	}

	/** Frozen BubbleColumnBlock direction for this block-state cell. */
	default BubbleColumnMode bubbleColumnModeAt(final int x, final int y, final int z) {
		return BubbleColumnMode.NONE;
	}

	/** Whether any captured cell can invoke BubbleColumnBlock.entityInside. */
	default boolean hasBubbleColumns() {
		return false;
	}

	/** Whether this cell's ordinary block collision shape is non-empty. */
	default boolean hasCollisionShapeAt(final int x, final int y, final int z) {
		return false;
	}

	/** The answer to {@link #airIn}: air is the block identity, not the absence of a shape. */
	enum Air {
		/** Every cell in the range is an air block state. */
		ALL_AIR,
		/** At least one cell in the range is known not to be air. */
		NOT_AIR,
		/** No known cell is non-air, but some cell's identity is not declared. */
		UNKNOWN
	}

	/**
	 * Whether every cell in the closed cell range is an air block state, the
	 * question {@code ServerGamePacketListenerImpl.noBlocksAround} asks for the
	 * floating latch. One known non-air cell decides {@link Air#NOT_AIR}
	 * whatever else is unknown; a range of known air is {@link Air#ALL_AIR}; a
	 * range with no known non-air cell and an undeclared identity is
	 * {@link Air#UNKNOWN}, which the caller carries rather than resolves. A view
	 * that declares no block identities answers unknown.
	 */
	default Air airIn(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		return Air.UNKNOWN;
	}

	/**
	 * Complete {@code LivingEntity.onClimbable} answer for the cell and glide
	 * mode, including glide-through blocks and ladder-aligned open trapdoors.
	 * {@code Player.onClimbable}'s flight gate is the caller's. A cell whose
	 * captured role is {@link Climbability#GLIDE_THROUGH} counts as climbable
	 * when not gliding: the capture assigns that role only to a state that is
	 * in both the can-glide-through and the climbable tag, since a state in
	 * the first tag alone would answer false either way and needs no role of
	 * its own. Tag membership is the capture's fact, not this interface's.
	 */
	default boolean onClimbableAt(final int x, final int y, final int z, final boolean fallFlying) {
		return false;
	}

	/*
	 * Whole-view coefficient facts. Movement asks for friction, speed factor,
	 * jump factor and climbability of specific cells every tick, and in almost
	 * every real world every cell answers the default. A view that knows this
	 * about itself lets the kernel skip the lookup rather than perform it and
	 * discard the result.
	 *
	 * Each default is the conservative answer, so a view that does not implement
	 * them keeps taking the ordinary path.
	 *
	 * Whole-view predicates become less selective as regions grow.
	 * {@link #propertiesIn} asks the same questions of the scanned span; these
	 * predicates remain its conservative fallback.
	 */

	/** Whether any cell is climbable. False permits skipping climbable lookups. */
	default boolean hasClimbables() {
		return true;
	}

	/** Whether any cell's friction differs from the ordinary 0.6. */
	default boolean hasNonDefaultFriction() {
		return true;
	}

	/** Whether any cell's speed factor differs from 1, or suppresses support. */
	default boolean hasNonDefaultSpeedFactor() {
		return true;
	}

	/** Whether any cell's jump factor differs from 1. */
	default boolean hasNonDefaultJumpFactor() {
		return true;
	}

	/**
	 * {@code LocalPlayer.suffocatesAt}: whether any suffocating block's collision
	 * shape reaches the deflated box that covers one whole block column over the
	 * player box's own Y span.
	 *
	 * <p>{@code LocalPlayer.aiStep} asks this four times a tick, once per box
	 * corner, and nudges {@code deltaMovement} towards the nearest non-suffocating
	 * side when it answers yes. The Y span is the player's, and the X/Z span is
	 * the cell's — wider than the player — so this is not "is the player inside a
	 * block", and a view must answer it as posed.
	 *
	 * <p>The default answers for a view that does not carry the fact, and it is
	 * exact exactly where the answer cannot be yes. {@code isSuffocating} defaults
	 * to {@code blocksMotion() && isCollisionShapeFullBlock()}, and the four
	 * blocks that replace the predicate with {@code Blocks::always} at this pin —
	 * farmland, soul sand, dirt path and mud — all carry collision geometry, so a
	 * column with no collision in this span cannot suffocate whatever its states
	 * are. Anything that does collide fails closed: exact movement forbids
	 * the convenient {@code false}, and geometry cannot imply the predicate (stone
	 * and glass are one shape and disagree).
	 *
	 * <p>It allocates. A view that intends to be fast overrides this.
	 */
	default boolean suffocatesAt(
	    final int cellX, final int cellZ, final double boundingBoxMinY, final double boundingBoxMaxY) {
		CollisionBuffer probe = new CollisionBuffer(1);
		collectCollisionBoxes(cellX + 1.0E-7, boundingBoxMinY + 1.0E-7, cellZ + 1.0E-7, cellX + 1.0 - 1.0E-7,
		    boundingBoxMaxY - 1.0E-7, cellZ + 1.0 - 1.0E-7, probe);
		if (probe.size() == 0) {
			return false;
		}
		throw UnimplementedMechanicException.deferred(RefusalCause.UNDECLARED_WORLD_FACT,
		    ()
		        -> "this world cannot resolve suffocation for the collision it has at " + cellX + "," + cellZ + " over "
		        + boundingBoxMinY + ".." + boundingBoxMaxY);
	}

	/** Context-sensitive collision behavior resolved for this exact cell. */
	default CollisionBehavior collisionBehaviorAt(final int x, final int y, final int z) {
		return CollisionBehavior.ORDINARY;
	}

	/** {@code Block.getBounceRestitution()} for this block-state cell. */
	default float bounceRestitutionAt(final int x, final int y, final int z) {
		return 0.0F;
	}

	/** Whether this cell is in {@code BlockTags.SUPPRESSES_BOUNCE}. */
	default boolean suppressesBounceAt(final int x, final int y, final int z) {
		return false;
	}

	/**
	 * Whether {@code Entity.getOnPos} returns the supporting position unchanged
	 * for this cell instead of re-deriving Y from the player.
	 *
	 * <p>{@code getOnPos(0.2F)} keeps the supporting block's own Y for fences,
	 * walls and fence gates and takes {@code floor(y - offset)} for everything
	 * else. A player standing on a fence is 1.5 blocks above its cell, so the two
	 * branches name different cells and the distinction is not cosmetic. The
	 * movement-affecting lookup at {@code getOnPos(0.500001F)} asks the same
	 * question; there the retained cell and {@code floor(y - offset)} coincide,
	 * since the player stands one and a half blocks above a fence's cell.
	 */
	default boolean retainsSupportPosAt(final int x, final int y, final int z) {
		return false;
	}

	/**
	 * {@code CollisionGetter.findSupportingBlock}: the cell of the collision
	 * nearest {@code (atX, atY, atZ)} whose shape reaches the box, or absent.
	 *
	 * <p>"Nearest" is {@code BlockPos.distToCenterSqr} to the player's position,
	 * with ties broken by keeping the larger {@code Vec3i.compareTo} — Y, then Z,
	 * then X. A tie is not exotic: a box straddling a cell boundary on a flat
	 * floor produces two cells equidistant from it, so the tie-break decides the
	 * answer on ordinary ground.
	 *
	 * <p>The default refuses. Every other unimplemented fact on this interface has
	 * a safe conservative answer; this one does not, because absent is a
	 * *different branch* of {@code getOnPos} rather than a weaker version of the
	 * same one, and guessing it silently names the wrong cell. Ground movement
	 * resolves it every tick because the following tick's friction, jump and
	 * speed-factor lookups all observe the retained identity.
	 */
	default void findSupportingBlock(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double atX, final double atY, final double atZ,
	    final SupportCell out) {
		throw new UnimplementedMechanicException(
		    RefusalCause.UNDECLARED_WORLD_FACT, "this world cannot resolve the supporting block");
	}

	/** Player-context form for scaffolding and powder-snow support shapes. */
	default void findSupportingBlock(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double atX, final double atY, final double atZ,
	    final double entityBottom, final boolean descending, final double fallDistance, final SupportCell out) {
		findSupportingBlock(minX, minY, minZ, maxX, maxY, maxZ, atX, atY, atZ, out);
	}

	default void findSupportingBlock(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double atX, final double atY, final double atZ,
	    final double entityBottom, final boolean descending, final double fallDistance,
	    final boolean canWalkOnPowderSnow, final SupportCell out) {
		findSupportingBlock(
		    minX, minY, minZ, maxX, maxY, maxZ, atX, atY, atZ, entityBottom, descending, fallDistance, out);
	}

	/**
	 * Whether the clipped segment hits water or a block whose captured state is
	 * in vanilla's {@code BlockTags.FALL_DAMAGE_RESETTING} tag.
	 *
	 * <p>{@code Entity.move} asks this only for a resolved move of at least one
	 * block while fall distance is non-zero. The segment passed here is already
	 * capped to vanilla's eight-block check distance. Unknown space must refuse
	 * rather than silently preserve the wrong fall distance.
	 *
	 * <p>The water shape vanilla clips against is cached per fluid state for
	 * the life of the client at whichever height the first cell that asked
	 * for it had, so a segment that meets water only between a cell's own
	 * height and the full cube has no world-determined answer. An
	 * implementation refuses that segment rather than choosing a height.
	 */
	default boolean resetsFallDistanceAlong(final double fromX, final double fromY, final double fromZ,
	    final double toX, final double toY, final double toZ) {
		throw new UnimplementedMechanicException(
		    RefusalCause.UNDECLARED_WORLD_FACT, "this world cannot resolve a fall-distance-resetting segment");
	}

	/**
	 * What the block at this cell does to a player who reaches it: the
	 * {@link BlockBehavior} resolved from the cell's palette entry, asked at
	 * every cell the inside-block traversal visits, at the block underfoot,
	 * and at the landed block. A compiled view resolves it once per entry.
	 * There is no default: a view answers {@link BlockBehavior#INERT} only
	 * where it has proved the cell holds no body, and a body it cannot name
	 * must refuse rather than pass as inert.
	 */
	BlockBehavior behaviorAt(int x, int y, int z);

	/**
	 * {@code Entity.isInWall}'s test: whether a block state that suffocates
	 * has collision geometry meeting the box, over the cells the box's bounds
	 * cover.
	 *
	 * <p>The default fails closed exactly where {@link #suffocatesAt} does: a
	 * box meeting no collision anywhere cannot be inside a wall, and anything
	 * that does collide refuses, because geometry cannot imply the predicate.
	 */
	default boolean suffocatingShapeMeets(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ) {
		for (int x = Mth.floor(minX); x <= Mth.floor(maxX); x++) {
			for (int y = Mth.floor(minY); y <= Mth.floor(maxY); y++) {
				for (int z = Mth.floor(minZ); z <= Mth.floor(maxZ); z++) {
					if (hasCollisionShapeAt(x, y, z)) {
						int atX = x;
						int atY = y;
						int atZ = z;
						throw UnimplementedMechanicException.deferred(RefusalCause.UNDECLARED_WORLD_FACT,
						    () -> "this world cannot resolve suffocation at " + atX + "," + atY + "," + atZ);
					}
				}
			}
		}
		return false;
	}

	/**
	 * {@code Level.isRainingAt} for the player's cell and the cell at the top
	 * of its box: whether rain reaches the player. Weather is not a captured
	 * fact, so the default refuses; the tick asks only while the player is
	 * on fire, which is when the answer changes the server's state.
	 */
	default boolean rainReaches(final int x, final int y, final int z, final int topY) {
		throw UnimplementedMechanicException.deferred(RefusalCause.UNDECLARED_WORLD_FACT,
		    () -> "this world cannot resolve whether rain reaches " + x + "," + y + "," + z);
	}

	/** True when collision answers depend on live player context, not only version. */
	default boolean hasContextSensitiveCollision() {
		return false;
	}

	default boolean hasPowderSnow() {
		return false;
	}

	/*
	 * Per-span behavior facts, one bit each. Validation: every whole-view flag above
	 * is permanently true in a region big enough to route through, so the gates
	 * built on them stop rejecting anything at realiztic scale. These ask the
	 * same questions of the closed cell span the guarded code would actually
	 * scan, which is a handful of cells around the player rather than a million.
	 */

	int PROPERTY_FLUID = 1;
	int PROPERTY_BUBBLE_COLUMN = 1 << 1;
	int PROPERTY_CLIMBABLE = 1 << 2;
	int PROPERTY_FRICTION = 1 << 3;
	int PROPERTY_SPEED_FACTOR = 1 << 4;
	int PROPERTY_JUMP_FACTOR = 1 << 5;
	int PROPERTY_POWDER_SNOW = 1 << 6;
	int PROPERTY_INSIDE_EFFECT = 1 << 7;
	/** Any cell whose {@code Block.getBounceRestitution()} is non-zero. */
	int PROPERTY_BOUNCE = 1 << 8;
	/** Any cell with a {@code stepOn} body the kernel runs. */
	int PROPERTY_STEP_ON = 1 << 9;
	/** Any cell with a server-only contact body, including one that refuses. */
	int PROPERTY_CONTACT = 1 << 11;
	int PROPERTIES_ALL = (1 << 10) - 1 | PROPERTY_CONTACT;

	/**
	 * Which of {@code wanted}'s behaviors any cell of the closed cell span may
	 * have. Never returns a bit outside {@code wanted}.
	 *
	 * <p>Callers ask for the bits they will act on rather than for all of them,
	 * because that is what lets an implementation answer without looking: a view
	 * that has no fluid anywhere can reject a fluid question with one field test,
	 * and it is worth keeping that cheap. The dry, wet, and working-set benchmark
	 * rows own whether an unconditional query is worthwhile. Small fixtures can
	 * make the skipped lookup cheaper than the mask itself.
	 *
	 * <p>Conservative in one direction only: a set bit the span does not actually
	 * contain costs a lookup, a clear bit the span does contain is a fidelity
	 * defect. Implementations may therefore leave a bit set after the cell that
	 * justified it is replaced, and must never clear one speculatively.
	 *
	 * <p>The default is the whole-view answer, so a view that does not carry
	 * section detail keeps exactly the behavior it had before this existed.
	 *
	 * <p>{@link #PROPERTY_INSIDE_EFFECT} is the one bit the default leaves clear,
	 * and that is not an oversight. The other bits pair with an accessor that
	 * fails closed off the end of what the view knows, so guessing them true only
	 * costs a lookup; {@link #behaviorAt} instead *defaults* to the block with
	 * no body, so a set bit would claim an effect the view has
	 * already said it does not have. It would also be expensive rather than
	 * merely wasteful: {@code applyEffectsFromBlocks} refuses movement past the
	 * {@code movedFar} threshold once it is past its gate, so a spuriously set bit
	 * would make every elytra world start refusing transitions it passes today.
	 */
	default int propertiesIn(
	    final int wanted, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		int properties = 0;
		if ((wanted & PROPERTY_FLUID) != 0 && hasFluids()) {
			properties |= PROPERTY_FLUID;
		}
		if ((wanted & PROPERTY_BUBBLE_COLUMN) != 0 && hasBubbleColumns()) {
			properties |= PROPERTY_BUBBLE_COLUMN;
		}
		if ((wanted & PROPERTY_CLIMBABLE) != 0 && hasClimbables()) {
			properties |= PROPERTY_CLIMBABLE;
		}
		if ((wanted & PROPERTY_FRICTION) != 0 && hasNonDefaultFriction()) {
			properties |= PROPERTY_FRICTION;
		}
		if ((wanted & PROPERTY_SPEED_FACTOR) != 0 && hasNonDefaultSpeedFactor()) {
			properties |= PROPERTY_SPEED_FACTOR;
		}
		if ((wanted & PROPERTY_JUMP_FACTOR) != 0 && hasNonDefaultJumpFactor()) {
			properties |= PROPERTY_JUMP_FACTOR;
		}
		if ((wanted & PROPERTY_POWDER_SNOW) != 0 && hasPowderSnow()) {
			properties |= PROPERTY_POWDER_SNOW;
		}
		return properties;
	}

	/**
	 * Which of {@code wanted}'s behaviors any cell of the whole view may have:
	 * {@link #propertiesIn} over every cell, asked without a span.
	 *
	 * <p>Every span answer must be a subset of this one, so a caller asks it
	 * first and computes the span only when a bit survives. Most movement
	 * questions are asked of a view that has no such cell anywhere, and the
	 * coordinate arithmetic for a span that will be answered zero is the whole
	 * cost of the question. The default answers every bit, which keeps any
	 * {@link #propertiesIn} override exact; a view with a compiled whole-view
	 * mask answers from it in one load.
	 */
	default int propertiesAnywhere(final int wanted) {
		return wanted;
	}

	/*
	 * Span reuse. A movement query scans a closed cell span, and because that
	 * span is integer it is usually the *same* span several queries running --
	 * consecutive ticks quantize to one cell, and a search's siblings share their
	 * parent's position. A view that can hand over a span's boxes unfiltered lets
	 * the caller retain them and refilter, instead of walking the cells again.
	 *
	 * The default answers no, so a view that does not implement this keeps taking
	 * the ordinary path.
	 */

	/**
	 * Whether {@link #collectSpanBoxes} is available. False for any view whose
	 * collision answers depend on player context or on shapes that reach outside
	 * their own cell, since neither can be replayed from a retained span.
	 */
	default boolean supportsSpanReuse() {
		return false;
	}

	/**
	 * Collision-buffer semantics implemented by this view. The legacy default is
	 * deliberate: a pre-grouping implementation can keep compiling, but retained
	 * refiltering will fall back to ordinary collection instead of guessing source
	 * identity from its boxes.
	 */
	default CollisionShapeProtocol collisionShapeProtocol() {
		return CollisionShapeProtocol.LEGACY_BOXES;
	}

	/**
	 * Whether the closed cell span can contain any collision at all. False must
	 * mean the span lies wholly inside the view and is provably collision-free at
	 * this collision version, so the caller may answer the query empty outright.
	 *
	 * <p>Empty spans are already answered by one section test, and routing them
	 * through a retained span only adds bookkeeping they never amortize. Mobility
	 * and working-set benchmarks own the hit-rate tradeoff. This keeps empty spans
	 * on the short path.
	 */
	default boolean spanHasCollisionPotential(
	    final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		return true;
	}

	/**
	 * Collect every collision shape owned by the closed cell span, in the order
	 * {@link #collectCollisionBoxes} visits cells and *without* its query-box
	 * filter. The unfiltered set is required: a later query can lie inside this
	 * span while its own bounds still reach a box this span's filter would have
	 * dropped.
	 *
	 * <p>Under {@link CollisionShapeProtocol#SOURCE_GROUPS_V1}, start each source
	 * shape with {@link CollisionBuffer#add}, append its remaining primitive boxes
	 * with {@link CollisionBuffer#addPart}, and retain canonical singleton full
	 * blocks with {@link CollisionBuffer#addCanonicalFullCube}. Span collection
	 * must preserve the same groups and identities as ordinary collection. Legacy
	 * box providers are never refiltered through this method.
	 *
	 * <p>Returns false without touching {@code target} when the span is not
	 * wholly serviceable, which leaves the caller on the ordinary path.
	 */
	default boolean collectSpanBoxes(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1,
	    final CollisionBuffer target) {
		return false;
	}

	/**
	 * Collect collision shapes reached by the query into caller-owned storage.
	 * Implementations clear {@code target} before appending results. A view that
	 * declares {@link CollisionShapeProtocol#SOURCE_GROUPS_V1} makes each source
	 * shape one buffer group: use {@link CollisionBuffer#add} for its first
	 * primitive box, {@link CollisionBuffer#addPart} for any remaining boxes, and
	 * {@link CollisionBuffer#addCanonicalFullCube} only for Minecraft's canonical
	 * singleton full-block shape. The legacy protocol treats every added box as
	 * an independent group and is not eligible for retained refiltering.
	 */
	void collectCollisionBoxes(
	    double minX, double minY, double minZ, double maxX, double maxY, double maxZ, CollisionBuffer target);

	/**
	 * Player-context form used by movement and fit queries. {@code entityBottom}
	 * is the player's feet Y; descending is vanilla {@code isShiftKeyDown()}.
	 */
	default void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double entityBottom, final boolean descending,
	    final CollisionBuffer target) {
		collectCollisionBoxes(minX, minY, minZ, maxX, maxY, maxZ, target);
	}

	default void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double entityBottom, final boolean descending,
	    final double fallDistance, final CollisionBuffer target) {
		collectCollisionBoxes(minX, minY, minZ, maxX, maxY, maxZ, entityBottom, descending, target);
	}

	default void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double entityBottom, final boolean descending,
	    final double fallDistance, final boolean canWalkOnPowderSnow, final CollisionBuffer target) {
		collectCollisionBoxes(minX, minY, minZ, maxX, maxY, maxZ, entityBottom, descending, fallDistance, target);
	}

	/** {@code Block.getFriction()} for the block at these coordinates. */
	float friction(int x, int y, int z);

	/** {@code Block.getSpeedFactor()} for the block at these coordinates. */
	float speedFactor(int x, int y, int z);

	/** True for water/bubble blocks whose own speed factor suppresses the support block. */
	default boolean suppressesSupportingSpeedFactor(final int x, final int y, final int z) {
		return false;
	}

	/** {@code Block.getJumpFactor()} for the block at these coordinates. */
	float jumpFactor(int x, int y, int z);

	/**
	 * False where the snapshot has no information. Unknown space
	 * is an explicit semantic, never silently air. {@code travelInAir} reads this —
	 * on the client a missing chunk below replaces gravity with a flat -0.1 —
	 * and so does the fluid refresh, where a missing chunk within one block of
	 * the box in X or Z makes vanilla's client find no fluid at all.
	 */
	boolean hasChunkAt(int x, int z);

	int minY();
}
