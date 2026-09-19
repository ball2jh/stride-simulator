package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.geometry.CollisionCollector;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * Whether the player's box, or a pose's box, fits at a position: the collision-free queries the
 * tick asks the world about itself.
 *
 * <p>Vanilla's {@code Entity.isFree}, {@code Entity.isAboveGround} with its {@code canFallAtLeast}
 * probe, and {@code Player.updatePlayerPose}'s {@code canPlayerFitWithinBlocksAndEntitiesWhen},
 * each transcribed. These are queries, not phases: {@link AiStep} asks about the crouch,
 * {@link Travel} about the fluid ledge climb, {@link Move} about the sneaking edge, and
 * {@link PlayerPose} about the pose, and none of them changes movement state beyond the pose-fit
 * cache {@link PlayerState} retains.
 *
 * <p>Reads position, box, pose, fall distance, and the powder-snow boots flag; writes only the
 * pose-fit cache. Refuses through the collision and fluid queries when they reach an unclassified
 * block or an unmodeled fluid.
 */
final class Clearance {
	/*
	 * Pose fit. Every admitted player pose is 0.6 wide and differs only in
	 * height -- SWIMMING and FALL_FLYING 0.6, CROUCHING 1.5, STANDING 1.8 -- so
	 * one query over the tallest box a caller needs gathers every box relevant to
	 * the shorter ones too, and the per-pose answer is one more comparison
	 * against each gathered box rather than another walk of the world.
	 *
	 * Pose-fit cache invariant (PlayerState.hasCachedPoseFit): a recorded fit
	 * names the exact position bits, the pose width, the tallest height shown to
	 * fit, and the world identity plus collision version (or span revision) it
	 * was shown in. A fit at one height covers every shorter same-width pose.
	 * PlayerState clears it on any position, box or pose write, and only this
	 * class, Move (after a collision that kept the box clear) and PlayerPose
	 * (after a resize it just checked) record one. Worlds with context-sensitive
	 * collision are never cached: their answer depends on the player, not just
	 * the position.
	 */

	static final int FIT_SWIMMING = 1;

	static final int FIT_CROUCHING = 2;

	static final int FIT_STANDING = 4;

	private Clearance() {}

	/**
	 * Which same-width poses fit at the player's current position, as a mask of the {@code FIT_*}
	 * bits.
	 *
	 * <p>Returned bits are meaningful only for poses no taller than {@code tallest}: a shorter probe
	 * cannot say anything about a taller pose, and callers ask for exactly the height they will
	 * consult.
	 */
	static int fitPoses(
	    final PlayerState state, final WorldView world, final PlayerState.Pose tallest, final Scratch scratch) {
		int all = FIT_SWIMMING | FIT_CROUCHING | FIT_STANDING;
		// A cached fit at this height covers every shorter same-width pose, which
		// is exactly what the cache's height comparison already means.
		if (!world.hasContextSensitiveCollision() && state.hasCachedPoseFit(world, tallest)) {
			return all;
		}
		double halfWidth = tallest.width / 2.0F;
		double minX = state.x - halfWidth + 1.0E-7;
		double minY = state.y + 1.0E-7;
		double minZ = state.z - halfWidth + 1.0E-7;
		double maxX = state.x + halfWidth - 1.0E-7;
		double maxZ = state.z + halfWidth - 1.0E-7;
		double swimmingMaxY = state.y + PlayerState.Pose.SWIMMING.height - 1.0E-7;
		double crouchingMaxY = state.y + PlayerState.Pose.CROUCHING.height - 1.0E-7;
		double tallestMaxY = state.y + tallest.height - 1.0E-7;
		CollisionBuffer collisions = CollisionCollector.collect(world, scratch, minX, minY, minZ, maxX, tallestMaxY,
		    maxZ, state.y, PlayerTick.isShiftKeyDown(state, scratch), state.fallDistance, state.canWalkOnPowderSnow);
		int fits = all;
		final int selected = collisions.selectedGroupCount();
		for (int k = 0; k < selected; k++) {
			final int group = collisions.selectedGroup(k);
			final int end = collisions.groupEnd(group);
			for (int i = collisions.groupStart(group); i < end; i++) {
				// The five bounds every pose shares. Only the box top separates them,
				// so it is the only test repeated per pose.
				if (!(minX < collisions.maxX(i) && maxX > collisions.minX(i) && minY < collisions.maxY(i)
				        && minZ < collisions.maxZ(i) && maxZ > collisions.minZ(i))) {
					continue;
				}
				double blockedFrom = collisions.minY(i);
				if (swimmingMaxY > blockedFrom) {
					fits &= ~FIT_SWIMMING;
				}
				if (crouchingMaxY > blockedFrom) {
					fits &= ~FIT_CROUCHING;
				}
				if (tallestMaxY > blockedFrom) {
					fits &= ~FIT_STANDING;
				}
			}
		}
		if (!world.hasContextSensitiveCollision()) {
			// Record the tallest pose actually shown to fit, since the cache
			// covers every shorter one for free.
			if (tallest == PlayerState.Pose.STANDING && (fits & FIT_STANDING) != 0) {
				state.cachePoseFit(world, PlayerState.Pose.STANDING);
			} else if (tallest.height >= PlayerState.Pose.CROUCHING.height && (fits & FIT_CROUCHING) != 0) {
				state.cachePoseFit(world, PlayerState.Pose.CROUCHING);
			} else if ((fits & FIT_SWIMMING) != 0) {
				state.cachePoseFit(world, PlayerState.Pose.SWIMMING);
			}
		}
		return fits;
	}

