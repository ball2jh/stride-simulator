package com.nettarion.stride.simulator.world;

/**
 * The section coordinates a snapshot's bounds touch: the first section on
 * each axis and how many follow, so a section is found by
 * {@code (world >> Section.SHIFT) - origin} and numbered y outermost, then z,
 * then x.
 *
 * @param sectionOriginX the section coordinate of the first section along X
 * @param sectionOriginY the section coordinate of the first section along Y
 * @param sectionOriginZ the section coordinate of the first section along Z
 * @param sectionsX how many sections the bounds touch along X
 * @param sectionsY how many sections the bounds touch along Y
 * @param sectionsZ how many sections the bounds touch along Z
 */
record SectionGrid(
    int sectionOriginX, int sectionOriginY, int sectionOriginZ, int sectionsX, int sectionsY, int sectionsZ) {
	/** The grid over these closed-open bounds, which need not lie on section boundaries. */
	static SectionGrid over(
	    final int originX, final int originY, final int originZ, final int sizeX, final int sizeY, final int sizeZ) {
		int firstX = originX >> Section.SHIFT;
		int firstY = originY >> Section.SHIFT;
		int firstZ = originZ >> Section.SHIFT;
		int lastX = (originX + sizeX - 1) >> Section.SHIFT;
		int lastY = (originY + sizeY - 1) >> Section.SHIFT;
		int lastZ = (originZ + sizeZ - 1) >> Section.SHIFT;
		return new SectionGrid(firstX, firstY, firstZ, lastX - firstX + 1, lastY - firstY + 1, lastZ - firstZ + 1);
	}

	/** Whether a region's faces all fall on section boundaries. */
	static boolean aligned(
	    final int originX, final int originY, final int originZ, final int sizeX, final int sizeY, final int sizeZ) {
		return (originX & Section.MASK) == 0 && (originY & Section.MASK) == 0 && (originZ & Section.MASK) == 0
		    && (sizeX & Section.MASK) == 0 && (sizeY & Section.MASK) == 0 && (sizeZ & Section.MASK) == 0;
	}

	/** How many sections the grid holds. */
	int count() {
		return this.sectionsX * this.sectionsY * this.sectionsZ;
	}

	/** The array slot of a section by its offset from the grid's origin. */
	int index(final int sectionX, final int sectionY, final int sectionZ) {
		return (sectionY * this.sectionsZ + sectionZ) * this.sectionsX + sectionX;
	}
}
