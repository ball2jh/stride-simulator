package com.nettarion.stride.simulator.world;

import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.geometry.Mth;

/**
 * {@code Level.clip} for {@code ClipContext.Block.FALLDAMAGE_RESETTING} with
 * {@code ClipContext.Fluid.WATER}: the cell traversal of {@code BlockGetter.traverseBlocks}
 * and the box clip of {@code VoxelShape.clip}, over the cells a {@link SnapshotView} holds.
 *
 * <p>Stateless. The per-cell answer is the view's, through
 * {@link SnapshotView#resetCellHit}; this class owns only the traversal order and
 * the clip arithmetic, both kept exactly as vanilla evaluates them.
 */
final class FallResetClip {
	/** The cell holds nothing the clip meets. */
	static final int MISS = 0;

	/** The clip meets a tagged block or water whatever the client's cached water height is. */
	static final int HIT = 1;

	/** The water shape's cached height decides the hit, and the capture cannot know it. */
	static final int SESSION_DEPENDENT = 2;

	/** {@code VoxelShape.clip} first probes this fraction of the ray for an immediate inside hit. */
	private static final double INSIDE_PROBE = 0.001;

	private FallResetClip() {}

	/**
	 * Whether the segment meets a fall-distance-resetting block or water in {@code view}.
	 *
	 * @throws UnimplementedMechanicException when no certain hit exists and some water cell's
	 *     answer depends on the client's session-cached water height
	 */
	static boolean resetsAlong(final SnapshotView view, final double fromX, final double fromY, final double fromZ,
	    final double toX, final double toY, final double toZ) {
		if (fromX == toX && fromY == toY && fromZ == toZ) {
			return false;
		}
		double adjustedToX = toX + -WorldView.VANILLA_EPSILON * (fromX - toX);
		double adjustedToY = toY + -WorldView.VANILLA_EPSILON * (fromY - toY);
		double adjustedToZ = toZ + -WorldView.VANILLA_EPSILON * (fromZ - toZ);
		double adjustedFromX = fromX + -WorldView.VANILLA_EPSILON * (toX - fromX);
		double adjustedFromY = fromY + -WorldView.VANILLA_EPSILON * (toY - fromY);
		double adjustedFromZ = fromZ + -WorldView.VANILLA_EPSILON * (toZ - fromZ);
		int cellX = Mth.floor(adjustedFromX);
		int cellY = Mth.floor(adjustedFromY);
		int cellZ = Mth.floor(adjustedFromZ);
		// A water cell whose hit depends on the client's session-cached fluid
		// shape is carried along the traversal: a certain hit further on still
		// answers yes, and only a segment with no certain hit refuses.
		boolean sessionDependent = false;
		int hit = view.resetCellHit(cellX, cellY, cellZ, fromX, fromY, fromZ, toX, toY, toZ);
		if (hit == HIT) {
			return true;
		}
		sessionDependent |= hit == SESSION_DEPENDENT;
		double dx = adjustedToX - adjustedFromX;
		double dy = adjustedToY - adjustedFromY;
		double dz = adjustedToZ - adjustedFromZ;
		int signX = Integer.compare((int) Math.signum(dx), 0);
		int signY = Integer.compare((int) Math.signum(dy), 0);
		int signZ = Integer.compare((int) Math.signum(dz), 0);
		double deltaMovementX = signX == 0 ? Double.MAX_VALUE : signX / dx;
		double deltaMovementY = signY == 0 ? Double.MAX_VALUE : signY / dy;
		double deltaMovementZ = signZ == 0 ? Double.MAX_VALUE : signZ / dz;
		double nextX = deltaMovementX * (signX > 0 ? 1.0 - fraction(adjustedFromX) : fraction(adjustedFromX));
		double nextY = deltaMovementY * (signY > 0 ? 1.0 - fraction(adjustedFromY) : fraction(adjustedFromY));
		double nextZ = deltaMovementZ * (signZ > 0 ? 1.0 - fraction(adjustedFromZ) : fraction(adjustedFromZ));
		while (nextX <= 1.0 || nextY <= 1.0 || nextZ <= 1.0) {
			if (nextX < nextY) {
				if (nextX < nextZ) {
					cellX += signX;
					nextX += deltaMovementX;
				} else {
					cellZ += signZ;
					nextZ += deltaMovementZ;
				}
			} else if (nextY < nextZ) {
				cellY += signY;
				nextY += deltaMovementY;
			} else {
				cellZ += signZ;
				nextZ += deltaMovementZ;
			}
			hit = view.resetCellHit(cellX, cellY, cellZ, fromX, fromY, fromZ, toX, toY, toZ);
			if (hit == HIT) {
				return true;
			}
			sessionDependent |= hit == SESSION_DEPENDENT;
		}
		if (sessionDependent) {
			throw UnimplementedMechanicException.deferred(RefusalCause.UNDECLARED_WORLD_FACT,
			    ()
			        -> "the fall-distance-resetting clip from " + fromX + "," + fromY + "," + fromZ + " to " + toX + ","
			        + toY + "," + toZ + " meets water only above the height the client's session-cached"
			        + " fluid shape may hold, which is not a world fact");
		}
		return false;
	}

