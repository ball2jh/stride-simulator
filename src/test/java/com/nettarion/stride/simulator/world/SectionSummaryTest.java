package com.nettarion.stride.simulator.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * A summary assembled from parts or cut from a source must answer every
 * coarse question exactly as a summary scanned from the same cells does, and
 * a view that edits its cells must not write through the shared summary.
 */
class SectionSummaryTest {
	private static final BlockEntry AIR = BlockEntry.builder(0, "minecraft:air").build();
	private static final BlockEntry STONE =
	    BlockEntry.builder(1, "minecraft:stone").fullCube().build();
	private static final BlockEntry ICE =
	    BlockEntry.builder(2, "minecraft:ice").fullCube().friction(0.98F).build();
	private static final BlockEntry FENCE =
	    BlockEntry.builder(3, "minecraft:oak_fence")
	        .boxes(new ShapeBox(0.375, 0.0, 0.375, 0.625, 1.5, 0.625))
	        .retainsSupportPos(true)
	        .build();
	private static final BlockEntry VINE =
	    BlockEntry.builder(4, "minecraft:vine").climbability(WorldView.Climbability.CLIMBABLE).build();
	private static final FluidEntry WATER = new FluidEntry(
	    1, "minecraft:water", FluidKind.WATER, 8.0 / 9.0, 0.0, 0.0, 0.0, true);

	@Test
	void aSnapshotComposedFromAlignedPartsAnswersLikeOneScannedFromItsCells() {
		Random random = new Random(11L);
		List<WorldSnapshot> parts = new ArrayList<>();
		for (int sectionX = 0; sectionX < 3; sectionX++) {
			for (int sectionZ = 0; sectionZ < 2; sectionZ++) {
				parts.add(part(random, sectionX * 16, -16, sectionZ * 16 + 32, 32));
			}
		}
		WorldSnapshot composed =
		    WorldSnapshot.compose(0, -16, 32, 48, 32, 32, OutsidePolicy.REFUSING, parts);
		// Parts on the union's palette are placed by reference, not copied.
		assertSame(parts.get(0).sections()[0], composed.sections()[composed.grid().index(0, 0, 0)]);
		WorldSnapshot scanned =
		    new WorldSnapshot(0, -16, 32, 48, 32, 32, OutsidePolicy.REFUSING,
		        composed.palette(), composed.cells(), composed.fluidPalette(), composed.fluidCells());
		assertSummariesAgree(composed.summary(), scanned.summary());
		assertViewsAgree(new SnapshotView(composed), new SnapshotView(scanned), random, 0, -16, 32, 48, 32, 32);
	}

	@Test
	void aSectionAlignedCropCarriesItsSourceSummaryAndAnUnalignedOneScans() {
		Random random = new Random(7L);
		List<WorldSnapshot> parts = new ArrayList<>();
		for (int sectionX = 0; sectionX < 4; sectionX++) {
			parts.add(part(random, sectionX * 16, 0, 0, 16));
		}
		WorldSnapshot source =
		    WorldSnapshot.compose(0, 0, 0, 64, 16, 16, OutsidePolicy.REFUSING, parts);
		WorldSnapshot aligned = source.crop(16, 0, 0, 32, 16, 16);
		WorldSnapshot rescanned =
		    new WorldSnapshot(16, 0, 0, 32, 16, 16, OutsidePolicy.REFUSING, aligned.palette(),
		        aligned.cells(), aligned.fluidPalette(), aligned.fluidCells());
		assertSummariesAgree(aligned.summary(), rescanned.summary());
		// An aligned window shares its source's sections outright.
		assertSame(source.sections()[source.grid().index(1, 0, 0)], aligned.sections()[0]);
		assertSame(parts.get(1).sections()[0], aligned.sections()[0]);
		WorldSnapshot unaligned = source.crop(5, 0, 3, 20, 16, 9);
		WorldSnapshot unalignedRescanned =
		    new WorldSnapshot(5, 0, 3, 20, 16, 9, OutsidePolicy.REFUSING, unaligned.palette(),
		        unaligned.cells(), unaligned.fluidPalette(), unaligned.fluidCells());
		assertSummariesAgree(unaligned.summary(), unalignedRescanned.summary());
		assertViewsAgree(new SnapshotView(unaligned), new SnapshotView(unalignedRescanned), random, 5, 0, 3, 20, 16, 9);
	}

