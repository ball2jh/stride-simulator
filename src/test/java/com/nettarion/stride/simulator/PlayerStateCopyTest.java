package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

/** {@code copyInto} carries every public field of each state class raw-bit exactly. */
final class PlayerStateCopyTest {
	@Test
	void copyIntoCarriesEveryPublicStateFieldRawBitExactly() throws IllegalAccessException {
		PlayerState source = new PlayerState();
		fill(source, PlayerState.class);
		PlayerState copy = new PlayerState();

		source.copyInto(copy);

		assertSameFields(source, copy, PlayerState.class);
	}

	@Test
	void aServerCopyCarriesItsServerFieldsIntoAServerTargetAndMovementOnlyIntoAClientTarget()
	    throws IllegalAccessException {
		ServerPlayerState source = new ServerPlayerState();
		fill(source, ServerPlayerState.class);
		ServerPlayerState serverCopy = new ServerPlayerState();
		PlayerState clientCopy = new PlayerState();

		source.copyInto(serverCopy);
		source.copyInto(clientCopy);

		assertSameFields(source, serverCopy, ServerPlayerState.class);
		assertTrue(ServerPlayerState.rawEquals(source, serverCopy));
		assertSameFields(source, clientCopy, PlayerState.class);
		assertTrue(PlayerState.rawEquals(source, clientCopy));
		// The static type does not decide what is copied: a server target reached
		// through a PlayerState reference still receives the server half.
		ServerPlayerState throughBase = new ServerPlayerState();
		PlayerState reference = source;
		reference.copyInto(throughBase);
		assertTrue(ServerPlayerState.rawEquals(source, throughBase));
	}

	private static void fill(final Object target, final Class<?> type) throws IllegalAccessException {
		int ordinal = 1;
		for (Field field : type.getFields()) {
			if (ReflectiveFields.isInstanceField(field)) {
				ReflectiveFields.fill(field, target, ordinal++);
			}
		}
	}

	private static void assertSameFields(final Object source, final Object copy, final Class<?> type)
	    throws IllegalAccessException {
		for (Field field : type.getFields()) {
			if (ReflectiveFields.isInstanceField(field)) {
				assertEquals(
				    ReflectiveFields.rawValue(field, source), ReflectiveFields.rawValue(field, copy), field.getName());
			}
		}
	}
}
