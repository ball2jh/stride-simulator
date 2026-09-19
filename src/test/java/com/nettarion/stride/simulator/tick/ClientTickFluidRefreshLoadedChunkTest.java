package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Vanilla's {@code EntityFluidInteraction.update} answers "no fluid" when any
 * chunk within one block of the box in X or Z is not loaded, whatever the
 * box's own cells hold. The server's copy answers from loaded chunks, so the
 * refresh refuses by name where a column of that widened footprint is unknown
 * and the box's cells could hold fluid.
 */
final class ClientTickFluidRefreshLoadedChunkTest {
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);

	@Test
	void aKnownWidenedFootprintRefreshesTheTrackers() {
		// The region ends at x = 8; a box at 6.2..6.8 widens to columns 5..7.
		PlayerState state = swimmer(6.5);
		EntityFluidInteraction.update(state, pool(true), new Scratch());
		assertTrue(state.waterHeight > 0.0, "every column of the widened footprint is known");
	}

	@Test
	void anUnknownNeighbouringColumnRefusesTheRefreshByName() {
		// A box at 7.2..7.8 widens to columns 6..8, and column 8 is unknown.
		PlayerState state = swimmer(7.5);
		UnimplementedMechanicException refusal = assertThrows(UnimplementedMechanicException.class,
		    () -> EntityFluidInteraction.update(state, pool(true), new Scratch()));
		assertTrue(refusal.getMessage().startsWith("unknown space beside the player at 8,"), refusal.getMessage());
	}

	@Test
	void theTickSurfacesTheRefusalFromItsFirstPhase() {
		PlayerState state = swimmer(7.5);
		UnimplementedMechanicException refusal =
		    assertThrows(UnimplementedMechanicException.class, () -> new ClientTick().tick(state, IDLE, pool(true)));
		assertTrue(refusal.getMessage().startsWith("unknown space beside the player at 8,"), refusal.getMessage());
	}

	@Test
	void aDryBoxBesideAnUnknownColumnDoesNotRefuse() {
		// With no fluid the box's cells could hold, vanilla's answer and the
		// server's agree, so the unknown column is not consulted.
		PlayerState state = swimmer(7.5);
		EntityFluidInteraction.update(state, pool(false), new Scratch());
		assertEquals(0.0, state.waterHeight);
		assertFalse(state.eyeInWater);
	}

	private static PlayerState swimmer(final double x) {
		PlayerState state = new PlayerState();
		state.placeAt(x, 1.5, 0.5);
		return state;
	}

	private static SnapshotView pool(final boolean water) {
		WorldSnapshot.Builder builder =
		    WorldSnapshot.builder(WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING, -8, -3, -8, 16, 12, 16)
		        .palette(WorldSnapshot.BlockEntry.builder(0, "minecraft:air").build(),
		            WorldSnapshot.BlockEntry.builder(1, "minecraft:stone")
		                .boxes(WorldSnapshot.ShapeBox.FULL_CUBE)
		                .build())
		        .fluidPalette(WorldSnapshot.FluidEntry.EMPTY,
		            new WorldSnapshot.FluidEntry(
		                1, "minecraft:water", WorldSnapshot.FluidKind.WATER, 8.0 / 9.0, 0, 0, 0, true));
		for (int x = -8; x < 8; x++) {
			for (int z = -8; z < 8; z++) {
				builder.set(x, -1, z, 1);
				if (water)
					for (int y = 0; y < 4; y++)
						builder.setFluid(x, y, z, 1);
			}
		}
		return SnapshotView.compile(builder.build());
	}
}
