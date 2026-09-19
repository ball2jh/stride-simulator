package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.trace.Capture;
import com.nettarion.stride.simulator.trace.Trace;
import com.nettarion.stride.simulator.world.SnapshotView;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pins the composed step's transport phase to the live captures. The
 * composed {@link Simulator} is driven through a capture's recorded inputs
 * from its initial state; the client tick before which each entity-data
 * write is applied to the client copy (the step's action plus one, the
 * convention of the capture's {@code tick_at_arrival}) must equal the
 * arrival the live client recorded in the {@code .entity-data} companion,
 * event for event. The composed client copy must also reproduce the
 * recorded client on every tick, so the comparison is like for like.
 */
class EchoPhaseMeasurementTest {
	private static final Path CAPTURES = Path.of("src/test/resources/captures");

	@Test
	void everyEchoIsAppliedAtTheTickTheLiveClientRecorded() throws IOException {
		for (String scenario : List.of("pose-echo-boundary", "elytra-glide")) {
			Path base = CAPTURES.resolve("live-" + scenario);
			Capture capture = Capture.read(base);
			String version = capture.trace().header().minecraftVersion();
			SnapshotView world =
			    SnapshotView.compile(capture.world().orElseThrow(), capture.catalog().orElseThrow(), version).fork();
			PlayerState client = capture.trace().initialState().toPlayerStateForTransition();
			Simulator simulator = new Simulator();
			SimulationState state = simulator.start(client, client);
			List<String> composed = new ArrayList<>();
			int divergentTicks = 0;
			for (Trace.TraceEntry entry : capture.trace().entries()) {
				Simulator.Step step;
				try {
					step = simulator.advance(state, entry.action(), world);
				} catch (PendingServerWriteException | UnimplementedMechanicException outsideSlice) {
					// The elytra capture reaches the glider's durability write.
					break;
				}
				state = step.state();
				int arrival = entry.tick() + 1;
				for (ServerWrite write : step.writes()) {
					if (!(write instanceof EntityDataWrite data)) continue;
					if ((data.dirty() & EntityDataWrite.FLAGS) != 0) {
						composed.add(arrival + "\t-\t" + (data.sharedFlags() & 0xFF) + "\t"
						    + ((data.sharedFlags() & 8) != 0) + "\t" + ((data.sharedFlags() & 16) != 0));
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
			List<String> live = new ArrayList<>();
			for (String row : Files.readAllLines(base.resolveSibling("live-" + scenario + ".entity-data"))) {
				if (row.startsWith("#") || row.startsWith("tick") || row.isBlank()) continue;
				live.add(row);
			}
			assertFalse(composed.isEmpty(), scenario + ": the composed replay produced no entity-data writes");
			assertEquals(0, divergentTicks, scenario + ": the composed client copy left the recorded client");
			assertEquals(live.subList(0, Math.min(live.size(), composed.size())), composed,
			    scenario + ": composed arrivals (tick, pose, flags, sprinting, swimming) against the live client's");
		}
	}
}
