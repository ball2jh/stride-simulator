package com.nettarion.stride.simulator.geometry;

import com.nettarion.stride.simulator.Refusal;
import com.nettarion.stride.simulator.UnimplementedMechanicException;

/** Admission for one vanilla-style closed integer collision cursor. */
public final class CollisionQuerySpan {
	private static final double EPSILON = 1.0E-7;
	private static final double FAST_MINIMUM_FACE = (double) Integer.MIN_VALUE + 2.0;
	private static final double FAST_MAXIMUM_FACE = (double) Integer.MAX_VALUE - 2.0;
	// An extent of 1280 can occupy at most 1284 cursor cells after epsilon,
	// flooring, and the two-cell outer ring; 1284^3 fits in a signed int.
	private static final double MAXIMUM_FAST_EXTENT = 1280.0;

	private CollisionQuerySpan() {}

	/**
	 * Proves the epsilon-expanded, one-cell-ring cursor before any narrowing.
	 *
	 * <p>The upper bound deliberately stops below {@link Integer#MAX_VALUE}:
	 * an inclusive {@code int} loop ending there would wrap when incremented.
	 * The dense cardinality check matches vanilla's int-sized cursor indexing.
	 */
	public static void requireSupported(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ) {
		if (fastAxis(minX, maxX) && fastAxis(minY, maxY) && fastAxis(minZ, maxZ)) {
			return;
		}
		if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ) || !Double.isFinite(maxX)
		    || !Double.isFinite(maxY) || !Double.isFinite(maxZ) || minX > maxX || minY > maxY || minZ > maxZ) {
			throw unsupported();
		}
		long width = cursorWidth(minX, maxX);
		long height = cursorWidth(minY, maxY);
		long depth = cursorWidth(minZ, maxZ);
		long area = width * height;
		if (area > Integer.MAX_VALUE || area * depth > Integer.MAX_VALUE) {
			throw unsupported();
		}
	}

	private static boolean fastAxis(final double minimum, final double maximum) {
		return minimum >= FAST_MINIMUM_FACE && maximum <= FAST_MAXIMUM_FACE && minimum <= maximum
		    && maximum - minimum <= MAXIMUM_FAST_EXTENT;
	}

	/**
	 * Whether the exact outer-ring cursor for a collision query lies inside an
	 * inclusive integer region. Invalid or nonrepresentable queries are refused.
	 *
	 * <p>This lets a world-capture owner prove that its first kernel query is
	 * answerable without duplicating the kernel's epsilon and cursor arithmetic.
	 */
	public static boolean fitsInside(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final int regionMinX, final int regionMinY, final int regionMinZ,
	    final int regionMaxX, final int regionMaxY, final int regionMaxZ) {
		requireSupported(minX, minY, minZ, maxX, maxY, maxZ);
		return Mth.floor(minX - EPSILON) - 1 >= regionMinX && Mth.floor(minY - EPSILON) - 1 >= regionMinY
		    && Mth.floor(minZ - EPSILON) - 1 >= regionMinZ && Mth.floor(maxX + EPSILON) + 1 <= regionMaxX
		    && Mth.floor(maxY + EPSILON) + 1 <= regionMaxY && Mth.floor(maxZ + EPSILON) + 1 <= regionMaxZ;
	}

	private static long cursorWidth(final double minimum, final double maximum) {
		double expandedMinimum = minimum - EPSILON;
		double expandedMaximum = maximum + EPSILON;
		if (!Double.isFinite(expandedMinimum) || !Double.isFinite(expandedMaximum)
		    || expandedMinimum < (double) Integer.MIN_VALUE + 1.0
		    || expandedMaximum >= (double) Integer.MAX_VALUE - 1.0) {
			throw unsupported();
		}
		int first = Mth.floor(expandedMinimum) - 1;
		int last = Mth.floor(expandedMaximum) + 1;
		long width = (long) last - first + 1L;
		if (width <= 0L || width > Integer.MAX_VALUE) {
			throw unsupported();
		}
		return width;
	}

	private static UnimplementedMechanicException unsupported() {
		return new UnimplementedMechanicException(
		    Refusal.INADMISSIBLE_COORDINATE, "collision query exceeds the exact integer-cursor domain");
	}
}
