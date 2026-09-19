package com.nettarion.stride.simulator.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SharedFlagBitsTest {
	private static StateVector decomposed(final byte flags) {
		StateVector.Builder builder = StateVector.builder();
		for (StateField field : StateField.ALL) {
			switch (field.kind()) {
				case DOUBLE -> builder.set(field, 0.0);
				case FLOAT -> builder.set(field, 0.0F);
				case BOOLEAN -> builder.set(field, false);
				case INT -> builder.set(field, 0);
				case BYTE_FLAGS -> builder.setFlags(field, (byte) 0);
				case ENUM -> builder.setEnum(field, 0, "STANDING");
			}
		}
		SharedFlagBits.decompose(builder, flags);
		return builder.build();
	}

	@Test
	void residualCoversEveryBitThatHasNoNamedField() {
		// If a bit is ever promoted to its own field, this is the test that
		// fails if it is not also removed from the residual mask.
		int named = (1 << SharedFlagBits.SHIFT_KEY_DOWN) | (1 << SharedFlagBits.SPRINTING)
		    | (1 << SharedFlagBits.SWIMMING) | (1 << SharedFlagBits.FALL_FLYING);
		assertEquals(0xFF, named | SharedFlagBits.RESIDUAL_MASK, "every bit is named or residual");
		assertEquals(0, named & SharedFlagBits.RESIDUAL_MASK, "no bit is both");
	}

	@Test
	void everyBitSurvivesADecomposeComposeRoundTrip() {
		for (int flags = 0; flags <= 0xFF; flags++) {
			assertEquals((byte) flags, SharedFlagBits.compose(decomposed((byte) flags)),
			    "flags 0x" + Integer.toHexString(flags));
		}
	}

	@Test
	void namedBitsLandInTheirOwnFields() {
		StateVector state = decomposed((byte) ((1 << SharedFlagBits.SPRINTING) | (1 << SharedFlagBits.SHIFT_KEY_DOWN)));

		assertTrue(state.getBoolean(StateField.SPRINTING));
		assertTrue(state.getBoolean(StateField.SHIFT_KEY_DOWN));
		assertFalse(state.getBoolean(StateField.SWIMMING));
		assertFalse(state.getBoolean(StateField.FALL_FLYING));
		// The whole point of the split: excluding the server-owned bit must not
		// touch the client-computed ones.
		assertEquals((byte) 0, state.getFlags(StateField.SHARED_FLAGS_RESIDUAL));
	}

	@Test
	void unnamedBitsAreStillCarried() {
		StateVector state = decomposed((byte) ((1 << SharedFlagBits.GLOWING) | (1 << SharedFlagBits.ON_FIRE)));

		assertEquals((byte) 0b0100_0001, state.getFlags(StateField.SHARED_FLAGS_RESIDUAL));
	}
}
