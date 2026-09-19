package com.nettarion.stride.simulator.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.tick.ClientTick;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A section-aligned composition whose parts leave a section uncovered holds
 * a missing section there: not air, not padding, a hole every read refuses
 * across, as the simulator refuses rather than guesses.
 */
final class MissingSectionTest {
	private static final BlockEntry AIR = BlockEntry.builder(0, "minecraft:air").build();

	private static final BlockEntry STONE = BlockEntry.builder(1, "minecraft:stone").solid().build();

	/** Two columns of two sections each, the far column's upper section absent. */
	private static WorldSnapshot holed() {
		SharedPalette palette = new SharedPalette();
		int air = palette.index(AIR);
		int stone = palette.index(STONE);
		List<WorldSnapshot> parts =
		    List.of(section(palette, 0, 0, stone), section(palette, 0, 16, air), section(palette, 16, 0, stone));
		return WorldSnapshot.compose(0, 0, 0, 32, 32, 16, OutsidePolicy.REFUSING, parts);
	}

	private static WorldSnapshot section(final SharedPalette palette, final int x, final int y, final int fill) {
		int[] cells = new int[Section.CELLS];
		if (fill != 0) {
			// A floor at row one, with the row below it inside the region so a
			// collision scan's ring under the floor stays in; the rest is air.
			for (int i = Section.EDGE * Section.EDGE; i < 2 * Section.EDGE * Section.EDGE; i++) {
				cells[i] = fill;
			}
		}
		return WorldSnapshot.owning(x, y, 0, 16, 16, 16, OutsidePolicy.REFUSING, palette.blocks(), cells,
		    List.of(FluidEntry.EMPTY), new int[Section.CELLS]);
	}

	@Test
	void anUncoveredSlotIsTheMissingSectionAndReadsIntoItRefuse() {
		WorldSnapshot snapshot = holed();
		assertTrue(snapshot.hasMissingSections());
		assertSame(Section.MISSING, snapshot.sections()[snapshot.grid().index(1, 1, 0)]);
		assertTrue(snapshot.isMissingAt(20, 20, 5));
		assertFalse(snapshot.isMissingAt(4, 20, 5));
		assertEquals(-1, snapshot.paletteIndexAt(20, 20, 5));
		assertEquals(-1, snapshot.fluidPaletteIndexAt(20, 20, 5));
		assertEquals(0, snapshot.paletteIndexAt(4, 20, 5));
		UnimplementedMechanicException dense =
		    assertThrows(UnimplementedMechanicException.class, snapshot::toDenseCells);
		assertSame(RefusalCause.UNDECLARED_WORLD_FACT, dense.cause(), "a hole inside the bounds is not outside them");

		SnapshotView view = SnapshotView.compile(snapshot);
		assertTrue(view.hasChunkAt(20, 5), "the column holds its lower section");
		UnimplementedMechanicException fluid =
		    assertThrows(UnimplementedMechanicException.class, () -> view.fluidAt(20, 20, 5, new FluidSample()));
		assertSame(RefusalCause.UNDECLARED_WORLD_FACT, fluid.cause());
		assertTrue(fluid.getMessage().contains("20,20,5"), fluid.getMessage());
		assertThrows(UnimplementedMechanicException.class,
		    () -> view.collectCollisionBoxes(19.5, 19.5, 4.5, 20.5, 20.5, 5.5, new CollisionBuffer()));
		assertFalse(view.collectSpanBoxes(19, 19, 4, 20, 20, 5, new CollisionBuffer()),
		    "a span into a hole declines retention; the ordinary path refuses the read");
		assertEquals(0L, view.spanRevision(19, 19, 4, 20, 20, 5), "a span into a hole has no revision");
		// Coarse questions do not answer the hole away: it carries every property.
		assertTrue(view.spanHasCollisionPotential(20, 20, 5, 20, 20, 5));
		assertEquals(WorldView.PROPERTY_FLUID, view.propertiesIn(WorldView.PROPERTY_FLUID, 20, 20, 5, 20, 20, 5));
		// The held sections answer as before.
		CollisionBuffer floor = new CollisionBuffer();
		view.collectCollisionBoxes(3.5, 1.5, 4.5, 4.5, 2.5, 5.5, floor);
		assertEquals(4, floor.size(), "the four floor cells the box straddles");
		UnimplementedMechanicException below = assertThrows(UnimplementedMechanicException.class,
		    ()
		        -> view.collectCollisionBoxes(3.5, -0.5, 4.5, 4.5, 0.5, 5.5, new CollisionBuffer()),
		    "a scan reaching below the bounds is outside the region, as before");
		assertSame(RefusalCause.OUTSIDE_REGION, below.cause());
	}

