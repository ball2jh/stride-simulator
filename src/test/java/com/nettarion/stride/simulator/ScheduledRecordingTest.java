package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.trace.ScheduledRecording;
import com.nettarion.stride.simulator.trace.SimulationCheckpoint;
import com.nettarion.stride.simulator.trace.TraceProducer;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A scheduled recording round-trips its interleaved events and packets, and an action schedule is validated. */
final class ScheduledRecordingTest {
	@TempDir Path directory;

	@Test
	void serializesInterleavedEventsWithPacketsRetainedAcrossClientActions() throws Exception {
		WorldSnapshot.Builder world = WorldSnapshot.builder(OutsidePolicy.REFUSING, -4, -2, -4, 12, 8, 12)
		                                  .palette(BlockEntry.builder(0, "minecraft:air").build(),
		                                      BlockEntry.builder(1, "minecraft:stone").fullCube().build());
		for (int x = -4; x < 8; x++) {
			for (int z = -4; z < 8; z++) {
				world.set(x, -1, z, 1);
			}
		}
		PlayerState player = new PlayerState();
		player.placeAt(0.5, 0, 0.5);
		player.onGround = true;
		SimulationState boundary = new SimulationState(player, ServerPlayerState.atBoundary(player), 0);
		ScheduledRecording trace = new ScheduledRecording(boundary, world.build(), "26.2", true, null);
		trace.append(SimulationEvent.Phase.LEVEL_TICK);
		trace.append(SimulationEvent.Phase.CONNECTION_TICK);
		for (int i = 0; i < 3; i++) {
			trace.append(new SimulationEvent.ClientTick(PlayerInput.of(0, 0, PlayerInput.Key.FORWARD)));
		}
		assertTrue(trace.state().pendingServerboundPackets() > 1);
		while (trace.state().pendingServerboundPackets() > 0) {
			trace.append(SimulationEvent.Phase.DELIVER_SERVERBOUND);
		}
		trace.append(SimulationEvent.Phase.PUBLISH_SERVER_ENTITY);
		while (trace.state().pendingClientboundPackets() > 0) {
			trace.append(SimulationEvent.Phase.DELIVER_CLIENTBOUND);
		}
		Path path = this.directory.resolve("batch.schedule");
		trace.write(path);
		ScheduledRecording loaded = ScheduledRecording.read(path, "26.2", null);
		assertEquals(trace.events(), loaded.events());
		assertArrayEquals(
		    SimulationCheckpoint.capture(trace.state()), SimulationCheckpoint.capture(loaded.replay(null)));
		assertThrows(IOException.class, () -> ScheduledRecording.read(path, "wrong", null));
		byte[] wrong = SimulationCheckpoint.capture(trace.state());
		wrong[0] ^= 1;
		trace.appendObserved(SimulationEvent.Phase.LEVEL_TICK, wrong, TraceProducer.HEADLESS_VANILLA);
		trace.write(this.directory.resolve("corrupt.schedule"));
		ScheduledRecording disagreeing =
		    ScheduledRecording.read(this.directory.resolve("corrupt.schedule"), "26.2", null);
		assertEquals(trace.events(), disagreeing.events(), "a disagreeing recording remains inspectable");
		assertThrows(IllegalStateException.class, () -> disagreeing.replay(null));
		assertThrows(IllegalArgumentException.class, () -> disagreeing.recomputeKernel(null));
	}

	@Test
	void scheduleRefusesMissingDeliveryAndMultipleClientTicks() {
		assertThrows(IllegalArgumentException.class,
		    () -> new ActionSchedule(List.of(List.of(ActionSchedule.Operation.LEVEL_TICK))));
	}
}
