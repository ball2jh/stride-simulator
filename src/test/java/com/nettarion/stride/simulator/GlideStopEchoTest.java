package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The server's glide stop lands where the bundled captures record it: the server tick that drained the
 * landing packet runs its connection tick at the head of the next step, after that step's tracker
 * sample, so the client glides on the ground for two more ticks before the echo clears its flag.
 */
final class GlideStopEchoTest {
	private static final PlayerInput GLIDE = PlayerInput.idle(-90.0F, 0.0F);

	@Test
	void aLandedGliderKeepsGlidingUntilTheServersStopEchoLands() {
		Simulator simulator = new Simulator();
		SnapshotView world = floorWorld();

		// Step 0: the client lands and its packet is drained; the server tick
		// that drains it has not run its connection tick yet.
		Simulator.Step landing = simulator.advance(
		    new SimulationState(descendingGlider(), ServerPlayerState.atBoundary(descendingGlider()), 0), GLIDE, world);
		PlayerState landedClient = landing.state().clientState();
		assertTrue(landedClient.onGround, "the client landed in its tick");
		assertTrue(landedClient.fallFlying, "the client's flag is not cleared by its own tick");
		assertTrue(landing.state().serverState().onGround, "the packet's ground bit is installed");
		assertTrue(
		    landing.state().serverState().fallFlying, "the server's connection tick after the drain has not run yet");
		assertTrue(landing.state().pendingHurt().isEmpty());

		// Step 1: that server tick finishes: its sample sees a still-gliding
		// copy, then its connection tick clears the flag after the sample. The
		// client glides on the ground, as vanilla's client does.
		Simulator.Step stop = simulator.advance(landing.state(), GLIDE, world);
		assertFalse(stop.state().serverState().fallFlying, "the server's copy stopped gliding in its connection tick");
		assertTrue(stop.writes().stream().noneMatch(
		               write -> write instanceof EntityDataWrite data && (data.dirty() & EntityDataWrite.FLAGS) != 0),
		    "the stop is not published by the sample that preceded it");
		assertTrue(stop.state().clientState().fallFlying);

		// Step 2: the next sample publishes the cleared flag and the echo lands
		// before client tick 3, the doTick-caused phase the captures measure.
		Simulator.Step echo = simulator.advance(stop.state(), GLIDE, world);
		EntityDataWrite cleared = echo.writes()
		                              .stream()
		                              .filter(write -> write instanceof EntityDataWrite)
		                              .map(write -> (EntityDataWrite) write)
		                              .filter(data -> (data.dirty() & EntityDataWrite.FLAGS) != 0)
		                              .findFirst()
		                              .orElseThrow();
		assertEquals(2, cleared.actionIndex());
		assertEquals(0, cleared.sharedFlags() & SharedFlag.FALL_FLYING_MASK, "the echo carries the cleared glide flag");
		assertFalse(echo.state().clientState().fallFlying, "the echo cleared the client's flag");
		assertEquals(0, echo.state().serverState().fallFlyTicks);

		// The fixed run agrees with the stepped one and completes without a
		// pending write.
		Simulator.Run run = new Simulator().run(descendingGlider(), ServerPlayerState.atBoundary(descendingGlider()),
		    List.of(GLIDE, GLIDE, GLIDE, GLIDE), world);
		Simulator.Step air = simulator.advance(echo.state(), GLIDE, world);
		assertEquals(StateDigest.state(air.state().clientState()), StateDigest.state(run.clientState()));
		assertFalse(air.state().clientState().fallFlying);
		assertEquals(StateDigest.server(air.state().serverState()), StateDigest.server(run.serverState()));
	}

	/** A glider a third of a block above the floor, descending faster than the fall clamp. */
	private static PlayerState descendingGlider() {
		PlayerState start = new PlayerState();
		start.pose = PlayerState.Pose.FALL_FLYING;
		start.placeAt(0.5, 0.3, 0.5);
		start.fallFlying = true;
		start.gliderUsable = true;
		start.fallFlyTicks = 5;
		start.deltaMovementX = 0.3;
		start.deltaMovementY = -0.6;
		start.yRot = -90.0F;
		return start;
	}

	/** Stone with its top at y=0 everywhere in the region. */
	private static SnapshotView floorWorld() {
		WorldSnapshot.Builder builder = WorldSnapshot.builder(OutsidePolicy.REFUSING, -6, -3, -6, 12, 12, 12)
		                                    .palette(BlockEntry.builder(0, "minecraft:air").build(),
		                                        BlockEntry.builder(1, "minecraft:stone").fullCube().build());
		for (int z = -6; z < 6; z++) {
			for (int x = -6; x < 6; x++) {
				builder.set(x, -1, z, 1);
			}
		}
		return SnapshotView.compile(builder.build());
	}
}
