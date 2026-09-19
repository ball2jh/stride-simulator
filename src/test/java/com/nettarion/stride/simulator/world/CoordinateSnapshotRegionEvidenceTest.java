package com.nettarion.stride.simulator.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Executable counterexample for unchecked closed-open snapshot ends. */
final class CoordinateSnapshotRegionEvidenceTest {
	@Test
	void integerMaximumOriginIsRefusedAndTheAdjacentOneCellRegionIsExact() {
		WorldSnapshot.BlockEntry air = new WorldSnapshot.BlockEntry(0, "minecraft:air", 0.6F, 1.0F, 1.0F, List.of());
		assertThrows(IllegalArgumentException.class,
		    ()
		        -> WorldSnapshot.builder(
		            WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING, Integer.MAX_VALUE, 0, 0, 1, 1, 1));

		WorldSnapshot adjacent =
		    WorldSnapshot.builder(WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING, Integer.MAX_VALUE - 1, 0, 0, 1, 1, 1)
		        .palette(air)
		        .build();
		assertEquals(Integer.MAX_VALUE, adjacent.originX() + adjacent.sizeX());
		assertTrue(adjacent.contains(Integer.MAX_VALUE - 1, 0, 0));
		assertEquals(0, adjacent.paletteIndexAt(Integer.MAX_VALUE - 1, 0, 0));
	}

	@Test
	void refusesEmptyAndNonRepresentableDenseRegionsBeforeAllocation() {
		assertThrows(IllegalArgumentException.class,
		    () -> WorldSnapshot.builder(WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING, 0, 0, 0, 0, 1, 1));
		assertThrows(IllegalArgumentException.class,
		    () -> WorldSnapshot.builder(WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING, 0, 0, 0, 50_000, 1, 50_000));
	}
}
