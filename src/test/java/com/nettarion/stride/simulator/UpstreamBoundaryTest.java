package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.server.ServerTick;
import com.nettarion.stride.simulator.server.TickAuthority;
import com.nettarion.stride.simulator.tick.Freezing;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.tick.Travel;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.BlockEntry;

/** Source-derived regressions for retained server facts and publication boundaries. */
class UpstreamBoundaryTest {
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
		tick.transact(server.copy(), MovementPacket.NONE, server, IDLE, world(true), false);
		assertEquals(Integer.MIN_VALUE, server.tickCount);
		server.tickCount = -1;
		tick.transact(server.copy(), MovementPacket.NONE, server, IDLE, world(true), false);
		assertEquals(0, server.tickCount);
	}

	@Test
	void pendingPublicationNeverSkipsConnectionMovement() {
		ServerPlayerState normal = state();
		normal.deltaMovementX = 0.1;
		ServerPlayerState pending = normal.copy();
		new ServerTick().transact(normal.copy(), MovementPacket.NONE, normal, IDLE, world(true), false);
		new ServerTick().transact(pending.copy(), MovementPacket.NONE, pending, IDLE, world(true), true);
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
		assertThrows(IllegalArgumentException.class, server::requireValid);
	}

	@Test
	void movedTooQuicklyUsesTheDedicatedServerCheckAndItsExemption() {
		for (boolean owner : new boolean[] {false, true}) {
			ServerPlayerState server = state();
			server.singleplayerOwner = owner;
			PlayerState report = server.copy();
			report.placeAt(12.5, 0, 0.5);
			ServerTick.Transaction result =
			    new ServerTick().transact(report, MovementPacket.POS, server, IDLE, world(true), false);
			if (owner)
				assertInstanceOf(ServerTick.Accepted.class, result);
			else
				assertEquals(
				    CorrectionReason.MOVED_TOO_QUICKLY, assertInstanceOf(ServerTick.Corrected.class, result).reason());
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
	void swimmingPitchShortCircuitsAndUsesTheSourceCellBoundary() throws Exception {
		var method =
		    Travel.class.getDeclaredMethod("applySwimmingPitch", PlayerState.class, WorldView.class, Scratch.class);
		method.setAccessible(true);
		PlayerState state = new PlayerState();
		state.deltaMovementX = -0.0;
		state.deltaMovementZ = -0.0;
		method.invoke(null, state, null, new Scratch());
		assertEquals(0L, Double.doubleToRawLongBits(state.deltaMovementX));
		assertEquals(0L, Double.doubleToRawLongBits(state.deltaMovementZ));
		state.xRot = -30;
		state.jumping = true;
		method.invoke(null, state, null, new Scratch());
		state.jumping = false;
		state.y = -0.9;
		int[] queriedY = {Integer.MAX_VALUE};
		WorldView world = (WorldView) java.lang.reflect.Proxy.newProxyInstance(
		    WorldView.class.getClassLoader(), new Class<?>[] {WorldView.class}, (proxy, called, arguments) -> {
			    assertEquals("propertiesIn", called.getName());
			    queriedY[0] = (Integer) arguments[2];
			    return 0;
		    });
		method.invoke(null, state, world, new Scratch());
		assertEquals(-1, queriedY[0]);
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
		WorldSnapshot.Builder builder =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, -3, -3, -3, 20, 8, 8)
		        .palette(BlockEntry.builder(0, "minecraft:air").build(),
		            BlockEntry.builder(1, "minecraft:stone")
		                .boxes(ShapeBox.FULL_CUBE)
		                .build());
		if (floor)
			for (int x = -3; x < 17; x++)
				for (int z = -3; z < 5; z++)
					builder.set(x, -1, z, 1);
		return SnapshotView.compile(builder.build());
	}
}
