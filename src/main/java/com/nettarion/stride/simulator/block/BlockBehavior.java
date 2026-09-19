package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.server.ServerDamage;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.WorldView;

import java.util.List;

/**
 * What one palette entry does to a player who reaches it.
 *
 * <p>A behavior is the set of vanilla movement hooks a block overrides and the tick calls: {@code entityInside}
 * on the client and on the server's copy, {@code stepOn}, {@code fallOn}, and the
 * {@code getEntityInsideCollisionShape} that decides whether a sweep reached the block at all. Speed and jump
 * factors, friction and bounce restitution are palette coefficients the tick reads, not hooks, and do not live
 * here.
 *
 * <p>Every block with a hook is one final class in this package named after the vanilla class it mirrors, a
 * singleton unless its facts vary per entry. An entry with no hook is {@link #INERT}; one whose hook the simulator
 * does not model, or whose hook depends on block state the capture did not record, is a sentinel that refuses on
 * contact. Consumers receive instances only through {@link WorldView#behaviorAt} or {@link #of(BlockEntry)}; the
 * constructor is package-private, so the set of behaviors is closed to this package. Instances are immutable and
 * shared across threads; the hooks mutate only the {@link PlayerState} and {@link Scratch} they are handed.
 */
public abstract class BlockBehavior {
	/** The bubble-column fact: {@code BubbleColumnBlock.entityInside}. */
	static final int BUBBLE = 1;

	/** The powder-snow collision fact, which owns the whole powder-snow hook. */
	static final int POWDER = 1 << 1;

	/** The client-visible {@code entityInside} fact. */
	static final int INSIDE = 1 << 2;

	/** The server-only contact fact: the {@code entityInside} or {@code stepOn} that hurts or refuses. */
	static final int CONTACT = 1 << 3;

	/** The client-visible {@code stepOn} fact. */
	static final int STEP_ON = 1 << 4;

	/** The {@code fallOn} fact. */
	static final int LANDING = 1 << 5;

	/**
	 * A block with no hook the tick runs: no {@code entityInside}, no {@code stepOn}, and the ordinary
	 * {@code fallOn}. The traversal compares against this by identity before dispatching.
	 */
	public static final BlockBehavior INERT = new BlockBehavior() {
		@Override
		public String toString() {
			return "inert";
		}
	};

	BlockBehavior() {}

	/** Resolves the behavior of one palette entry from its facts; see {@link #of(BlockEntry)}. */
	public static BlockBehavior of(final BlockEntry entry) {
		return of(entry.bubbleColumnMode(), entry.collisionBehavior(), entry.insideEffect(), entry.stepOn(),
		    entry.contact(), entry.landing());
	}

