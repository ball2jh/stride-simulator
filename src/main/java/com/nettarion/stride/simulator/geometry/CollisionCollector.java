package com.nettarion.stride.simulator.geometry;

import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.SupportCell;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * Answers collision and support queries through one exact retained cell span.
 *
 * <p>A query returns the buffer to read: the scratch's own collection when the world answered it
 * directly, or the shared {@link RetainedSpan} with the groups the query box reaches selected.
 * Callers walk the selected groups and never hold a returned buffer across another query.
 *
 * <p>Every query takes the player's collision context as scalars, named as vanilla's
 * {@code EntityCollisionContext} names them: {@code entityBottom} is the feet Y, in blocks;
 * {@code descending} is {@code isDescending()}, which for a player is the shift key (the tick calls
 * the same boolean {@code shiftDown} where it is the input and {@code descending} where it is the
 * context); {@code fallDistance} is in blocks; {@code canWalkOnPowderSnow} is the boots test. Only
 * scaffolding and powder snow read them. Stateless apart from the scratch it is handed.
 */
public final class CollisionCollector {
	/**
	 * The feet a placement context reports. {@code CollisionContext.withPosition} builds an
	 * {@code EntityCollisionContext} whose {@code placement} is true, and under one
	 * {@code PowderSnowBlock.getCollisionShape} and {@code ScaffoldingBlock.getCollisionShape} answer
	 * {@code Shapes.empty()} before reading feet, shift, fall distance or boots.
	 */
	private static final double PLACEMENT_FEET = Double.NEGATIVE_INFINITY;

	/** {@code Shapes.EPSILON}: vanilla's shape-join tolerance, in blocks. */
	private static final double SHAPE_EPSILON = 1.0E-7;

	private CollisionCollector() {}

	/**
	 * {@code CollisionGetter.getPreMoveCollisions}: the block shapes meeting the box under a
	 * placement context, in which scaffolding and powder snow have no shape whatever the player's
	 * feet, shift key, fall distance or boots would otherwise say. Every other captured shape is
	 * context-free.
	 *
	 * <p>The listener's new-collision test is the one caller; movement itself uses {@link #collect}
	 * with the player's context. The world models both blocks' context by feet, descent, fall and
	 * boots ({@code WorldView.CollisionBehavior}), and the answers below are the ones under which
	 * each of them is empty: feet below every cell, so no cell is "above" the player; descending; no
	 * fall past the powder-snow threshold; no boots.
	 */
	public static CollisionBuffer collectPreMove(final WorldView world, final Scratch scratch, final double minX,
	    final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
		return collect(world, scratch, minX, minY, minZ, maxX, maxY, maxZ, PLACEMENT_FEET, true, 0.0, false);
	}

	/**
	 * The collision shapes meeting the query box, in blocks, under the player's context: the buffer
	 * to read, with the groups the box reaches selected.
	 *
	 * @throws com.nettarion.stride.simulator.UnimplementedMechanicException when the box leaves the
	 *         integer-cursor domain or the world refuses a cell
	 */
	public static CollisionBuffer collect(final WorldView world, final Scratch scratch, final double minX,
	    final double minY, final double minZ, final double maxX, final double maxY, final double maxZ,
	    final double entityBottom, final boolean descending, final double fallDistance,
	    final boolean canWalkOnPowderSnow) {
		RetainedSpan span = scratch.span;
		// A query the retained span covers was admitted with the span, and its
		// faces were just shown ordered, so admission is re-derived only on a miss.
		if (!span.covers(world, minX - SHAPE_EPSILON, minY - SHAPE_EPSILON, minZ - SHAPE_EPSILON, maxX + SHAPE_EPSILON,
		        maxY + SHAPE_EPSILON, maxZ + SHAPE_EPSILON)) {
			CollisionQuerySpan.requireSupported(minX, minY, minZ, maxX, maxY, maxZ);
			// A retained span shows the world supports reuse; only a world with
			// none has to be asked. A view without an identity can never be
			// covered, so building a span for it would be wasted work.
			if (world.identity() == 0L || !span.names(world) && !supportsGroupedSpanReuse(world)) {
				world.collectCollisionBoxes(minX, minY, minZ, maxX, maxY, maxZ, entityBottom, descending, fallDistance,
				    canWalkOnPowderSnow, scratch.collisions);
				return scratch.collisions;
			}
			int x0 = Mth.floor(minX - SHAPE_EPSILON);
			int y0 = Mth.floor(minY - SHAPE_EPSILON);
			int z0 = Mth.floor(minZ - SHAPE_EPSILON);
			int x1 = Mth.floor(maxX + SHAPE_EPSILON);
			int y1 = Mth.floor(maxY + SHAPE_EPSILON);
			int z1 = Mth.floor(maxZ + SHAPE_EPSILON);
			if (!world.spanHasCollisionPotential(x0, y0, z0, x1, y1, z1)) {
				scratch.collisions.clear();
				return scratch.collisions;
			}
			if (!world.collectSpanBoxes(x0, y0, z0, x1, y1, z1, span.boxes)) {
				span.discard();
				world.collectCollisionBoxes(minX, minY, minZ, maxX, maxY, maxZ, entityBottom, descending, fallDistance,
				    canWalkOnPowderSnow, scratch.collisions);
				return scratch.collisions;
			}
			span.retain(world, x0, y0, z0, x1, y1, z1);
		}

		// Reselect the groups this query reaches, in the span's own order, which
		// is the order the ordinary collection visits their cells.
		CollisionBuffer retained = span.boxes;
		retained.clearSelection();
		final int groups = retained.groupCount();
		for (int group = 0; group < groups; group++) {
			if (groupIntersects(retained, group, minX, minY, minZ, maxX, maxY, maxZ)) {
				retained.select(group);
			}
		}
		return retained;
	}

