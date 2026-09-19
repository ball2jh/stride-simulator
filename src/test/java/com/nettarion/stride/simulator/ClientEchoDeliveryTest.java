package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

/**
 * The composed schedule applies the tracker's entity-data echo to the client copy before its next
 * tick; the observed-connection mode records the same echo and leaves the client's flags alone.
 */
final class ClientEchoDeliveryTest {
	@Test
	void theComposedScheduleAppliesTheEchoAndTheObservedModeOnlyRecordsIt() {
		SnapshotView world = floor();
		PlayerInput idle = PlayerInput.idle(0.0F, 0.0F);

		Simulator.Step scheduled = new Simulator().advance(boundary(), idle, world);
		assertTrue(scheduled.writes().stream().anyMatch(write -> write instanceof EntityDataWrite));
		assertTrue(scheduled.state().clientState().sprinting,
		    "under the composed schedule the server's stale sprint flag reaches the client copy");

		Simulator.Step observed = Simulator.forObservedConnection().advance(boundary(), idle, world);
		assertTrue(observed.writes().stream().anyMatch(write -> write instanceof EntityDataWrite),
		    "the write is still recorded so its arrival can be confirmed");
		assertFalse(observed.state().clientState().sprinting,
		    "the client copy keeps what its own tick decided until the echo is observed");
		assertFalse(scheduled.confirmations().isEmpty(), "the stale flag is published, so there is a confirmation");
		assertEquals(scheduled.confirmations(), observed.confirmations(), "both modes expect the same confirmations");
	}

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
		BlockEntry air = BlockEntry.builder(0, "minecraft:air").build();
		BlockEntry stone = BlockEntry.builder(1, "minecraft:stone").fullCube().build();
		WorldSnapshot.Builder builder =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, -8, 60, -8, 16, 16, 16).palette(air, stone);
		for (int x = -8; x < 8; x++) {
			for (int z = -8; z < 8; z++) {
				for (int y = 60; y < 65; y++) {
					builder.set(x, y, z, 1);
				}
			}
		}
		return SnapshotView.compile(builder.build());
	}
}
