package com.nettarion.stride.simulator.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nettarion.stride.simulator.PlayerInput;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The packed-action cells of a trace row decode every admitted input and refuse unknown bits. */
final class TraceActionCodecTest {
	@Test
	void allSevenBitInputsRoundTripWithoutTruncation() throws IOException {
		for (int flags = 0; flags <= 0x7f; flags++) {
			PlayerInput action = TraceCodec.decodeAction(Integer.toHexString(flags), "bfa00000", "3e800000");
			assertEquals(flags, Byte.toUnsignedInt(action.packedInput()));
			assertEquals(0xbfa00000, Float.floatToRawIntBits(action.yRot()));
			assertEquals(0x3e800000, Float.floatToRawIntBits(action.xRot()));
		}
	}

	@Test
	void unknownHighBitsCannotTurnIntoDifferentValidActions() {
		for (String input : List.of("80", "ff", "100", "145", "ffffffff")) {
			assertThrows(IOException.class, () -> TraceCodec.decodeAction(input, "00000000", "00000000"), input);
		}
	}
}
