package com.nettarion.stride.simulator.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

final class TraceCodecTest {
	private static Trace trace(final TraceProducer producer, final StateVector first) {
		return trace(producer, "unit", TraceFixtures.MINECRAFT_VERSION, TraceFixtures.zeroState(), first);
	}

	private static Trace trace(final TraceProducer producer, final String scenario, final String version,
	    final StateVector initial, final StateVector first) {
		return new Trace(new Trace.TraceHeader(producer, version, scenario), initial,
		    List.of(new Trace.TraceEntry(0, PlayerInput.idle(0.0F, 0.0F), first)));
	}

	private static String written(final Trace trace) throws IOException {
		StringWriter writer = new StringWriter();
		TraceCodec.write(trace, writer);
		return writer.toString();
	}

	private static Trace read(final String text) throws IOException {
		return TraceCodec.read(new BufferedReader(new StringReader(text)));
	}

	private static Trace roundTrip(final Trace original) throws IOException {
		return read(written(original));
	}

	@Test
	void roundTripPreservesEverySignificantBitPattern() throws IOException {
		StateVector awkward = TraceFixtures.zeroStateBuilder()
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
		// The reason the relation is raw-bit rather than ==. Vanilla collision code produces
		// signed zero and it changes later behavior.
		Trace positive = trace(
		    TraceProducer.HEADLESS_VANILLA, TraceFixtures.zeroStateBuilder().set(StateField.DELTA_X, 0.0).build());
		Trace negative =
		    trace(TraceProducer.PURE_KERNEL, TraceFixtures.zeroStateBuilder().set(StateField.DELTA_X, -0.0).build());

		TraceComparison result = TraceComparison.compare(positive, negative);

		TraceComparison.Divergence divergence = assertInstanceOf(TraceComparison.Divergence.class, result);
		assertEquals(0, divergence.tick());
		assertEquals(List.of(StateField.DELTA_X),
		    divergence.fields().stream().map(TraceComparison.FieldDivergence::field).toList());
	}

	@Test
	void aVisualCrawlKeepsPoseIndependentFromSwimmingAndCrouchingFlags() throws IOException {
		StateVector crawl = TraceFixtures.zeroStateBuilder()
		                        .setEnum(StateField.POSE, 3, "SWIMMING")
		                        .set(StateField.SWIMMING, false)
		                        .set(StateField.CROUCHING, false)
		                        .set(StateField.WATER_HEIGHT, 0.0)
		                        .build();

		StateVector decoded = roundTrip(trace(TraceProducer.LIVE_GAME, crawl)).entries().getFirst().state();

		assertEquals(3, decoded.getEnumId(StateField.POSE));
		assertEquals("SWIMMING", decoded.label(StateField.POSE));
		assertFalse(decoded.getBoolean(StateField.SWIMMING));
		assertFalse(decoded.getBoolean(StateField.CROUCHING));
		assertEquals(0.0, decoded.getDouble(StateField.WATER_HEIGHT));
	}

	@Test
	void identicalTracesAgree() {
		StateVector only = TraceFixtures.zeroStateBuilder().set(StateField.POS_Y, 64.0).build();

		TraceComparison result = TraceComparison.compare(
		    trace(TraceProducer.HEADLESS_VANILLA, only), trace(TraceProducer.PURE_KERNEL, only));

		assertEquals(1, assertInstanceOf(TraceComparison.Agreement.class, result).ticks());
	}

	@Test
	void excludedFieldsAreIgnored() {
		Trace reference = trace(TraceProducer.HEADLESS_VANILLA,
		    TraceFixtures.zeroStateBuilder().set(StateField.FALL_DISTANCE, 1.0).build());
		Trace candidate = trace(
		    TraceProducer.PURE_KERNEL, TraceFixtures.zeroStateBuilder().set(StateField.FALL_DISTANCE, 2.0).build());

		assertInstanceOf(TraceComparison.Divergence.class, TraceComparison.compare(reference, candidate));
		assertInstanceOf(TraceComparison.Agreement.class,
		    TraceComparison.compare(reference, candidate, EnumSet.of(StateField.FALL_DISTANCE)));
	}

