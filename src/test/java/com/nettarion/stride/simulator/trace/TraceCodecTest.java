package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.PlayerInput;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.PlayerInput;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class TraceCodecTest {
	private static StateVector.Builder state() {
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
		return builder;
	}

	private static Trace trace(final TraceProducer producer, final StateVector first) {
		return new Trace(new Trace.TraceHeader(producer, "26.2", "unit"), state().build(),
		    List.of(new Trace.TraceEntry(0, PlayerInput.idle(0.0F, 0.0F), first)));
	}

	private static Trace roundTrip(final Trace original) throws IOException {
		StringWriter writer = new StringWriter();
		TraceCodec.write(original, writer);
		return TraceCodec.read(new BufferedReader(new StringReader(writer.toString())));
	}

	@Test
	void roundTripPreservesEverySignificantBitPattern() throws IOException {
		StateVector awkward = state()
		                          .set(StateField.POS_X, -0.0)
		                          .set(StateField.POS_Y, Double.longBitsToDouble(0x7FF8_0000_0000_0001L))
		                          .set(StateField.DELTA_Y, -0.0784000015258789)
		                          .set(StateField.Y_ROT, Float.intBitsToFloat(0xFFC0_0001))
		                          .set(StateField.FALL_DISTANCE, Double.MIN_VALUE)
		                          .set(StateField.STUCK_SPEED_X, -0.0)
		                          .set(StateField.STUCK_SPEED_Y, Double.MIN_VALUE)
		                          .set(StateField.STUCK_SPEED_Z, (double) 0.9F)
		                          .set(StateField.IN_POWDER_SNOW, true)
		                          .set(StateField.WAS_IN_POWDER_SNOW, true)
		                          .set(StateField.TICKS_FROZEN, 139)
		                          .set(StateField.CAN_FREEZE, true)
		                          .set(StateField.FROST_SPEED_TICKS, 73)
		                          .set(StateField.CAN_WALK_ON_POWDER_SNOW, true)
		                          .set(StateField.SPRINT_WINDOW_TICKS, 4)
		                          .set(StateField.AUTO_JUMP_ENABLED, false)
		                          .set(StateField.FAST_LAVA, true)
		                          .set(StateField.SUPPORTING_BLOCK_PRESENT, true)
		                          .set(StateField.SUPPORTING_BLOCK_X, -19)
		                          .set(StateField.SUPPORTING_BLOCK_Y, 203)
		                          .set(StateField.SUPPORTING_BLOCK_Z, Integer.MIN_VALUE)
		                          .set(StateField.ON_GROUND_NO_BLOCKS, true)
		                          .set(StateField.SPRINTING, true)
		                          .setFlags(StateField.SHARED_FLAGS_RESIDUAL, (byte) 0b0100_0000)
		                          .set(StateField.SPRINT_TRIGGER_TIME, -7)
		                          .setEnum(StateField.POSE, 5, "CROUCHING")
		                          .build();

		Trace decoded = roundTrip(trace(TraceProducer.LIVE_GAME, awkward));
		StateVector result = decoded.entries().getFirst().state();

		for (StateField field : StateField.ALL) {
			assertEquals(awkward.rawBits(field), result.rawBits(field), field.name());
		}
		assertEquals("CROUCHING", result.label(StateField.POSE));
		assertEquals(TraceProducer.LIVE_GAME, decoded.header().producer());
	}

	@Test
	void negativeZeroIsNotZero() {
		// The reason the relation is raw-bit rather than ==. Vanilla collision
		// code produces signed zero and it changes later behavior.
		Trace positive = trace(TraceProducer.HEADLESS_VANILLA, state().set(StateField.DELTA_X, 0.0).build());
		Trace negative = trace(TraceProducer.PURE_KERNEL, state().set(StateField.DELTA_X, -0.0).build());

		TraceComparison result = TraceComparison.compare(positive, negative);

		TraceComparison.Divergence divergence = assertInstanceOf(TraceComparison.Divergence.class, result);
		assertEquals(0, divergence.tick());
		assertEquals(List.of(StateField.DELTA_X),
		    divergence.fields().stream().map(TraceComparison.FieldDivergence::field).toList());
	}

	@Test
	void dryVisualCrawlKeepsPoseIndependentFromSwimmingAndCrouchingFlags() throws IOException {
		StateVector crawl = state()
		                        .setEnum(StateField.POSE, 3, "SWIMMING")
		                        .set(StateField.SWIMMING, false)
		                        .set(StateField.CROUCHING, false)
		                        .set(StateField.WATER_HEIGHT, 0.0)
		                        .build();

		StateVector decoded = roundTrip(trace(TraceProducer.LIVE_GAME, crawl)).entries().getFirst().state();

		assertEquals(3, decoded.getEnumOrdinal(StateField.POSE));
		assertEquals("SWIMMING", decoded.label(StateField.POSE));
		assertEquals(false, decoded.getBoolean(StateField.SWIMMING));
		assertEquals(false, decoded.getBoolean(StateField.CROUCHING));
		assertEquals(0.0, decoded.getDouble(StateField.WATER_HEIGHT));
	}

	@Test
	void identicalTracesAgree() {
		StateVector only = state().set(StateField.POS_Y, 64.0).build();

		TraceComparison result = TraceComparison.compare(
		    trace(TraceProducer.HEADLESS_VANILLA, only), trace(TraceProducer.PURE_KERNEL, only));

		assertEquals(1, assertInstanceOf(TraceComparison.Agreement.class, result).ticks());
	}

	@Test
	void excludedFieldsAreIgnored() {
		Trace reference = trace(TraceProducer.HEADLESS_VANILLA, state().set(StateField.FALL_DISTANCE, 1.0).build());
		Trace candidate = trace(TraceProducer.PURE_KERNEL, state().set(StateField.FALL_DISTANCE, 2.0).build());

		assertInstanceOf(TraceComparison.Divergence.class, TraceComparison.compare(reference, candidate));
		assertInstanceOf(TraceComparison.Agreement.class,
		    TraceComparison.compare(reference, candidate, EnumSet.of(StateField.FALL_DISTANCE)));
	}

	@Test
	void differingActionsAreIncomparableRatherThanDivergent() {
		StateVector only = state().build();
		Trace reference = trace(TraceProducer.HEADLESS_VANILLA, only);
		Trace candidate = new Trace(new Trace.TraceHeader(TraceProducer.PURE_KERNEL, "26.2", "unit"), state().build(),
		    List.of(new Trace.TraceEntry(
		        0, new PlayerInput(true, false, false, false, false, false, false, 0.0F, 0.0F), only)));

		assertInstanceOf(TraceComparison.Incomparable.class, TraceComparison.compare(reference, candidate));
	}

	@Test
	void anUnassignedFieldFailsClosed() {
		StateVector.Builder incomplete = StateVector.builder().set(StateField.POS_X, 1.0);

		IllegalStateException failure = assertThrows(IllegalStateException.class, incomplete::build);

		assertTrue(failure.getMessage().contains("POS_Y"), failure.getMessage());
	}

	@Test
	void aTraceWrittenAgainstADifferentVectorIsRejected() {
		String renamedColumn = TraceCodecTest.class.getName();
		StringWriter writer = new StringWriter();
		assertThrows(IOException.class, () -> {
			TraceCodec.write(trace(TraceProducer.HEADLESS_VANILLA, state().build()), writer);
			String corrupted =
			    writer.toString().replaceFirst("\t" + StateField.POS_X.name() + "\t", "\t" + renamedColumn + "\t");
			TraceCodec.read(new BufferedReader(new StringReader(corrupted)));
		});
	}
}
