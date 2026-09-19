package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.Refusal;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.world.WorldView;

/** Exact fluid tracker sampling and current application for one player state. */
public final class EntityFluidInteraction {
	private static final double SLOW_LAVA_FLOW_SCALE = 0.0023333333333333335;
	private static final double FAST_LAVA_FLOW_SCALE = 0.007;

	private EntityFluidInteraction() {}

	/** Whether any cell scanned by {@link #update} can hold fluid. */
	static boolean mayContainFluid(final PlayerState state, final WorldView world) {
		if (world.propertiesAnywhere(WorldView.PROPERTY_FLUID) == 0) {
			return false;
		}
		int x0 = Mth.floor(state.boundingBoxMinX + 0.001);
		int y0 = Mth.floor(state.boundingBoxMinY + 0.001);
		int z0 = Mth.floor(state.boundingBoxMinZ + 0.001);
		int x1 = (int) Math.ceil(state.boundingBoxMaxX - 0.001) - 1;
		int y1 = (int) Math.ceil(state.boundingBoxMaxY - 0.001) - 1;
		int z1 = (int) Math.ceil(state.boundingBoxMaxZ - 0.001) - 1;
		return world.propertiesIn(WorldView.PROPERTY_FLUID, x0, y0, z0, x1, y1, z1) != 0;
	}

	/**
	 * The tracker refresh {@code LivingEntity.checkFallDamage} opens with: only
	 * when the player was dry before the movement, so first water contact is
	 * visible at the post-move boundary on both the client and the server's copy.
	 */
	public static void refreshWhenDry(final PlayerState state, final WorldView world, final Scratch scratch) {
		if (!(state.waterHeight > 0.0) && mayContainFluid(state, world)) {
			update(state, world, scratch);
		}
	}

