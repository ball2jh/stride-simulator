package com.nettarion.stride.simulator.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Executable boundary probe for 26.2's double-scale trigonometric lookup. */
final class VanillaMathExtremeYawTest {
	private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
	private static final double SIN_SCALE = 10430.378350470453;

	@Test
	void firstPositiveFloatYawWhoseRadianLookupSaturatesLong() {
		float yaw = Float.intBitsToFloat(0x5b34_0000);
		float priorYaw = Math.nextDown(yaw);
		float radians = yaw * DEG_TO_RAD;
		float priorRadians = priorYaw * DEG_TO_RAD;

		assertTrue((double) priorRadians * SIN_SCALE < (double) Long.MAX_VALUE);
		assertTrue((double) radians * SIN_SCALE >= (double) Long.MAX_VALUE);
		assertEquals(Long.MAX_VALUE, (long) ((double) radians * SIN_SCALE));
		assertEquals(65535, Mth.sinIndex(radians));
		assertEquals(65535, Mth.cosIndex(radians));

		assertEquals(Long.MIN_VALUE, (long) (-(double) radians * SIN_SCALE));
		assertEquals(0, Mth.sinIndex(-radians));
		assertEquals(0, Mth.cosIndex(-radians));
		assertEquals(0.0F, Mth.sin(-radians));
		assertEquals(0.0F, Mth.cos(-radians));
	}

	@Test
	void historicalLargeHalfAngleExamplesDoNotCreateModernSpeedGain() {
		assertNearUnit(135.0055F);
		assertNearUnit(5_898_195.0F);
		assertNearUnit(11_796_480.0F);
		assertNearUnit(121_000_000.0F);
	}

	private static void assertNearUnit(final float yaw) {
		float radians = yaw * DEG_TO_RAD;
		double norm = Math.hypot(Mth.sin(radians), Mth.cos(radians));
		assertTrue(Math.abs(norm - 1.0) < 5.0E-8, () -> yaw + " produced " + norm);
	}
}