	/**
	 * Resolves the behavior of one set of palette facts.
	 *
	 * <p>Every fact resolves to the block that owns its hook; the block's singleton answers when it owns exactly
	 * the facts present. Facts naming two different blocks, or an incomplete set for one block (a slime whose
	 * landing was captured as ordinary is not a slime), refuse with {@link RefusalCause#INADMISSIBLE_BLOCK_FACTS}
	 * rather than synthesize a block from unrelated hooks. A contact or landing fact captured as unmodeled or
	 * unrecorded resolves to a sentinel that refuses on contact for exactly those hook families.
	 */
	static BlockBehavior of(final WorldView.BubbleColumnMode bubbleColumnMode,
	    final WorldView.CollisionBehavior collisionBehavior, final WorldView.InsideEffect insideEffect,
	    final WorldView.StepOn stepOn, final WorldView.Contact contact, final WorldView.Landing landing) {
		BlockBehavior bubble = switch (bubbleColumnMode) {
			case NONE -> INERT;
			case DRAG_DOWN -> BubbleColumnBlock.DRAG_DOWN;
			case PUSH_UP -> BubbleColumnBlock.PUSH_UP;
		};
		BlockBehavior powder =
		    collisionBehavior == WorldView.CollisionBehavior.POWDER_SNOW_NO_BOOTS ? PowderSnowBlock.INSTANCE : INERT;
		BlockBehavior inside = switch (insideEffect) {
			case HONEY -> HoneyBlock.INSTANCE;
			case COBWEB -> WebBlock.INSTANCE;
			case SWEET_BERRY_BUSH ->
				switch (contact) {
					case NONE -> SweetBerryBushBlock.YOUNG;
					case UNRECORDED -> SweetBerryBushBlock.UNRECORDED_AGE;
					default -> SweetBerryBushBlock.INSTANCE;
				};
			case LAVA_CAULDRON -> LavaCauldronBlock.INSTANCE;
			case NONE -> INERT;
		};
		BlockBehavior contacting = switch (contact) {
			case NONE, UNMODELED -> INERT;
			case CACTUS -> CactusBlock.INSTANCE;
			case SWEET_BERRY_BUSH -> SweetBerryBushBlock.INSTANCE;
			case HOT_FLOOR -> MagmaBlock.INSTANCE;
			case CAMPFIRE -> CampfireBlock.CAMPFIRE;
			case SOUL_CAMPFIRE -> CampfireBlock.SOUL_CAMPFIRE;
			case FIRE -> BaseFireBlock.FIRE;
			case SOUL_FIRE -> BaseFireBlock.SOUL_FIRE;
			case LAVA_CAULDRON -> LavaCauldronBlock.INSTANCE;
			// A bush captured before the age fact existed keeps its inside hook.
			case UNRECORDED ->
				insideEffect == WorldView.InsideEffect.SWEET_BERRY_BUSH ? SweetBerryBushBlock.UNRECORDED_AGE : INERT;
		};
		BlockBehavior underfoot = switch (stepOn) {
			case NONE -> INERT;
			case SLIME -> SlimeBlock.INSTANCE;
		};
		BlockBehavior landed = switch (landing) {
			case ORDINARY, UNMODELED, UNRECORDED -> INERT;
			case HAY -> HayBlock.INSTANCE;
			case HONEY -> HoneyBlock.INSTANCE;
			case SLIME -> SlimeBlock.INSTANCE;
			case BED -> BedBlock.INSTANCE;
			case STALAGMITE -> PointedDripstoneBlock.INSTANCE;
			case FARMLAND -> FarmlandBlock.INSTANCE;
			case POWDER_SNOW -> PowderSnowBlock.INSTANCE;
		};
		// The hook families the capture marked unmodeled, and those it left unrecorded.
		int unmodeled = (contact == WorldView.Contact.UNMODELED ? CONTACT : 0)
		    | (landing == WorldView.Landing.UNMODELED ? LANDING : 0);
		int unrecorded = (contact == WorldView.Contact.UNRECORDED && contacting == INERT ? CONTACT : 0)
		    | (landing == WorldView.Landing.UNRECORDED ? LANDING : 0);
		BlockBehavior single = null;
		for (BlockBehavior owner : new BlockBehavior[] {bubble, powder, inside, contacting, underfoot, landed}) {
			if (owner == INERT) {
				continue;
			}
			if (single == null) {
				single = owner;
			} else if (single != owner) {
				throw new UnimplementedMechanicException(RefusalCause.INADMISSIBLE_BLOCK_FACTS,
				    "inconsistent block behavior facts: " + single + " and " + owner);
			}
		}
		if (single == null) {
			if (unmodeled != 0 && unrecorded != 0) {
				throw new UnimplementedMechanicException(RefusalCause.INADMISSIBLE_BLOCK_FACTS,
				    "inconsistent block behavior facts: an unmodeled hook beside an unrecorded one");
			}
			if (unmodeled != 0) {
				return UnmodeledBlock.of(unmodeled);
			}
			if (unrecorded != 0) {
				return UnrecordedStateBlock.of(unrecorded);
			}
			return INERT;
		}
		if (unmodeled != 0 || unrecorded != 0) {
			throw new UnimplementedMechanicException(RefusalCause.INADMISSIBLE_BLOCK_FACTS,
			    "inconsistent block behavior facts: " + single + " beside an unmodeled or unrecorded hook");
		}
		// The singleton answers only when it owns exactly the facts present.
		int present = 0;
		if (bubble == single) {
			present |= BUBBLE;
		}
		if (powder == single) {
			present |= POWDER;
		}
		if (inside == single) {
			present |= INSIDE;
		}
		if (contacting == single) {
			present |= CONTACT;
		}
		if (underfoot == single) {
			present |= STEP_ON;
		}
		if (landed == single) {
			present |= LANDING;
		}
		if (present != single.families()) {
			throw new UnimplementedMechanicException(
			    RefusalCause.INADMISSIBLE_BLOCK_FACTS, "incomplete block behavior facts for " + single);
		}
		return single;
	}

