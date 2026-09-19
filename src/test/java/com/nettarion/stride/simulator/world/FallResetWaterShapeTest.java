package com.nettarion.stride.simulator.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.UnimplementedMechanicException;
import org.junit.jupiter.api.Test;

/**
 * {@code Level.clip} for the fall-distance reset against water, whose shape
 * vanilla caches per fluid state at whichever height the first cell that
 * asked for it had. A segment that only meets water between a cell's own
 * height and the full cube has no world-determined answer and refuses.
 */
final class FallResetWaterShapeTest {
	private static final BlockEntry AIR = BlockEntry.builder(0, "minecraft:air").build();

	private static final double OWN_HEIGHT = 8 / 9.0F;

	@Test
	void aSegmentThroughTheBodyOfASourceHits() {
		SnapshotView world = world(OWN_HEIGHT, true);
		assertTrue(world.resetsFallDistanceAlong(0.1, 0.5, 0.5, 1.9, 0.5, 0.5));
	}

	@Test
	void aSegmentAboveTheCubeMisses() {
		SnapshotView world = world(OWN_HEIGHT, true);
		assertFalse(world.resetsFallDistanceAlong(0.1, 1.5, 0.5, 1.9, 1.5, 0.5));
	}

	@Test
	void aSegmentOnlyInTheTopNinthOfASourceRefuses() {
		SnapshotView world = world(OWN_HEIGHT, true);
		UnimplementedMechanicException refusal = assertThrows(
		    UnimplementedMechanicException.class, () -> world.resetsFallDistanceAlong(0.1, 0.95, 0.5, 1.9, 0.95, 0.5));
		assertTrue(refusal.getMessage().contains("session-cached"), refusal.getMessage());
	}

	@Test
	void aSourceUnderWaterKeepsTheSameBandUnresolved() {
		// Captured height 1.0 says the same fluid lies above; the cache may
		// still hold the state's own eight ninths from an earlier cell.
		SnapshotView world = world(1.0, true);
		assertTrue(world.resetsFallDistanceAlong(0.1, 0.5, 0.5, 1.9, 0.5, 0.5));
		assertThrows(
		    UnimplementedMechanicException.class, () -> world.resetsFallDistanceAlong(0.1, 0.95, 0.5, 1.9, 0.95, 0.5));
	}

	@Test
	void aCertainHitFurtherAlongTheSegmentStillAnswers() {
		// Cell 1 is water met only in its top ninth; cell 2 is a tagged block,
		// which the clip meets as a full cube whatever the water's shape.
		BlockEntry cobweb = BlockEntry.builder(9, "minecraft:cobweb").fallDistanceResetting(true).build();
		FluidEntry water = new FluidEntry(7, "minecraft:water", FluidKind.WATER, OWN_HEIGHT, 0.0, 0.0, 0.0, true);
		SnapshotView world = SnapshotView.compile(WorldSnapshot.builder(OutsidePolicy.SEALED, 0, 0, 0, 3, 2, 1)
		        .palette(AIR, cobweb)
		        .fluidPalette(FluidEntry.EMPTY, water)
		        .set(2, 0, 0, 1)
		        .setFluid(1, 0, 0, 1)
		        .build());
		assertTrue(world.resetsFallDistanceAlong(0.1, 0.95, 0.5, 2.9, 0.95, 0.5));
	}

	private static SnapshotView world(final double height, final boolean source) {
		FluidEntry water = new FluidEntry(7, "minecraft:water", FluidKind.WATER, height, 0.0, 0.0, 0.0, source);
		return SnapshotView.compile(WorldSnapshot.builder(OutsidePolicy.SEALED, 0, 0, 0, 2, 2, 1)
		        .palette(AIR)
		        .fluidPalette(FluidEntry.EMPTY, water)
		        .setFluid(1, 0, 0, 1)
		        .build());
	}
}