	@Test
	void airInAnswersUnknownAcrossAHoleAndNotAirPastAKnownBlock() {
		SnapshotView view = SnapshotView.compile(holed());
		assertEquals(WorldView.Air.UNKNOWN, view.airIn(18, 18, 2, 22, 22, 6), "a range wholly in the hole");
		assertEquals(WorldView.Air.UNKNOWN, view.airIn(14, 14, 2, 18, 18, 6), "held air and the hole");
		assertEquals(
		    WorldView.Air.NOT_AIR, view.airIn(14, 1, 2, 18, 18, 6), "the floor decides whatever the hole holds");
		assertEquals(WorldView.Air.ALL_AIR, view.airIn(2, 18, 2, 6, 22, 6), "held air alone");
	}

	@Test
	void aBodyWalksInTheHeldSectionsAndRefusesAtTheHole() {
		SnapshotView view = SnapshotView.compile(holed());
		PlayerState state = new PlayerState();
		state.placeAt(4.5, 2.0, 8.5);
		state.onGround = true;
		ClientTick tick = new ClientTick();
		PlayerInput walk = new PlayerInput(true, false, false, false, false, false, true, 0.0F, 0.0F);
		for (int i = 0; i < 20; i++) {
			tick.tick(state, walk, view);
		}
		assertTrue(state.z > 9.0);
		state.placeAt(20.5, 17.0, 8.5);
		state.onGround = false;
		UnimplementedMechanicException refused = assertThrows(
		    UnimplementedMechanicException.class, () -> tick.tick(state, PlayerInput.idle(0.0F, 0.0F), view));
		assertSame(RefusalCause.UNDECLARED_WORLD_FACT, refused.cause());
	}

	@Test
	void aColumnOfNothingButHolesIsNotAChunk() {
		SharedPalette palette = new SharedPalette();
		int stone = palette.index(STONE);
		palette.index(AIR);
		WorldSnapshot snapshot =
		    WorldSnapshot.compose(0, 0, 0, 32, 16, 16, OutsidePolicy.REFUSING, List.of(section(palette, 0, 0, stone)));
		SnapshotView view = SnapshotView.compile(snapshot);
		assertTrue(view.hasChunkAt(4, 4));
		assertFalse(view.hasChunkAt(20, 4));
	}

	@Test
	void aLaterPartFillsAnEarlierPartsHoleWithoutOverlapping() {
		SharedPalette palette = new SharedPalette();
		int air = palette.index(AIR);
		int stone = palette.index(STONE);
		// The first part is itself holed: two slots, one covered.
		WorldSnapshot holedPart =
		    WorldSnapshot.compose(0, 0, 0, 32, 16, 16, OutsidePolicy.REFUSING, List.of(section(palette, 0, 0, stone)));
		assertTrue(holedPart.hasMissingSections());
		WorldSnapshot filled = WorldSnapshot.compose(
		    0, 0, 0, 32, 16, 16, OutsidePolicy.REFUSING, List.of(holedPart, section(palette, 16, 0, air)));
		assertFalse(filled.hasMissingSections(), "the later part fills the hole");
		assertEquals(air, filled.paletteIndexAt(20, 5, 5));
		// The reverse order fills it too: an incoming hole claims nothing.
		WorldSnapshot reversed = WorldSnapshot.compose(
		    0, 0, 0, 32, 16, 16, OutsidePolicy.REFUSING, List.of(section(palette, 16, 0, air), holedPart));
		assertFalse(reversed.hasMissingSections());
		// Two held sections in one slot are still an overlap.
		assertThrows(IllegalArgumentException.class,
		    ()
		        -> WorldSnapshot.compose(
		            0, 0, 0, 32, 16, 16, OutsidePolicy.REFUSING, List.of(holedPart, section(palette, 0, 0, air))));
	}

	@Test
	void anAlignedCropOfAHoledSnapshotKeepsTheHoleAndAnUnalignedOneRefuses() {
		WorldSnapshot snapshot = holed();
		WorldSnapshot upper = snapshot.crop(0, 16, 0, 32, 16, 16);
		assertTrue(upper.hasMissingSections(), "the aligned window shares the missing section");
		assertTrue(upper.isMissingAt(20, 20, 5));
		assertEquals(0, upper.paletteIndexAt(4, 20, 5));
		WorldSnapshot held = snapshot.crop(0, 0, 0, 32, 16, 16);
		assertFalse(held.hasMissingSections(), "a window over held sections only has no hole");
		UnimplementedMechanicException refused =
		    assertThrows(UnimplementedMechanicException.class, () -> snapshot.crop(18, 18, 2, 4, 4, 4));
		assertSame(RefusalCause.UNDECLARED_WORLD_FACT, refused.cause());
		WorldSnapshot unalignedHeld = snapshot.crop(2, 0, 2, 4, 4, 4);
		assertEquals(1, unalignedHeld.paletteIndexAt(3, 1, 3), "an unaligned window over held cells copies them");
		assertEquals(0, unalignedHeld.paletteIndexAt(3, 2, 3));
	}
}
