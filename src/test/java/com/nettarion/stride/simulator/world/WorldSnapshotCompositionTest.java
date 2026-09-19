package com.nettarion.stride.simulator.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class WorldSnapshotCompositionTest {
	@Test
	void joinsPortablePartsAndDeduplicatesTheirPalettes() {
		BlockEntry air = block("air", List.of());
		BlockEntry stone = block("stone", List.of(new ShapeBox(0, 0, 0, 1, 1, 1)));
		WorldSnapshot left = new WorldSnapshot(
		    0, 0, 0, 1, 1, 1, OutsidePolicy.REFUSING, List.of(air, stone), new int[] {1});
		WorldSnapshot right = new WorldSnapshot(
		    1, 0, 0, 1, 1, 1, OutsidePolicy.REFUSING, List.of(stone, air), new int[] {1});

		WorldSnapshot joined = WorldSnapshot.compose(
		    0, 0, 0, 2, 1, 1, OutsidePolicy.REFUSING, List.of(left, right));

		assertEquals(2, joined.palette().size());
		assertEquals(stone, joined.entryAt(0, 0, 0));
		assertEquals(air, joined.entryAt(1, 0, 0));
	}

	@Test
	void refusesAnUnalignedCompositionWithAnUncoveredCell() {
		BlockEntry air = block("air", List.of());
		WorldSnapshot oneCell = new WorldSnapshot(
		    0, 0, 0, 1, 1, 1, OutsidePolicy.REFUSING, List.of(air), new int[] {0});

		// Cell by cell there is no section to be missing; the hole is refused
		// at composition. Section-aligned holes are MissingSectionTest's.
		assertThrows(IllegalArgumentException.class,
		    ()
		        -> WorldSnapshot.compose(
		            0, 0, 0, 2, 1, 1, OutsidePolicy.REFUSING, List.of(oneCell)));
	}

	@Test
	void createsAFluidPlaneOnlyWhenAPartContainsFluid() {
		BlockEntry air = block("air", List.of());
		FluidEntry water =
		    new FluidEntry(1, "water", FluidKind.WATER, 1.0, 0.0, 0.0, 0.0, true);
		WorldSnapshot dry = new WorldSnapshot(
		    0, 0, 0, 1, 1, 1, OutsidePolicy.REFUSING, List.of(air), new int[] {0});
		WorldSnapshot wet = new WorldSnapshot(1, 0, 0, 1, 1, 1, OutsidePolicy.REFUSING,
		    List.of(air), new int[] {0}, List.of(FluidEntry.EMPTY, water), new int[] {1});

		WorldSnapshot joined =
		    WorldSnapshot.compose(0, 0, 0, 2, 1, 1, OutsidePolicy.REFUSING, List.of(dry, wet));

		assertEquals(FluidEntry.EMPTY, joined.fluidEntryAt(0, 0, 0));
		assertEquals(water, joined.fluidEntryAt(1, 0, 0));
	}

	@Test
	void partsOnOneSharedPaletteComposeWithoutRemappingAndAgreeWithRemappedParts() {
		SharedPalette shared = new SharedPalette();
		BlockEntry air = block("air", List.of());
		BlockEntry stone = block("stone", List.of(new ShapeBox(0, 0, 0, 1, 1, 1)));
		BlockEntry slab = block("slab", List.of(new ShapeBox(0, 0, 0, 1, 0.5, 1)));
		// The first section meets air and stone; the second meets slab as well,
		// so its palette is the first's plus one.
		int airIndex = shared.index(air);
		int stoneIndex = shared.index(stone);
		int[] leftCells = new int[16 * 16 * 16];
		for (int i = 0; i < leftCells.length; i++)
			leftCells[i] = (i % 3 == 0) ? stoneIndex : airIndex;
		WorldSnapshot left = WorldSnapshot.owning(
		    0, 0, 0, 16, 16, 16, OutsidePolicy.REFUSING, shared.blocks(), leftCells);
		int slabIndex = shared.index(slab);
		int[] rightCells = new int[16 * 16 * 16];
		for (int i = 0; i < rightCells.length; i++)
			rightCells[i] = (i % 5 == 0) ? slabIndex : (i % 2 == 0) ? stoneIndex : airIndex;
		WorldSnapshot right = WorldSnapshot.owning(
		    16, 0, 0, 16, 16, 16, OutsidePolicy.REFUSING, shared.blocks(), rightCells);

		WorldSnapshot shared2 = WorldSnapshot.compose(
		    0, 0, 0, 32, 16, 16, OutsidePolicy.REFUSING, List.of(left, right));
		// The same cells through parts with their own, differently ordered palettes.
		WorldSnapshot leftOwn = new WorldSnapshot(0, 0, 0, 16, 16, 16, OutsidePolicy.REFUSING,
		    List.of(stone, air), remapped(leftCells, new int[] {1, 0}));
		WorldSnapshot rightOwn =
		    new WorldSnapshot(16, 0, 0, 16, 16, 16, OutsidePolicy.REFUSING,
		        List.of(slab, air, stone), remapped(rightCells, new int[] {1, 2, 0}));
		WorldSnapshot remappedComposite = WorldSnapshot.compose(
		    0, 0, 0, 32, 16, 16, OutsidePolicy.REFUSING, List.of(leftOwn, rightOwn));

		assertEquals(shared.blocks(), shared2.palette(), "a shared prefix is the union");
		for (int x = 0; x < 32; x += 3)
			for (int y = 0; y < 16; y += 5)
				for (int z = 0; z < 16; z += 7) {
					assertEquals(remappedComposite.entryAt(x, y, z), shared2.entryAt(x, y, z), x + "," + y + "," + z);
				}
	}

	private static int[] remapped(final int[] cells, final int[] map) {
		int[] out = new int[cells.length];
		for (int i = 0; i < cells.length; i++)
			out[i] = map[cells[i]];
		return out;
	}

	private static BlockEntry block(final String name, final List<ShapeBox> boxes) {
		return new BlockEntry(name.hashCode(), name, 0.6F, 1.0F, 1.0F, boxes);
	}
}
