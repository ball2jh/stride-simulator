package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorldSnapshotCodecTest {
	@Test
	void rejectsACellWhosePaletteIndexDoesNotExist() {
		WorldSnapshot.BlockEntry air = new WorldSnapshot.BlockEntry(0, "air", 0.6F, 1.0F, 1.0F, List.of());
		assertThrows(IllegalArgumentException.class,
		    () -> new WorldSnapshot(0, 0, 0, 1, 1, 1, WorldSnapshot.OutsideRegion.SEALED, List.of(air), new int[] {1}));
	}

	@Test
	void rejectsAFluidCellWhosePaletteIndexDoesNotExist() {
		WorldSnapshot.BlockEntry air = new WorldSnapshot.BlockEntry(0, "air", 0.6F, 1.0F, 1.0F, List.of());
		assertThrows(IllegalArgumentException.class,
		    ()
		        -> new WorldSnapshot(0, 0, 0, 1, 1, 1, WorldSnapshot.OutsideRegion.SEALED, List.of(air), new int[] {0},
		            List.of(WorldSnapshot.FluidEntry.EMPTY), new int[] {1}));
	}

	private static WorldSnapshot sample() {
		// A slab is the shape that matters here: half-height geometry is what
		// the flat-floor world could never represent.
		WorldSnapshot.BlockEntry air = new WorldSnapshot.BlockEntry(0, "minecraft:air", 0.6F, 1.0F, 1.0F, List.of());
		WorldSnapshot.BlockEntry stone = new WorldSnapshot.BlockEntry(
		    1, "minecraft:stone", 0.6F, 1.0F, 1.0F, List.of(new WorldSnapshot.ShapeBox(0, 0, 0, 1, 1, 1)));
		WorldSnapshot.BlockEntry slab = new WorldSnapshot.BlockEntry(
		    2, "minecraft:stone_slab", 0.6F, 1.0F, 1.0F, List.of(new WorldSnapshot.ShapeBox(0, 0, 0, 1, 0.5, 1)));

		int[] cells = new int[2 * 2 * 2];
		cells[0] = 1;
		cells[3] = 2;
		return new WorldSnapshot(
		    10, -60, 20, 2, 2, 2, WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING, List.of(air, stone, slab), cells);
	}

	@Test
	void roundTripPreservesShapeBitsAndCells() throws IOException {
		WorldSnapshot original = sample();
		StringWriter writer = new StringWriter();
		WorldSnapshotCodec.write(original, writer);
		WorldSnapshot decoded = WorldSnapshotCodec.read(new BufferedReader(new StringReader(writer.toString())));

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
		WorldSnapshot.BlockEntry canonical = WorldSnapshot.BlockEntry.builder(1, "test:canonical").fullCube().build();
		WorldSnapshot.BlockEntry general = WorldSnapshot.BlockEntry.builder(2, "test:general-unit-cube")
		                                       .boxes(WorldSnapshot.ShapeBox.FULL_CUBE)
		                                       .build();
		WorldSnapshot original = new WorldSnapshot(
		    0, 0, 0, 2, 1, 1, WorldSnapshot.OutsideRegion.SEALED, List.of(canonical, general), new int[] {0, 1});
		StringWriter writer = new StringWriter();
		WorldSnapshotCodec.write(original, writer);

		WorldSnapshot decoded = WorldSnapshotCodec.read(new BufferedReader(new StringReader(writer.toString())));

		assertEquals(true, decoded.palette().get(0).canonicalFullCollisionShape());
		assertEquals(false, decoded.palette().get(1).canonicalFullCollisionShape());
		assertEquals(decoded.palette().get(0).boxes(), decoded.palette().get(1).boxes(),
		    "box geometry alone cannot carry singleton shape identity");
	}

	@Test
	void versionThirteenUnitCubesRetainLegacyClassificationWithoutInventingIdentity() throws IOException {
		WorldSnapshot.BlockEntry general = WorldSnapshot.BlockEntry.builder(1, "test:legacy-unit-cube")
		                                       .boxes(new WorldSnapshot.ShapeBox(-0.0, -0.0, -0.0, 1.0, 1.0, 1.0))
		                                       .build();
		WorldSnapshot original =
		    new WorldSnapshot(0, 0, 0, 1, 1, 1, WorldSnapshot.OutsideRegion.SEALED, List.of(general), new int[] {0});
		StringWriter writer = new StringWriter();
		WorldSnapshotCodec.write(original, writer);

		WorldSnapshot decoded =
		    WorldSnapshotCodec.read(new BufferedReader(new StringReader(asVersionThirteen(writer.toString()))));

		assertEquals(WorldSnapshot.CollisionShapeIdentity.LEGACY_GEOMETRY,
		    decoded.palette().getFirst().collisionShapeIdentity());
		assertEquals(false, decoded.palette().getFirst().canonicalFullCollisionShape(),
		    "legacy geometry classification is not source identity");

		CollisionBuffer collisions = new CollisionBuffer();
		new SnapshotView(decoded).collectCollisionBoxes(0.0, 0.1, 0.1, Math.nextDown(1.0E-7), 0.9, 0.9, collisions);
		assertEquals(1, collisions.size(), "numeric legacy classification still accepts negative-zero unit minima");
	}

	@Test
	void roundTripPreservesExplicitMovingPistonBehavior() throws IOException {
		WorldSnapshot.BlockEntry piston = new WorldSnapshot.BlockEntry(36, "not-used-for-behavior", 0.6F, 1.0F, 1.0F,
		    true, false, WorldView.BubbleColumnMode.NONE, false, WorldView.CollisionBehavior.ORDINARY,
		    WorldSnapshot.Suffocation.UNKNOWN, WorldView.InsideEffect.NONE, 0.0F, false, WorldView.StepOn.NONE, false,
		    WorldView.Climbability.NONE, List.of(new WorldSnapshot.ShapeBox(0, 0, 0, 1.5, 1, 1)));
		WorldSnapshot original =
		    new WorldSnapshot(0, 0, 0, 1, 1, 1, WorldSnapshot.OutsideRegion.SEALED, List.of(piston), new int[] {0});
		StringWriter writer = new StringWriter();
		WorldSnapshotCodec.write(original, writer);

		WorldSnapshot decoded = WorldSnapshotCodec.read(new BufferedReader(new StringReader(writer.toString())));

		assertEquals(true, decoded.palette().getFirst().movingPiston());
	}

	@Test
	void roundTripPreservesBubbleColumnModeWithoutInferringFromName() throws IOException {
		WorldSnapshot.BlockEntry bubble = new WorldSnapshot.BlockEntry(77, "not-used-for-behavior", 0.6F, 1.0F, 1.0F,
		    false, true, WorldView.BubbleColumnMode.PUSH_UP, false, WorldView.CollisionBehavior.ORDINARY,
		    WorldSnapshot.Suffocation.UNKNOWN, WorldView.InsideEffect.NONE, 0.0F, false, WorldView.StepOn.NONE, false,
		    WorldView.Climbability.NONE, List.of());
		WorldSnapshot original =
		    new WorldSnapshot(0, 0, 0, 1, 1, 1, WorldSnapshot.OutsideRegion.SEALED, List.of(bubble), new int[] {0});
		StringWriter writer = new StringWriter();
		WorldSnapshotCodec.write(original, writer);

		WorldSnapshot decoded = WorldSnapshotCodec.read(new BufferedReader(new StringReader(writer.toString())));

		assertEquals(WorldView.BubbleColumnMode.PUSH_UP, decoded.palette().getFirst().bubbleColumnMode());
	}

	@Test
	void roundTripPreservesExplicitClimbableBehaviorWithoutInferringFromShape() throws IOException {
		WorldSnapshot.BlockEntry vine = new WorldSnapshot.BlockEntry(88, "not-used-for-behavior", 0.6F, 1.0F, 1.0F,
		    false, false, WorldView.BubbleColumnMode.NONE, true, WorldView.CollisionBehavior.ORDINARY,
		    WorldSnapshot.Suffocation.UNKNOWN, WorldView.InsideEffect.NONE, 0.0F, false, WorldView.StepOn.NONE, false,
		    WorldView.Climbability.CLIMBABLE, List.of());
		WorldSnapshot original =
		    new WorldSnapshot(0, 0, 0, 1, 1, 1, WorldSnapshot.OutsideRegion.SEALED, List.of(vine), new int[] {0});
		StringWriter writer = new StringWriter();
		WorldSnapshotCodec.write(original, writer);

		WorldSnapshot decoded = WorldSnapshotCodec.read(new BufferedReader(new StringReader(writer.toString())));

		assertEquals(true, decoded.palette().getFirst().fallDistanceResetting());
	}

	@Test
	void roundTripPreservesResolvedClimbabilityRole() throws IOException {
		WorldSnapshot.BlockEntry trapdoor = new WorldSnapshot.BlockEntry(89, "not-used-for-behavior", 0.6F, 1.0F, 1.0F,
		    false, false, WorldView.BubbleColumnMode.NONE, false, WorldView.CollisionBehavior.ORDINARY,
		    WorldSnapshot.Suffocation.NO, WorldView.InsideEffect.NONE, 0.0F, false, WorldView.StepOn.NONE, false,
		    WorldView.Climbability.OPEN_TRAPDOOR_WEST, List.of());
		WorldSnapshot original =
		    new WorldSnapshot(0, 0, 0, 1, 1, 1, WorldSnapshot.OutsideRegion.SEALED, List.of(trapdoor), new int[] {0});
		StringWriter writer = new StringWriter();
		WorldSnapshotCodec.write(original, writer);

		WorldSnapshot decoded = WorldSnapshotCodec.read(new BufferedReader(new StringReader(writer.toString())));

		assertEquals(WorldView.Climbability.OPEN_TRAPDOOR_WEST, decoded.palette().getFirst().climbability());
	}

	@Test
	void roundTripPreservesExplicitContextSensitiveCollisionBehavior() throws IOException {
		WorldSnapshot.BlockEntry scaffolding =
		    new WorldSnapshot.BlockEntry(89, "not-used-for-behavior", 0.6F, 1.0F, 1.0F, false, false,
		        WorldView.BubbleColumnMode.NONE, true, WorldView.CollisionBehavior.SCAFFOLDING_SUPPORTED,
		        WorldSnapshot.Suffocation.UNKNOWN, WorldView.InsideEffect.NONE, 0.0F, false, WorldView.StepOn.NONE,
		        false, WorldView.Climbability.CLIMBABLE, List.of());
		WorldSnapshot original = new WorldSnapshot(
		    0, 0, 0, 1, 1, 1, WorldSnapshot.OutsideRegion.SEALED, List.of(scaffolding), new int[] {0});
		StringWriter writer = new StringWriter();
		WorldSnapshotCodec.write(original, writer);

		WorldSnapshot decoded = WorldSnapshotCodec.read(new BufferedReader(new StringReader(writer.toString())));

		assertEquals(
		    WorldView.CollisionBehavior.SCAFFOLDING_SUPPORTED, decoded.palette().getFirst().collisionBehavior());
	}

	@Test
	void roundTripPreservesUnstableBottomScaffoldingBehavior() throws IOException {
		WorldSnapshot.BlockEntry scaffolding =
		    new WorldSnapshot.BlockEntry(90, "not-used-for-behavior", 0.6F, 1.0F, 1.0F, false, false,
		        WorldView.BubbleColumnMode.NONE, true, WorldView.CollisionBehavior.SCAFFOLDING_UNSTABLE_BOTTOM,
		        WorldSnapshot.Suffocation.NO, WorldView.InsideEffect.NONE, 0.0F, false, WorldView.StepOn.NONE, false,
		        WorldView.Climbability.CLIMBABLE, List.of());
		WorldSnapshot original = new WorldSnapshot(
		    0, 0, 0, 1, 1, 1, WorldSnapshot.OutsideRegion.SEALED, List.of(scaffolding), new int[] {0});
		StringWriter writer = new StringWriter();
		WorldSnapshotCodec.write(original, writer);

		WorldSnapshot decoded = WorldSnapshotCodec.read(new BufferedReader(new StringReader(writer.toString())));

		assertEquals(
		    WorldView.CollisionBehavior.SCAFFOLDING_UNSTABLE_BOTTOM, decoded.palette().getFirst().collisionBehavior());
	}

	@Test
	void roundTripPreservesPowderSnowCollisionBehavior() throws IOException {
		WorldSnapshot.BlockEntry powder = new WorldSnapshot.BlockEntry(90, "not-used-for-behavior", 0.6F, 1.0F, 1.0F,
		    false, false, WorldView.BubbleColumnMode.NONE, false, WorldView.CollisionBehavior.POWDER_SNOW_NO_BOOTS,
		    WorldSnapshot.Suffocation.UNKNOWN, WorldView.InsideEffect.NONE, 0.0F, false, WorldView.StepOn.NONE, false,
		    WorldView.Climbability.NONE, List.of());
		WorldSnapshot original =
		    new WorldSnapshot(0, 0, 0, 1, 1, 1, WorldSnapshot.OutsideRegion.SEALED, List.of(powder), new int[] {0});
		StringWriter writer = new StringWriter();
		WorldSnapshotCodec.write(original, writer);

		WorldSnapshot decoded = WorldSnapshotCodec.read(new BufferedReader(new StringReader(writer.toString())));

		assertEquals(
		    WorldView.CollisionBehavior.POWDER_SNOW_NO_BOOTS, decoded.palette().getFirst().collisionBehavior());
	}

	@Test
	void roundTripPreservesResolvedFluidBitsAndIndependentBlockBehavior() throws IOException {
		WorldSnapshot.BlockEntry waterBlock = new WorldSnapshot.BlockEntry(42, "not-used-for-behavior", 0.6F, 1.0F,
		    1.0F, false, true, WorldView.BubbleColumnMode.NONE, false, WorldView.CollisionBehavior.ORDINARY,
		    WorldSnapshot.Suffocation.UNKNOWN, WorldView.InsideEffect.NONE, 0.0F, false, WorldView.StepOn.NONE, false,
		    WorldView.Climbability.NONE, List.of());
		double height = (double) 0.9F;
		double flowX = Double.longBitsToDouble(1L);
		double flowY = -0.0;
		double flowZ = -0.125;
		WorldSnapshot.FluidEntry water = new WorldSnapshot.FluidEntry(
		    7, "minecraft:flowing_water", WorldSnapshot.FluidKind.WATER, height, flowX, flowY, flowZ, false);
		WorldSnapshot original = new WorldSnapshot(0, 0, 0, 1, 1, 1, WorldSnapshot.OutsideRegion.SEALED,
		    List.of(waterBlock), new int[] {0}, List.of(WorldSnapshot.FluidEntry.EMPTY, water), new int[] {1});
		StringWriter writer = new StringWriter();
		WorldSnapshotCodec.write(original, writer);

		WorldSnapshot decoded = WorldSnapshotCodec.read(new BufferedReader(new StringReader(writer.toString())));
		WorldSnapshot.FluidEntry decodedWater = decoded.fluidEntryAt(0, 0, 0);

		assertEquals(true, decoded.hasFluids());
		assertEquals(true, decoded.palette().getFirst().suppressesSupportingSpeedFactor());
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
		WorldSnapshot.BlockEntry stone = new WorldSnapshot.BlockEntry(1, "minecraft:stone", 0.6F, 1.0F, 1.0F, false,
		    false, WorldView.BubbleColumnMode.NONE, false, WorldView.CollisionBehavior.ORDINARY,
		    WorldSnapshot.Suffocation.YES, WorldView.InsideEffect.NONE, 0.0F, false, WorldView.StepOn.NONE, false,
		    WorldView.Climbability.NONE, List.of(new WorldSnapshot.ShapeBox(0, 0, 0, 1, 1, 1)));
		WorldSnapshot.BlockEntry glass = new WorldSnapshot.BlockEntry(2, "minecraft:glass", 0.6F, 1.0F, 1.0F, false,
		    false, WorldView.BubbleColumnMode.NONE, false, WorldView.CollisionBehavior.ORDINARY,
		    WorldSnapshot.Suffocation.NO, WorldView.InsideEffect.NONE, 0.0F, false, WorldView.StepOn.NONE, false,
		    WorldView.Climbability.NONE, List.of(new WorldSnapshot.ShapeBox(0, 0, 0, 1, 1, 1)));
		WorldSnapshot snapshot = new WorldSnapshot(
		    0, 0, 0, 2, 1, 1, WorldSnapshot.OutsideRegion.SEALED, List.of(stone, glass), new int[] {0, 1});

		StringWriter text = new StringWriter();
		WorldSnapshotCodec.write(snapshot, text);
		WorldSnapshot decoded = WorldSnapshotCodec.read(new BufferedReader(new StringReader(text.toString())));

		assertEquals(WorldSnapshot.Suffocation.YES, decoded.entryAt(0, 0, 0).suffocation());
		assertEquals(WorldSnapshot.Suffocation.NO, decoded.entryAt(1, 0, 0).suffocation());
	}

	@Test
	void roundTripPreservesInsideEffectIndependentlyOfShapeAndName() throws IOException {
		// Both carry no geometry and both are named nothing the reader could key
		// on, so the decoded effect can only have come from the written column.
		WorldSnapshot.BlockEntry web = new WorldSnapshot.BlockEntry(1, "minecraft:cobweb", 0.6F, 1.0F, 1.0F, false,
		    false, WorldView.BubbleColumnMode.NONE, false, WorldView.CollisionBehavior.ORDINARY,
		    WorldSnapshot.Suffocation.NO, WorldView.InsideEffect.COBWEB, 0.0F, false, WorldView.StepOn.NONE, false,
		    WorldView.Climbability.NONE, List.of());
		WorldSnapshot.BlockEntry bush = new WorldSnapshot.BlockEntry(2, "minecraft:sweet_berry_bush", 0.6F, 1.0F, 1.0F,
		    false, false, WorldView.BubbleColumnMode.NONE, false, WorldView.CollisionBehavior.ORDINARY,
		    WorldSnapshot.Suffocation.NO, WorldView.InsideEffect.SWEET_BERRY_BUSH, 0.0F, false, WorldView.StepOn.NONE,
		    false, WorldView.Climbability.NONE, List.of());
		WorldSnapshot.BlockEntry lavaCauldron = new WorldSnapshot.BlockEntry(3, "minecraft:lava_cauldron", 0.6F, 1.0F,
		    1.0F, false, false, WorldView.BubbleColumnMode.NONE, false, WorldView.CollisionBehavior.ORDINARY,
		    WorldSnapshot.Suffocation.NO, WorldView.InsideEffect.LAVA_CAULDRON, 0.0F, false, WorldView.StepOn.NONE,
		    false, WorldView.Climbability.NONE, List.of());
		WorldSnapshot.BlockEntry air = new WorldSnapshot.BlockEntry(0, "minecraft:air", 0.6F, 1.0F, 1.0F, List.of());
		WorldSnapshot snapshot = new WorldSnapshot(0, 0, 0, 4, 1, 1, WorldSnapshot.OutsideRegion.SEALED,
		    List.of(web, bush, lavaCauldron, air), new int[] {0, 1, 2, 3});

		StringWriter text = new StringWriter();
		WorldSnapshotCodec.write(snapshot, text);
		WorldSnapshot decoded = WorldSnapshotCodec.read(new BufferedReader(new StringReader(text.toString())));

		assertEquals(WorldView.InsideEffect.COBWEB, decoded.entryAt(0, 0, 0).insideEffect());
		assertEquals(WorldView.InsideEffect.SWEET_BERRY_BUSH, decoded.entryAt(1, 0, 0).insideEffect());
		assertEquals(WorldView.InsideEffect.LAVA_CAULDRON, decoded.entryAt(2, 0, 0).insideEffect());
		assertEquals(WorldView.InsideEffect.NONE, decoded.entryAt(3, 0, 0).insideEffect());
	}

	@Test
	void allEmptyDenseFluidPlaneCanonicalizesWithoutLosingLookupSemantics() {
		WorldSnapshot.BlockEntry air = new WorldSnapshot.BlockEntry(0, "air", 0.6F, 1.0F, 1.0F, List.of());
		WorldSnapshot snapshot = new WorldSnapshot(0, 0, 0, 2, 1, 1, WorldSnapshot.OutsideRegion.SEALED, List.of(air),
		    new int[] {0, 0}, List.of(WorldSnapshot.FluidEntry.EMPTY), new int[] {0, 0});

		assertEquals(false, snapshot.hasFluids());
		assertEquals(0, snapshot.fluidCells().length);
		assertEquals(WorldSnapshot.FluidEntry.EMPTY, snapshot.fluidEntryAt(1, 0, 0));
		assertNull(snapshot.fluidEntryAt(2, 0, 0));
	}

	@Test
	void positionsOutsideTheRegionAreNotAir() {
		WorldSnapshot snapshot = sample();

		// -1, not palette index 0. Outside is a declared semantic, and reading
		// it as "the first palette entry" would silently mean air.
		assertEquals(-1, snapshot.paletteIndexAt(999, -60, 20));
		assertNull(snapshot.entryAt(999, -60, 20));
	}

	@Test
	void halfHeightGeometrySurvivesTheCodec() throws IOException {
		StringWriter writer = new StringWriter();
		WorldSnapshotCodec.write(sample(), writer);
		WorldSnapshot decoded = WorldSnapshotCodec.read(new BufferedReader(new StringReader(writer.toString())));

		WorldSnapshot.ShapeBox slab = decoded.palette().get(2).boxes().getFirst();
		assertEquals(Double.doubleToRawLongBits(0.5), Double.doubleToRawLongBits(slab.maxY()));
	}

	@Test
	void denseCellsCannotMutateASharedSnapshot() {
		WorldSnapshot snapshot = sample();
		int before = snapshot.paletteIndexAt(10, -60, 20);
		int[] exposed = snapshot.cells();
		exposed[0] = 0;

		assertEquals(before, snapshot.paletteIndexAt(10, -60, 20));
	}

	@Test
	void denseFluidCellsCannotMutateASharedSnapshot() {
		WorldSnapshot.BlockEntry air = new WorldSnapshot.BlockEntry(0, "air", 0.6F, 1.0F, 1.0F, List.of());
		WorldSnapshot.FluidEntry water =
		    new WorldSnapshot.FluidEntry(1, "water", WorldSnapshot.FluidKind.WATER, 1.0, 0.0, 0.0, 0.0, true);
		WorldSnapshot snapshot = new WorldSnapshot(0, 0, 0, 1, 1, 1, WorldSnapshot.OutsideRegion.SEALED, List.of(air),
		    new int[] {0}, List.of(WorldSnapshot.FluidEntry.EMPTY, water), new int[] {1});
		int[] exposed = snapshot.fluidCells();
		exposed[0] = 0;

		assertEquals(water, snapshot.fluidEntryAt(0, 0, 0));
	}

	/** Drops the identity, contact, and landing columns the later formats added. */
	private static String asVersionThirteen(final String current) {
		StringBuilder migrated = new StringBuilder();
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
				if (field < 19 || field > 21) {
					migrated.append(field == 0 ? "" : "\t").append(fields[field]);
				}
			}
			migrated.append('\n');
		}
		return migrated.toString();
	}
}
