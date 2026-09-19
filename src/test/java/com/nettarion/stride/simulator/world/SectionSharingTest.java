package com.nettarion.stride.simulator.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * A snapshot is a grid of immutable sections: a composition or a crop on
 * section boundaries holds its sources' section objects, a uniform section
 * holds no plane, and a part on another palette is re-indexed without
 * losing the facts its cells carry.
 */
final class SectionSharingTest {
	private static final BlockEntry AIR = BlockEntry.builder(0, "minecraft:air").build();

	private static final BlockEntry STONE = BlockEntry.builder(1, "minecraft:stone").solid().build();

	private static final BlockEntry FENCE = BlockEntry.builder(3, "minecraft:oak_fence")
	                                            .boxes(List.of(new ShapeBox(0.375, 0.0, 0.375, 0.625, 1.5, 0.625)))
	                                            .build();

	private static final FluidEntry WATER =
	    new FluidEntry(1, "minecraft:water", FluidKind.WATER, 8.0 / 9.0, 0.0, 0.0, 0.0, true);

	@Test
	void changingOutsidePolicySharesSectionsAndPalettesAndKeepsMissingAndFluidFacts() {
		WorldSnapshot part = WorldSnapshot.builder(OutsidePolicy.SEALED, -16, 0, 0, 16, 16, 16)
		                         .palette(AIR, STONE)
		                         .fluidPalette(FluidEntry.EMPTY, WATER)
		                         .set(-16, 1, 2, 1)
		                         .setFluid(-15, 1, 2, 1)
		                         .build();
		WorldSnapshot source = WorldSnapshot.compose(-16, 0, 0, 32, 16, 16, OutsidePolicy.SEALED, List.of(part));
		WorldSnapshot changed = source.withOutside(OutsidePolicy.REFUSING);
		assertNotSame(source, changed);
		assertSame(source.sections(), changed.sections());
		assertSame(source.grid(), changed.grid());
		assertSame(source.palette(), changed.palette());
		assertSame(source.fluidPalette(), changed.fluidPalette());
		assertSame(source, source.withOutside(OutsidePolicy.SEALED));
		assertSame(changed, changed.withOutside(OutsidePolicy.REFUSING));
		assertThrows(NullPointerException.class, () -> source.withOutside(null));
		assertEquals(OutsidePolicy.SEALED, source.outside());
		assertEquals(OutsidePolicy.REFUSING, changed.outside());
		assertTrue(changed.hasFluids());
		assertTrue(changed.hasMissingSections());
		assertTrue(changed.isMissingAt(0, 1, 2));
		assertEquals(STONE, changed.entryAt(-16, 1, 2));
		assertEquals(WATER, changed.fluidEntryAt(-15, 1, 2));
		assertEquals(source.originX(), changed.originX());
		assertEquals(source.originY(), changed.originY());
		assertEquals(source.originZ(), changed.originZ());
		assertEquals(source.sizeX(), changed.sizeX());
		assertEquals(source.sizeY(), changed.sizeY());
		assertEquals(source.sizeZ(), changed.sizeZ());
		WorldSnapshot cropped = source.crop(-16, 0, 0, 16, 16, 16).withOutside(OutsidePolicy.REFUSING);
		assertSame(part.sections()[0], cropped.sections()[0]);
		assertFalse(cropped.hasMissingSections());
		assertTrue(cropped.hasFluids());
	}

	@Test
	void aRecomposedSnapshotHoldsTheSameSectionObjectsForEverySectionItKept() {
		SharedPalette palette = new SharedPalette();
		int air = palette.index(AIR);
		int stone = palette.index(STONE);
		palette.fluidIndex(WATER);
		Random random = new Random(5L);
		WorldSnapshot[] sections = new WorldSnapshot[4];
		for (int i = 0; i < sections.length; i++) {
			sections[i] = section(palette, i * 16, random, air, stone);
		}
		WorldSnapshot first = WorldSnapshot.compose(
		    0, 0, 0, 48, 16, 16, OutsidePolicy.REFUSING, List.of(sections[0], sections[1], sections[2]));
		WorldSnapshot next = WorldSnapshot.compose(
		    16, 0, 0, 48, 16, 16, OutsidePolicy.REFUSING, List.of(sections[1], sections[2], sections[3]));
		// The two sections both snapshots hold are the same objects in both.
		assertSame(first.sections()[1], next.sections()[0]);
		assertSame(first.sections()[2], next.sections()[1]);
		assertSame(sections[1].sections()[0], next.sections()[0]);
		// And their compiled facts are the same arrays, not re-scanned copies.
		assertSame(first.sections()[1].fineProperties, next.sections()[0].fineProperties);
		for (int x = 16; x < 48; x++) {
			for (int y = 0; y < 16; y += 3) {
				for (int z = 0; z < 16; z += 5) {
					assertEquals(first.paletteIndexAt(x, y, z), next.paletteIndexAt(x, y, z));
				}
			}
		}
	}

