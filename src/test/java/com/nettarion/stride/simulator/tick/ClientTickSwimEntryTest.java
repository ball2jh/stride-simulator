package com.nettarion.stride.simulator.tick;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.server.TickAuthority;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.FluidEntry;
import com.nettarion.stride.simulator.world.FluidKind;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;

import org.junit.jupiter.api.Test;

/**
 * {@code Entity.updateSwimming}'s entering branch asks the virtual
 * {@code isUnderWater} and then the fluid of the feet block. On the client
 * that is {@code LocalPlayer.isUnderWater}, the previous refresh's eye latch
 * alone; on the server's copy it is {@code Entity.isUnderWater}, the latch
 * and the refreshed in-water answer together.
 */
final class ClientTickSwimEntryTest {
	private static final PlayerInput SPRINT_FORWARD =
	    new PlayerInput(true, false, false, false, false, false, true, 0.0F, 0.0F);

	@Test
	void aSprintingPlayerWithEyesUnderLastTickStartsSwimmingInsideTheWater() {
		// Water fills cells 0..3; a source's surface is eight ninths up its cell.
		PlayerState submerged = sprinter(new PlayerState(), 3.5);
		new ClientTick().tick(submerged, SPRINT_FORWARD, pool());
		assertTrue(submerged.swimming, "inside the water the sprint becomes a swim");
	}

	@Test
	void theClientStartsSwimmingFromTheEyeLatchAloneWhenTheFeetBlockIsWater() {
		// Lifted just above the surface inside the same cell, as a bubble column
		// does: the feet block is water and the eye latch is still set, but the
		// tracker finds no water. LocalPlayer.isUnderWater is wasUnderwater with
		// no isInWater conjunct, so the client still enters the swim.
		PlayerState lifted = sprinter(new PlayerState(), 3.9);
		new ClientTick().tick(lifted, SPRINT_FORWARD, pool());
		assertFalse(lifted.waterHeight > 0.0, "the box clears the surface");
		assertTrue(lifted.swimming, "LocalPlayer.isUnderWater is the eye latch alone");
	}

	@Test
	void theServersCopyAlsoNeedsTheRefreshedInWaterAnswerToStartSwimming() {
		// Entity.isUnderWater is wasEyeInWater && isInWater(), and ServerPlayer
		// does not override it.
		ServerPlayerState lifted = sprinter(new ServerPlayerState(), 3.9);
		TickAuthority authority = TickAuthority.server();
		authority.begin(lifted);
		PlayerTick.serverTick(lifted, pool(), new Scratch(authority));
		assertFalse(lifted.waterHeight > 0.0, "the box clears the surface");
		assertFalse(lifted.swimming, "Entity.isUnderWater needs the box to hold water");

		ServerPlayerState submerged = sprinter(new ServerPlayerState(), 3.5);
		authority = TickAuthority.server();
		authority.begin(submerged);
		PlayerTick.serverTick(submerged, pool(), new Scratch(authority));
		assertTrue(submerged.swimming, "inside the water the server's copy swims too");
	}

	private static <S extends PlayerState> S sprinter(final S state, final double y) {
		state.placeAt(0.5, y, 0.5);
		state.sprinting = true;
		state.eyeInWater = true;
		state.deltaMovementZ = 0.1;
		return state;
	}

	private static SnapshotView pool() {
		WorldSnapshot.Builder builder =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, -8, -3, -8, 16, 12, 16)
		        .palette(BlockEntry.builder(0, "minecraft:air").build(),
		            BlockEntry.builder(1, "minecraft:stone").boxes(ShapeBox.FULL_CUBE).build())
		        .fluidPalette(
		            FluidEntry.EMPTY, new FluidEntry(1, "minecraft:water", FluidKind.WATER, 8.0 / 9.0, 0, 0, 0, true));
		for (int x = -8; x < 8; x++) {
			for (int z = -8; z < 8; z++) {
				builder.set(x, -1, z, 1);
				for (int y = 0; y < 4; y++) {
					builder.setFluid(x, y, z, 1);
				}
			}
		}
		return SnapshotView.compile(builder.build());
	}
}
