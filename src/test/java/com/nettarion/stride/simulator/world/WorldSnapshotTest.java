package com.nettarion.stride.simulator.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorldSnapshotTest {
	@Test
	void anEmptyFluidEntryRebuiltRatherThanSharedIsStillEmpty() {
		// A snapshot read back from a capture, or composed from another, holds
		// its own copy of the empty entry rather than the canonical constant.
		// Emptiness is the kind, never the instance: asking by identity is how
		// a rebuilt entry gets written into a fluid palette as though a cell
		// held something.
		WorldSnapshot.FluidEntry rebuilt = new WorldSnapshot.FluidEntry(
		    0, "minecraft:empty", WorldSnapshot.FluidKind.EMPTY, 0.0, 0.0, 0.0, 0.0, false);

		assertNotSame(WorldSnapshot.FluidEntry.EMPTY, rebuilt);
		assertEquals(WorldSnapshot.FluidEntry.EMPTY, rebuilt);
		assertTrue(rebuilt.isEmpty());
		assertTrue(WorldSnapshot.FluidEntry.EMPTY.isEmpty());
	}

	@Test
	void aFluidEntryCarryingFluidIsNotEmptyWhateverItIsNamed() {
		WorldSnapshot.FluidEntry water =
		    new WorldSnapshot.FluidEntry(1, "minecraft:water", WorldSnapshot.FluidKind.WATER, 1.0, 0.0, 0.0, 0.0, true);

		assertFalse(water.isEmpty());
	}

	@Test
	void rejectsACellWhosePaletteIndexDoesNotExist() {
		WorldSnapshot.BlockEntry air = block(0, "air", List.of());
		assertThrows(IllegalArgumentException.class,
		    () -> new WorldSnapshot(0, 0, 0, 1, 1, 1, WorldSnapshot.OutsideRegion.SEALED, List.of(air), new int[] {1}));
	}

	@Test
	void rejectsAFluidCellWhosePaletteIndexDoesNotExist() {
		WorldSnapshot.BlockEntry air = block(0, "air", List.of());
		assertThrows(IllegalArgumentException.class,
		    ()
		        -> new WorldSnapshot(0, 0, 0, 1, 1, 1, WorldSnapshot.OutsideRegion.SEALED, List.of(air), new int[] {0},
		            List.of(WorldSnapshot.FluidEntry.EMPTY), new int[] {1}));
	}

	@Test
	void allEmptyDenseFluidPlaneCanonicalizesWithoutLosingLookupSemantics() {
		WorldSnapshot snapshot =
		    new WorldSnapshot(0, 0, 0, 2, 1, 1, WorldSnapshot.OutsideRegion.SEALED, List.of(block(0, "air", List.of())),
		        new int[] {0, 0}, List.of(WorldSnapshot.FluidEntry.EMPTY), new int[] {0, 0});

		assertEquals(false, snapshot.hasFluids());
		assertEquals(0, snapshot.fluidCells().length);
		assertEquals(WorldSnapshot.FluidEntry.EMPTY, snapshot.fluidEntryAt(1, 0, 0));
		assertNull(snapshot.fluidEntryAt(2, 0, 0));
	}

	@Test
	void denseCellsCannotMutateASharedSnapshot() {
		WorldSnapshot snapshot = sample();
		int before = snapshot.paletteIndexAt(0, 0, 0);
		int[] exposed = snapshot.cells();
		exposed[0] = 0;

		assertEquals(before, snapshot.paletteIndexAt(0, 0, 0));
	}

	@Test
	void positionsOutsideTheRegionAreNotAir() {
		WorldSnapshot snapshot = sample();
		assertEquals(-1, snapshot.paletteIndexAt(2, 0, 0));
		assertNull(snapshot.entryAt(2, 0, 0));
	}

	private static WorldSnapshot sample() {
		WorldSnapshot.BlockEntry air = block(0, "air", List.of());
		WorldSnapshot.BlockEntry stone = block(1, "stone", List.of(new WorldSnapshot.ShapeBox(0, 0, 0, 1, 1, 1)));
		return new WorldSnapshot(
		    0, 0, 0, 1, 1, 1, WorldSnapshot.OutsideRegion.SEALED, List.of(air, stone), new int[] {1});
	}

	private static WorldSnapshot.BlockEntry block(
	    final int id, final String name, final List<WorldSnapshot.ShapeBox> boxes) {
		return new WorldSnapshot.BlockEntry(id, name, 0.6F, 1.0F, 1.0F, false, false, WorldView.BubbleColumnMode.NONE,
		    false, WorldView.CollisionBehavior.ORDINARY, WorldSnapshot.Suffocation.NO, WorldView.InsideEffect.NONE,
		    0.0F, false, WorldView.StepOn.NONE, false, WorldView.Climbability.NONE, boxes);
	}
}
