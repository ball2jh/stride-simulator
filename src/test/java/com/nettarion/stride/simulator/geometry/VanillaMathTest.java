package com.nettarion.stride.simulator.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

final class VanillaMathTest {
	private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

	@Test
	void trigonometryUsesThePublishedLookupIndices() {
		double radians = 1.23456789;
		int sine = Mth.sinIndex(radians);
		int cosine = Mth.cosIndex(radians);

		assertEquals(sine + 16384 & 0xffff, cosine);
		assertEquals(Mth.sin(radians), Mth.sin(sine / 10430.378350470453));
		assertNotEquals(Mth.sin(radians), Mth.sin((sine + 1.5) / 10430.378350470453));
	}

	@Test
	void modernLookupRetiresHistoricalPositiveHalfAnglePairs() {
		assertEquals(16384, cosineOffset(90.016464F));
		assertEquals(16384, cosineOffset(135.0055F));
		assertEquals(16384, cosineOffset(5_898_195.0F));
		assertEquals(16383, cosineOffset(-45.0F));
	}

	private static int cosineOffset(final float yawDegrees) {
		float radians = yawDegrees * DEG_TO_RAD;
		return Mth.cosIndex(radians) - Mth.sinIndex(radians) & 0xffff;
	}
}
