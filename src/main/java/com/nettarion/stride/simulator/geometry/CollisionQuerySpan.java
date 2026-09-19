package com.nettarion.stride.simulator.geometry;

import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.UnimplementedMechanicException;

/**
 * Admission of a collision query box to vanilla's closed integer cell cursor.
 *
 * <p>Vanilla walks the cells a query reaches with an {@code int}-indexed {@code Cursor3D} over the
 * box expanded by {@code 1.0E-7} and one ring of cells. A box whose cursor would not fit that
 * indexing is refused here rather than walked wrongly. Stateless and thread-safe.
 */
public final class CollisionQuerySpan {
	private static final double EPSILON = 1.0E-7;

	private static final double FAST_MINIMUM_FACE = (double) Integer.MIN_VALUE + 2.0;

	private static final double FAST_MAXIMUM_FACE = (double) Integer.MAX_VALUE - 2.0;

	// An extent of 1280 can occupy at most 1284 cursor cells after epsilon,
	// flooring, and the two-cell outer ring; 1284^3 fits in a signed int.
	private static final double MAXIMUM_FAST_EXTENT = 1280.0;

	private CollisionQuerySpan() {}

	/**
	 * Refuses a query box, in blocks, whose epsilon-expanded one-ring cursor leaves the {@code int}
	 * domain or whose cell count overflows an {@code int}.
	 *
	 * <p>The upper bound deliberately stops below {@link Integer#MAX_VALUE}: an inclusive
	 * {@code int} loop ending there would wrap when incremented. The dense cardinality check matches
	 * vanilla's int-sized cursor indexing.
	 *
	 * @throws UnimplementedMechanicException with {@code INADMISSIBLE_COORDINATE} when a face is not
	 *         finite, the box is inverted, or the cursor would not fit
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
		    RefusalCause.INADMISSIBLE_COORDINATE, "collision query exceeds the exact integer-cursor domain");
	}
}
