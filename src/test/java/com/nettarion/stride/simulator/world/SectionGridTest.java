package com.nettarion.stride.simulator.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A section grid names the sections a region's bounds touch and numbers them y outermost, then z, then x. */
final class SectionGridTest {
	@Test
	void anAlignedRegionTouchesExactlyItsOwnSections() {
		SectionGrid grid = SectionGrid.over(-32, 0, 16, 48, 16, 32);
		assertEquals(new SectionGrid(-2, 0, 1, 3, 1, 2), grid);
		assertEquals(6, grid.count());
		assertTrue(SectionGrid.aligned(-32, 0, 16, 48, 16, 32));
	}

	@Test
	void anUnalignedRegionTouchesEverySectionItsCellsLieIn() {
		// Cells -5..15 span sections -1 and 0; 3..11 lie in section 0; 7..19 span 0 and 1.
		SectionGrid grid = SectionGrid.over(-5, 3, 7, 21, 9, 13);
		assertEquals(new SectionGrid(-1, 0, 0, 2, 1, 2), grid);
		assertFalse(SectionGrid.aligned(-5, 3, 7, 21, 9, 13));
		assertFalse(SectionGrid.aligned(0, 0, 0, 16, 16, 15), "a size off a section boundary is unaligned");
		// A one-cell region on the last cell of a section touches that section alone.
		assertEquals(new SectionGrid(0, 0, 0, 1, 1, 1), SectionGrid.over(15, 15, 15, 1, 1, 1));
		assertEquals(new SectionGrid(0, 0, 0, 2, 1, 1), SectionGrid.over(15, 15, 15, 2, 1, 1));
	}

	@Test
	void indexNumbersYOutermostThenZThenX() {
		SectionGrid grid = new SectionGrid(0, 0, 0, 3, 2, 4);
		assertEquals(0, grid.index(0, 0, 0));
		assertEquals(1, grid.index(1, 0, 0));
		assertEquals(3, grid.index(0, 0, 1));
		assertEquals(12, grid.index(0, 1, 0));
		assertEquals(grid.count() - 1, grid.index(2, 1, 3));
	}

	@Test
	void aRegionAtTheIntegerLimitStaysInsideTheIntDomain() {
		SectionGrid grid = SectionGrid.over(Integer.MAX_VALUE - 1, 0, 0, 1, 1, 1);
		assertEquals((Integer.MAX_VALUE - 1) >> Section.SHIFT, grid.sectionOriginX());
		assertEquals(1, grid.sectionsX());
	}
}
