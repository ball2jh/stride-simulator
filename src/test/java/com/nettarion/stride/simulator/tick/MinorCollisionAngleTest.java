package com.nettarion.stride.simulator.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link Move#isMinorAngle} decides vanilla's {@code acos(r) < angle} by comparing {@code r}
 * against {@code cos(angle)} and only calling {@code acos} inside a narrow band. That is an
 * identity over the reals, not over doubles, so these tests check the two forms agree on the
 * doubles the predicate can be asked about, including the out-of-domain ratios ordinary sampling
 * never produces.
 *
 * <p>The sweeps are representative, not exhaustive: the whole domain is sampled at a fixed stride
 * and the band is walked ulp by ulp for a short run on each side. The band's width is justified
 * analytically in {@code Move}; the sweeps guard the transcription of that argument.
 */
final class MinorCollisionAngleTest {
	/** {@code Mth.DEG_TO_RAD * 8}, as {@code Move} spells it. */
	private static final double ANGLE = 0.13962634F;

	private static final double COSINE = Math.cos(ANGLE);

	private static final double BAND = 1.0E-9;

	/** What vanilla computes, verbatim. */
	private static boolean reference(final double ratio) {
		return Math.acos(ratio) < ANGLE;
	}

	@Test
	void agreesAcrossTheDomainAtAFixedStride() {
		int samples = 40_000;
		for (int i = 0; i <= samples; i++) {
			double ratio = -1.0 + 2.0 * i / samples;
			assertEquals(reference(ratio), Move.isMinorAngle(ratio), () -> "disagreement at ratio " + ratio);
		}
	}

	/** Ulp by ulp across the band, where the two forms could part. */
	@Test
	void agreesAroundTheThreshold() {
		double cursor = COSINE - 4.0 * BAND;
		int ulps = 40_000;
		for (int i = 0; i < ulps; i++) {
			cursor = Math.nextUp(cursor);
			double ratio = cursor;
			assertEquals(reference(ratio), Move.isMinorAngle(ratio), () -> "disagreement near the band at " + ratio);
		}
		assertTrue(cursor < COSINE + 4.0 * BAND, "the walk must stay inside the band it is checking");
		double above = COSINE + 4.0 * BAND;
		for (int i = 0; i < ulps; i++) {
			double ratio = above;
			assertEquals(reference(ratio), Move.isMinorAngle(ratio), () -> "disagreement above the band at " + ratio);
			above = Math.nextDown(above);
		}
	}

	/**
	 * A ratio just above 1 is the parallel case: moving straight into a wall. Vanilla's acos returns
	 * NaN there and the comparison is false, so the shortcut must not read a large ratio as a small
	 * angle.
	 */
	@Test
	void ratiosAboveOneAreNotMinor() {
		double ratio = 1.0;
		for (int i = 0; i < 1000; i++) {
			ratio = Math.nextUp(ratio);
			assertFalse(Move.isMinorAngle(ratio), "ratio above 1 must not be minor");
			assertEquals(reference(ratio), Move.isMinorAngle(ratio));
		}
		assertTrue(Move.isMinorAngle(1.0), "an exactly parallel ratio is a zero angle");
		assertEquals(reference(1.0), Move.isMinorAngle(1.0));
	}

	@Test
	void notANumberIsNotMinor() {
		assertFalse(Move.isMinorAngle(Double.NaN));
		assertEquals(reference(Double.NaN), Move.isMinorAngle(Double.NaN));
		assertEquals(reference(Double.NEGATIVE_INFINITY), Move.isMinorAngle(Double.NEGATIVE_INFINITY));
		assertEquals(reference(Double.POSITIVE_INFINITY), Move.isMinorAngle(Double.POSITIVE_INFINITY));
	}
}
