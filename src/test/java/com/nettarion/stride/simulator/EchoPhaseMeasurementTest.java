package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.nettarion.stride.simulator.trace.Capture;
import com.nettarion.stride.simulator.trace.Trace;
import com.nettarion.stride.simulator.world.SnapshotView;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The composed step applies each entity-data write to the client copy at the tick the recorded client
 * applied it. Each bundled capture is replayed from its initial state; the arrival tick of every
 * entity-data write (the step's action plus one, the capture's {@code tick_at_arrival} convention) must
 * match the {@code .entity-data} companion event for event, and the replayed client must match the
 * recorded client on every tick.
 */
final class EchoPhaseMeasurementTest {
	/** Each bundled capture with the number of actions the composed step replays before it refuses. */
	private static final List<Scenario> SCENARIOS =
	    List.of(new Scenario("pose-echo-boundary", 0), new Scenario("elytra-glide", 76));

	@Test
	void everyEchoIsAppliedAtTheTickTheLiveClientRecorded() throws IOException, URISyntaxException {
		for (Scenario scenario : SCENARIOS) {
			Path base = capture("live-" + scenario.name());
			Capture capture = Capture.read(base);
			String version = capture.trace().header().minecraftVersion();
			SnapshotView world =
			    SnapshotView.compile(capture.world().orElseThrow(), capture.catalog().orElseThrow(), version).fork();
			PlayerState client = capture.trace().initialState().toPlayerStateForTransition();
			Simulator simulator = new Simulator();
			SimulationState state = new SimulationState(client, ServerPlayerState.atBoundary(client), 0);
			List<String> composed = new ArrayList<>();
			int divergentTicks = 0;
			int refusedActions = 0;
			for (Trace.TraceEntry entry : capture.trace().entries()) {
				Simulator.Step step;
				try {
					step = simulator.advance(state, entry.action(), world);
				} catch (PendingServerWriteException | UnimplementedMechanicException outsideDomain) {
					// The elytra capture reaches the glider's durability write.
					refusedActions = capture.trace().entries().size() - state.completedActions();
					break;
				}
				state = step.state();
				int arrival = entry.tick() + 1;
				for (ServerWrite write : step.writes()) {
					if (!(write instanceof EntityDataWrite data)) {
						continue;
					}
					if ((data.dirty() & EntityDataWrite.FLAGS) != 0) {
						composed.add(arrival + "\t-\t" + (data.sharedFlags() & 0xFF) + "\t"
						    + SharedFlag.isSet(data.sharedFlags(), SharedFlag.SPRINTING) + "\t"
						    + SharedFlag.isSet(data.sharedFlags(), SharedFlag.SWIMMING));
					}
					if ((data.dirty() & EntityDataWrite.POSE) != 0) {
						composed.add(arrival + "\t" + data.pose() + "\t-\t-\t-");
					}
				}
				PlayerState recorded = entry.state().toPlayerStateForTransition();
				PlayerState replayed = state.clientState();
				if (Double.doubleToRawLongBits(recorded.x) != Double.doubleToRawLongBits(replayed.x)
				    || Double.doubleToRawLongBits(recorded.y) != Double.doubleToRawLongBits(replayed.y)
				    || Double.doubleToRawLongBits(recorded.z) != Double.doubleToRawLongBits(replayed.z)
				    || recorded.sprinting != replayed.sprinting || recorded.pose != replayed.pose
				    || recorded.fallFlying != replayed.fallFlying) {
					divergentTicks++;
				}
			}
			assertEquals(scenario.refusedActions(), refusedActions,
			    scenario.name() + ": actions left unreplayed by the first refusal");
			List<String> live = new ArrayList<>();
			for (String row : Files.readAllLines(base.resolveSibling("live-" + scenario.name() + ".entity-data"))) {
				if (row.startsWith("#") || row.startsWith("tick") || row.isBlank()) {
					continue;
				}
				live.add(row);
			}
			assertEquals(0, divergentTicks, scenario.name() + ": the composed client copy left the recorded client");
			assertEquals(live.subList(0, Math.min(live.size(), composed.size())), composed,
			    scenario.name()
			        + ": composed arrivals (tick, pose, flags, sprinting, swimming) against the live client's");
			assertEquals(composed.size(), live.size(), scenario.name() + ": every recorded arrival is reproduced");
		}
	}

	/** The base path of the bundled capture {@code name}, located through its trace on the test classpath. */
	private static Path capture(final String name) throws URISyntaxException {
		var trace = EchoPhaseMeasurementTest.class.getResource("/captures/" + name + ".tsv");
		assertNotNull(trace, "capture " + name + " is not on the test classpath");
		return Path.of(trace.toURI()).resolveSibling(name);
	}

	private record Scenario(String name, int refusedActions) {}
}
