package com.nettarion.stride.simulator.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.FluidEntry;
import com.nettarion.stride.simulator.world.FluidKind;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.ShapeProvenance;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.Suffocation;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The world file carries every palette fact explicitly; nothing is inferred from a name or a shape on read. */
final class WorldSnapshotCodecTest {
	private static WorldSnapshot sample() {
		// A slab is the shape that matters here: half-height geometry is what a flat floor could
		// never represent.
		BlockEntry air = BlockEntry.builder(0, "minecraft:air").build();
		BlockEntry stone = BlockEntry.builder(1, "minecraft:stone").boxes(new ShapeBox(0, 0, 0, 1, 1, 1)).build();
		BlockEntry slab = BlockEntry.builder(2, "minecraft:stone_slab").boxes(new ShapeBox(0, 0, 0, 1, 0.5, 1)).build();
		return WorldSnapshot.builder(OutsidePolicy.REFUSING, 10, -60, 20, 2, 2, 2)
		    .palette(air, stone, slab)
		    .set(10, -60, 20, 1)
		    .set(11, -60, 21, 2)
		    .build();
	}

	private static WorldSnapshot roundTrip(final WorldSnapshot original) throws IOException {
		StringWriter writer = new StringWriter();
		WorldSnapshotCodec.write(original, writer);
		return WorldSnapshotCodec.read(new BufferedReader(new StringReader(writer.toString())));
	}

	private static WorldSnapshot oneCell(final BlockEntry entry) {
		return WorldSnapshot.builder(OutsidePolicy.SEALED, 0, 0, 0, 1, 1, 1).palette(entry).build();
	}

	@Test
	void roundTripPreservesShapeBitsAndCells() throws IOException {
		WorldSnapshot original = sample();

		WorldSnapshot decoded = roundTrip(original);

		assertEquals(original.originX(), decoded.originX());
		assertEquals(original.outside(), decoded.outside());
		assertEquals(original.palette(), decoded.palette());
		for (int y = -60; y < -58; y++) {
			for (int z = 20; z < 22; z++) {
				for (int x = 10; x < 12; x++) {
					assertEquals(
					    original.paletteIndexAt(x, y, z), decoded.paletteIndexAt(x, y, z), x + "," + y + "," + z);
				}
			}
		}
	}

	@Test
	void roundTripPreservesCanonicalFullIdentityApartFromGeometry() throws IOException {
		BlockEntry canonical = BlockEntry.builder(1, "test:canonical").fullCube().build();
		BlockEntry general = BlockEntry.builder(2, "test:general-unit-cube").boxes(ShapeBox.FULL_CUBE).build();
		WorldSnapshot original = WorldSnapshot.builder(OutsidePolicy.SEALED, 0, 0, 0, 2, 1, 1)
		                             .palette(canonical, general)
		                             .set(1, 0, 0, 1)
		                             .build();

		WorldSnapshot decoded = roundTrip(original);

		assertTrue(decoded.palette().get(0).canonicalFullCollisionShape());
		assertFalse(decoded.palette().get(1).canonicalFullCollisionShape());
		assertEquals(decoded.palette().get(0).boxes(), decoded.palette().get(1).boxes(),
		    "box geometry alone cannot carry singleton shape identity");
	}

	@Test
	void versionThirteenUnitCubesRetainLegacyClassificationWithoutInventingIdentity() throws IOException {
		BlockEntry general =
		    BlockEntry.builder(1, "test:legacy-unit-cube").boxes(new ShapeBox(-0.0, -0.0, -0.0, 1.0, 1.0, 1.0)).build();
		StringWriter writer = new StringWriter();
		WorldSnapshotCodec.write(oneCell(general), writer);

		WorldSnapshot decoded =
		    WorldSnapshotCodec.read(new BufferedReader(new StringReader(asVersionThirteen(writer.toString()))));

		assertEquals(ShapeProvenance.LEGACY_GEOMETRY, decoded.palette().getFirst().shapeProvenance());
		assertFalse(decoded.palette().getFirst().canonicalFullCollisionShape(),
		    "legacy geometry classification is not catalog identity");

		CollisionBuffer collisions = new CollisionBuffer();
		SnapshotView.compile(decoded).collectCollisionBoxes(0.0, 0.1, 0.1, Math.nextDown(1.0E-7), 0.9, 0.9, collisions);
		assertEquals(1, collisions.size(), "numeric legacy classification still accepts negative-zero unit minima");
	}

