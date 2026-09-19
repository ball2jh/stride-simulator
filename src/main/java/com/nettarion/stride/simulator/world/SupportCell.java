package com.nettarion.stride.simulator.world;

import com.nettarion.stride.simulator.FluidSample;
/**
 * Caller-owned storage for {@code Entity.mainSupportingBlockPos}, on the model
 * of {@link FluidSample}: the query writes into it rather than allocating an
 * {@code Optional<BlockPos>} on a path that runs every tick.
 *
 * <p>{@link #present} false is {@code Optional.empty()} and the three
 * coordinates are then meaningless. Vanilla distinguishes the two cases in
 * {@code Entity.getOnPos}, which takes a completely different branch when the
 * position is absent, so a caller must test {@link #present} rather than
 * comparing coordinates against a sentinel.
 */
public final class SupportCell {
	/** Whether the coordinates hold a resolved support cell. */
	public boolean present;
	/** X coordinate of the resolved support cell. */
	public int x;
	/** Y coordinate of the resolved support cell. */
	public int y;
	/** Z coordinate of the resolved support cell. */
	public int z;

	/** Marks this result absent; existing coordinates become unspecified. */
	public void clear() {
		this.present = false;
	}

	/** Stores a resolved support cell and marks this result present. */
	public void set(final int cellX, final int cellY, final int cellZ) {
		this.present = true;
		this.x = cellX;
		this.y = cellY;
		this.z = cellZ;
	}

	/** {@code Vec3i.compareTo} of this cell against another: Y, then Z, then X. */
	public int compareTo(final int cellX, final int cellY, final int cellZ) {
		if (this.y == cellY) {
			return this.z == cellZ ? this.x - cellX : this.z - cellZ;
		}
		return this.y - cellY;
	}
}
