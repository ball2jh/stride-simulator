package com.nettarion.stride.simulator.world;

/** Checked closed-open bounds and dense cardinality for a planning snapshot. */
final class PlanningSnapshotRegion {
	private PlanningSnapshotRegion() {}

	static int requireCellCount(
	    final int originX, final int originY, final int originZ, final int sizeX, final int sizeY, final int sizeZ) {
		if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || (long) originX + sizeX > Integer.MAX_VALUE
		    || (long) originY + sizeY > Integer.MAX_VALUE || (long) originZ + sizeZ > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("snapshot region must have positive dimensions and checked int ends");
		}
		long area = (long) sizeX * sizeY;
		if (area > Integer.MAX_VALUE || area * sizeZ > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("snapshot dense cardinality exceeds the int array domain");
		}
		return (int) (area * sizeZ);
	}
}
