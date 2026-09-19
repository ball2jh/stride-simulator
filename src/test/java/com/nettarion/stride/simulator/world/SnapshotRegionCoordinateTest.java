package com.nettarion.stride.simulator.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A snapshot's closed-open ends are checked in {@code long} arithmetic, so a region at the int limit is exact. */
final class SnapshotRegionCoordinateTest {
	@Test
	void integerMaximumOriginIsRefusedAndTheAdjacentOneCellRegionIsExact() {
		BlockEntry air = BlockEntry.builder(0, "minecraft:air").build();
		assertThrows(IllegalArgumentException.class,
		    () -> WorldSnapshot.builder(OutsidePolicy.REFUSING, Integer.MAX_VALUE, 0, 0, 1, 1, 1));

		WorldSnapshot adjacent =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, Integer.MAX_VALUE - 1, 0, 0, 1, 1, 1).palette(air).build();
		assertEquals(Integer.MAX_VALUE, adjacent.originX() + adjacent.sizeX());
		assertTrue(adjacent.contains(Integer.MAX_VALUE - 1, 0, 0));
		assertEquals(0, adjacent.paletteIndexAt(Integer.MAX_VALUE - 1, 0, 0));
	}

	@Test
	void refusesEmptyAndNonRepresentableDenseRegionsBeforeAllocation() {
		assertThrows(
		    IllegalArgumentException.class, () -> WorldSnapshot.builder(OutsidePolicy.REFUSING, 0, 0, 0, 0, 1, 1));
		assertThrows(IllegalArgumentException.class,
		    () -> WorldSnapshot.builder(OutsidePolicy.REFUSING, 0, 0, 0, 50_000, 1, 50_000));
	}
}
