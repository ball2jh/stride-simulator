package com.nettarion.stride.simulator.trace;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.Interaction;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.ScheduledSimulation;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.SimulationEvent;
import com.nettarion.stride.simulator.SimulationState;
import com.nettarion.stride.simulator.world.SnapshotView;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SimulationCheckpointTest {
	private static ScheduledSimulation simulation() {
		PlayerState player = new PlayerState();
		player.placeAt(0.5, 0, 0.5);
		player.onGround = true;
		SimulationState boundary = new SimulationState(player, ServerPlayerState.atBoundary(player), 0);
		return new ScheduledSimulation(boundary, SnapshotView.compile(TraceFixtures.flatFloorWorld()), Interaction.ALLOWED);
	}

	@Test
	void everyTagIsDistinctAndDecodesBackToItself() throws IOException {
		for (SimulationCheckpoint.Tag tag : SimulationCheckpoint.Tag.values()) {
			assertEquals(tag, SimulationCheckpoint.Tag.of(tag.wire()), tag.wire());
			assertFalse(tag.wire().contains("."), tag.wire() + " must not be a Java class name");
		}
		assertThrows(IOException.class, () -> SimulationCheckpoint.Tag.of("java.lang.Double"));
	}

	@Test
	void aCheckpointWithPacketsInTransitFlattensToNamedFieldsAndRoundTripsItsBytes() throws IOException {
		ScheduledSimulation simulation = simulation();
		SimulationEvent.Phase.LEVEL_TICK.apply(simulation);
		new SimulationEvent.ClientTick(PlayerInput.of(0.0F, 0.0F, PlayerInput.Key.FORWARD)).apply(simulation);
		new SimulationEvent.ClientTick(PlayerInput.of(0.0F, 0.0F, PlayerInput.Key.FORWARD, PlayerInput.Key.SPRINT))
		    .apply(simulation);

		byte[] encoded = SimulationCheckpoint.capture(simulation).encode();
		List<String> values = SimulationCheckpoint.flatten(encoded);

		assertEquals(SimulationStateCodec.SCHEMA, SimulationStateCodec.peekSchema(encoded));
		assertTrue(values.contains("transport.tag=transport"), values.toString());
		assertTrue(values.stream().anyMatch(v -> v.startsWith("transport.serverbound[0].tag=")), values.toString());
		assertTrue(values.stream().anyMatch(v
		               -> v.equals("transport.serverbound[0].tag=input")
		                   || v.equals("transport.serverbound[0].tag=move-packet")),
		    values.toString());
		assertTrue(values.stream().anyMatch(v -> v.startsWith("transport.mayInteract=z:true")), values.toString());
		assertTrue(values.stream().anyMatch(v -> v.startsWith("boundary.client.deltaMovementZ=")), values.toString());
		assertTrue(values.stream().anyMatch(v -> v.startsWith("clientWorld[0]=#stride-world")), values.toString());
		assertArrayEquals(encoded, SimulationCheckpoint.capture(simulation).encode(), "capture is deterministic");
	}

	@Test
	void integersAndBytesAreWrittenAtTheirNaturalWidth() throws IOException {
		ScheduledSimulation simulation = simulation();
		new SimulationEvent.ClientTick(PlayerInput.idle(0.0F, 0.0F)).apply(simulation);
		byte[] encoded = SimulationCheckpoint.capture(simulation).encode();

		// Skip the boundary: its length is what the flattener reads; then the transport record
		// begins with its tag and first field name.
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
		SimulationStateCodec.read(in);
		assertEquals("transport", in.readUTF());
		assertEquals("serverbound", in.readUTF());
		assertEquals("list", in.readUTF());
		int serverbound = in.readInt();
		for (int i = 0; i < serverbound; i++) {
			skipValue(in);
		}
		assertEquals("clientbound", in.readUTF());
		assertEquals("list", in.readUTF());
		assertEquals(0, in.readInt());
		assertEquals("pendingHurt", in.readUTF());
		assertEquals("null", in.readUTF());
		assertEquals("hurtAction", in.readUTF());
		assertEquals("i", in.readUTF());
		assertEquals(-1, in.readInt(), "an int occupies four bytes; no hurt has been attributed yet");
		assertEquals("mayInteract", in.readUTF());
		assertEquals("z", in.readUTF());
		assertTrue(in.readBoolean());
	}

	private static void skipValue(final DataInputStream in) throws IOException {
		SimulationCheckpoint.Tag tag = SimulationCheckpoint.Tag.of(in.readUTF());
		switch (tag.shape()) {
			case NULL -> {
			}
			case LIST -> {
				int size = in.readInt();
				for (int i = 0; i < size; i++) {
					skipValue(in);
				}
			}
			case LONG -> in.readLong();
			case INT -> in.readInt();
			case BYTE -> in.readByte();
			case BOOLEAN -> in.readBoolean();
			case UTF -> in.readUTF();
			case RECORD -> {
				for (int i = 0; i < tag.fields().size(); i++) {
					in.readUTF();
					skipValue(in);
				}
			}
		}
	}

	@Test
	void firstDifferenceNamesTheFieldThatDiffers() {
		ScheduledSimulation simulation = simulation();
		byte[] before = SimulationCheckpoint.capture(simulation).encode();
		new SimulationEvent.ClientTick(PlayerInput.of(0.0F, 0.0F, PlayerInput.Key.JUMP)).apply(simulation);
		byte[] after = SimulationCheckpoint.capture(simulation).encode();

		assertEquals(Optional.empty(), SimulationCheckpoint.firstDifference(before, before));
		String difference = SimulationCheckpoint.firstDifference(before, after).orElseThrow();
		assertTrue(difference.startsWith("boundary.client."), difference);
		assertTrue(difference.contains("expected") && difference.contains("actual"), difference);
	}

	@Test
	void encodeLikeReEncodesAtTheArchivedSchemaOnlyWhenRepresentable() {
		ScheduledSimulation simulation = simulation();
		SimulationCheckpoint.Checkpoint checkpoint = SimulationCheckpoint.capture(simulation);
		byte[] schemaOne = checkpoint.encode(1);
		byte[] current = checkpoint.encode();

		assertArrayEquals(schemaOne, SimulationCheckpoint.encodeLike(schemaOne, simulation));
		assertArrayEquals(current, SimulationCheckpoint.encodeLike(current, simulation));
		assertArrayEquals(current, SimulationCheckpoint.encodeLike(new byte[] {1}, simulation),
		    "an unreadable archive compares at the current schema");
		assertArrayEquals(current, SimulationCheckpoint.encodeLike(new byte[] {0, 0, 0, 9}, simulation),
		    "an unknown schema compares at the current schema");
	}

	@Test
	void aPendingHurtLivesInTheTransportNotTheBoundary() {
		PlayerState player = new PlayerState();
		player.placeAt(0.5, 0, 0.5);
		SimulationState boundary = new SimulationState(player, ServerPlayerState.atBoundary(player), 3, HurtCause.FALL);
		ScheduledSimulation simulation =
		    new ScheduledSimulation(boundary, SnapshotView.compile(TraceFixtures.flatFloorWorld()), Interaction.DENIED);

		SimulationCheckpoint.Checkpoint checkpoint = SimulationCheckpoint.capture(simulation);

		assertEquals(Optional.empty(), checkpoint.players().pendingHurt());
		assertEquals(HurtCause.FALL, checkpoint.transport().pendingHurt());
		assertEquals(3, checkpoint.players().completedActions());
	}
}