	/** {@code VoxelShape.clip} of one box against the segment: an inside probe, then the entry faces. */
	static boolean clipBox(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double fromX, final double fromY, final double fromZ,
	    final double toX, final double toY, final double toZ) {
		double dx = toX - fromX;
		double dy = toY - fromY;
		double dz = toZ - fromZ;
		// VoxelShape.clip first probes 0.1% along the ray. If that point is
		// inside, it returns an immediate inside hit rather than waiting for a
		// boundary crossing. This matters when a fast entity starts in water or
		// in a tagged reset block.
		double probeX = fromX + dx * INSIDE_PROBE;
		double probeY = fromY + dy * INSIDE_PROBE;
		double probeZ = fromZ + dz * INSIDE_PROBE;
		if (probeX >= minX && probeX < maxX && probeY >= minY && probeY < maxY && probeZ >= minZ && probeZ < maxZ) {
			return true;
		}
		return dx > WorldView.VANILLA_EPSILON
		    && clipPoint(dx, dy, dz, minX, minY, maxY, minZ, maxZ, fromX, fromY, fromZ)
		    || dx < -WorldView.VANILLA_EPSILON
		    && clipPoint(dx, dy, dz, maxX, minY, maxY, minZ, maxZ, fromX, fromY, fromZ)
		    || dy > WorldView.VANILLA_EPSILON
		    && clipPoint(dy, dz, dx, minY, minZ, maxZ, minX, maxX, fromY, fromZ, fromX)
		    || dy < -WorldView.VANILLA_EPSILON
		    && clipPoint(dy, dz, dx, maxY, minZ, maxZ, minX, maxX, fromY, fromZ, fromX)
		    || dz > WorldView.VANILLA_EPSILON
		    && clipPoint(dz, dx, dy, minZ, minX, maxX, minY, maxY, fromZ, fromX, fromY)
		    || dz < -WorldView.VANILLA_EPSILON
		    && clipPoint(dz, dx, dy, maxZ, minX, maxX, minY, maxY, fromZ, fromX, fromY);
	}

	/** {@code AABB.clipPoint}: whether the ray crosses one face plane inside the face's bounds. */
	private static boolean clipPoint(final double da, final double db, final double dc, final double point,
	    final double minB, final double maxB, final double minC, final double maxC, final double fromA,
	    final double fromB, final double fromC) {
		double scale = (point - fromA) / da;
		double pointB = fromB + scale * db;
		double pointC = fromC + scale * dc;
		return 0.0 < scale && scale < 1.0 && minB - WorldView.VANILLA_EPSILON < pointB
		    && pointB < maxB + WorldView.VANILLA_EPSILON && minC - WorldView.VANILLA_EPSILON < pointC
		    && pointC < maxC + WorldView.VANILLA_EPSILON;
	}

	/** {@code Mth.frac}: the fractional part of a coordinate. */
	private static double fraction(final double value) {
		return value - Math.floor(value);
	}
}