	private static boolean groupIntersects(final CollisionBuffer shapes, final int group, final double minX,
	    final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
		int start = shapes.groupStart(group);
		return shapes.groupCanonicalFull(group)
		    ? strictIntersects(shapes.minX(start), shapes.minY(start), shapes.minZ(start), shapes.maxX(start),
		          shapes.maxY(start), shapes.maxZ(start), minX, minY, minZ, maxX, maxY, maxZ)
		    : generalGroupIntersects(shapes, start, shapes.groupEnd(group), minX, minY, minZ, maxX, maxY, maxZ);
	}

	private static boolean supportsGroupedSpanReuse(final WorldView world) {
		return world.supportsSpanReuse()
		    && world.collisionShapeProtocol() == WorldView.CollisionShapeProtocol.SOURCE_GROUPS_V1;
	}

	/**
	 * {@code Shapes.joinIsNotEmpty(shape.move(cell), Shapes.create(query), AND)} for a general source
	 * shape held as its boxes: whether some box and the query box share a merged cell that is full in
	 * both.
	 *
	 * <p>Vanilla's answer has three parts, each kept in its own arithmetic. {@code Shapes.create}
	 * makes a query thinner than {@code 1.0E-7} on any axis the empty shape, and the join is then
	 * empty. {@code joinIsNotEmpty} rejects when the whole shape's bounds and the query's are more
	 * than {@code 1.0E-7} apart on an axis. Otherwise {@code IndirectMerger} merges the two
	 * coordinate lists per axis, absorbing any coordinate within {@code 1.0E-7} of the previous one,
	 * and the join is non-empty when some merged interval lies inside a box on every axis;
	 * {@link #axisJoins} is that merge for one box and one axis.
	 *
	 * <p>This form reads a shape as a flat {@code double[]} of six-tuples at a cell offset, which is
	 * how a {@code SnapshotView} palette holds it; the private overload below is the same three
	 * steps over a buffer's group. The bodies are duplicated rather than shared through an accessor
	 * interface because both run in the collision hot path per query and the indirection measured.
	 */
	public static boolean generalGroupIntersects(final double[] shape, final int x, final int y, final int z,
	    final double minX, final double minY, final double minZ, final double maxX, final double maxY,
	    final double maxZ) {
		if (!generalQueryExists(minX, minY, minZ, maxX, maxY, maxZ)) {
			return false;
		}
		double shapeMinX = Double.POSITIVE_INFINITY;
		double shapeMinY = Double.POSITIVE_INFINITY;
		double shapeMinZ = Double.POSITIVE_INFINITY;
		double shapeMaxX = Double.NEGATIVE_INFINITY;
		double shapeMaxY = Double.NEGATIVE_INFINITY;
		double shapeMaxZ = Double.NEGATIVE_INFINITY;
		for (int at = 0; at < shape.length; at += 6) {
			shapeMinX = Math.min(shapeMinX, shape[at] + x);
			shapeMinY = Math.min(shapeMinY, shape[at + 1] + y);
			shapeMinZ = Math.min(shapeMinZ, shape[at + 2] + z);
			shapeMaxX = Math.max(shapeMaxX, shape[at + 3] + x);
			shapeMaxY = Math.max(shapeMaxY, shape[at + 4] + y);
			shapeMaxZ = Math.max(shapeMaxZ, shape[at + 5] + z);
		}
		if (!boundsMayJoin(
		        shapeMinX, shapeMinY, shapeMinZ, shapeMaxX, shapeMaxY, shapeMaxZ, minX, minY, minZ, maxX, maxY, maxZ)) {
			return false;
		}
		for (int at = 0; at < shape.length; at += 6) {
			if (generalBoxIntersects(shape[at] + x, shape[at + 1] + y, shape[at + 2] + z, shape[at + 3] + x,
			        shape[at + 4] + y, shape[at + 5] + z, minX, minY, minZ, maxX, maxY, maxZ)) {
				return true;
			}
		}
		return false;
	}

