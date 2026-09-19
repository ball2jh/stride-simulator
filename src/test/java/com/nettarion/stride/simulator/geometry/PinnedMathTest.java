package com.nettarion.stride.simulator.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The platform math the transcription calls is pinned bit for bit.
 *
 * <p>Vanilla itself reaches {@code Math.sin} for its sine table, {@code Math.sqrt}
 * for vector lengths, and {@code Math.cos} for the glide lift, so the simulator
 * calls the same functions rather than their strict variants: switching would
 * break parity on the very JVM the game runs on. What that leaves open is the
 * platform. {@code Math.sin} and {@code Math.cos} are permitted one ulp of error
 * and may be intrinsified differently across JIT tiers, architectures and JDK
 * releases; {@code Math.sqrt} is correctly rounded everywhere. A divergence the
 * differential gate reports at one of those sites is first a platform question,
 * and this test answers it before any physics is suspected: the bits below were
 * recorded on the pinned development platform, and a change here with no source
 * change means the platform moved, not the transcription. Recorded on JDK 26
 * on amd64; a port records its own bits beside these under its own name.
 *
 * <p>The sine table's generating expression is the pinned source's,
 * {@code (float) Math.sin(i / 10430.378350470453)}; the entries checked span the
 * table's quadrants and its densest region near zero.
 */
class PinnedMathTest {
	@Test
	void sineTableEntriesMatchTheRecordedBits() {
		assertTableEntry(0, 0x00000000);
		assertTableEntry(1, 0x38c90fdb);
		assertTableEntry(2, 0x39490fdb);
		assertTableEntry(1000, 0x3dc40c83);
		assertTableEntry(16384, 0x3f800000);
		assertTableEntry(32768, 0x250d3132);
		assertTableEntry(49152, 0xbf800000);
		assertTableEntry(65535, 0xb8c90fdb);
	}

	@Test
	void glideLiftCosineMatchesTheRecordedBits() {
		// The lift is Math.cos over a float lean angle widened to double.
		assertEquals(0x3ff0000000000000L, liftBits(0.0F));
		assertEquals(0x3fe6a09e5e333598L, liftBits(45.0F));
		assertEquals(0x3f9f457e62d2edcaL, liftBits(-88.25F));
		assertEquals(0x3feffffff7d2af65L, liftBits(0.01F));
		assertEquals(0x3febb67ae46f065cL, liftBits(30.0F));
	}

	@Test
	void doubleSquareRootIsCorrectlyRounded() {
		// sqrt is exact to the last bit on every platform; these guard the
		// conversion points the transcription performs around it.
		assertEquals(0x3ff6a09e667f3bcdL, Double.doubleToRawLongBits(Math.sqrt(2.0)));
		assertEquals(0x3fb999999999999aL, Double.doubleToRawLongBits(Math.sqrt(0.01)));
		assertEquals(0x3fb504f3, Float.floatToRawIntBits(Mth.sqrt(2.0F)));
	}

	private static void assertTableEntry(final int index, final int expectedBits) {
		double radians = index / 10430.378350470453;
		assertEquals(index, Mth.sinIndex(radians), "the index arithmetic must select entry " + index);
		assertEquals(expectedBits, Float.floatToRawIntBits(Mth.sin(radians)), "sine table entry " + index);
	}

	private static long liftBits(final float leanDegrees) {
		float leanAngle = leanDegrees * (float) (Math.PI / 180.0);
		return Double.doubleToRawLongBits(Math.cos(leanAngle));
	}
}
