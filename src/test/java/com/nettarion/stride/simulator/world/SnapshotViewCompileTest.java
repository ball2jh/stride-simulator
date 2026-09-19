package com.nettarion.stride.simulator.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.block.LayeredCauldronBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link SnapshotView#compile} yields one frozen view over a snapshot, forks
 * edit their own copy, and a catalog-backed compilation verifies the palette
 * and installs cauldron successors.
 */
final class SnapshotViewCompileTest {
	private static final String VERSION = "26.2";

	private static final BlockEntry AIR = BlockEntry.builder(0, "minecraft:air").build();

	private static final BlockEntry STONE = BlockEntry.builder(1, "minecraft:stone").solid().build();

	/** Water cauldron level three, whose successor is level two. */
	private static final BlockEntry CAULDRON_3 =
	    BlockEntry.builder(30, "minecraft:water_cauldron").contact(WorldView.Contact.NONE).build();

	private static final BlockEntry CAULDRON_2 =
	    BlockEntry.builder(29, "minecraft:water_cauldron").contact(WorldView.Contact.NONE).build();

	private static final List<ShapeBox> CONTENTS = List.of(new ShapeBox(0.125, 0.25, 0.125, 0.875, 0.9375, 0.875));

	@Test
	void exposesOneSharedFrozenExactWorld() {
		WorldSnapshot snapshot = WorldSnapshot.builder(OutsidePolicy.REFUSING, 0, 0, 0, 1, 1, 1).palette(AIR).build();

		SnapshotView compiled = SnapshotView.compile(snapshot);

		assertSame(snapshot, compiled.snapshot());
		assertSame(compiled, compiled.frozen(), "an already frozen view is its own frozen successor");
		assertSame(snapshot, compiled.fork().snapshot());
		assertTrue(compiled.movementFactsImmutable());
		assertFalse(compiled.fork().movementFactsImmutable());
		assertThrows(IllegalStateException.class, () -> compiled.replaceCell(0, 0, 0, 0));
	}

	@Test
	void sparseForkMutationCannotEscapeIntoTheSharedSnapshotBacking() {
		WorldSnapshot snapshot =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, 0, 0, 0, 1, 1, 1).palette(AIR, STONE).build();
		SnapshotView compiled = SnapshotView.compile(snapshot);

		SnapshotView successor = compiled.fork();
		successor.replaceCell(0, 0, 0, 1);

