package com.nettarion.stride.simulator.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the banded replacement of {@code Math.acos} in the minor-collision test.
 *
 * <p>The kernel decides {@code acos(r) < angle} by comparing {@code r} against
 * {@code cos(angle)} and only calling {@code acos} inside a narrow band. That is
 * an identity over the reals, not over doubles, so these tests check the two
 * forms agree on every double the predicate can be asked about — including the
 * out-of-domain ratios that ordinary sampling never produces.
 */
class MinorCollisionAngleTest {
	private static final double ANGLE = 0.13962634F;
	private static final double COSINE = Math.cos(ANGLE);
	private static final double BAND = 1.0E-9;

	/** What vanilla computes, verbatim. */
	private static boolean reference(final double ratio) {
		return Math.acos(ratio) < ANGLE;
	}

	/** What the kernel computes. Kept in sync with ClientTick by these tests. */
	private static boolean banded(final double ratio) {
		if (ratio > 1.0) {
			return false;
		}
		if (ratio > COSINE + BAND) {
			return true;
		}
		if (ratio < COSINE - BAND) {
			return false;
		}
		return Math.acos(ratio) < ANGLE;
	}

	@Test
	void agreesAcrossTheWholeDomain() {
		for (int i = 0; i <= 4_000_000; i++) {
			double ratio = -1.0 + 2.0 * i / 4_000_000.0;
			assertEquals(reference(ratio), banded(ratio), () -> "disagreement at ratio " + ratio);
		}
	}

	/** Densely inside and around the band, where the two forms could part. */
	@Test
	void agreesAroundTheThreshold() {
		double cursor = COSINE - 4.0 * BAND;
		for (int i = 0; i < 2_000_000; i++) {
			cursor = Math.nextUp(cursor);
			double ratio = cursor;
			assertEquals(reference(ratio), banded(ratio), () -> "disagreement near the band at ratio " + ratio);
		}
	}

	/**
	 * A ratio just above 1 is the parallel case: moving straight into a wall.
	 * Vanilla's acos returns NaN there and the comparison is false, so the
	 * shortcut must not read a large ratio as a small angle.
	 */
	@Test
	void ratiosAboveOneAreNotMinor() {
		double ratio = 1.0;
		for (int i = 0; i < 1000; i++) {
			ratio = Math.nextUp(ratio);
			assertFalse(banded(ratio), "ratio above 1 must not be minor");
			assertEquals(reference(ratio), banded(ratio));
		}
		assertTrue(banded(1.0), "an exactly parallel ratio is a zero angle");
		assertEquals(reference(1.0), banded(1.0));
	}

	@Test
	void notANumberIsNotMinor() {
		assertFalse(banded(Double.NaN));
		assertEquals(reference(Double.NaN), banded(Double.NaN));
		assertEquals(reference(Double.NEGATIVE_INFINITY), banded(Double.NEGATIVE_INFINITY));
		assertEquals(reference(Double.POSITIVE_INFINITY), banded(Double.POSITIVE_INFINITY));
	}
}