	/**
	 * Every palette fact that a reader could be tempted to infer from the name or the shape, each
	 * declared on a block whose name and shape say nothing about it.
	 */
	static Stream<Arguments> explicitPaletteFacts() {
		return Stream.of(Arguments.of("moving piston",
		                     BlockEntry.builder(36, "not-used-for-behavior")
		                         .movingPiston(true)
		                         .boxes(new ShapeBox(0, 0, 0, 1.5, 1, 1))
		                         .build(),
		                     (UnaryOperator<BlockEntry>) entry -> entry, true, "movingPiston"),
		    Arguments.of("bubble column mode",
		        BlockEntry.builder(77, "not-used-for-behavior")
		            .suppressesSupportingSpeedFactor(true)
		            .bubbleColumnMode(WorldView.BubbleColumnMode.PUSH_UP)
		            .build(),
		        null, WorldView.BubbleColumnMode.PUSH_UP, "bubbleColumnMode"),
		    Arguments.of("fall-distance resetting",
		        BlockEntry.builder(88, "not-used-for-behavior")
		            .fallDistanceResetting(true)
		            .climbability(WorldView.Climbability.CLIMBABLE)
		            .build(),
		        null, true, "fallDistanceResetting"),
		    Arguments.of("climbability role",
		        BlockEntry.builder(89, "not-used-for-behavior")
		            .suffocation(Suffocation.NO)
		            .climbability(WorldView.Climbability.OPEN_TRAPDOOR_WEST)
		            .build(),
		        null, WorldView.Climbability.OPEN_TRAPDOOR_WEST, "climbability"),
		    Arguments.of("supported scaffolding",
		        BlockEntry.builder(89, "not-used-for-behavior")
		            .fallDistanceResetting(true)
		            .collisionBehavior(WorldView.CollisionBehavior.SCAFFOLDING_SUPPORTED)
		            .climbability(WorldView.Climbability.CLIMBABLE)
		            .build(),
		        null, WorldView.CollisionBehavior.SCAFFOLDING_SUPPORTED, "collisionBehavior"),
		    Arguments.of("unstable-bottom scaffolding",
		        BlockEntry.builder(90, "not-used-for-behavior")
		            .fallDistanceResetting(true)
		            .collisionBehavior(WorldView.CollisionBehavior.SCAFFOLDING_UNSTABLE_BOTTOM)
		            .suffocation(Suffocation.NO)
		            .climbability(WorldView.Climbability.CLIMBABLE)
		            .build(),
		        null, WorldView.CollisionBehavior.SCAFFOLDING_UNSTABLE_BOTTOM, "collisionBehavior"),
		    Arguments.of("powder snow collision",
		        BlockEntry.builder(90, "not-used-for-behavior")
		            .collisionBehavior(WorldView.CollisionBehavior.POWDER_SNOW_NO_BOOTS)
		            .build(),
		        null, WorldView.CollisionBehavior.POWDER_SNOW_NO_BOOTS, "collisionBehavior"),
		    Arguments.of("supporting speed factor suppression",
		        BlockEntry.builder(42, "not-used-for-behavior").suppressesSupportingSpeedFactor(true).build(), null,
		        true, "suppressesSupportingSpeedFactor"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("explicitPaletteFacts")
	void roundTripPreservesEachExplicitPaletteFact(final String fact, final BlockEntry entry,
	    final UnaryOperator<BlockEntry> ignored, final Object expected, final String accessor) throws Exception {
		WorldSnapshot decoded = roundTrip(oneCell(entry));

		BlockEntry decodedEntry = decoded.palette().getFirst();
		assertEquals(expected, BlockEntry.class.getMethod(accessor).invoke(decodedEntry), fact);
		assertEquals(entry, decodedEntry, fact);
	}

	@Test
	void roundTripPreservesResolvedFluidBits() throws IOException {
		double height = (double) 0.9F;
		double flowX = Double.longBitsToDouble(1L);
		double flowY = -0.0;
		double flowZ = -0.125;
		FluidEntry water =
		    new FluidEntry(7, "minecraft:flowing_water", FluidKind.WATER, height, flowX, flowY, flowZ, false);
		WorldSnapshot original = WorldSnapshot.builder(OutsidePolicy.SEALED, 0, 0, 0, 1, 1, 1)
		                             .palette(BlockEntry.builder(42, "not-used-for-behavior").build())
		                             .fluidPalette(FluidEntry.EMPTY, water)
		                             .setFluid(0, 0, 0, 1)
		                             .build();

		WorldSnapshot decoded = roundTrip(original);
		FluidEntry decodedWater = decoded.fluidEntryAt(0, 0, 0);

		assertTrue(decoded.hasFluids());
		assertEquals(water.fluidStateId(), decodedWater.fluidStateId());
		assertEquals(water.name(), decodedWater.name());
		assertEquals(water.kind(), decodedWater.kind());
		assertEquals(Double.doubleToRawLongBits(height), Double.doubleToRawLongBits(decodedWater.height()));
		assertEquals(Double.doubleToRawLongBits(flowX), Double.doubleToRawLongBits(decodedWater.flowX()));
		assertEquals(Double.doubleToRawLongBits(flowY), Double.doubleToRawLongBits(decodedWater.flowY()));
		assertEquals(Double.doubleToRawLongBits(flowZ), Double.doubleToRawLongBits(decodedWater.flowZ()));
	}

	@Test
	void roundTripPreservesSuffocationIndependentlyOfShapeAndName() throws IOException {
		BlockEntry stone = BlockEntry.builder(1, "minecraft:stone")
		                       .suffocation(Suffocation.YES)
		                       .boxes(new ShapeBox(0, 0, 0, 1, 1, 1))
		                       .build();
		BlockEntry glass = BlockEntry.builder(2, "minecraft:glass")
		                       .suffocation(Suffocation.NO)
		                       .boxes(new ShapeBox(0, 0, 0, 1, 1, 1))
		                       .build();
		WorldSnapshot snapshot =
		    WorldSnapshot.builder(OutsidePolicy.SEALED, 0, 0, 0, 2, 1, 1).palette(stone, glass).set(1, 0, 0, 1).build();

		WorldSnapshot decoded = roundTrip(snapshot);

		assertEquals(Suffocation.YES, decoded.entryAt(0, 0, 0).suffocation());
		assertEquals(Suffocation.NO, decoded.entryAt(1, 0, 0).suffocation());
	}

	@Test
	void roundTripPreservesInsideEffectIndependentlyOfShapeAndName() throws IOException {
		// None carries geometry and none is named anything the reader keys on, so the decoded
		// effect can only have come from the written column.
		BlockEntry web = BlockEntry.builder(1, "minecraft:cobweb")
		                     .suffocation(Suffocation.NO)
		                     .insideEffect(WorldView.InsideEffect.COBWEB)
		                     .build();
		BlockEntry bush = BlockEntry.builder(2, "minecraft:sweet_berry_bush")
		                      .suffocation(Suffocation.NO)
		                      .insideEffect(WorldView.InsideEffect.SWEET_BERRY_BUSH)
		                      .build();
		BlockEntry lavaCauldron = BlockEntry.builder(3, "minecraft:lava_cauldron")
		                              .suffocation(Suffocation.NO)
		                              .insideEffect(WorldView.InsideEffect.LAVA_CAULDRON)
		                              .build();
		BlockEntry air = BlockEntry.builder(0, "minecraft:air").build();
		WorldSnapshot snapshot = WorldSnapshot.builder(OutsidePolicy.SEALED, 0, 0, 0, 4, 1, 1)
		                             .palette(web, bush, lavaCauldron, air)
		                             .set(1, 0, 0, 1)
		                             .set(2, 0, 0, 2)
		                             .set(3, 0, 0, 3)
		                             .build();

		WorldSnapshot decoded = roundTrip(snapshot);

		assertEquals(WorldView.InsideEffect.COBWEB, decoded.entryAt(0, 0, 0).insideEffect());
		assertEquals(WorldView.InsideEffect.SWEET_BERRY_BUSH, decoded.entryAt(1, 0, 0).insideEffect());
		assertEquals(WorldView.InsideEffect.LAVA_CAULDRON, decoded.entryAt(2, 0, 0).insideEffect());
		assertEquals(WorldView.InsideEffect.NONE, decoded.entryAt(3, 0, 0).insideEffect());
	}

	@Test
	void halfHeightGeometrySurvivesTheCodec() throws IOException {
		WorldSnapshot decoded = roundTrip(sample());

		ShapeBox slab = decoded.palette().get(2).boxes().getFirst();
		assertEquals(Double.doubleToRawLongBits(0.5), Double.doubleToRawLongBits(slab.maxY()));
	}

	@Test
	void paletteColumnIndicesMatchTheWrittenRow() throws IOException {
		StringWriter writer = new StringWriter();
		WorldSnapshotCodec.write(sample(), writer);
		String paletteRow =
		    writer.toString().lines().filter(line -> line.startsWith("p\t2\t")).findFirst().orElseThrow();
		String[] cells = paletteRow.split("\t", -1);

		int version = WorldSnapshotCodec.FORMAT_VERSION;
		assertEquals("minecraft:stone_slab", cells[WorldSnapshotCodec.PaletteColumn.NAME.indexIn(version)]);
		assertEquals("GENERAL", cells[WorldSnapshotCodec.PaletteColumn.SHAPE_PROVENANCE.indexIn(version)]);
		assertEquals("NONE", cells[WorldSnapshotCodec.PaletteColumn.CONTACT.indexIn(version)]);
		assertEquals("ORDINARY", cells[WorldSnapshotCodec.PaletteColumn.LANDING.indexIn(version)]);
		assertEquals("1", cells[WorldSnapshotCodec.PaletteColumn.BOX_COUNT.indexIn(version)]);
		assertEquals(WorldSnapshotCodec.PaletteColumn.BOX_COUNT.indexIn(version) + 2, cells.length);
		assertEquals(19, WorldSnapshotCodec.PaletteColumn.BOX_COUNT.indexIn(WorldSnapshotCodec.OLDEST_FORMAT_VERSION));
	}

	/** Drops the provenance, contact and landing columns the later formats added. */
	private static String asVersionThirteen(final String current) {
		StringBuilder migrated = new StringBuilder();
		int firstDropped = WorldSnapshotCodec.PaletteColumn.SHAPE_PROVENANCE.indexIn(WorldSnapshotCodec.FORMAT_VERSION);
		int lastDropped = WorldSnapshotCodec.PaletteColumn.LANDING.indexIn(WorldSnapshotCodec.FORMAT_VERSION);
		for (String line : current.split("\\n")) {
			if (line.startsWith("#stride-world\t")) {
				migrated.append("#stride-world\t13\n");
				continue;
			}
			if (!line.startsWith("p\t")) {
				migrated.append(line).append('\n');
				continue;
			}
			String[] fields = line.split("\t", -1);
			for (int field = 0; field < fields.length; field++) {
				if (field < firstDropped || field > lastDropped) {
					migrated.append(field == 0 ? "" : "\t").append(fields[field]);
				}
			}
			migrated.append('\n');
		}
		return migrated.toString();
	}
}
