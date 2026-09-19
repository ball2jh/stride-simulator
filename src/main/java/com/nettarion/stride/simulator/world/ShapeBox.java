package com.nettarion.stride.simulator.world;

/**
 * One box of a block's collision shape, in block-local coordinates: a full
 * cube runs from 0 to 1 on every axis, and a box may leave the cell.
 *
 * @param minX the low X face, in blocks from the cell's origin
 * @param minY the low Y face
 * @param minZ the low Z face
 * @param maxX the high X face
 * @param maxY the high Y face
 * @param maxZ the high Z face
 */
public record ShapeBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
	/** The shape of an ordinary solid block. */
	public static final ShapeBox FULL_CUBE = new ShapeBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);

	/**
	 * Validates the faces.
	 *
	 * @throws IllegalArgumentException when a face is not finite or a low face exceeds its high face
	 */
	public ShapeBox {
		if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ) || !Double.isFinite(maxX)
		    || !Double.isFinite(maxY) || !Double.isFinite(maxZ) || minX > maxX || minY > maxY || minZ > maxZ) {
			throw new IllegalArgumentException("invalid collision shape bounds");
		}
	}
}