	/** {@code Entity.isAboveGround}: on the ground, or less than a step above something. */
	static boolean isAboveGround(
	    final PlayerState state, final WorldView world, final boolean descending, final Scratch scratch) {
		return state.onGround
		    || state.fallDistance < PlayerAttributes.MAX_UP_STEP
		    && !canFallAtLeast(
		        state, world, 0.0, 0.0, PlayerAttributes.MAX_UP_STEP - state.fallDistance, descending, scratch);
	}

	/** {@code Entity.canFallAtLeast}: nothing collides in the slab {@code minHeight} under the moved box. */
	static boolean canFallAtLeast(final PlayerState state, final WorldView world, final double dx, final double dz,
	    final double minHeight, final boolean descending, final Scratch scratch) {
		return CollisionCollector
		    .collect(world, scratch, state.boundingBoxMinX + 1.0E-7 + dx, state.boundingBoxMinY - minHeight - 1.0E-7,
		        state.boundingBoxMinZ + 1.0E-7 + dz, state.boundingBoxMaxX - 1.0E-7 + dx, state.boundingBoxMinY,
		        state.boundingBoxMaxZ - 1.0E-7 + dz, state.y, descending, state.fallDistance, state.canWalkOnPowderSnow)
		    .isEmpty();
	}

	/** {@code Entity.isFree}: the moved box meets no collision shape and no fluid cell. */
	static boolean isFree(final PlayerState state, final double dx, final double dy, final double dz,
	    final boolean descending, final WorldView world, final Scratch scratch) {
		double minX = state.boundingBoxMinX + dx;
		double minY = state.boundingBoxMinY + dy;
		double minZ = state.boundingBoxMinZ + dz;
		double maxX = state.boundingBoxMaxX + dx;
		double maxY = state.boundingBoxMaxY + dy;
		double maxZ = state.boundingBoxMaxZ + dz;
		if (!CollisionCollector
		        .collect(world, scratch, minX, minY, minZ, maxX, maxY, maxZ, state.y, descending, state.fallDistance,
		            state.canWalkOnPowderSnow)
		        .isEmpty()) {
			return false;
		}
		int x0 = Mth.floor(minX);
		int y0 = Mth.floor(minY);
		int z0 = Mth.floor(minZ);
		int x1 = (int) Math.ceil(maxX) - 1;
		int y1 = (int) Math.ceil(maxY) - 1;
		int z1 = (int) Math.ceil(maxZ) - 1;
		if (world.propertiesIn(WorldView.PROPERTY_FLUID, x0, y0, z0, x1, y1, z1) == 0) {
			return true;
		}
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					world.fluidAt(x, y, z, scratch.fluidCell);
					if (scratch.fluidCell.kind != FluidSample.Kind.EMPTY) {
						EntityFluidInteraction.requireSupported(scratch.fluidCell, x, y, z);
						// LevelReader.containsAnyLiquid tests the block state's
						// fluid for every cell overlapped by the candidate AABB. It
						// deliberately does not compare the resolved fluid height.
						return false;
					}
				}
			}
		}
		return true;
	}
}
