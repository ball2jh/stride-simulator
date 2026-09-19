package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.trace.BoundaryCodec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The digest and the boundary wire of one fixed composed state are pinned
 * bit for bit.
 *
 * <p>Every pin in the repository is a {@link StateDigest}, and every archived
 * boundary is a {@link BoundaryCodec} stream, so the two encodings are part
 * of the recorded evidence: a change to either, however the plumbing behind
 * them is arranged, is a change to what every pin means. The state here sets
 * every public field of all three copies to a distinct raw pattern by
 * reflection, so a field dropped from or reordered in either encoding moves
 * the constant. A deliberate change to an encoding re-records the constants
 * in the same change, with the pins it invalidates.
 */
class StateEncodingStabilityTest {
	@Test
	void theDigestsOfTheFixedStateAreStable() {
		assertEquals("73cd0519c21609df", Long.toHexString(StateDigest.state(fixedClient())));
		assertEquals("7ccb2c2e4d0ae1ee", Long.toHexString(StateDigest.server(fixedServer())));
	}

	@Test
	void theBoundaryWireOfTheFixedStateIsStable() throws Exception {
		assertEquals(
		    "54d03645e60e0ed43b32e86867269aad4af1b58aaace641d709ce823436d15bb", sha256(encode(fixedComposite(), 2)));
		// Schema 1 carries only vanilla's default damage rules.
		ServerPlayerState defaults = fixedServer();
		defaults.fallDamage = true;
		defaults.fireDamage = true;
		defaults.drowningDamage = true;
		defaults.freezeDamage = true;
		assertEquals("8f8bce91eeae52000d9e1ac6a779695c12b6bf471ad9ae8931ede7a1d65be8bb",
		    sha256(encode(
		        SimulationState.takeOwnership(fixedClient(), fixedPublisher(), defaults, 7, HurtCause.FALL), 1)));
	}

	@Test
	void copiesOfTheFixedStateAreRawEqual() {
		PlayerState client = fixedClient();
		assertTrue(PlayerState.rawEquals(client, client.copy()));
		ServerPlayerState server = fixedServer();
		assertTrue(ServerPlayerState.rawEquals(server, server.copy()));
		Publisher publisher = fixedPublisher();
		assertTrue(Publisher.rawEquals(publisher, publisher.copy()));
	}

	static PlayerState fixedClient() {
		PlayerState state = new PlayerState();
		fill(state, PlayerState.class, 0x100);
		return state;
	}

	static ServerPlayerState fixedServer() {
		ServerPlayerState state = new ServerPlayerState();
		fill(state, ServerPlayerState.class, 0x200);
		state.restoreAdditionalMovements(List.of(
		    new ServerPlayerState.Movement(1.5, -2.25, 3.125, 4.0625, -5.03125, 6.015625, 7.0078125, -8.00390625),
		    new ServerPlayerState.Movement(-0.5, 0.25, -0.125, 0.0625, -0.03125, 0.015625, -0.0078125, 0.00390625)));
		state.difficulty = ServerPlayerState.Difficulty.HARD;
		state.fallDamage = false;
		state.freezeDamage = true;
		return state;
	}

	static Publisher fixedPublisher() {
		Publisher publisher = new Publisher();
		fill(publisher, Publisher.class, 0x300);
		return publisher;
	}

	/**
	 * The composite through the ownership constructor: admission is not the
	 * question here, and a state with every field set to a distinct pattern
	 * would not pass it.
	 */
	static SimulationState fixedComposite() {
		return SimulationState.takeOwnership(fixedClient(), fixedPublisher(), fixedServer(), 7, HurtCause.FALL);
	}

	/** Every public field a distinct raw pattern keyed on its ordinal, whatever its type. */
	private static void fill(final Object target, final Class<?> type, final int salt) {
		int ordinal = 0;
		for (Field field : type.getFields()) {
			if (Modifier.isStatic(field.getModifiers())) continue;
			ordinal++;
			try {
				Class<?> t = field.getType();
				if (t == double.class) {
					field.setDouble(target,
					    Double.longBitsToDouble(0x4000_0000_0000_0000L + (long) (salt + ordinal) * 0x1_0001_0001L));
				} else if (t == float.class) {
					field.setFloat(target, Float.intBitsToFloat(0x4000_0000 + (salt + ordinal) * 0x101));
				} else if (t == boolean.class) {
					field.setBoolean(target, (ordinal & 1) == 0);
				} else if (t == int.class) {
					field.setInt(target, (salt + ordinal) * 0x1_0001);
				} else if (t == long.class) {
					field.setLong(target, (long) (salt + ordinal) * 0x1_0000_0001L);
				} else if (t == byte.class) {
					field.setByte(target, (byte) (salt + ordinal * 7));
				} else if (t.isEnum()) {
					Object[] constants = t.getEnumConstants();
					field.set(target, constants[ordinal % constants.length]);
				} else {
					throw new AssertionError("unhandled field type " + t + " on " + field.getName());
				}
			} catch (IllegalAccessException e) {
				throw new AssertionError(e);
			}
		}
	}

	private static byte[] encode(final SimulationState state, final int schema) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		BoundaryCodec.write(state, new DataOutputStream(bytes), schema);
		return bytes.toByteArray();
	}

	private static String sha256(final byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}
}
