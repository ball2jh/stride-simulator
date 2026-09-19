package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.*;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.Publisher;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.SimulationState;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Guards the fixed wire inventory against silent field omissions and runtime-subclass drift. */
class BoundarySchemaTest {
	public static final class ExtendedPlayer extends PlayerState {
		public int unrelated = 73;
	}

	@Test
	void concreteJavaSubclassCannotChangeTheDeclaredClientSchema() throws Exception {
		var extended = new ExtendedPlayer();
		extended.placeAt(.5, 2, .5);
		var base = new PlayerState();
		extended.copyInto(base);
		var server = ServerPlayerState.atBoundary(base);
		assertArrayEquals(
		    encode(new SimulationState(base, server, 0)), encode(new SimulationState(extended, server, 0)));
	}

	@Test
	void everyPublicSemanticFieldBelongsToTheExplicitInventory() throws Exception {
		var player = new PlayerState();
		player.placeAt(.5, 2, .5);
		var in = new DataInputStream(
		    new ByteArrayInputStream(encode(new SimulationState(player, ServerPlayerState.atBoundary(player), 0))));
		assertEquals(2, in.readInt());
		for (var type : List.of(PlayerState.class, Publisher.class, ServerPlayerState.class)) {
			var expected = new TreeMap<String, Class<?>>();
			for (var f : type.getFields())
				if (!Modifier.isStatic(f.getModifiers())) expected.put(f.getName(), f.getType());
			assertEquals(expected.size(), in.readInt(), type.getSimpleName());
			for (var entry : expected.entrySet()) {
				assertEquals(entry.getKey(), in.readUTF());
				assertEquals(entry.getValue().getName(), in.readUTF());
				var t = entry.getValue();
				if (t == double.class || t == long.class)
					in.readLong();
				else if (t == float.class || t == int.class)
					in.readInt();
				else if (t == boolean.class || t == byte.class)
					in.readByte();
				else if (t.isEnum())
					in.readUTF();
				else
					fail("unhandled semantic type " + t);
			}
		}
	}

	@Test
	void aSchemaOneBoundaryReadsWithVanillasDefaultDamageRules() throws Exception {
		var capture = java.nio.file.Path.of("src/test/resources/captures/schema-1-boundary.bin");
		assertTrue(java.nio.file.Files.exists(capture), "bundled schema-1 fixture is required");
		try (var in = new DataInputStream(new BufferedInputStream(java.nio.file.Files.newInputStream(capture)))) {
			var server = BoundaryCodec.read(in).serverState();
			assertEquals(-1, in.read(), "no trailing data");
			assertTrue(server.fallDamage && server.fireDamage && server.drowningDamage && server.freezeDamage);
		}
	}

	@Test
	void aResidualCarryingANamedBitIsRefusedOnRead() throws Exception {
		var player = new PlayerState();
		player.placeAt(.5, 2, .5);
		player.sharedFlagsResidual = (byte) (1 << 3);
		var bytes = encode(new SimulationState(player, ServerPlayerState.atBoundary(player), 0));
		assertThrows(IOException.class, () -> BoundaryCodec.read(new DataInputStream(new ByteArrayInputStream(bytes))));
	}

	@Test
	void theCurrentSchemaRoundTripsTheDamageRules() throws Exception {
		var player = new PlayerState();
		player.placeAt(.5, 2, .5);
		var server = ServerPlayerState.atBoundary(player);
		server.fireDamage = false;
		server.freezeDamage = false;
		var back =
		    BoundaryCodec
		        .read(new DataInputStream(new ByteArrayInputStream(encode(new SimulationState(player, server, 0)))))
		        .serverState();
		assertTrue(back.fallDamage && !back.fireDamage && back.drowningDamage && !back.freezeDamage);
		assertTrue(ServerPlayerState.rawEquals(server, back));
	}

	private static byte[] encode(SimulationState state) throws IOException {
		var bytes = new ByteArrayOutputStream();
		BoundaryCodec.write(state, new DataOutputStream(bytes));
		return bytes.toByteArray();
	}
}
