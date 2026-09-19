package com.nettarion.stride.simulator.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A snapshot validates its planes against its palettes, canonicalizes an
 * empty fluid plane, hands out copies of its cells, and answers nothing
 * outside its bounds.
 */
final class WorldSnapshotTest {
	@Test
	void anEmptyFluidEntryRebuiltRatherThanSharedIsStillEmpty() {
		// A snapshot read back from a capture, or composed from another, holds
		// its own copy of the empty entry rather than the canonical constant.
		// Emptiness is the kind, never the instance: asking by identity is how
		// a rebuilt entry gets written into a fluid palette as though a cell
		// held something.
		FluidEntry rebuilt = new FluidEntry(0, "minecraft:empty", FluidKind.EMPTY, 0.0, 0.0, 0.0, 0.0, false);

		assertNotSame(FluidEntry.EMPTY, rebuilt);
		assertEquals(FluidEntry.EMPTY, rebuilt);
		assertTrue(rebuilt.isEmpty());
		assertTrue(FluidEntry.EMPTY.isEmpty());
	}

	@Test
	void aFluidEntryCarryingFluidIsNotEmptyWhateverItIsNamed() {
		FluidEntry water = new FluidEntry(1, "minecraft:water", FluidKind.WATER, 1.0, 0.0, 0.0, 0.0, true);

		assertFalse(water.isEmpty());
	}

	@Test
	void rejectsACellWhosePaletteIndexDoesNotExist() {
		BlockEntry air = block(0, "air", List.of());
		assertThrows(IllegalArgumentException.class,
		    () -> WorldSnapshot.owning(0, 0, 0, 1, 1, 1, OutsidePolicy.SEALED, List.of(air), new int[] {1}));
	}

	@Test
	void rejectsAFluidCellWhosePaletteIndexDoesNotExist() {
		BlockEntry air = block(0, "air", List.of());
		assertThrows(IllegalArgumentException.class,
		    ()
		        -> WorldSnapshot.owning(0, 0, 0, 1, 1, 1, OutsidePolicy.SEALED, List.of(air), new int[] {0},
		            List.of(FluidEntry.EMPTY), new int[] {1}));
	}

	@Test
	void rejectsAPlaneOfTheWrongLengthAndAFluidPaletteNotStartingEmpty() {
		BlockEntry air = block(0, "air", List.of());
		FluidEntry water = new FluidEntry(1, "minecraft:water", FluidKind.WATER, 1.0, 0.0, 0.0, 0.0, true);
		assertThrows(IllegalArgumentException.class,
		    () -> WorldSnapshot.owning(0, 0, 0, 2, 1, 1, OutsidePolicy.SEALED, List.of(air), new int[] {0}));
		assertThrows(IllegalArgumentException.class,
		    ()
		        -> WorldSnapshot.owning(0, 0, 0, 1, 1, 1, OutsidePolicy.SEALED, List.of(air), new int[] {0},
		            List.of(FluidEntry.EMPTY), new int[] {0, 0}));
		assertThrows(IllegalArgumentException.class,
		    ()
		        -> WorldSnapshot.owning(0, 0, 0, 1, 1, 1, OutsidePolicy.SEALED, List.of(air), new int[] {0},
		            List.of(water, FluidEntry.EMPTY), new int[] {0}));
	}

	@Test
	void allEmptyDenseFluidPlaneCanonicalizesWithoutLosingLookupSemantics() {
		WorldSnapshot snapshot = WorldSnapshot.owning(0, 0, 0, 2, 1, 1, OutsidePolicy.SEALED,
		    List.of(block(0, "air", List.of())), new int[] {0, 0}, List.of(FluidEntry.EMPTY), new int[] {0, 0});

		assertFalse(snapshot.hasFluids());
		assertEquals(0, snapshot.toDenseFluidCells().length);
		assertEquals(FluidEntry.EMPTY, snapshot.fluidEntryAt(1, 0, 0));
		assertNull(snapshot.fluidEntryAt(2, 0, 0));
	}

	@Test
	void denseCellsCannotMutateASharedSnapshot() {
		WorldSnapshot snapshot = sample();
		int before = snapshot.paletteIndexAt(0, 0, 0);
		int[] exposed = snapshot.toDenseCells();
		exposed[0] = 0;

		assertEquals(before, snapshot.paletteIndexAt(0, 0, 0));
	}

	@Test
	void positionsOutsideTheRegionAreNotAir() {
		WorldSnapshot snapshot = sample();
		assertEquals(-1, snapshot.paletteIndexAt(2, 0, 0));
		assertNull(snapshot.entryAt(2, 0, 0));
	}

	@Test
	void theBuilderMakesAnOrdinarySolidBlockSuffocateAndLeavesADeclaredAnswerAlone() {
		BlockEntry stone = BlockEntry.builder(1, "minecraft:stone").solid().build();
		assertEquals(Suffocation.YES, stone.suffocation());
		assertEquals(ShapeProvenance.CANONICAL_FULL, stone.shapeProvenance());
		assertTrue(stone.canonicalFullCollisionShape());
		BlockEntry glass = BlockEntry.builder(2, "minecraft:glass").suffocation(Suffocation.NO).solid().build();
		assertEquals(Suffocation.NO, glass.suffocation());
		BlockEntry bare = BlockEntry.builder(3, "minecraft:leaves").fullCube().build();
		assertEquals(Suffocation.UNKNOWN, bare.suffocation(), "fullCube alone leaves suffocation undeclared");
		assertThrows(IllegalArgumentException.class,
		    ()
		        -> new BlockEntry(4, "bad", 0.6F, 1.0F, 1.0F, false, false, WorldView.BubbleColumnMode.NONE, false,
		            WorldView.CollisionBehavior.ORDINARY, Suffocation.NO, WorldView.InsideEffect.NONE, 0.0F, false,
		            WorldView.StepOn.NONE, false, WorldView.Climbability.NONE, ShapeProvenance.CANONICAL_FULL,
		            WorldView.Contact.NONE, WorldView.Landing.ORDINARY, List.of()),
		    "a canonical full shape must be exactly one unit cube");
	}

	@Test
	void aLayeredCauldronsContactIsUnmodeledHoweverTheEntryIsBuilt() {
		for (String name : new String[] {"minecraft:water_cauldron", "minecraft:powder_snow_cauldron"}) {
			assertTrue(BlockEntry.isCauldronWithUnmodeledContact(name));
			assertEquals(WorldView.Contact.UNMODELED, BlockEntry.legacyContact(name));
			assertEquals(WorldView.Contact.UNMODELED,
			    BlockEntry.builder(1, name).contact(WorldView.Contact.NONE).build().contact(),
			    "the canonical constructor keeps a body the name proves exists from passing as inert");
		}
		assertFalse(BlockEntry.isCauldronWithUnmodeledContact("minecraft:lava_cauldron"));
		assertEquals(
		    WorldView.Contact.LAVA_CAULDRON, BlockEntry.builder(1, "minecraft:lava_cauldron").build().contact());
	}

	private static WorldSnapshot sample() {
		BlockEntry air = block(0, "air", List.of());
		BlockEntry stone = block(1, "stone", List.of(ShapeBox.FULL_CUBE));
		return WorldSnapshot.owning(0, 0, 0, 1, 1, 1, OutsidePolicy.SEALED, List.of(air, stone), new int[] {1});
	}

	private static BlockEntry block(final int id, final String name, final List<ShapeBox> boxes) {
		return BlockEntry.builder(id, name).suffocation(Suffocation.NO).boxes(boxes).build();
	}
}
