package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The constructed schedule samples the server's stale flag at the step's head and applies the echo to the client copy before its next tick, the phase the live captures measure; the observing mode records it and leaves the flags alone. */
class ClientEchoDeliveryTest {
	private static SimulationState boundary() {
		PlayerState client = new PlayerState();
		client.placeAt(0.5, 65.0, 0.5);
		client.onGround = true;
		ServerPlayerState server = ServerPlayerState.atBoundary(client);
		// The server still holds the sprint flag the client has already dropped, and its tracker will publish it.
		server.setSprinting(true);
		return new SimulationState(client, server, 0);
	}

	private static SnapshotView floor() {
		WorldSnapshot.BlockEntry air = WorldSnapshot.BlockEntry.builder(0, "minecraft:air").build();
		WorldSnapshot.BlockEntry stone = WorldSnapshot.BlockEntry.builder(1, "minecraft:stone").fullCube().build();
		WorldSnapshot.Builder builder =
		    WorldSnapshot.builder(WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING, -8, 60, -8, 16, 16, 16)
		        .palette(air, stone);
		for (int x = -8; x < 8; x++) {
			for (int z = -8; z < 8; z++) {
				for (int y = 60; y < 65; y++)
					builder.set(x, y, z, 1);
			}
		}
		return SnapshotView.compile(builder.build());
	}

	@Test
	void theConstructedScheduleAppliesTheEchoAndTheObservingModeOnlyRecordsIt() {
		SnapshotView world = floor();
		PlayerInput idle = PlayerInput.idle(0.0F, 0.0F);

		Simulator.Step scheduled = new Simulator().advance(boundary(), idle, world);
		assertTrue(scheduled.writes().stream().anyMatch(write -> write instanceof EntityDataWrite));
		assertTrue(scheduled.state().clientState().sprinting,
		    "under the constructed schedule the server's stale sprint flag reaches the client copy");

		Simulator.Step observed = Simulator.observed().advance(boundary(), idle, world);
		assertTrue(observed.writes().stream().anyMatch(write -> write instanceof EntityDataWrite),
		    "the publication is still recorded so its arrival can be confirmed");
		assertFalse(observed.state().clientState().sprinting,
		    "the client copy keeps what its own tick decided until the echo is observed");
		assertTrue(List.of(scheduled.confirmations().size(), observed.confirmations().size())
		               .stream()
		               .allMatch(count -> count > 0),
		    "both schedules expect the same confirmations");
	}
}