	/**
	 * {@code EntityFluidInteraction.update} for the default, unmounted player box.
	 *
	 * <p>Vanilla's {@code hasFluidAndLoaded} first asks that every chunk under
	 * the deflated box widened by one block in X and Z be loaded, and answers
	 * "no fluid at all" when one is not, whatever the box's own cells hold.
	 * The server's copy stands in loaded chunks and answers from them, so an
	 * unloaded neighbour makes the two copies disagree the way an unloaded
	 * column below does for gravity in {@link Travel}; neither outcome is a
	 * captured fact, so the refresh refuses whenever those cells could hold
	 * fluid and a column of the widened footprint is unknown.
	 *
	 * @throws UnimplementedMechanicException when a column within one block of
	 *         the box in X or Z is not loaded and the box's cells may hold fluid
	 */
	static void update(final PlayerState state, final WorldView world, final Scratch scratch) {
		state.waterHeight = 0.0;
		state.lavaHeight = 0.0;
		state.eyeInWater = false;
		if (!mayContainFluid(state, world)) {
			return;
		}

		double minX = state.boundingBoxMinX + 0.001;
		double minY = state.boundingBoxMinY + 0.001;
		double minZ = state.boundingBoxMinZ + 0.001;
		double maxX = state.boundingBoxMaxX - 0.001;
		double maxY = state.boundingBoxMaxY - 0.001;
		double maxZ = state.boundingBoxMaxZ - 0.001;
		int x0 = Mth.floor(minX);
		int y0 = Mth.floor(minY);
		int z0 = Mth.floor(minZ);
		int x1 = (int) Math.ceil(maxX) - 1;
		int y1 = (int) Math.ceil(maxY) - 1;
		int z1 = (int) Math.ceil(maxZ) - 1;
		requireLoadedAround(world, x0 - 1, z0 - 1, x1 + 1, z1 + 1);
		int eyeBlockX = Mth.floor(state.x);
		int eyeBlockZ = Mth.floor(state.z);
		double eyeY = state.y + state.pose.eyeHeight;
		double currentX = 0.0;
		double currentY = 0.0;
		double currentZ = 0.0;
		int currentCount = 0;
		double lavaCurrentX = 0.0;
		double lavaCurrentY = 0.0;
		double lavaCurrentZ = 0.0;
		int lavaCurrentCount = 0;

		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					world.fluidAt(x, y, z, scratch.fluidCell);
					FluidSample cell = scratch.fluidCell;
					if (cell.kind == FluidSample.Kind.EMPTY) {
						continue;
					}
					requireSupported(cell, x, y, z);
					double fluidBottom = y;
					double fluidTop = fluidBottom + cell.height;
					if (!(fluidTop < minY)) {
						if (cell.kind == FluidSample.Kind.WATER && x == eyeBlockX && z == eyeBlockZ
						    && eyeY >= fluidBottom && eyeY <= fluidTop) {
							state.eyeInWater = true;
						}
						double trackerHeight;
						if (cell.kind == FluidSample.Kind.WATER) {
							state.waterHeight = Math.max(fluidTop - state.boundingBoxMinY, state.waterHeight);
							trackerHeight = state.waterHeight;
						} else {
							state.lavaHeight = Math.max(fluidTop - state.boundingBoxMinY, state.lavaHeight);
							trackerHeight = state.lavaHeight;
						}
						double flowX = cell.flowX;
						double flowY = cell.flowY;
						double flowZ = cell.flowZ;
						if (trackerHeight < 0.4) {
							flowX *= trackerHeight;
							flowY *= trackerHeight;
							flowZ *= trackerHeight;
						}
						if (cell.kind == FluidSample.Kind.WATER) {
							currentX += flowX;
							currentY += flowY;
							currentZ += flowZ;
							currentCount++;
						} else {
							lavaCurrentX += flowX;
							lavaCurrentY += flowY;
							lavaCurrentZ += flowZ;
							lavaCurrentCount++;
						}
					}
				}
			}
		}
		if (state.waterHeight > 0.0) {
			state.fallDistance = 0.0;
		}
		if (!state.flying) {
			applyCurrentTo(state, currentX, currentY, currentZ, currentCount, 0.014);
			applyCurrentTo(state, lavaCurrentX, lavaCurrentY, lavaCurrentZ, lavaCurrentCount,
			    state.fastLava ? FAST_LAVA_FLOW_SCALE : SLOW_LAVA_FLOW_SCALE);
		}
	}

	/**
	 * The loaded-chunk half of vanilla's {@code hasFluidAndLoaded}: every column
	 * of the widened footprint must be known. The view answers per column, so
	 * the columns are asked one by one; there are at most sixteen.
	 */
	private static void requireLoadedAround(
	    final WorldView world, final int x0, final int z0, final int x1, final int z1) {
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				if (!world.hasChunkAt(x, z)) {
					int atX = x;
					int atZ = z;
					throw UnimplementedMechanicException.deferred(Refusal.OUTSIDE_REGION,
					    ()
					        -> "unknown space beside the player at " + atX + "," + atZ
					        + "; vanilla's fluid refresh finds no fluid there and the"
					        + " server's copy does not");
				}
			}
		}
	}

	public static FluidSample.Kind kindAt(
	    final WorldView world, final int x, final int y, final int z, final Scratch scratch) {
		if (world.propertiesIn(WorldView.PROPERTY_FLUID, x, y, z, x, y, z) == 0) {
			return FluidSample.Kind.EMPTY;
		}
		world.fluidAt(x, y, z, scratch.fluidCell);
		if (scratch.fluidCell.kind != FluidSample.Kind.EMPTY) {
			requireSupported(scratch.fluidCell, x, y, z);
		}
		return scratch.fluidCell.kind;
	}

	static void requireSupported(final FluidSample cell, final int x, final int y, final int z) {
		if (cell.kind != FluidSample.Kind.WATER && cell.kind != FluidSample.Kind.LAVA) {
			throw UnimplementedMechanicException.deferred(Refusal.UNMODELLED_BLOCK,
			    ()
			        -> "unsupported fluid at " + x + "," + y + "," + z + ": kind=" + cell.kind
			        + ", source=" + cell.source + ", flow=" + cell.flowX + "," + cell.flowY + "," + cell.flowZ);
		}
	}

	/** {@code EntityFluidInteraction.Tracker.applyCurrentTo} for a player. */
	private static void applyCurrentTo(final PlayerState state, final double accumulatedX, final double accumulatedY,
	    final double accumulatedZ, final int count, final double scale) {
		if (count == 0
		    || accumulatedX * accumulatedX + accumulatedY * accumulatedY + accumulatedZ * accumulatedZ < 1.0E-5F) {
			return;
		}

		double inverseCount = 1.0 / count;
		double impulseX = accumulatedX * inverseCount;
		double impulseY = accumulatedY * inverseCount;
		double impulseZ = accumulatedZ * inverseCount;
		double oldX = state.deltaMovementX;
		double oldZ = state.deltaMovementZ;
		impulseX *= scale;
		impulseY *= scale;
		impulseZ *= scale;
		double impulseLength = Math.sqrt(impulseX * impulseX + impulseY * impulseY + impulseZ * impulseZ);
		if (Math.abs(oldX) < 0.003 && Math.abs(oldZ) < 0.003 && impulseLength < 0.0045000000000000005) {
			if (impulseLength < 1.0E-5F) {
				impulseX = 0.0;
				impulseY = 0.0;
				impulseZ = 0.0;
			} else {
				impulseX /= impulseLength;
				impulseY /= impulseLength;
				impulseZ /= impulseLength;
				impulseX *= 0.0045000000000000005;
				impulseY *= 0.0045000000000000005;
				impulseZ *= 0.0045000000000000005;
			}
		}

		state.deltaMovementX += impulseX;
		state.deltaMovementY += impulseY;
		state.deltaMovementZ += impulseZ;
	}
}
