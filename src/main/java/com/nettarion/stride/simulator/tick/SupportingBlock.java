package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.geometry.CollisionCollector;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.world.SupportCell;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * The block underfoot: which cell supports the player, and the friction, speed, and jump factors
 * that block applies.
 *
 * <p>Vanilla's {@code Entity.checkSupportingBlock} with its retained
 * {@code mainSupportingBlockPos}, {@code Entity.getOnPosLegacy},
 * {@code Entity.getBlockPosBelowThatAffectsMyMovement}, and the {@code getBlockSpeedFactor} and
 * {@code getBlockJumpFactor} reads, each transcribed. A query the tick asks from {@link Travel}
 * (friction), {@link AiStep} (the jump), {@link Move} (support, the destination speed factor,
 * restitution), {@code BlockEffects} (the {@code stepOn} cell), and the server package's
 * {@code ServerMovementListener} (the landed cell of an accepted packet).
 *
 * <p>Reads position, box, ground, and the retained support; {@link #checkSupportingBlock} writes
 * the retained support identity and {@code onGroundNoBlocks}, and nothing else here writes state.
 * {@link #getOnPosLegacy} writes the derived cell into {@link Scratch#support}. Refuses through the
 * support query when the slab reaches an unclassified block.
 */
public final class SupportingBlock {
	private SupportingBlock() {}

	/**
	 * {@code Entity.checkSupportingBlock} for the on-ground case a step can reach, with the state's
	 * own {@code onGround}.
	 *
	 * <p>The slab is a micron under the box, so on ordinary ground it always finds something:
	 * {@code onGround} means a collision stopped the descent, which puts a shape's top face exactly
	 * at {@code boundingBoxMinY}, and the slab reaches it.
	 *
	 * <p>The previous {@code onGroundNoBlocks} latch decides whether an empty first search clears the
	 * retained position or triggers the backward second search.
	 */
	public static void checkSupportingBlock(final PlayerState state, final double movedX, final double movedZ,
	    final boolean descending, final WorldView world, final Scratch scratch) {
		checkSupportingBlockWithGround(state, state.onGround, movedX, movedZ, descending, world, scratch);
	}

	/**
	 * {@code Entity.checkSupportingBlock(onGround, movement)} with the ground answer supplied by the
	 * caller, as the listener's corrected path passes the packet's bit without installing it.
	 */
	public static void checkSupportingBlockWithGround(final PlayerState state, final boolean onGround,
	    final double movedX, final double movedZ, final boolean descending, final WorldView world,
	    final Scratch scratch) {
		if (!onGround) {
			scratch.support.clear();
			commit(state, scratch.support);
			state.onGroundNoBlocks = false;
			return;
		}
		CollisionCollector.findSupportingBlock(world, scratch, state.boundingBoxMinX, state.boundingBoxMinY - 1.0E-6,
		    state.boundingBoxMinZ, state.boundingBoxMaxX, state.boundingBoxMinY, state.boundingBoxMaxZ, state.x,
		    state.y, state.z, state.y, descending, state.fallDistance, state.canWalkOnPowderSnow, scratch.support);
		if (!scratch.support.present && !state.onGroundNoBlocks) {
			// The second search, over the slab moved back along this tick's travel.
			CollisionCollector.findSupportingBlock(world, scratch, state.boundingBoxMinX - movedX,
			    state.boundingBoxMinY - 1.0E-6, state.boundingBoxMinZ - movedZ, state.boundingBoxMaxX - movedX,
			    state.boundingBoxMinY, state.boundingBoxMaxZ - movedZ, state.x, state.y, state.z, state.y, descending,
			    state.fallDistance, state.canWalkOnPowderSnow, scratch.support);
		}
		commit(state, scratch.support);
		state.onGroundNoBlocks = !scratch.support.present;
	}

	/**
	 * The former name of {@link #checkSupportingBlockWithGround}, kept only until
	 * {@code ServerMovementListener} calls the new one; not annotated deprecated because the build
	 * treats lint as an error.
	 */
	public static void checkSupportingBlock(final PlayerState state, final boolean onGround, final double movedX,
	    final double movedZ, final boolean descending, final WorldView world, final Scratch scratch) {
		checkSupportingBlockWithGround(state, onGround, movedX, movedZ, descending, world, scratch);
	}

	/**
	 * {@code Entity.getOnPosLegacy}, which is {@code getOnPos(0.2F)}.
	 *
	 * <p>X and Z come from the supporting block and Y from the player, except for fences, walls and
	 * fence gates, which keep the supporting block's own Y. With no supporting block the whole
	 * position is {@code floor} of the player's, which is a different cell near an edge — that
	 * difference is why this is transcribed rather than approximated.
	 *
	 * <p>Writes a derived cell into {@code scratch.support} without mutating the retained identity in
	 * {@link PlayerState}.
	 */
	public static void getOnPosLegacy(final PlayerState state, final WorldView world, final Scratch scratch) {
		if (!state.mainSupportingBlockPosPresent) {
			scratch.support.set(Mth.floor(state.x), Mth.floor(state.y - 0.2F), Mth.floor(state.z));
			return;
		}
		scratch.support.set(
		    state.mainSupportingBlockPosX, state.mainSupportingBlockPosY, state.mainSupportingBlockPosZ);
		if (world.retainsSupportPosAt(scratch.support.x, scratch.support.y, scratch.support.z)) {
			return;
		}
		scratch.support.y = Mth.floor(state.y - 0.2F);
	}

	static int getBlockPosBelowThatAffectsMyMovementX(final PlayerState state) {
		return state.mainSupportingBlockPosPresent ? state.mainSupportingBlockPosX : Mth.floor(state.x);
	}

	static int getBlockPosBelowThatAffectsMyMovementY(final PlayerState state, final WorldView world) {
		if (state.mainSupportingBlockPosPresent
		    && world.retainsSupportPosAt(
		        state.mainSupportingBlockPosX, state.mainSupportingBlockPosY, state.mainSupportingBlockPosZ)) {
			return state.mainSupportingBlockPosY;
		}
		return Mth.floor(state.y - (double) 0.500001F);
	}

	static int getBlockPosBelowThatAffectsMyMovementZ(final PlayerState state) {
		return state.mainSupportingBlockPosPresent ? state.mainSupportingBlockPosZ : Mth.floor(state.z);
	}

	private static void commit(final PlayerState state, final SupportCell support) {
		state.mainSupportingBlockPosPresent = support.present;
		if (support.present) {
			state.mainSupportingBlockPosX = support.x;
			state.mainSupportingBlockPosY = support.y;
			state.mainSupportingBlockPosZ = support.z;
		} else {
			// The trace contract canonicalizes Optional.empty() to zero coordinates.
			// Keeping stale coordinates would make two semantically identical empty
			// optionals compare differently after stepping off an edge.
			state.mainSupportingBlockPosX = 0;
			state.mainSupportingBlockPosY = 0;
			state.mainSupportingBlockPosZ = 0;
		}
	}

	static float getBlockJumpFactor(final PlayerState state, final WorldView world) {
		// The support cell is derived first because deriving it can refuse; the
		// whole-view answer then decides whether the cells are worth naming.
		int belowY = getBlockPosBelowThatAffectsMyMovementY(state, world);
		if (world.propertiesAnywhere(WorldView.PROPERTY_JUMP_FACTOR) == 0) {
			return 1.0F;
		}
		int hereX = Mth.floor(state.x);
		int hereY = Mth.floor(state.y);
		int hereZ = Mth.floor(state.z);
		int belowX = getBlockPosBelowThatAffectsMyMovementX(state);
		int belowZ = getBlockPosBelowThatAffectsMyMovementZ(state);
		if (world.propertiesIn(WorldView.PROPERTY_JUMP_FACTOR, Math.min(hereX, belowX), Math.min(hereY, belowY),
		        Math.min(hereZ, belowZ), Math.max(hereX, belowX), Math.max(hereY, belowY), Math.max(hereZ, belowZ))
		    == 0) {
			return 1.0F;
		}
		float here = world.jumpFactor(hereX, hereY, hereZ);
		return here == 1.0F ? world.jumpFactor(belowX, belowY, belowZ) : here;
	}

	static float getBlockSpeedFactor(final PlayerState state, final WorldView world) {
		if (state.flying || state.fallFlying) {
			return 1.0F;
		}
		// The support cell is derived first because deriving it can refuse; the
		// whole-view answer then decides whether the cells are worth naming.
		int belowY = getBlockPosBelowThatAffectsMyMovementY(state, world);
		if (world.propertiesAnywhere(WorldView.PROPERTY_SPEED_FACTOR) == 0) {
			return 1.0F;
		}
		int x = Mth.floor(state.x);
		int y = Mth.floor(state.y);
		int z = Mth.floor(state.z);
		int belowX = getBlockPosBelowThatAffectsMyMovementX(state);
		int belowZ = getBlockPosBelowThatAffectsMyMovementZ(state);
		if (world.propertiesIn(WorldView.PROPERTY_SPEED_FACTOR, Math.min(x, belowX), Math.min(belowY, y),
		        Math.min(z, belowZ), Math.max(x, belowX), Math.max(belowY, y), Math.max(z, belowZ))
		    == 0) {
			return 1.0F;
		}
		float here = world.speedFactor(x, y, z);
		if (world.suppressesSupportingSpeedFactor(x, y, z) || here != 1.0F) {
			return here;
		}
		return world.speedFactor(belowX, belowY, belowZ);
	}
}
