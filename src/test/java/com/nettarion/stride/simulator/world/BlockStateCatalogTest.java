package com.nettarion.stride.simulator.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A catalog holds independently extracted block records, verifies a snapshot's
 * palette against them, and names each cauldron's successor exactly once.
 */
final class BlockStateCatalogTest {
	private static final String VERSION = "26.2";

	private static final BlockEntry AIR = BlockEntry.builder(0, "minecraft:air").build();

	private static final BlockEntry STONE = BlockEntry.builder(1, "minecraft:stone").solid().build();

	private static final BlockEntry GLASS =
	    BlockEntry.builder(2, "minecraft:glass").fullCube().suffocation(Suffocation.NO).build();

	private static final BlockEntry CAULDRON_3 =
	    BlockEntry.builder(30, "minecraft:water_cauldron").contact(WorldView.Contact.NONE).build();

	private static final BlockEntry CAULDRON_2 =
	    BlockEntry.builder(29, "minecraft:water_cauldron").contact(WorldView.Contact.NONE).build();

	private static final List<ShapeBox> CONTENTS = List.of(new ShapeBox(0.125, 0.25, 0.125, 0.875, 0.9375, 0.875));

	private static WorldSnapshot snapshotOf(final BlockEntry... palette) {
		return WorldSnapshot.builder(OutsidePolicy.REFUSING, 0, 0, 0, 1, 1, 1).palette(palette).build();
	}

	@Test
	void listsEntriesCauldronsAndShapesInStateOrder() {
		BlockStateCatalog catalog = new BlockStateCatalog(VERSION, List.of(STONE, CAULDRON_3, AIR, CAULDRON_2, GLASS),
		    List.of(new BlockStateCatalog.Cauldron(30, 29, CONTENTS)),
		    Map.of(30, new BlockStateCatalog.InsideShape(CONTENTS, false)));
		assertEquals(VERSION, catalog.minecraftVersion());
		assertEquals(List.of(AIR, STONE, GLASS, CAULDRON_2, CAULDRON_3), catalog.entries());
		assertEquals(1, catalog.cauldrons().size());
		assertEquals(29, catalog.cauldrons().getFirst().successorStateId());
		assertEquals(CONTENTS, catalog.insideShapes().get(30).boxes());
		assertThrows(UnsupportedOperationException.class, () -> catalog.insideShapes().clear());
	}

	@Test
	void verifyAcceptsAnEqualEntryAndRefusesADifferingOneOrAnotherVersion() {
		BlockStateCatalog catalog = new BlockStateCatalog(VERSION, List.of(AIR, STONE));
		catalog.verify(snapshotOf(AIR, STONE), VERSION);
		BlockEntry rebuilt = BlockEntry.builder(1, "minecraft:stone").solid().build();
		catalog.verify(snapshotOf(rebuilt), VERSION);

		BlockEntry slippery = BlockEntry.builder(1, "minecraft:stone").solid().friction(0.98F).build();
		UnimplementedMechanicException differing =
		    assertThrows(UnimplementedMechanicException.class, () -> catalog.verify(snapshotOf(slippery), VERSION));
		assertSame(RefusalCause.INADMISSIBLE_BLOCK_FACTS, differing.cause());
		UnimplementedMechanicException unknown =
		    assertThrows(UnimplementedMechanicException.class, () -> catalog.verify(snapshotOf(GLASS), VERSION));
		assertTrue(unknown.getMessage().contains("minecraft:glass"), unknown.getMessage());
		assertThrows(IllegalArgumentException.class, () -> catalog.verify(snapshotOf(AIR), "26.1"));
	}

	@Test
	void severalVariantsOfOneStateIdAllVerify() {
		// A context-dependent shape is extracted as each variant the capture may hold.
		BlockEntry lower = BlockEntry.builder(5, "minecraft:scaffolding")
		                       .collisionBehavior(WorldView.CollisionBehavior.SCAFFOLDING_SUPPORTED)
		                       .build();
		BlockEntry bottom = BlockEntry.builder(5, "minecraft:scaffolding")
		                        .collisionBehavior(WorldView.CollisionBehavior.SCAFFOLDING_UNSTABLE_BOTTOM)
		                        .build();
		BlockStateCatalog catalog = new BlockStateCatalog(VERSION, List.of(lower, bottom));
		catalog.verify(snapshotOf(lower), VERSION);
		catalog.verify(snapshotOf(bottom), VERSION);
		assertEquals(2, catalog.entries().size());
	}