	/** The public {@code generalGroupIntersects} over one buffer group instead of a flat shape array. */
	private static boolean generalGroupIntersects(final CollisionBuffer shapes, final int start, final int end,
	    final double minX, final double minY, final double minZ, final double maxX, final double maxY,
	    final double maxZ) {
		if (!generalQueryExists(minX, minY, minZ, maxX, maxY, maxZ)) {
			return false;
		}
		double shapeMinX = Double.POSITIVE_INFINITY;
		double shapeMinY = Double.POSITIVE_INFINITY;
		double shapeMinZ = Double.POSITIVE_INFINITY;
		double shapeMaxX = Double.NEGATIVE_INFINITY;
		double shapeMaxY = Double.NEGATIVE_INFINITY;
		double shapeMaxZ = Double.NEGATIVE_INFINITY;
		for (int index = start; index < end; index++) {
			shapeMinX = Math.min(shapeMinX, shapes.minX(index));
			shapeMinY = Math.min(shapeMinY, shapes.minY(index));
			shapeMinZ = Math.min(shapeMinZ, shapes.minZ(index));
			shapeMaxX = Math.max(shapeMaxX, shapes.maxX(index));
			shapeMaxY = Math.max(shapeMaxY, shapes.maxY(index));
			shapeMaxZ = Math.max(shapeMaxZ, shapes.maxZ(index));
		}
		if (!boundsMayJoin(
		        shapeMinX, shapeMinY, shapeMinZ, shapeMaxX, shapeMaxY, shapeMaxZ, minX, minY, minZ, maxX, maxY, maxZ)) {
			return false;
		}
		for (int index = start; index < end; index++) {
			if (generalBoxIntersects(shapes.minX(index), shapes.minY(index), shapes.minZ(index), shapes.maxX(index),
			        shapes.maxY(index), shapes.maxZ(index), minX, minY, minZ, maxX, maxY, maxZ)) {
				return true;
			}
		}
		return false;
	}

	/** {@code Shapes.create}: a box thinner than the epsilon on any axis is the empty shape. */
	private static boolean generalQueryExists(final double minX, final double minY, final double minZ,
	    final double maxX, final double maxY, final double maxZ) {
		return !(maxX - minX < SHAPE_EPSILON) && !(maxY - minY < SHAPE_EPSILON) && !(maxZ - minZ < SHAPE_EPSILON);
	}

	/**
	 * {@code Shapes.joinIsNotEmpty}'s per-axis rejection on the whole shape's bounds:
	 * {@code first.max(axis) < second.min(axis) - 1.0E-7} or the reverse.
	 */
	private static boolean boundsMayJoin(final double shapeMinX, final double shapeMinY, final double shapeMinZ,
	    final double shapeMaxX, final double shapeMaxY, final double shapeMaxZ, final double minX, final double minY,
	    final double minZ, final double maxX, final double maxY, final double maxZ) {
		return !(shapeMaxX < minX - SHAPE_EPSILON) && !(maxX < shapeMinX - SHAPE_EPSILON)
		    && !(shapeMaxY < minY - SHAPE_EPSILON) && !(maxY < shapeMinY - SHAPE_EPSILON)
		    && !(shapeMaxZ < minZ - SHAPE_EPSILON) && !(maxZ < shapeMinZ - SHAPE_EPSILON);
	}

	private static boolean generalBoxIntersects(final double shapeMinX, final double shapeMinY, final double shapeMinZ,
	    final double shapeMaxX, final double shapeMaxY, final double shapeMaxZ, final double minX, final double minY,
	    final double minZ, final double maxX, final double maxY, final double maxZ) {
		return axisJoins(shapeMinX, shapeMaxX, minX, maxX) && axisJoins(shapeMinY, shapeMaxY, minY, maxY)
		    && axisJoins(shapeMinZ, shapeMaxZ, minZ, maxZ);
	}