	@Test
	void differingActionsAreIncomparableRatherThanDivergent() {
		StateVector only = TraceFixtures.zeroState();
		Trace reference = trace(TraceProducer.HEADLESS_VANILLA, only);
		Trace candidate =
		    new Trace(new Trace.TraceHeader(TraceProducer.PURE_KERNEL, TraceFixtures.MINECRAFT_VERSION, "unit"), only,
		        List.of(new Trace.TraceEntry(0, PlayerInput.of(0.0F, 0.0F, PlayerInput.Key.FORWARD), only)));

		TraceComparison.Incomparable result =
		    assertInstanceOf(TraceComparison.Incomparable.class, TraceComparison.compare(reference, candidate));
		assertTrue(result.reason().contains("actions"), result.reason());
	}

	@Test
	void everyOtherMismatchOfRunsIsIncomparableAndNamed() {
		StateVector zero = TraceFixtures.zeroState();
		StateVector moved = TraceFixtures.zeroStateBuilder().set(StateField.POS_X, 1.0).build();
		Trace reference = trace(TraceProducer.HEADLESS_VANILLA, "unit", TraceFixtures.MINECRAFT_VERSION, zero, zero);

		assertIncomparable(reference,
		    trace(TraceProducer.PURE_KERNEL, "other", TraceFixtures.MINECRAFT_VERSION, zero, zero), "scenarios");
		assertIncomparable(
		    reference, trace(TraceProducer.PURE_KERNEL, "unit", "1.21", zero, zero), "Minecraft versions");
		assertIncomparable(reference,
		    trace(TraceProducer.PURE_KERNEL, "unit", TraceFixtures.MINECRAFT_VERSION, moved, zero), "initial states");
		Trace twoTicks = new Trace(reference.header(), zero,
		    List.of(new Trace.TraceEntry(0, PlayerInput.idle(0.0F, 0.0F), zero),
		        new Trace.TraceEntry(1, PlayerInput.idle(0.0F, 0.0F), zero)));
		assertIncomparable(reference, twoTicks, "tick counts");
		Trace renumbered =
		    new Trace(reference.header(), zero, List.of(new Trace.TraceEntry(7, PlayerInput.idle(0.0F, 0.0F), zero)));
		assertIncomparable(reference, renumbered, "tick numbering");
	}

	private static void assertIncomparable(final Trace reference, final Trace candidate, final String named) {
		TraceComparison.Incomparable result =
		    assertInstanceOf(TraceComparison.Incomparable.class, TraceComparison.compare(reference, candidate));
		assertTrue(result.reason().contains(named), result.reason());
	}

	@Test
	void anUnassignedFieldFailsClosed() {
		StateVector.Builder incomplete = StateVector.builder().set(StateField.POS_X, 1.0);

		IllegalStateException failure = assertThrows(IllegalStateException.class, incomplete::build);

		assertTrue(failure.getMessage().contains("POS_Y"), failure.getMessage());
	}

	@Test
	void aTraceWrittenAgainstADifferentVectorIsRejected() throws IOException {
		String renamedColumn = TraceCodecTest.class.getName();
		String corrupted = written(trace(TraceProducer.HEADLESS_VANILLA, TraceFixtures.zeroState()))
		                       .replaceFirst("\t" + StateField.POS_X.name() + "\t", "\t" + renamedColumn + "\t");

		assertThrows(IOException.class, () -> read(corrupted));
	}

	@Test
	void anOlderFormatVersionIsRefusedByName() throws IOException {
		String older = written(trace(TraceProducer.LIVE_GAME, TraceFixtures.zeroState()))
		                   .replaceFirst("#stride-trace\t" + Trace.FORMAT_VERSION, "#stride-trace\t12");

		IOException refusal = assertThrows(IOException.class, () -> read(older));

		assertTrue(refusal.getMessage().contains("12"), refusal.getMessage());
		assertTrue(refusal.getMessage().contains(Integer.toString(Trace.FORMAT_VERSION)), refusal.getMessage());
	}

	@Test
	void malformedNumbersAreRefusedAsIOExceptions() throws IOException {
		String text = written(trace(TraceProducer.LIVE_GAME, TraceFixtures.zeroState()));

		assertThrows(IOException.class, () -> read(text.replaceFirst("#stride-trace\t13", "#stride-trace\tthirteen")));
		assertThrows(IOException.class, () -> read(text.replaceFirst("\n0\t00\t", "\nzero\t00\t")));
		assertThrows(IOException.class, () -> read(text.replaceFirst("\t0\t0\t0\t0\t0\t", "\t0\t2\t0\t0\t0\t")));
	}
}
