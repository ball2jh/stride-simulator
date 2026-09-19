package com.nettarion.stride.simulator.geometry;

/**
 * The pinned game's own math, reproduced exactly.
 *
 * <p>{@code Mth.sin} is a 65,536-entry float lookup table, and an
 * approximation of any kind breaks parity. The table is regenerated here with
 * the same expression and the same index arithmetic as the pinned source, so
 * yaw values that fall between table entries quantise identically.
 */
public final class Mth {
	private static final double SIN_SCALE = 10430.378350470453;
	private static final long SIN_MASK = 65535L;
	private static final float[] SIN = new float[65536];

	/** {@code Mth.EPSILON}, used by the collision-flag comparison in move(). */
	public static final float EPSILON = 1.0E-5F;

	static {
		for (int i = 0; i < SIN.length; i++) {
			SIN[i] = (float) Math.sin(i / SIN_SCALE);
		}
	}

	private Mth() {}

	public static float sin(final double radians) {
		return SIN[sinIndex(radians)];
	}

	public static float cos(final double radians) {
		return SIN[cosIndex(radians)];
	}

	/** Exact lookup-table index selected by the pinned game's {@code Mth.sin}. */
	public static int sinIndex(final double radians) {
		return (int) ((long) (radians * SIN_SCALE) & SIN_MASK);
	}

	/** Exact lookup-table index selected by the pinned game's {@code Mth.cos}. */
	public static int cosIndex(final double radians) {
		return (int) ((long) (radians * SIN_SCALE + 16384.0) & SIN_MASK);
	}

	public static float clamp(final float value, final float min, final float max) {
		return value < min ? min : Math.min(value, max);
	}

	public static double clamp(final double value, final double min, final double max) {
		return value < min ? min : Math.min(value, max);
	}

	/** {@code Mth.equal} — note the float epsilon even on the double overload. */
	public static boolean equal(final double a, final double b) {
		return Math.abs(b - a) < EPSILON;
	}

	public static float sqrt(final float value) {
		return (float) Math.sqrt(value);
	}

	public static float square(final float value) {
		return value * value;
	}

	public static int floor(final double value) {
		int truncated = (int) value;
		return value < truncated ? truncated - 1 : truncated;
	}

	/** {@code Mth.frac} — note it floors through {@code long}, not {@code int}. */
	public static double frac(final double value) {
		return value - (long) Math.floor(value);
	}
	/**
	 * {@code Mth.wrapDegrees(float)}.
	 *
	 * <p>The remainder is taken only when it can change the angle. A float
	 * remainder is exact (JLS 15.17.3), so for {@code |angle| < 360} it is the
	 * angle itself, signed zero included; the direct test is far cheaper than
	 * the remainder, and a NaN fails both comparisons and still takes it.
	 */
	public static float wrapDegrees(final float angle) {
		float normalized = angle > -360.0F && angle < 360.0F ? angle : angle % 360.0F;
		if (normalized >= 180.0F) {
			normalized -= 360.0F;
		}
		if (normalized < -180.0F) {
			normalized += 360.0F;
		}
		return normalized;
	}
}
