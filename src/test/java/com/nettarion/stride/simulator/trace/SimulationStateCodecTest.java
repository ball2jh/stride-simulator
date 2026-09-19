package com.nettarion.stride.simulator.trace;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.Publisher;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.SimulationState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** The boundary wire carries exactly the declared field inventory, whatever the runtime class. */
final class SimulationStateCodecTest {
	private static final String SCHEMA_ONE_FIXTURE = "/captures/schema-1-boundary.bin";

	public static final class ExtendedPlayer extends PlayerState {
		public int unrelated = 73;
	}

	@Test
	void aJavaSubclassCannotChangeTheDeclaredClientSchema() throws Exception {
		ExtendedPlayer extended = new ExtendedPlayer();
		extended.placeAt(0.5, 2, 0.5);
		PlayerState base = new PlayerState();
		extended.copyInto(base);
		ServerPlayerState server = ServerPlayerState.atBoundary(base);
		assertArrayEquals(
		    encode(new SimulationState(base, server, 0)), encode(new SimulationState(extended, server, 0)));
	}

	@Test
	void everyPublicFieldBelongsToTheExplicitInventory() throws Exception {
		PlayerState player = new PlayerState();
		player.placeAt(0.5, 2, 0.5);
		DataInputStream in = new DataInputStream(
		    new ByteArrayInputStream(encode(new SimulationState(player, ServerPlayerState.atBoundary(player), 0))));
		assertEquals(SimulationStateCodec.SCHEMA, in.readInt());
		for (Class<?> type : List.of(PlayerState.class, Publisher.class, ServerPlayerState.class)) {
			Map<String, Class<?>> expected = new TreeMap<>();
			for (Field field : type.getFields()) {
				if (!Modifier.isStatic(field.getModifiers())) {
					expected.put(field.getName(), field.getType());
				}
			}
			assertEquals(expected.size(), in.readInt(), type.getSimpleName());
			for (Map.Entry<String, Class<?>> entry : expected.entrySet()) {
				assertEquals(entry.getKey(), in.readUTF());
				assertEquals(entry.getValue().getName(), in.readUTF());
				Class<?> t = entry.getValue();
				if (t == double.class || t == long.class) {
					in.readLong();
				} else if (t == float.class || t == int.class) {
					in.readInt();
				} else if (t == boolean.class || t == byte.class) {
					in.readByte();
				} else if (t.isEnum()) {
					in.readUTF();
				} else {
					fail("unhandled field type " + t);
				}
			}
		}
	}

	@Test
	void aSchemaOneBoundaryReadsWithVanillasDefaultDamageRules() throws Exception {
		try (InputStream fixture = SimulationStateCodecTest.class.getResourceAsStream(SCHEMA_ONE_FIXTURE)) {
			assertNotNull(fixture, "bundled schema-1 fixture is required");
			byte[] encoded = fixture.readAllBytes();
			assertEquals(1, SimulationStateCodec.peekSchema(encoded));
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
			ServerPlayerState server = SimulationStateCodec.read(in).serverState();
			assertEquals(-1, in.read(), "no trailing data");
			assertTrue(server.fallDamage && server.fireDamage && server.drowningDamage && server.freezeDamage);
		}
	}

	@Test
	void peekSchemaRefusesAShortOrUnsupportedStream() {
		assertThrows(IOException.class, () -> SimulationStateCodec.peekSchema(new byte[] {0, 0, 1}));
		assertThrows(IOException.class, () -> SimulationStateCodec.peekSchema(new byte[] {0, 0, 0, 9}));
	}

	@Test
	void aResidualCarryingANamedBitIsRefusedOnRead() throws Exception {
		PlayerState player = new PlayerState();
		player.placeAt(0.5, 2, 0.5);
		player.sharedFlagsResidual = (byte) (1 << SharedFlagBits.SPRINTING);
		byte[] bytes = encode(new SimulationState(player, ServerPlayerState.atBoundary(player), 0));
		assertThrows(
		    IOException.class, () -> SimulationStateCodec.read(new DataInputStream(new ByteArrayInputStream(bytes))));
	}

	@Test
	void anInvalidPublisherFieldIsRefusedAsAnIOException() throws Exception {
		PlayerState player = new PlayerState();
		player.placeAt(0.5, 2, 0.5);
		Publisher publisher = Publisher.atBoundary(player);
		publisher.positionReminder = -1;
		byte[] bytes = encode(new SimulationState(player, publisher, ServerPlayerState.atBoundary(player), 0, null), 2);
		assertThrows(
		    IOException.class, () -> SimulationStateCodec.read(new DataInputStream(new ByteArrayInputStream(bytes))));
	}

	@Test
	void theCurrentSchemaRoundTripsTheDamageRules() throws Exception {
		PlayerState player = new PlayerState();
		player.placeAt(0.5, 2, 0.5);
		ServerPlayerState server = ServerPlayerState.atBoundary(player);
		server.fireDamage = false;
		server.freezeDamage = false;
		ServerPlayerState back =
		    SimulationStateCodec
		        .read(new DataInputStream(new ByteArrayInputStream(encode(new SimulationState(player, server, 0)))))
		        .serverState();
		assertTrue(back.fallDamage && !back.fireDamage && back.drowningDamage && !back.freezeDamage);
		assertTrue(ServerPlayerState.rawEquals(server, back));
	}

	@Test
	void schemaOneRefusesNonDefaultDamageRules() {
		PlayerState player = new PlayerState();
		player.placeAt(0.5, 2, 0.5);
		ServerPlayerState server = ServerPlayerState.atBoundary(player);
		server.fireDamage = false;
		SimulationState state = new SimulationState(player, server, 0);
		assertThrows(IllegalArgumentException.class, () -> encode(state, 1));
		assertThrows(IllegalArgumentException.class, () -> encode(state, SimulationStateCodec.SCHEMA + 1));
	}

	private static byte[] encode(final SimulationState state) throws IOException {
		return encode(state, SimulationStateCodec.SCHEMA);
	}

	private static byte[] encode(final SimulationState state, final int schema) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		SimulationStateCodec.write(state, new DataOutputStream(bytes), schema);
		return bytes.toByteArray();
	}
}