	/**
	 * {@code IndirectMerger} over the shape box's two coordinates and the query's two on one axis, for
	 * the AND join: whether the merge leaves an interval that is inside both.
	 *
	 * <p>The merger walks both lists ascending, taking the shape's coordinate while it is below the
	 * query's plus {@code 1.0E-7}, drops a coordinate that precedes the other list entirely, and folds
	 * a coordinate into the previous one when it is not more than {@code 1.0E-7} above it, which
	 * moves the previous interval's cell indices onto it. The branches below are that walk written
	 * out for two coordinates a side; the comparisons keep the merger's operand order and its
	 * placement of the epsilon.
	 */
	static boolean axisJoins(
	    final double shapeMin, final double shapeMax, final double queryMin, final double queryMax) {
		double queryMinFuzz = queryMin + SHAPE_EPSILON;
		double queryMaxFuzz = queryMax + SHAPE_EPSILON;
		if (shapeMin < queryMinFuzz) {
			// The shape's first coordinate is taken and dropped. Its second is
			// dropped too when it also precedes the query; otherwise the query's
			// start opens an interval that ends at whichever comes next.
			if (shapeMax < queryMinFuzz) {
				return false;
			}
			return shapeMax < queryMaxFuzz ? !(queryMin >= shapeMax - SHAPE_EPSILON)
			                               : !(queryMin >= queryMax - SHAPE_EPSILON);
		}
		// The query's first coordinate is dropped. The shape's start opens an
		// interval only if it precedes the query's end, and that interval must
		// not be folded into its own end.
		if (!(shapeMin < queryMaxFuzz)) {
			return false;
		}
		return shapeMax < queryMaxFuzz ? !(shapeMin >= shapeMax - SHAPE_EPSILON)
		                               : !(shapeMin >= queryMax - SHAPE_EPSILON);
	}

	private static boolean strictIntersects(final double shapeMinX, final double shapeMinY, final double shapeMinZ,
	    final double shapeMaxX, final double shapeMaxY, final double shapeMaxZ, final double minX, final double minY,
	    final double minZ, final double maxX, final double maxY, final double maxZ) {
		return shapeMaxX > minX && shapeMinX < maxX && shapeMaxY > minY && shapeMinY < maxY && shapeMaxZ > minZ
		    && shapeMinZ < maxZ;
	}

	/**
	 * Answers from the unfiltered retained shapes when they cover the support slab.
	 *
	 * <p>A retained span guarantees that every shape is cell-bounded and independent of player
	 * context. The version and bounds check below preserves that guarantee. A miss leaves
	 * {@code out} untouched so the ordinary world query can own every unsupported case.
	 */
	static boolean tryFindSupportingBlock(final WorldView world, final Scratch scratch, final double minX,
	    final double minY, final double minZ, final double maxX, final double maxY, final double maxZ, final double atX,
	    final double atY, final double atZ, final SupportCell out) {
		RetainedSpan span = scratch.span;
		if (!span.covers(world, minX - SHAPE_EPSILON, minY - SHAPE_EPSILON, minZ - SHAPE_EPSILON, maxX + SHAPE_EPSILON,
		        maxY + SHAPE_EPSILON, maxZ + SHAPE_EPSILON)) {
			return false;
		}

		out.clear();
		double best = Double.MAX_VALUE;
		CollisionBuffer retained = span.boxes;
		final int groups = retained.groupCount();
		for (int group = 0; group < groups; group++) {
			if (!groupIntersects(retained, group, minX, minY, minZ, maxX, maxY, maxZ)) {
				continue;
			}
			// The owning cell, as BlockCollisions names it: span retention only
			// accepts cell-bounded shapes, so every box of the group lies in it.
			int x = retained.groupCellX(group);
			int y = retained.groupCellY(group);
			int z = retained.groupCellZ(group);
			double dx = x + 0.5 - atX;
			double dy = y + 0.5 - atY;
			double dz = z + 0.5 - atZ;
			double distance = dx * dx + dy * dy + dz * dz;
			if (distance < best || distance == best && (!out.present || out.compareTo(x, y, z) < 0)) {
				best = distance;
				out.set(x, y, z);
			}
		}
		return true;
	}

	/**
	 * {@code Entity.checkSupportingBlock}'s search: the cell of the shape meeting the support slab,
	 * in blocks, nearest to {@code (atX, atY, atZ)}, written into {@code out} (cleared when none).
	 *
	 * @throws com.nettarion.stride.simulator.UnimplementedMechanicException when the slab leaves the
	 *         integer-cursor domain or the world refuses a cell
	 */
	public static void findSupportingBlock(final WorldView world, final Scratch scratch, final double minX,
	    final double minY, final double minZ, final double maxX, final double maxY, final double maxZ, final double atX,
	    final double atY, final double atZ, final double entityBottom, final boolean descending,
	    final double fallDistance, final boolean canWalkOnPowderSnow, final SupportCell out) {
		// A covered query was admitted with the span; admission is re-derived on a miss.
		if (tryFindSupportingBlock(world, scratch, minX, minY, minZ, maxX, maxY, maxZ, atX, atY, atZ, out)) {
			return;
		}
		CollisionQuerySpan.requireSupported(minX, minY, minZ, maxX, maxY, maxZ);
		world.findSupportingBlock(minX, minY, minZ, maxX, maxY, maxZ, atX, atY, atZ, entityBottom, descending,
		    fallDistance, canWalkOnPowderSnow, out);
	}
}
