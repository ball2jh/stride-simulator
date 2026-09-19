package com.nettarion.stride.simulator.world;

import java.util.Objects;

/**
 * One resolved fluid state. Height and flow deliberately live here rather
 * than on {@link BlockEntry}: both may depend on neighboring cells even
 * when the underlying block and fluid state ids are identical. Immutable.
 *
 * @param fluidStateId vanilla's fluid-state registry id
 * @param name vanilla's fluid registry name, such as {@code minecraft:water}
 * @param kind which fluid tag the state is in
 * @param height {@code FluidState.getHeight} at this cell, in blocks, in {@code [0, 1]}
 * @param flowX the X component of {@code FluidState.getFlow} at this cell
 * @param flowY the Y component of the flow
 * @param flowZ the Z component of the flow
 * @param source whether the state is a source block
 */
public record FluidEntry(int fluidStateId, String name, FluidKind kind, double height, double flowX, double flowY,
    double flowZ, boolean source) {
	/**
	 * The entry for a cell holding no fluid, and the entry every fluid
	 * palette begins with.
	 *
	 * <p>It is a convenience, not an identity: ask {@link #isEmpty()}
	 * rather than comparing against this instance. A snapshot read back
	 * from a capture, or composed from another, carries its own equal
	 * copy, so {@code entry != EMPTY} is true of an empty cell whenever
	 * the entry did not come from the one factory that returns this
	 * constant. That comparison has already produced a defect once.
	 */
	public static final FluidEntry EMPTY =
	    new FluidEntry(0, "minecraft:empty", FluidKind.EMPTY, 0.0, 0.0, 0.0, 0.0, false);

	/**
	 * Validates the components.
	 *
	 * @throws IllegalArgumentException when the height is outside {@code [0, 1]}, a component is not
	 *     finite, or an empty kind carries any fluid semantics
	 */
	public FluidEntry {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(kind, "kind");
		if (!Double.isFinite(height) || height < 0.0 || height > 1.0) {
			throw new IllegalArgumentException("fluid height must be finite and in [0, 1]");
		}
		if (!Double.isFinite(flowX) || !Double.isFinite(flowY) || !Double.isFinite(flowZ)) {
			throw new IllegalArgumentException("fluid flow must be finite");
		}
		if (kind == FluidKind.EMPTY && (height != 0.0 || flowX != 0.0 || flowY != 0.0 || flowZ != 0.0 || source)) {
			throw new IllegalArgumentException("empty fluid cannot carry fluid semantics");
		}
	}

	/**
	 * Whether this entry describes a cell holding no fluid. The compact
	 * constructor refuses an empty kind carrying any fluid semantics, so
	 * the kind alone decides it however the entry was built.
	 */
	public boolean isEmpty() {
		return this.kind == FluidKind.EMPTY;
	}

	/** Equal entries hash equally, by state id and name; see {@link BlockEntry#hashCode}. */
	@Override
	public int hashCode() {
		return 31 * this.fluidStateId + this.name.hashCode();
	}
}
