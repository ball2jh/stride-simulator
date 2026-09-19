package com.nettarion.stride.simulator.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nettarion.stride.simulator.PlayerState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

final class StateEventTraceTest {
	@Test
	void readsOrderedServerWrittenRawValues() throws IOException {
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
	void rejectsDerivedFieldsAndDuplicateTickField() {
		// A derived field, not merely an unlisted one: the box is recomputed from position and
		// pose and can never arrive on its own.
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

	@Test
	void aBooleanValueMustBeZeroOrOne() throws IOException {
		assertEquals(1L, read("""
			#stride-state-events	2
			#scenario	x
			tick	phase	field	raw_value
			1	PRE	SPRINTING	0000000000000001
			""").events().getFirst().rawBits());
		assertThrows(IOException.class, () -> read("""
			#stride-state-events	2
			#scenario	x
			tick	phase	field	raw_value
			1	PRE	SPRINTING	0000000000000002
			"""));
	}

	@Test
	void writeRoundTripsEveryEvent() throws IOException {
		StateEventTrace original = new StateEventTrace("round-trip",
		    List.of(new StateEventTrace.StateEvent(
		                3, StateEventTrace.Phase.PRE, StateField.POS_X, Double.doubleToRawLongBits(-0.0)),
		        new StateEventTrace.StateEvent(
		            3, StateEventTrace.Phase.POST, StateField.POSE, PlayerState.Pose.SWIMMING.id),
		        new StateEventTrace.StateEvent(9, StateEventTrace.Phase.PRE, StateField.FLYING_SPEED,
		            Integer.toUnsignedLong(Float.floatToRawIntBits(0.05F)))));
		StringWriter writer = new StringWriter();

		StateEventTrace.write(original, writer);

		assertEquals(original, read(writer.toString()));
	}

	@Test
	void applyingAPoseRecomputesTheBoxAndAPositionMovesIt() {
		PlayerState state = new PlayerState();
		state.placeAt(0.5, 64.0, 0.5);

		StateEventTrace.apply(state,
		    new StateEventTrace.StateEvent(
		        0, StateEventTrace.Phase.PRE, StateField.POSE, PlayerState.Pose.CROUCHING.id));
		PlayerState crouching = new PlayerState();
		crouching.pose = PlayerState.Pose.CROUCHING;
		crouching.placeAt(0.5, 64.0, 0.5);
		assertEquals(PlayerState.Pose.CROUCHING, state.pose);
		assertEquals(
		    Double.doubleToRawLongBits(crouching.boundingBoxMaxY), Double.doubleToRawLongBits(state.boundingBoxMaxY));

		StateEventTrace.apply(state,
		    new StateEventTrace.StateEvent(
		        0, StateEventTrace.Phase.PRE, StateField.POS_X, Double.doubleToRawLongBits(2.25)));
		crouching.placeAt(2.25, 64.0, 0.5);
		assertEquals(2.25, state.x);
		assertEquals(
		    Double.doubleToRawLongBits(crouching.boundingBoxMinX), Double.doubleToRawLongBits(state.boundingBoxMinX));
		assertEquals(64.0, state.y);
	}

	private static StateEventTrace read(final String text) throws IOException {
		return StateEventTrace.read(new BufferedReader(new StringReader(text)));
	}
}