	@Test
	void editingOneViewDoesNotWriteThroughTheSummaryAnotherViewShares() {
		Random random = new Random(3L);
		WorldSnapshot snapshot = part(random, 0, 0, 0, 16);
		SnapshotView edited = new SnapshotView(snapshot);
		SnapshotView pristine = new SnapshotView(snapshot);
		int fenceIndex = snapshot.palette().indexOf(FENCE);
		int airIndex = snapshot.palette().indexOf(AIR);
		// A fence in an air cell adds a large shape, collision and a support fact.
		int x = 3;
		int y = 15;
		int z = 4;
		assertEquals(airIndex, snapshot.paletteIndexAt(x, y, z), "the test cell must start as air");
		edited.replaceCell(x, y, z, fenceIndex);
		assertSame(snapshot.summary(), snapshot.summary());
		assertNotSame(edited, pristine);
		assertEquals(pristine.propertiesIn(WorldView.PROPERTIES_ALL, x, y, z, x, y, z),
		    new SnapshotView(snapshot).propertiesIn(WorldView.PROPERTIES_ALL, x, y, z, x, y, z),
		    "the untouched view and a fresh one read the same summary");
		assertEquals(true, edited.hasCollisionShapeAt(x, y, z));
		assertEquals(false, pristine.hasCollisionShapeAt(x, y, z));
	}

	/** One 16-wide section column of random terrain with fences, ice, vines and pools. */
	private static WorldSnapshot part(
	    final Random random, final int originX, final int originY, final int originZ, final int sizeY) {
		WorldSnapshot.Builder builder =
		    WorldSnapshot
		        .builder(OutsidePolicy.REFUSING, originX, originY, originZ, 16, sizeY, 16)
		        .palette(AIR, STONE, ICE, FENCE, VINE)
		        .fluidPalette(FluidEntry.EMPTY, WATER);
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				int ground = originY + sizeY / 2 + random.nextInt(5) - 2;
				for (int y = originY; y < ground && y < originY + sizeY; y++) {
					builder.set(originX + x, y, originZ + z, random.nextInt(9) == 0 ? 2 : 1);
				}
				if (ground < originY + sizeY) {
					double roll = random.nextDouble();
					if (roll < 0.05)
						builder.set(originX + x, ground, originZ + z, 3);
					else if (roll < 0.10)
						builder.set(originX + x, ground, originZ + z, 4);
					else if (roll < 0.16)
						builder.setFluid(originX + x, ground, originZ + z, 1);
				}
			}
		}
		// Leave the top row of the first column free for the edit test.
		return builder.set(originX + 3, originY + sizeY - 1, originZ + 4, 0).build();
	}

	private static void assertSummariesAgree(final SectionSummary actual, final SectionSummary expected) {
		assertEquals(expected.sectionsX, actual.sectionsX);
		assertEquals(expected.sectionsY, actual.sectionsY);
		assertEquals(expected.sectionsZ, actual.sectionsZ);
		org.junit.jupiter.api.Assertions.assertArrayEquals(expected.counts, actual.counts);
		org.junit.jupiter.api.Assertions.assertArrayEquals(expected.properties, actual.properties);
		org.junit.jupiter.api.Assertions.assertArrayEquals(expected.large, actual.large);
		org.junit.jupiter.api.Assertions.assertArrayEquals(expected.fineProperties, actual.fineProperties);
		org.junit.jupiter.api.Assertions.assertArrayEquals(expected.fineLarge, actual.fineLarge);
		org.junit.jupiter.api.Assertions.assertArrayEquals(expected.uniform, actual.uniform);
	}

	private static void assertViewsAgree(final SnapshotView actual, final SnapshotView expected, final Random random,
	    final int originX, final int originY, final int originZ, final int sizeX, final int sizeY, final int sizeZ) {
		for (int trial = 0; trial < 2000; trial++) {
			int x0 = originX + random.nextInt(sizeX);
			int y0 = originY + random.nextInt(sizeY);
			int z0 = originZ + random.nextInt(sizeZ);
			int x1 = Math.min(originX + sizeX - 1, x0 + random.nextInt(6));
			int y1 = Math.min(originY + sizeY - 1, y0 + random.nextInt(6));
			int z1 = Math.min(originZ + sizeZ - 1, z0 + random.nextInt(6));
			assertEquals(expected.propertiesIn(WorldView.PROPERTIES_ALL, x0, y0, z0, x1, y1, z1),
			    actual.propertiesIn(WorldView.PROPERTIES_ALL, x0, y0, z0, x1, y1, z1));
			assertEquals(expected.spanHasCollisionPotential(x0, y0, z0, x1, y1, z1),
			    actual.spanHasCollisionPotential(x0, y0, z0, x1, y1, z1));
			assertEquals(
			    expected.collisionVersionIn(x0, y0, z0, x1, y1, z1), actual.collisionVersionIn(x0, y0, z0, x1, y1, z1));
		}
	}
}
