package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.server.Survival;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;
import com.nettarion.stride.simulator.world.BlockEntry;

/**
 * What one palette entry does to a player who reaches it: the movement
 * hooks of vanilla's {@code BlockBehavior} the tick can call,
 * {@code getEntityInsideCollisionShape}, {@code entityInside} on both sides,
 * {@code stepOn}, and {@code fallOn}, resolved once per entry when a
 * {@link com.nettarion.stride.simulator.world.SnapshotView} compiles and asked through {@link com.nettarion.stride.simulator.world.WorldView#behaviorAt}.
 *
 * <p>A block with a body the tick runs is one class named for it:
 * {@link HoneyBlock}, {@link SlimeBlock}, {@link PowderSnowBlock}, and so on,
 * each a singleton overriding the hooks vanilla's class overrides. An entry
 * with no body at all is {@link #INERT}, which the traversal recognizes by
 * identity before it dispatches. An entry whose body the slice does not
 * model is {@link UnmodeledBlock}, and one whose body depends on block
 * state the capture did not record is {@link UnknownStateBlock}; both refuse
 * on contact, never at compile time, so a world holding such a block still
 * steps until the player reaches it.
 *
 * <p>Each admitted palette tuple resolves to one upstream block behavior.
 * Conflicting or incomplete tuples refuse compilation instead of synthesizing a
 * block from unrelated mechanics. Older captures needing missing state facts must
 * be recaptured; known server-only unmodeled bodies still refuse on contact.
 */
public abstract class BlockBehavior {
	/** The palette facts a block may own, one bit each. */
	static final int BUBBLE = 1;
	static final int POWDER = 1 << 1;
	static final int INSIDE = 1 << 2;
	static final int CONTACT = 1 << 3;
	static final int STEP_ON = 1 << 4;
	static final int LANDING = 1 << 5;

	/**
	 * A block with no body the tick runs: no {@code entityInside}, no
	 * {@code stepOn}, and the ordinary {@code fallOn}. The traversal compares
	 * against this by identity before dispatching.
	 */
	public static final BlockBehavior INERT = new BlockBehavior() {
		@Override
		public String toString() {
			return "inert";
		}
	};

	BlockBehavior() {}

	/** Which palette facts this block owns, as {@link #BUBBLE} and its siblings. */
	int families() {
		return 0;
	}

	/** Whether a visit runs {@link #entityInside}: the client-visible half of the body. */
	boolean hasEntityInside() {
		return false;
	}

	/** Whether a visit runs {@link #entityInside}: the server-only half of the body. */
	boolean hasContact() {
		return false;
	}

	/**
	 * {@code Entity.collidedWithShapeMovingFrom} against this block's
	 * {@code getEntityInsideCollisionShape}: whether the box swept from the
	 * sub-movement's origin reached the shape. True for a block that keeps the
	 * full cube, which vanilla short-circuits without asking.
	 */
	boolean entityInsideShapeReached(
	    final PlayerState state, final int x, final int y, final int z, final Scratch scratch) {
		return true;
	}

	/**
	 * {@code entityInside} as the client sees it, run at the visit with the
	 * cell's coordinates and whether the destination box overlaps the cell.
	 */
	void entityInside(final PlayerState state, final int x, final int y, final int z, final boolean isPrecise,
	    final WorldView world, final Scratch scratch) {}

	/** {@code Block.stepOn} for the block underfoot, at {@code getOnPosLegacy}. */
	void stepOn(final PlayerState state, final int x, final int y, final int z, final Scratch scratch) {}

	/**
	 * {@code Block.fallOn} of the landed block: how the fall distance becomes
	 * a hit, through {@code Player.causeFallDamage} on the server's copy.
	 * The ordinary block lands at multiplier one.
	 */
	public void fallOn(final double fallDistance, final int x, final int y, final int z, final Survival survival) {
		survival.causeFallDamage(fallDistance, 1.0F, HurtCause.FALL);
	}

