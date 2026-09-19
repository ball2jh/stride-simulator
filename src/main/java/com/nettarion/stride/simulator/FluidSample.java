package com.nettarion.stride.simulator;

/** Caller-owned result of one resolved world-fluid cell lookup. */
public final class FluidSample {
	/** The supported fluid categories returned by a world query. */
	public enum Kind {
		/** No fluid is present in the queried cell. */
		EMPTY,
		/** Water with the captured height and flow. */
		WATER,
		/** Lava with the captured height and flow. */
		LAVA
	}

	/** Fluid category; an empty sample has zero height and zero flow. */
	public Kind kind = Kind.EMPTY;
	/** Fluid surface height within this cell, in blocks. */
	public double height;
	/** Resolved X component of the fluid flow vector. */
	public double flowX;
	/** Resolved vertical component of the fluid flow vector. */
	public double flowY;
	/** Resolved Z component of the fluid flow vector. */
	public double flowZ;
	/** Whether the cell contains a source fluid state. */
	public boolean source;

	/** Supplies a resolved sample to the kernel's caller-owned output object. */
	public void set(final Kind kind, final double height, final double flowX, final double flowY, final double flowZ,
	    final boolean source) {
		this.kind = kind;
		this.height = height;
		this.flowX = flowX;
		this.flowY = flowY;
		this.flowZ = flowZ;
		this.source = source;
	}

	/** Supplies the canonical empty-fluid sample. */
	public void clear() {
		set(Kind.EMPTY, 0.0, 0.0, 0.0, 0.0, false);
	}

	/** Returns the resolved fluid category. */
	public Kind kind() {
		return this.kind;
	}
	/** Returns the fluid surface height within the cell, in blocks. */
	public double height() {
		return this.height;
	}
	/** Returns the X component of the resolved flow vector. */
	public double flowX() {
		return this.flowX;
	}
	/** Returns the vertical component of the resolved flow vector. */
	public double flowY() {
		return this.flowY;
	}
	/** Returns the Z component of the resolved flow vector. */
	public double flowZ() {
		return this.flowZ;
	}
	/** Returns whether this sample describes a source fluid state. */
	public boolean source() {
		return this.source;
	}
}
