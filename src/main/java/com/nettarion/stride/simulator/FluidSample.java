package com.nettarion.stride.simulator;

/**
 * Caller-owned result of one fluid cell lookup, which a world query fills in place instead of
 * allocating.
 *
 * <p>The fields are public because the tick reads them on its hot path; the accessors exist for
 * callers outside this package's mechanism. One thread owns an instance.
 */
public final class FluidSample {
	/** The fluid category of the cell; an empty sample has zero height and zero flow. */
	public Kind kind = Kind.EMPTY;

	/** The fluid surface height within the cell, in blocks above the cell floor. */
	public double height;

	/** The X component of the resolved flow vector, in blocks per tick. */
	public double flowX;

	/** The vertical component of the resolved flow vector, in blocks per tick. */
	public double flowY;

	/** The Z component of the resolved flow vector, in blocks per tick. */
	public double flowZ;

	/** Whether the cell holds a source fluid state rather than flowing fluid. */
	public boolean source;

	/** An empty sample; a world query fills it. */
	public FluidSample() {}

	/** Sets every field of this sample. */
	public void set(final Kind kind, final double height, final double flowX, final double flowY, final double flowZ,
	    final boolean source) {
		this.kind = kind;
		this.height = height;
		this.flowX = flowX;
		this.flowY = flowY;
		this.flowZ = flowZ;
		this.source = source;
	}

	/** Resets this sample to the canonical empty fluid. */
	public void clear() {
		set(Kind.EMPTY, 0.0, 0.0, 0.0, 0.0, false);
	}

	/** The fluid category; see {@link #kind}. */
	public Kind kind() {
		return this.kind;
	}

	/** The fluid surface height within the cell, in blocks; see {@link #height}. */
	public double height() {
		return this.height;
	}

	/** The X component of the flow vector; see {@link #flowX}. */
	public double flowX() {
		return this.flowX;
	}

	/** The vertical component of the flow vector; see {@link #flowY}. */
	public double flowY() {
		return this.flowY;
	}

	/** The Z component of the flow vector; see {@link #flowZ}. */
	public double flowZ() {
		return this.flowZ;
	}

	/** Whether the cell holds a source fluid state; see {@link #source}. */
	public boolean source() {
		return this.source;
	}

	/** The fluid categories a world query can answer. */
	public enum Kind {
		/** No fluid is present in the queried cell. */
		EMPTY,
		/** Water with the captured height and flow. */
		WATER,
		/** Lava with the captured height and flow. */
		LAVA
	}
}
