package com.nettarion.stride.simulator.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

class StateEventTraceTest {
	@Test
	void readsOrderedAuthoritativeRawValues() throws IOException {
		StateEventTrace trace = read("""
			#stride-state-events	2
			#scenario	powder-snow
			tick	phase	field	raw_value
			24	PRE	TICKS_FROZEN	0000000000000001
			24	POST	TICKS_FROZEN	0000000000000002
			""");

		assertEquals("powder-snow", trace.scenario());
		assertEquals(2, trace.events().size());
		assertEquals(StateField.TICKS_FROZEN, trace.events().getFirst().field());
		assertEquals(1L, trace.events().getFirst().rawBits());
		assertEquals(StateEventTrace.Phase.POST, trace.events().getLast().phase());
	}

	@Test
	void rejectsNonAuthorityFieldsAndDuplicateTickField() {
		// A derived field, not merely an unlisted one. POS_X used to stand here
		// and is now a real authority write (handleMovePlayer), so the case has
		// to be carried by something that stays off the list by rule: the box is
		// recomputed from position and pose and can never arrive on its own.
		assertThrows(IOException.class, () -> read("""
			#stride-state-events	2
			#scenario	x
			tick	phase	field	raw_value
			1	PRE	BB_MIN_X	0000000000000000
			"""));
		assertThrows(IOException.class, () -> read("""
			#stride-state-events	2
			#scenario	x
			tick	phase	field	raw_value
			1	PRE	TICKS_FROZEN	0000000000000000
			1	PRE	TICKS_FROZEN	0000000000000001
			"""));
	}

	private static StateEventTrace read(final String text) throws IOException {
		return StateEventTrace.read(new BufferedReader(new StringReader(text)));
	}
}
