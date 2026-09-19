package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.tick.ClientTick;
import com.nettarion.stride.simulator.trace.*;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ScheduledTraceTest {
	@TempDir Path directory;
	@Test
	void serializesInterleavedEventsWithPacketsRetainedAcrossClientActions() throws Exception {
		var world = WorldSnapshot.builder(WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING, -4, -2, -4, 12, 8, 12)
		                .palette(WorldSnapshot.BlockEntry.builder(0, "minecraft:air").build(),
		                    WorldSnapshot.BlockEntry.builder(1, "minecraft:stone").fullCube().build());
		for (int x = -4; x < 8; x++)
			for (int z = -4; z < 8; z++)
				world.set(x, -1, z, 1);
		var player = new PlayerState();
		player.placeAt(.5, 0, .5);
		player.onGround = true;
		var boundary = new SimulationState(player, ServerPlayerState.atBoundary(player), 0);
		var trace = new ScheduledTrace(boundary, world.build(), "26.2", true, null);
		trace.append(SimulationEvent.Phase.LEVEL_TICK);
		trace.append(SimulationEvent.Phase.CONNECTION_TICK);
		for (int i = 0; i < 3; i++)
			trace.append(
			    new SimulationEvent.ClientTick(new PlayerInput(true, false, false, false, false, false, false, 0, 0)));
		assertTrue(trace.state().pendingServerboundPackets() > 1);
		while (trace.state().pendingServerboundPackets() > 0)
			trace.append(SimulationEvent.Phase.DELIVER_SERVERBOUND);
		trace.append(SimulationEvent.Phase.PUBLISH_SERVER_ENTITY);
		while (trace.state().pendingClientboundPackets() > 0)
			trace.append(SimulationEvent.Phase.DELIVER_CLIENTBOUND);
		var path = directory.resolve("batch.schedule");
		trace.write(path);
		var loaded = ScheduledTrace.read(path, "26.2", null);
		assertEquals(trace.events(), loaded.events());
		assertArrayEquals(
		    SimulationCheckpoint.capture(trace.state()), SimulationCheckpoint.capture(loaded.replay(null)));
		assertThrows(java.io.IOException.class, () -> ScheduledTrace.read(path, "wrong", null));
		byte[] wrong = SimulationCheckpoint.capture(trace.state());
		wrong[0] ^= 1;
		trace.appendObserved(SimulationEvent.Phase.LEVEL_TICK, wrong, TraceProducer.HEADLESS_VANILLA);
		trace.write(directory.resolve("corrupt.schedule"));
		var disagreeing = ScheduledTrace.read(directory.resolve("corrupt.schedule"), "26.2", null);
		assertEquals(trace.events(), disagreeing.events(), "disagreeing evidence remains inspectable");
		assertThrows(IllegalStateException.class, () -> disagreeing.replay(null));
		assertThrows(IllegalArgumentException.class, () -> disagreeing.recomputeKernel(null));
	}
	@Test
	void scheduleRefusesMissingDeliveryAndMultipleClientTicks() {
		assertThrows(IllegalArgumentException.class,
		    () -> new ActionSchedule(List.of(List.of(ActionSchedule.Operation.LEVEL_TICK))));
	}
}