	/**
	 * {@code Player.makeStuckInBlock}: the fall reset and the stuck vector the
	 * next {@code move} consumes, behind the player's own flying gate, then the
	 * wind-charge impulse context settled whatever the gate said. Written once
	 * for the blocks that call it, because the gate is theirs to share and
	 * honey, in the same traversal, has no such gate.
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

	/** The behavior of one palette entry. */
	public static BlockBehavior of(final BlockEntry entry) {
		return of(entry.bubbleColumnMode(), entry.collisionBehavior(), entry.insideEffect(), entry.stepOn(),
		    entry.contact(), entry.landing());
	}

	/**
	 * The behavior of one tuple of palette facts. Every fact resolves to the
	 * block that owns its body; the block's singleton answers when it owns
	 * exactly the facts present, otherwise incomplete or conflicting facts refuse.
	 */
	public static BlockBehavior of(final WorldView.BubbleColumnMode bubbleColumnMode,
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
					case UNKNOWN -> SweetBerryBushBlock.UNKNOWN_AGE;
					default -> SweetBerryBushBlock.INSTANCE;
				};
			case LAVA_CAULDRON -> LavaCauldronBlock.INSTANCE;
			case NONE -> INERT;
		};
		BlockBehavior contacting = switch (contact) {
			case NONE -> INERT;
			case CACTUS -> CactusBlock.INSTANCE;
			case SWEET_BERRY_BUSH -> SweetBerryBushBlock.INSTANCE;
			case HOT_FLOOR -> MagmaBlock.INSTANCE;
			case CAMPFIRE -> CampfireBlock.CAMPFIRE;
			case SOUL_CAMPFIRE -> CampfireBlock.SOUL_CAMPFIRE;
			case FIRE -> BaseFireBlock.FIRE;
			case SOUL_FIRE -> BaseFireBlock.SOUL_FIRE;
			case LAVA_CAULDRON -> LavaCauldronBlock.INSTANCE;
			case UNMODELED -> UnmodeledBlock.INSTANCE;
			// A bush captured before the age fact existed keeps its inside body.
			case UNKNOWN ->
				insideEffect == WorldView.InsideEffect.SWEET_BERRY_BUSH ? SweetBerryBushBlock.UNKNOWN_AGE
				                                                        : UnknownStateBlock.INSTANCE;
		};
		BlockBehavior underfoot = switch (stepOn) {
			case NONE -> INERT;
			case SLIME -> SlimeBlock.INSTANCE;
		};
		BlockBehavior landed = switch (landing) {
			case ORDINARY -> INERT;
			case HAY -> HayBlock.INSTANCE;
			case HONEY -> HoneyBlock.INSTANCE;
			case SLIME -> SlimeBlock.INSTANCE;
			case BED -> BedBlock.INSTANCE;
			case STALAGMITE -> PointedDripstoneBlock.INSTANCE;
			case FARMLAND -> FarmlandBlock.INSTANCE;
			case POWDER_SNOW -> PowderSnowBlock.INSTANCE;
			case UNMODELED -> UnmodeledBlock.INSTANCE;
			case UNKNOWN -> UnknownStateBlock.INSTANCE;
		};
		BlockBehavior[] owners = {bubble, powder, inside, contacting, underfoot, landed};
		BlockBehavior single = null;
		for (BlockBehavior owner : owners) {
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
			return INERT;
		}
		// The singleton answers only when it owns exactly the facts present;
		// a slime whose landing fact was captured as ordinary is not a slime.
		int present = 0;
		for (int family = 0; family < owners.length; family++) {
			if (owners[family] == single) {
				present |= 1 << family;
			}
		}
		if (present != single.families() && single != UnknownStateBlock.INSTANCE
		    && single != UnmodeledBlock.INSTANCE) {
			throw new UnimplementedMechanicException(
			    RefusalCause.INADMISSIBLE_BLOCK_FACTS, "incomplete block behavior facts for " + single);
		}
		return single;
	}
}
