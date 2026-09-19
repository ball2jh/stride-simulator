package com.nettarion.stride.simulator.world;

/** One box of a block's collision shape, in block-local coordinates. */
public record ShapeBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
	/** The shape of an ordinary solid block. */
	public static final ShapeBox FULL_CUBE = new ShapeBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);

	public ShapeBox {
		if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ) || !Double.isFinite(maxX)
		    || !Double.isFinite(maxY) || !Double.isFinite(maxZ) || minX > maxX || minY > maxY || minZ > maxZ) {
			throw new IllegalArgumentException("invalid collision shape bounds");
		}
	}
}
