package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.tick.Scratch;

/** An axis-aligned box, matching vanilla's {@code AABB} semantics. */
public record AABB(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
	/**
	 * Player boxes are built from a width and height centred on x/z and resting
	 * on y, exactly as {@code EntityDimensions.makeBoundingBox} does.
	 */
	public static AABB around(final double x, final double y, final double z, final float width, final float height) {
		double halfWidth = width / 2.0F;
		return new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + height, z + halfWidth);
	}

	/** {@code AABB.intersects}: open on every face, so touching boxes do not intersect. */
	public boolean intersects(final AABB other) {
		return this.minX<other.maxX&& this.maxX> other.minX && this.minY<other.maxY&& this.maxY> other.minY
		    && this.minZ<other.maxZ&& this.maxZ> other.minZ;
	}

	/** {@code AABB.inflate}: each face moved outward by the amount on its axis. */
	public AABB inflate(final double x, final double y, final double z) {
		return new AABB(this.minX - x, this.minY - y, this.minZ - z, this.maxX + x, this.maxY + y, this.maxZ + z);
	}

	/** {@code AABB.expandTowards}: the box extended along each axis by the signed amount. */
	public AABB expandTowards(final double x, final double y, final double z) {
		return new AABB(Math.min(this.minX, this.minX + x), Math.min(this.minY, this.minY + y),
		    Math.min(this.minZ, this.minZ + z), Math.max(this.maxX, this.maxX + x), Math.max(this.maxY, this.maxY + y),
		    Math.max(this.maxZ, this.maxZ + z));
	}

	/** {@code AABB.minmax}: the smallest box containing both. */
	public AABB union(final AABB other) {
		return new AABB(Math.min(this.minX, other.minX), Math.min(this.minY, other.minY),
		    Math.min(this.minZ, other.minZ), Math.max(this.maxX, other.maxX), Math.max(this.maxY, other.maxY),
		    Math.max(this.maxZ, other.maxZ));
	}

	/** {@code AABB.contains}: half-open on the maximum face. */
	public static boolean contains(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double x, final double y, final double z) {
		return x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ;
	}

	/**
	 * {@code AABB.clip(from, to).isPresent()}. Vanilla threads a shrinking scale
	 * through its face tests to pick which face was hit; presence alone is
	 * monotone in that scale, so any accepted face answers the question.
	 */
	public static boolean clip(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double fromX, final double fromY, final double fromZ,
	    final double toX, final double toY, final double toZ) {
		double dx = toX - fromX;
		double dy = toY - fromY;
		double dz = toZ - fromZ;
		if (dx > 1.0E-7 && clipPoint(1.0, dx, dy, dz, minX, minY, maxY, minZ, maxZ, fromX, fromY, fromZ) >= 0.0) {
			return true;
		}
		if (dx < -1.0E-7 && clipPoint(1.0, dx, dy, dz, maxX, minY, maxY, minZ, maxZ, fromX, fromY, fromZ) >= 0.0) {
			return true;
		}
		if (dy > 1.0E-7 && clipPoint(1.0, dy, dz, dx, minY, minZ, maxZ, minX, maxX, fromY, fromZ, fromX) >= 0.0) {
			return true;
		}
		if (dy < -1.0E-7 && clipPoint(1.0, dy, dz, dx, maxY, minZ, maxZ, minX, maxX, fromY, fromZ, fromX) >= 0.0) {
			return true;
		}
		if (dz > 1.0E-7 && clipPoint(1.0, dz, dx, dy, minZ, minX, maxX, minY, maxY, fromZ, fromX, fromY) >= 0.0) {
			return true;
		}
		return dz < -1.0E-7 && clipPoint(1.0, dz, dx, dy, maxZ, minX, maxX, minY, maxY, fromZ, fromX, fromY) >= 0.0;
	}

	/** {@code AABB.clip} against one unit cell, writing the hit point to scratch. */
	public static boolean clipCell(final int cellX, final int cellY, final int cellZ, final double fromX,
	    final double fromY, final double fromZ, final double toX, final double toY, final double toZ,
	    final Scratch scratch) {
		double dx = toX - fromX;
		double dy = toY - fromY;
		double dz = toZ - fromZ;
		double scale = 1.0;
		boolean hit = false;
		if (dx > 1.0E-7) {
			double s = clipPoint(scale, dx, dy, dz, cellX, cellY, cellY + 1, cellZ, cellZ + 1, fromX, fromY, fromZ);
			if (s >= 0.0) {
				scale = s;
				hit = true;
			}
		} else if (dx < -1.0E-7) {
			double s = clipPoint(scale, dx, dy, dz, cellX + 1, cellY, cellY + 1, cellZ, cellZ + 1, fromX, fromY, fromZ);
			if (s >= 0.0) {
				scale = s;
				hit = true;
			}
		}
		if (dy > 1.0E-7) {
			double s = clipPoint(scale, dy, dz, dx, cellY, cellZ, cellZ + 1, cellX, cellX + 1, fromY, fromZ, fromX);
			if (s >= 0.0) {
				scale = s;
				hit = true;
			}
		} else if (dy < -1.0E-7) {
			double s = clipPoint(scale, dy, dz, dx, cellY + 1, cellZ, cellZ + 1, cellX, cellX + 1, fromY, fromZ, fromX);
			if (s >= 0.0) {
				scale = s;
				hit = true;
			}
		}
		if (dz > 1.0E-7) {
			double s = clipPoint(scale, dz, dx, dy, cellZ, cellX, cellX + 1, cellY, cellY + 1, fromZ, fromX, fromY);
			if (s >= 0.0) {
				scale = s;
				hit = true;
			}
		} else if (dz < -1.0E-7) {
			double s = clipPoint(scale, dz, dx, dy, cellZ + 1, cellX, cellX + 1, cellY, cellY + 1, fromZ, fromX, fromY);
			if (s >= 0.0) {
				scale = s;
				hit = true;
			}
		}
		if (!hit) {
			return false;
		}
		scratch.clipHitX = fromX + scale * dx;
		scratch.clipHitY = fromY + scale * dy;
		scratch.clipHitZ = fromZ + scale * dz;
		return true;
	}

	/** {@code AABB.clipPoint}, returning the accepted scale or a negative miss. */
	private static double clipPoint(final double scale, final double da, final double db, final double dc,
	    final double point, final double minB, final double maxB, final double minC, final double maxC,
	    final double fromA, final double fromB, final double fromC) {
		double s = (point - fromA) / da;
		double pb = fromB + s * db;
		double pc = fromC + s * dc;
		if (0.0 < s && s < scale && minB - 1.0E-7 < pb && pb < maxB + 1.0E-7 && minC - 1.0E-7 < pc
		    && pc < maxC + 1.0E-7) {
			return s;
		}
		return -1.0;
	}
}
