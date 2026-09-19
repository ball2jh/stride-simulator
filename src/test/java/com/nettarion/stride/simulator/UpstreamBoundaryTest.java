package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.server.ServerTick;
import com.nettarion.stride.simulator.server.TickAuthority;
import com.nettarion.stride.simulator.tick.Freezing;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.tick.Travel;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;
import org.junit.jupiter.api.Test;

/** Regressions read off vanilla for retained server facts and the composed step's server phases. */
final class UpstreamBoundaryTest {
	private static final PlayerInput IDLE = PlayerInput.idle(0, 0);

	@Test
	void frostRequiresANonAirLegacySupportCell() {
		for (boolean supported : new boolean[] {false, true}) {
			ServerPlayerState server = state();
			server.ticksFrozen = 10;
			server.frostSpeedTicks = 10;
			TickAuthority authority = TickAuthority.server();
			authority.begin(server);
			Freezing.run(server, world(supported), new Scratch(authority));
			assertEquals(8, server.ticksFrozen);
			assertEquals(supported ? 8 : 0, server.frostSpeedTicks);
		}
	}

	@Test
	void tickCountWrapsAsAnIntAndNegativeOneIsKnown() {
		ServerPlayerState server = state();
		server.tickCount = Integer.MAX_VALUE;
		ServerTick tick = new ServerTick();
		tick.transact(server.copy(), MovementPacket.NONE, server, IDLE, world(true));
		assertEquals(Integer.MIN_VALUE, server.tickCount);
		server.tickCount = -1;
		tick.transact(server.copy(), MovementPacket.NONE, server, IDLE, world(true));
		assertEquals(0, server.tickCount);
	}

	@Test
	void pendingPublicationNeverSkipsConnectionMovement() {
		ServerPlayerState normal = state();
		normal.deltaMovementX = 0.1;
		ServerPlayerState pending = normal.copy();
		new ServerTick().transact(normal.copy(), MovementPacket.NONE, normal, IDLE, world(true));
		new ServerTick().transact(pending.copy(), MovementPacket.NONE, pending, IDLE, world(true));
		assertTrue(ServerPlayerState.rawEquals(normal, pending));
		assertNotEquals(0.1, pending.deltaMovementX);
	}

	@Test
	void unknownDeliveryAndNegativeImpulseGraceRefuse() {
		ServerPlayerState server = state();
		server.publicationScheduleKnown = false;
		assertThrows(PendingServerWriteException.class,
		    () -> new Simulator().advance(new SimulationState(server, server, 0), IDLE, world(true)));
		server.publicationScheduleKnown = true;
		server.currentImpulseContextResetGraceTime = -1;
		assertThrows(IllegalStateException.class, server::requireValid);
	}

	@Test
	void movedTooQuicklyUsesTheDedicatedServerCheckAndItsExemption() {
		for (boolean owner : new boolean[] {false, true}) {
			ServerPlayerState server = state();
			server.singleplayerOwner = owner;
			PlayerState report = server.copy();
			report.placeAt(12.5, 0, 0.5);
			ServerTick.Transaction result =
			    new ServerTick().transact(report, MovementPacket.POS, server, IDLE, world(true));
			if (owner) {
				assertInstanceOf(ServerTick.Accepted.class, result);
			} else {
				assertEquals(
				    CorrectionReason.MOVED_TOO_QUICKLY, assertInstanceOf(ServerTick.Corrected.class, result).reason());
			}
		}
	}

	@Test
	void layeredCauldronsAreNeverClassifiedAsInertServerContact() {
		for (String name : new String[] {"minecraft:water_cauldron", "minecraft:powder_snow_cauldron"}) {
			assertEquals(WorldView.Contact.UNMODELED, BlockEntry.legacyContact(name));
			assertEquals(WorldView.Contact.UNMODELED,
			    BlockEntry.builder(0, name).contact(WorldView.Contact.NONE).build().contact());
		}
	}

	@Test
	void lavaGravityAddsZeroToTheHorizontalComponents() {
		PlayerState state = new PlayerState();
		state.placeAt(0.5, 2, 0.5);
		state.deltaMovementX = -0.0;
		state.deltaMovementZ = -0.0;
		state.lavaHeight = 1;
		Travel.run(state, IDLE, world(false), new Scratch());
		assertEquals(0L, Double.doubleToRawLongBits(state.deltaMovementX));
		assertEquals(0L, Double.doubleToRawLongBits(state.deltaMovementZ));
	}

	private static ServerPlayerState state() {
		ServerPlayerState state = new ServerPlayerState();
		state.placeAt(0.5, 0, 0.5);
		state.onGround = true;
		return state;
	}

	private static SnapshotView world(final boolean floor) {
		WorldSnapshot.Builder builder = WorldSnapshot.builder(OutsidePolicy.REFUSING, -3, -3, -3, 20, 8, 8)
		                                    .palette(BlockEntry.builder(0, "minecraft:air").build(),
		                                        BlockEntry.builder(1, "minecraft:stone").fullCube().build());
		if (floor) {
			for (int x = -3; x < 17; x++) {
				for (int z = -3; z < 5; z++) {
					builder.set(x, -1, z, 1);
				}
			}
		}
		return SnapshotView.compile(builder.build());
	}
}
