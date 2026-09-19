package com.nettarion.stride.simulator.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Raw-bit assertions, so a {@code -0.0} or a one-ulp difference cannot compare equal. */
final class RawBits {
	private RawBits() {}

	static void assertRaw(final double expected, final double actual) {
		assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
	}

	static void assertRaw(final double expected, final double actual, final String message) {
		assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual), message);
	}
}