	/** The bubble-column behavior for {@code mode}, or {@link #INERT} for {@code NONE}. */
	public static BlockBehavior bubbleColumn(final WorldView.BubbleColumnMode mode) {
		return switch (mode) {
			case NONE -> INERT;
			case DRAG_DOWN -> BubbleColumnBlock.DRAG_DOWN;
			case PUSH_UP -> BubbleColumnBlock.PUSH_UP;
		};
	}

	/**
	 * The behavior of one layered water or powder-snow cauldron: its {@code entityInside} shape as the
	 * {@code BlockStateCatalog} extracted it and the palette index of the state one level lower.
	 *
	 * <p>One instance per catalog entry rather than a singleton, because both facts vary with the level. On the
	 * server's copy a visit queues the lowering under {@code EXTINGUISH}, which refuses unless the caller owns the
	 * world.
	 */
	public static BlockBehavior layeredCauldron(final int successor, final List<ShapeBox> boxes) {
		return new LayeredCauldronBlock(successor, boxes);
	}

	/**
	 * Flattens shape boxes into the {@code [minX, minY, minZ, maxX, maxY, maxZ, ...]} array the sweep tests
	 * against.
	 *
	 * <p>Coordinates are in blocks, relative to the cell. The result is a fresh array the caller owns; the shape
	 * of one cell is passed to {@code InsideBlockTraversal.collidedWithShapeMovingFrom} in this layout.
	 */
	public static double[] flattenShape(final List<ShapeBox> boxes) {
		double[] shape = new double[boxes.size() * 6];
		for (int i = 0; i < boxes.size(); i++) {
			ShapeBox box = boxes.get(i);
			int at = i * 6;
			shape[at] = box.minX();
			shape[at + 1] = box.minY();
			shape[at + 2] = box.minZ();
			shape[at + 3] = box.maxX();
			shape[at + 4] = box.maxY();
			shape[at + 5] = box.maxZ();
		}
		return shape;
	}

	/**
	 * {@code Block.fallOn} of the block the player landed on: the fall becomes a hit on the server's copy.
	 *
	 * <p>Public because {@code ServerMovementListener} runs it when an accepted movement packet lands the player.
	 * {@code fallDistance} is in blocks; {@code x, y, z} are the supporting cell from {@code getOnPosLegacy}, not
	 * the cell under the player's center. The ordinary block lands at multiplier one through
	 * {@code Player.causeFallDamage}. The sentinel behaviors refuse here when the landing hook is unmodeled or
	 * unrecorded.
	 */
	public void fallOn(final double fallDistance, final int x, final int y, final int z, final ServerDamage damage) {
		damage.causeFallDamage(fallDistance, 1.0F, HurtCause.FALL);
	}