	@Test
	void rejectsCauldronsWithoutShapesUnknownStatesAndDuplicates() {
		List<BlockStateCatalog.Cauldron> transition = List.of(new BlockStateCatalog.Cauldron(30, 29, CONTENTS));
		Map<Integer, BlockStateCatalog.InsideShape> shapes =
		    Map.of(30, new BlockStateCatalog.InsideShape(CONTENTS, false));
		assertThrows(IllegalArgumentException.class,
		    () -> new BlockStateCatalog(VERSION, List.of(CAULDRON_3, CAULDRON_2), transition, Map.of()));
		assertThrows(IllegalArgumentException.class,
		    () -> new BlockStateCatalog(VERSION, List.of(CAULDRON_3), transition, shapes));
		assertThrows(IllegalArgumentException.class,
		    ()
		        -> new BlockStateCatalog(VERSION, List.of(CAULDRON_3, CAULDRON_2),
		            List.of(transition.getFirst(), transition.getFirst()), shapes));
		assertThrows(NullPointerException.class, () -> new BlockStateCatalog(null, List.of(AIR)));
	}

	@Test
	void withSuccessorsAppendsAnAbsentSuccessorOnceAndKeepsAPresentOne() {
		BlockStateCatalog catalog = new BlockStateCatalog(VERSION, List.of(AIR, CAULDRON_3, CAULDRON_2),
		    List.of(new BlockStateCatalog.Cauldron(30, 29, CONTENTS)),
		    Map.of(30, new BlockStateCatalog.InsideShape(CONTENTS, false)));
		WorldSnapshot absent = snapshotOf(AIR, CAULDRON_3);
		WorldSnapshot extended = catalog.withSuccessors(absent, VERSION);
		assertEquals(List.of(AIR, CAULDRON_3, CAULDRON_2), extended.palette());
		assertSame(absent.sections()[0], extended.sections()[0], "the sections are shared");

		WorldSnapshot present = snapshotOf(CAULDRON_2, CAULDRON_3);
		assertSame(present.palette(), catalog.withSuccessors(present, VERSION).palette());
		WorldSnapshot none = snapshotOf(AIR);
		assertSame(none, catalog.withSuccessors(none, VERSION), "no cauldron, no change");
	}

	@Test
	void withSuccessorsRefusesAnAbsentSuccessorWithSeveralVariants() {
		BlockEntry otherVariant =
		    BlockEntry.builder(29, "minecraft:water_cauldron").contact(WorldView.Contact.NONE).friction(0.8F).build();
		BlockStateCatalog catalog = new BlockStateCatalog(VERSION, List.of(AIR, CAULDRON_3, CAULDRON_2, otherVariant),
		    List.of(new BlockStateCatalog.Cauldron(30, 29, CONTENTS)),
		    Map.of(30, new BlockStateCatalog.InsideShape(CONTENTS, false)));
		UnimplementedMechanicException refused = assertThrows(
		    UnimplementedMechanicException.class, () -> catalog.withSuccessors(snapshotOf(AIR, CAULDRON_3), VERSION));
		assertSame(RefusalCause.INADMISSIBLE_BLOCK_FACTS, refused.cause());
		// A palette that already names one of the variants is unambiguous.
		assertEquals(List.of(otherVariant, CAULDRON_3),
		    catalog.withSuccessors(snapshotOf(otherVariant, CAULDRON_3), VERSION).palette());
	}

	@Test
	void aSourceVerifiesWhatItExtracts() {
		BlockStateCatalog.Source source =
		    new BlockStateCatalog.Source(VERSION, ignored -> new BlockStateCatalog(VERSION, List.of(AIR, STONE)));
		assertEquals(List.of(AIR, STONE), source.catalog(snapshotOf(AIR)).entries());
		assertThrows(UnimplementedMechanicException.class, () -> source.catalog(snapshotOf(GLASS)));
		BlockStateCatalog.Source mismatched =
		    new BlockStateCatalog.Source("26.1", ignored -> new BlockStateCatalog(VERSION, List.of(AIR)));
		assertThrows(IllegalArgumentException.class, () -> mismatched.catalog(snapshotOf(AIR)));
		assertThrows(NullPointerException.class, () -> new BlockStateCatalog.Source(VERSION, null));
	}
}