	@Test
	void aUniformSectionHoldsNoPlaneAndIsFoundByEveryReadAsIfItDid() {
		WorldSnapshot.Builder builder =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, 0, 0, 0, 32, 16, 16).palette(AIR, STONE);
		for (int x = 0; x < 16; x++) {
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					builder.set(x, y, z, 1);
				}
			}
		}
		builder.set(20, 3, 4, 1);
		WorldSnapshot snapshot = builder.build();
		Section stone = snapshot.sections()[0];
		Section mixed = snapshot.sections()[1];
		assertTrue(stone.isUniform());
		assertNull(stone.rawCells());
		assertEquals(1, stone.uniformIndex());
		assertEquals(Section.CELLS, stone.collisionCount);
		assertFalse(mixed.isUniform());
		assertEquals(1, mixed.collisionCount);
		assertEquals(1, snapshot.paletteIndexAt(7, 7, 7));
		assertEquals(1, snapshot.paletteIndexAt(20, 3, 4));
		assertEquals(0, snapshot.paletteIndexAt(21, 3, 4));
		int[] dense = snapshot.toDenseCells();
		assertEquals(1, dense[(3 * 16 + 4) * 32 + 20]);
		assertEquals(1, dense[(7 * 16 + 7) * 32 + 7]);
		SnapshotView view = SnapshotView.compile(snapshot);
		assertTrue(view.hasCollisionShapeAt(0, 0, 0));
		assertFalse(view.hasCollisionShapeAt(16, 0, 0));
		assertEquals(WorldView.Air.ALL_AIR, view.airIn(16, 0, 0, 19, 15, 15));
		assertEquals(WorldView.Air.NOT_AIR, view.airIn(0, 0, 0, 16, 0, 0));
	}

	@Test
	void airInPastTheBoundsIsSolidWhenSealedAndUnknownWhenRefusing() {
		WorldSnapshot.Builder builder =
		    WorldSnapshot.builder(OutsidePolicy.SEALED, 0, 0, 0, 2, 1, 1).palette(AIR, STONE);
		WorldSnapshot sealed = builder.build();
		assertEquals(WorldView.Air.ALL_AIR, SnapshotView.compile(sealed).airIn(0, 0, 0, 1, 0, 0));
		assertEquals(WorldView.Air.NOT_AIR, SnapshotView.compile(sealed).airIn(0, 0, 0, 2, 0, 0),
		    "sealed space is a full block, which decides");
		SnapshotView refusing = SnapshotView.compile(sealed.withOutside(OutsidePolicy.REFUSING));
		assertEquals(WorldView.Air.UNKNOWN, refusing.airIn(0, 0, 0, 2, 0, 0),
		    "refusing space claims nothing, and the held cells are air");
		SnapshotView withStone = refusing.fork();
		withStone.replaceCell(1, 0, 0, 1);
		assertEquals(WorldView.Air.NOT_AIR, withStone.airIn(-1, 0, 0, 2, 0, 0),
		    "one known non-air cell decides whatever else is unknown");
	}

	@Test
	void aPartOnAnotherPaletteIsReindexedAndKeepsItsFacts() {
		Random random = new Random(9L);
		WorldSnapshot.Builder builder = WorldSnapshot.builder(OutsidePolicy.REFUSING, 0, 0, 0, 16, 16, 16)
		                                    .palette(STONE, AIR, FENCE)
		                                    .fluidPalette(FluidEntry.EMPTY, WATER);
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				int ground = 4 + random.nextInt(4);
				for (int y = 0; y < ground; y++) {
					builder.set(x, y, z, 0);
				}
				for (int y = ground; y < 16; y++) {
					builder.set(x, y, z, 1);
				}
				if (random.nextInt(6) == 0) {
					builder.set(x, ground, z, 2);
				}
				if (random.nextInt(6) == 0) {
					builder.setFluid(x, ground, z, 1);
				}
			}
		}
		WorldSnapshot own = builder.build();
		WorldSnapshot shared =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, 16, 0, 0, 16, 16, 16).palette(AIR, STONE, FENCE).build();
		WorldSnapshot composed =
		    WorldSnapshot.compose(0, 0, 0, 32, 16, 16, OutsidePolicy.REFUSING, List.of(shared, own));
		Section reindexed = composed.sections()[0];
		assertNotSame(own.sections()[0], reindexed);
		assertSame(own.sections()[0].fineProperties, reindexed.fineProperties);
		assertEquals(own.sections()[0].collisionCount, reindexed.collisionCount);
		assertEquals(own.sections()[0].properties, reindexed.properties);
		for (int x = 0; x < 16; x++) {
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					assertEquals(own.entryAt(x, y, z), composed.entryAt(x, y, z), x + "," + y + "," + z);
					assertEquals(own.fluidEntryAt(x, y, z), composed.fluidEntryAt(x, y, z));
				}
			}
		}
		assertSame(shared.sections()[0], composed.sections()[1]);
	}

	@Test
	void anUnalignedRegionPadsItsEdgeSectionsAndReadsExactlyInsideItsBounds() {
		Random random = new Random(2L);
		WorldSnapshot.Builder builder =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, -5, 3, 7, 21, 9, 13).palette(AIR, STONE);
		int[] expected = new int[21 * 9 * 13];
		for (int x = -5; x < 16; x++) {
			for (int y = 3; y < 12; y++) {
				for (int z = 7; z < 20; z++) {
					int value = random.nextInt(2);
					builder.set(x, y, z, value);
					expected[((y - 3) * 13 + (z - 7)) * 21 + (x + 5)] = value;
				}
			}
		}
		WorldSnapshot snapshot = builder.build();
		assertEquals(2 * 1 * 2, snapshot.sections().length);
		assertArrayEquals(expected, snapshot.toDenseCells());
		for (int x = -5; x < 16; x++) {
			for (int y = 3; y < 12; y++) {
				for (int z = 7; z < 20; z++) {
					assertEquals(expected[((y - 3) * 13 + (z - 7)) * 21 + (x + 5)], snapshot.paletteIndexAt(x, y, z));
				}
			}
		}
		assertEquals(-1, snapshot.paletteIndexAt(-6, 3, 7));
		assertEquals(-1, snapshot.paletteIndexAt(16, 3, 7));
		WorldSnapshot cropped = snapshot.crop(-2, 4, 9, 10, 5, 8);
		for (int x = -2; x < 8; x++) {
			for (int y = 4; y < 9; y++) {
				for (int z = 9; z < 17; z++) {
					assertEquals(snapshot.paletteIndexAt(x, y, z), cropped.paletteIndexAt(x, y, z));
				}
			}
		}
	}

	@Test
	void theBuilderRefusesAnIndexThePaletteDoesNotHoldAtTheWrite() {
		WorldSnapshot.Builder builder =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, 0, 0, 0, 2, 2, 2).palette(AIR, STONE);
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> builder.set(1, 1, 1, 2));
		assertTrue(refused.getMessage().contains("1,1,1"), refused.getMessage());
		assertThrows(IllegalArgumentException.class, () -> builder.set(1, 1, 1, -1));
		assertThrows(IllegalArgumentException.class, () -> builder.set(2, 1, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> builder.setFluid(1, 1, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> builder.fluidPalette(WATER));
		// A palette declared after the cells is checked at build.
		WorldSnapshot.Builder late = WorldSnapshot.builder(OutsidePolicy.REFUSING, 0, 0, 0, 2, 2, 2).set(1, 1, 1, 1);
		assertThrows(IllegalArgumentException.class, () -> late.palette(AIR).build());
		assertEquals(1, late.palette(AIR, STONE).build().paletteIndexAt(1, 1, 1));
	}

	private static WorldSnapshot section(
	    final SharedPalette palette, final int originX, final Random random, final int air, final int stone) {
		int[] cells = new int[Section.CELLS];
		int[] fluids = new int[Section.CELLS];
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				int ground = 5 + random.nextInt(5);
				for (int y = 0; y < 16; y++) {
					cells[Section.index(x, y, z)] = y < ground ? stone : air;
				}
				if (random.nextInt(5) == 0) {
					fluids[Section.index(x, ground, z)] = 1;
				}
			}
		}
		return WorldSnapshot.owning(
		    originX, 0, 0, 16, 16, 16, OutsidePolicy.REFUSING, palette.blocks(), cells, palette.fluids(), fluids);
	}
}