	/**
	 * {@code Player.makeStuckInBlock}: the fall reset and the stuck vector the next {@code move} consumes.
	 *
	 * <p>Shared by the cobweb and the sweet berry bush, whose {@code entityInside} for a player reduces to this one
	 * call; honey, in the same traversal, has no such gate. The whole {@code Entity.makeStuckInBlock} call is
	 * behind the player's own flying gate, so a flying player gets neither the vector nor the fall-distance reset,
	 * and neither hook reads {@code isPrecise}. {@code tryResetCurrentImpulseContext} then runs whatever the gate
	 * said, on the server's copy, where an imported state can carry a wind-charge impulse context that
	 * {@code ServerDamage.causeFallDamage} caps the next fall by. The Weaving effect's larger vector,
	 * {@code (0.5, 0.25, 0.5)}, is outside the admitted domain and never proposed here.
	 */
	static void makeStuckInBlock(
	    final PlayerState state, final double x, final double y, final double z, final Scratch scratch) {
		// Player.makeStuckInBlock's own gate, and the whole super call is behind
		// it, so a flying player gets neither the vector nor the fall-distance
		// reset.
		if (!state.flying) {
			// Entity.makeStuckInBlock: resetFallDistance() first, then the vector.
			state.fallDistance = 0.0;
			state.stuckSpeedMultiplierX = x;
			state.stuckSpeedMultiplierY = y;
			state.stuckSpeedMultiplierZ = z;
		}
		// tryResetCurrentImpulseContext follows unconditionally; the context is
		// the server's copy's, where causeFallDamage reads it.
		if (scratch.authority.isServer()) {
			scratch.authority.serverState().tryResetCurrentImpulseContext();
		}
	}

	/** Which palette facts this block owns, as {@link #BUBBLE} and its siblings. */
	int families() {
		return 0;
	}

	/**
	 * Whether a visit runs this block's {@code entityInside} on both sides.
	 *
	 * <p>Vanilla runs one {@code entityInside} per visited cell; here that hook is split by who can observe it.
	 * The client-visible half (stuck vectors, the honey slide, bubble drag, the collected FREEZE and
	 * CLEAR_FREEZE) runs on the client and on the server's copy, and a block that has it answers true here. The
	 * server-only half (hits, ignition, extinguish, world writes) runs on the server's copy alone, and a block that
	 * has it answers true to {@link #hasContact}. The traversal visits a cell when either is true, and
	 * {@link #entityInside} checks {@code scratch.authority.isServer()} itself before its server-only part.
	 */
	boolean hasEntityInside() {
		return false;
	}

	/** Whether a visit runs the server-only half of this block's {@code entityInside}; see {@link #hasEntityInside}. */
	boolean hasContact() {
		return false;
	}

	/**
	 * {@code Entity.collidedWithShapeMovingFrom} against this block's {@code getEntityInsideCollisionShape}:
	 * whether the box swept from the sub-movement's origin reached the shape.
	 *
	 * <p>True for a block that keeps the full cube, which vanilla short-circuits without asking: the bubble
	 * column, the cobweb and the sweet berry bush keep it, so their visits are unconditional. Powder snow and the
	 * cauldrons override it and sweep.
	 */
	boolean entityInsideShapeReached(
	    final PlayerState state, final int x, final int y, final int z, final Scratch scratch) {
		return true;
	}

	/**
	 * {@code Block.entityInside} for the cell at {@code x, y, z}, run at the visit.
	 *
	 * <p>May mutate the player's velocity, fall distance, stuck-speed multiplier and frozen count directly, and
	 * may queue collected effects on {@code scratch.insideEffects}; it never moves the player or touches the
	 * visited set. Server-only parts go through {@code scratch.authority.damage()} after checking
	 * {@code scratch.authority.isServer()}. World queries stay within one cell of {@code x, y, z}, which is the
	 * margin {@code BlockEffects} widens the traversal cover by.
	 *
	 * @param isPrecise whether the sub-movement's destination box overlaps this cell, read by the bubble column
	 */
	void entityInside(final PlayerState state, final int x, final int y, final int z, final boolean isPrecise,
	    final WorldView world, final Scratch scratch) {}

	/**
	 * {@code Block.stepOn} for the block underfoot, at {@code getOnPosLegacy}, while on the ground.
	 *
	 * <p>Runs on both sides; a block whose {@code stepOn} only hurts checks {@code scratch.authority.isServer()}
	 * itself. {@code x, y, z} are the supporting cell.
	 */
	void stepOn(final PlayerState state, final int x, final int y, final int z, final Scratch scratch) {}
}
