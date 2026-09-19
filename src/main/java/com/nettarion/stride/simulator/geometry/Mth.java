package com.nettarion.stride.simulator.geometry;

/**
 * Vanilla's {@code Mth} math helpers, reproduced exactly.
 *
 * <p>The class keeps vanilla's name so a transcribed expression reads the same as its original.
 * {@code Mth.sin} is a 65,536-entry float lookup table, and an approximation of any kind breaks
 * parity; the table is regenerated here with the same expression and the same index arithmetic as
 * vanilla, so yaw values that fall between table entries quantize identically. Stateless and
 * thread-safe.
 */
public final class Mth {
	/**
	 * {@code Mth.EPSILON}: the float tolerance of {@link #equal}, which {@code Entity.move} uses to
	 * decide the horizontal collision flags.
	 */
	public static final float EPSILON = 1.0E-5F;

	private static final double SIN_SCALE = 10430.378350470453;

	private static final long SIN_MASK = 65535L;

	private static final float[] SIN = new float[65536];

	static {
		for (int i = 0; i < SIN.length; i++) {
			SIN[i] = (float) Math.sin(i / SIN_SCALE);
		}
	}

	private Mth() {}

	/** {@code Mth.sin}: the table entry for an angle in radians. */
	public static float sin(final double radians) {
		return SIN[sinIndex(radians)];
	}

	/** {@code Mth.cos}: the table entry a quarter turn ahead of the angle in radians. */
	public static float cos(final double radians) {
		return SIN[cosIndex(radians)];
	}

	/** The table index vanilla's {@code Mth.sin} selects: truncate the scaled angle, then mask. */
	public static int sinIndex(final double radians) {
		return (int) ((long) (radians * SIN_SCALE) & SIN_MASK);
	}

	/**
	 * The table index vanilla's {@code Mth.cos} selects: offset the scaled angle by a quarter turn
	 * before truncating, so {@code cos(-a)} and {@code cos(a)} may read different entries.
	 */
	public static int cosIndex(final double radians) {
		return (int) ((long) (radians * SIN_SCALE + 16384.0) & SIN_MASK);
	}

	/** {@code Mth.clamp(float, float, float)}. */
	public static float clamp(final float value, final float min, final float max) {
		return value < min ? min : Math.min(value, max);
	}

	/** {@code Mth.clamp(double, double, double)}. */
	public static double clamp(final double value, final double min, final double max) {
		return value < min ? min : Math.min(value, max);
	}

	/** {@code Mth.equal(double, double)}: within {@link #EPSILON}, a float tolerance even on doubles. */
	public static boolean equal(final double a, final double b) {
		return Math.abs(b - a) < EPSILON;
	}

	/** {@code Mth.sqrt(float)}: the double square root narrowed to float. */
	public static float sqrt(final float value) {
		return (float) Math.sqrt(value);
	}

	/** {@code Mth.square(float)}. */
	public static float square(final float value) {
		return value * value;
	}

	/**
	 * {@code Mth.floor(double)}: an {@code int} cast, then one down when the cast rounded up.
	 *
	 * <p>Not {@code Math.floor}: the cast saturates, so values at or beyond {@code Integer.MAX_VALUE}
	 * answer {@code Integer.MAX_VALUE}, values at or below {@code Integer.MIN_VALUE} answer
	 * {@code Integer.MIN_VALUE}, and NaN answers zero. Callers whose coordinates may leave the
	 * {@code int} domain check it first; see {@link CollisionQuerySpan#requireSupported}.
	 */
	public static int floor(final double value) {
		int truncated = (int) value;
		return value < truncated ? truncated - 1 : truncated;
	}

	/** {@code Mth.frac(double)}: floors through {@code long}, not {@code int}. */
	public static double frac(final double value) {
		return value - (long) Math.floor(value);
	}

	/**
	 * {@code Mth.wrapDegrees(float)}: the angle folded into {@code [-180, 180)}.
	 *
	 * <p>The remainder is taken only when it can change the angle. A float remainder is exact
	 * (JLS 15.17.3), so for {@code |angle| < 360} it is the angle itself, signed zero included; the
	 * direct test is far cheaper than the remainder, and a NaN fails both comparisons and still
	 * takes it.
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