		assertEquals(1, successor.paletteIndexAt(0, 0, 0));
		assertEquals(0, compiled.paletteIndexAt(0, 0, 0));
		assertEquals(0, snapshot.paletteIndexAt(0, 0, 0));
	}

	@Test
	void materializedSnapshotIsTheOriginalUntilACellIsReplaced() {
		WorldSnapshot snapshot =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, 0, 0, 0, 2, 1, 1).palette(AIR, STONE).build();
		SnapshotView compiled = SnapshotView.compile(snapshot);
		assertSame(snapshot, compiled.materializedSnapshot(), "no replacement, no copy");

		SnapshotView fork = compiled.fork();
		assertSame(snapshot, fork.materializedSnapshot());
		fork.replaceCell(1, 0, 0, 1);
		WorldSnapshot materialized = fork.materializedSnapshot();
		assertNotSame(snapshot, materialized);
		assertEquals(1, materialized.paletteIndexAt(1, 0, 0));
		assertEquals(0, materialized.paletteIndexAt(0, 0, 0));
		assertSame(snapshot.palette(), materialized.palette(), "the palette is shared");

		fork.replaceCell(1, 0, 0, 0);
		assertSame(snapshot, fork.materializedSnapshot(), "replacing back to the base is no replacement");
	}

	@Test
	void materializedSnapshotCarriesAFluidReplacement() {
		FluidEntry water = new FluidEntry(7, "minecraft:water", FluidKind.WATER, 1.0, 0.0, 0.0, 0.0, true);
		WorldSnapshot snapshot = WorldSnapshot.builder(OutsidePolicy.REFUSING, 0, 0, 0, 1, 1, 1)
		                             .palette(AIR)
		                             .fluidPalette(FluidEntry.EMPTY, water)
		                             .build();
		SnapshotView fork = SnapshotView.compile(snapshot).fork();
		fork.replaceFluidCell(0, 0, 0, 1);
		WorldSnapshot materialized = fork.materializedSnapshot();
		assertTrue(materialized.hasFluids());
		assertEquals(water, materialized.fluidEntryAt(0, 0, 0));
		FluidSample sample = new FluidSample();
		SnapshotView.compile(materialized).fluidAt(0, 0, 0, sample);
		assertEquals(FluidSample.Kind.WATER, sample.kind());
	}

	@Test
	void aCatalogCompilationInstallsTheCauldronSuccessorAndItsBehavior() {
		WorldSnapshot snapshot = WorldSnapshot.builder(OutsidePolicy.REFUSING, 0, 0, 0, 1, 1, 1)
		                             .palette(AIR, CAULDRON_3)
		                             .set(0, 0, 0, 1)
		                             .build();
		BlockStateCatalog catalog = new BlockStateCatalog(VERSION, List.of(AIR, CAULDRON_3, CAULDRON_2),
		    List.of(new BlockStateCatalog.Cauldron(30, 29, CONTENTS)),
		    Map.of(30, new BlockStateCatalog.InsideShape(CONTENTS, false)));

		SnapshotView view = SnapshotView.compile(snapshot, catalog, VERSION);

		assertTrue(view.movementFactsImmutable());
		assertTrue(view.hasSourceInsideShapes());
		assertEquals(List.of(AIR, CAULDRON_3, CAULDRON_2), view.snapshot().palette(), "the successor is appended");
		assertTrue(view.behaviorAt(0, 0, 0) instanceof LayeredCauldronBlock);
		assertSame(view.behaviorAt(0, 0, 0), view.fork().behaviorAt(0, 0, 0), "a fork shares the installed behavior");
		// A fork can publish the successor the catalog appended.
		SnapshotView fork = view.fork();
		fork.replaceCell(0, 0, 0, 2);
		assertEquals(29, fork.snapshot().palette().get(fork.paletteIndexAt(0, 0, 0)).blockStateId());
	}

	@Test
	void aCatalogCompilationRefusesADifferingEntryAndAWrongVersion() {
		BlockEntry slipperyStone = BlockEntry.builder(1, "minecraft:stone").solid().friction(0.98F).build();
		WorldSnapshot snapshot =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, 0, 0, 0, 1, 1, 1).palette(AIR, slipperyStone).build();
		BlockStateCatalog catalog = new BlockStateCatalog(VERSION, List.of(AIR, STONE));

		UnimplementedMechanicException differing =
		    assertThrows(UnimplementedMechanicException.class, () -> SnapshotView.compile(snapshot, catalog, VERSION));
		assertSame(RefusalCause.INADMISSIBLE_BLOCK_FACTS, differing.cause());
		assertTrue(differing.getMessage().contains("minecraft:stone"), differing.getMessage());

		WorldSnapshot agreeing =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, 0, 0, 0, 1, 1, 1).palette(AIR, STONE).build();
		assertThrows(IllegalArgumentException.class, () -> SnapshotView.compile(agreeing, catalog, "26.1"));
		assertSame(agreeing, SnapshotView.compile(agreeing, catalog, VERSION).snapshot(),
		    "no cauldron, no successor, the snapshot is kept");
	}

	@Test
	void sourceInsideReachedRefusesOutsideTheRegionAndForAnUnextractedShape() {
		WorldSnapshot snapshot = WorldSnapshot.builder(OutsidePolicy.REFUSING, 0, 0, 0, 2, 1, 1)
		                             .palette(AIR, CAULDRON_3)
		                             .set(1, 0, 0, 1)
		                             .build();
		BlockStateCatalog catalog = new BlockStateCatalog(VERSION, List.of(AIR, CAULDRON_3, CAULDRON_2),
		    List.of(new BlockStateCatalog.Cauldron(30, 29, CONTENTS)),
		    Map.of(30, new BlockStateCatalog.InsideShape(CONTENTS, true)));
		SnapshotView view = SnapshotView.compile(snapshot, catalog, VERSION);
		PlayerState state = new PlayerState();
		state.placeAt(1.5, 0.5, 0.5);

		assertTrue(view.sourceInsideReached(state, 1, 0, 0, null), "a canonical-full inside shape is always reached");
		assertFalse(view.sourceInsideReached(state, 0, 0, 0, null), "air is never reached");
		assertSame(RefusalCause.OUTSIDE_REGION,
		    assertThrows(UnimplementedMechanicException.class, () -> view.sourceInsideReached(state, 2, 0, 0, null))
		        .cause());
	}
}
