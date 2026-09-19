package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.trace.ScheduledRecording;
import com.nettarion.stride.simulator.trace.SimulationCheckpoint;
import com.nettarion.stride.simulator.trace.TraceProducer;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.Suffocation;
import com.nettarion.stride.simulator.world.BlockStateCatalog;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.WorldSnapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ScheduledRecordingTest {
	private static final String VERSION = "26.2";

	@TempDir Path directory;

	private static WorldSnapshot floor() {
		WorldSnapshot.Builder world = WorldSnapshot.builder(OutsidePolicy.REFUSING, -4, -2, -4, 12, 8, 12)
		                                  .palette(BlockEntry.builder(0, "minecraft:air").build(),
		                                      BlockEntry.builder(1, "minecraft:stone").fullCube().build());
		for (int x = -4; x < 8; x++) {
			for (int z = -4; z < 8; z++) {
				world.set(x, -1, z, 1);
			}
		}
		return world.build();
	}

	private static SimulationState start() {
		PlayerState player = new PlayerState();
		player.placeAt(0.5, 0, 0.5);
		player.onGround = true;
		return new SimulationState(player, ServerPlayerState.atBoundary(player), 0);
	}

	/** Three forward ticks with every packet delivered afterwards, so packets are retained across actions. */
	private static ScheduledRecording walkThreeTicks() {
		ScheduledRecording recording = new ScheduledRecording(start(), floor(), VERSION, Interaction.ALLOWED, null);
		recording.append(SimulationEvent.Phase.LEVEL_TICK);
		recording.append(SimulationEvent.Phase.CONNECTION_TICK);
		for (int i = 0; i < 3; i++) {
			recording.append(new SimulationEvent.ClientTick(PlayerInput.of(0.0F, 0.0F, PlayerInput.Key.FORWARD)));
		}
		assertTrue(recording.forkCurrent().pendingServerboundPackets() > 1);
		while (recording.forkCurrent().pendingServerboundPackets() > 0) {
			recording.append(SimulationEvent.Phase.DELIVER_SERVERBOUND);
		}
		recording.append(SimulationEvent.Phase.PUBLISH_SERVER_ENTITY);
		while (recording.forkCurrent().pendingClientboundPackets() > 0) {
			recording.append(SimulationEvent.Phase.DELIVER_CLIENTBOUND);
		}
		return recording;
	}

	@Test
	void aWrittenRecordingReadsBackAndReplaysToTheSameCheckpoint() throws IOException {
		ScheduledRecording recording = walkThreeTicks();
		Path path = this.directory.resolve("walk.recording");

		recording.write(path);
		ScheduledRecording loaded = ScheduledRecording.read(path, VERSION, null);

		assertEquals(recording.events(), loaded.events());
		assertEquals(Interaction.ALLOWED, loaded.interaction());
		assertFalse(loaded.verified());
		assertArrayEquals(SimulationCheckpoint.capture(recording.forkCurrent()).encode(),
		    SimulationCheckpoint.capture(loaded.replay(null)).encode());
		assertThrows(IllegalStateException.class, loaded::forkCurrent, "a read recording has not executed");
		assertEquals(3, loaded.resume(null).forkCurrent().completedActions(), "resume replays the whole prefix");
	}

	@Test
	void readRefusesAnotherMinecraftVersionAndACatalogForAnUnverifiedRecording() throws IOException {
		Path path = this.directory.resolve("walk.recording");
		walkThreeTicks().write(path);
		BlockStateCatalog.Source source =
		    new BlockStateCatalog.Source(VERSION, world -> new BlockStateCatalog(VERSION, world.palette()));

		assertThrows(IOException.class, () -> ScheduledRecording.read(path, "wrong", null));
		assertThrows(IllegalArgumentException.class, () -> ScheduledRecording.read(path, VERSION, null).replay(source));
	}

	@Test
	void aDisagreeingObservedCheckpointIsInspectableNamedOnReplayAndNotRecomputable() throws IOException {
		ScheduledRecording recording = walkThreeTicks();
		SimulationCheckpoint.Checkpoint fresh = SimulationCheckpoint.capture(recording.forkCurrent());
		SimulationState players = fresh.players();
		PlayerState nudged = players.clientState();
		nudged.deltaMovementY = Math.nextUp(nudged.deltaMovementY);
		SimulationCheckpoint.Checkpoint wrong = new SimulationCheckpoint.Checkpoint(
		    new SimulationState(nudged, players.publisher(), players.serverState(), players.completedActions(), null),
		    fresh.transport(), fresh.writes(), fresh.clientWorld(), fresh.serverWorld());

		recording.appendObserved(SimulationEvent.Phase.LEVEL_TICK, wrong, TraceProducer.HEADLESS_VANILLA);
		Path path = this.directory.resolve("disagreeing.recording");
		recording.write(path);
		ScheduledRecording disagreeing = ScheduledRecording.read(path, VERSION, null);

		assertEquals(recording.events(), disagreeing.events(), "disagreeing checkpoints remain inspectable");
		assertEquals(TraceProducer.HEADLESS_VANILLA, disagreeing.checkpointProducers().getLast());
		IllegalStateException mismatch = assertThrows(IllegalStateException.class, () -> disagreeing.replay(null));
		assertTrue(mismatch.getMessage().contains("boundary.client.deltaMovementY"), mismatch.getMessage());
		assertThrows(IllegalArgumentException.class, () -> disagreeing.recompute(null));
	}

	@Test
	void aVerifiedRecordingNeedsItsCatalogSourceAndRefusesADifferentCatalog() throws IOException {
		WorldSnapshot world = floor();
		BlockStateCatalog.Source source = new BlockStateCatalog.Source(
		    VERSION, snapshot -> new BlockStateCatalog(VERSION, snapshot.palette()));
		ScheduledRecording recording = new ScheduledRecording(start(), world, VERSION, Interaction.DENIED, source);
		recording.append(SimulationEvent.Phase.LEVEL_TICK);
		Path path = this.directory.resolve("verified.recording");
		recording.write(path);

		assertTrue(recording.verified());
		assertThrows(IOException.class, () -> ScheduledRecording.read(path, VERSION, null));
		ScheduledRecording loaded = ScheduledRecording.read(path, VERSION, source);
		assertEquals(recording.events(), loaded.events());
		loaded.replay(source);

		BlockStateCatalog.Source other = new BlockStateCatalog.Source(VERSION,
		    snapshot
		    -> new BlockStateCatalog(VERSION,
		        List.of(snapshot.palette().getFirst(),
		            BlockEntry.builder(1, "minecraft:stone")
		                .fullCube()
		                .suffocation(Suffocation.YES)
		                .build())));
		assertThrows(IOException.class, () -> ScheduledRecording.read(path, VERSION, other));
	}

	@Test
	void recomputeReproducesTheSameCheckpoints() throws IOException {
		ScheduledRecording recording = walkThreeTicks();
		Path original = this.directory.resolve("original.recording");
		Path recomputed = this.directory.resolve("recomputed.recording");

		recording.write(original);
		recording.recompute(null).write(recomputed);

		ScheduledRecording a = ScheduledRecording.read(original, VERSION, null);
		ScheduledRecording b = ScheduledRecording.read(recomputed, VERSION, null);
		assertEquals(a.events(), b.events());
		assertArrayEquals(SimulationCheckpoint.capture(a.replay(null)).encode(),
		    SimulationCheckpoint.capture(b.replay(null)).encode());
	}
}
