package com.nettarion.stride.simulator.world;

import com.nettarion.stride.simulator.block.BlockBehavior;

/**
 * Test-world shorthand: a {@link WorldView} that declares its movement facts complete and answers
 * vanilla's defaults for everything a fixture does not override.
 *
 * <p>The defaults are the bare-world answers: default friction, unit speed and jump factors, every
 * column loaded, a floor at -64, no suffocating column, a constant collision version, and no block
 * bodies. A fixture overrides only the facts it is about.
 */
public interface CompleteWorldView extends WorldView {
	@Override
	default boolean movementFactsComplete() {
		return true;
	}

	/** A test world holds no block body unless it says which. */
	@Override
	default BlockBehavior behaviorAt(final int x, final int y, final int z) {
		return BlockBehavior.INERT;
	}

	/** {@code Block.getFriction()} default. */
	@Override
	default float friction(final int x, final int y, final int z) {
		return 0.6F;
	}

	@Override
	default float speedFactor(final int x, final int y, final int z) {
		return 1.0F;
	}

	@Override
	default float jumpFactor(final int x, final int y, final int z) {
		return 1.0F;
	}

	@Override
	default boolean hasChunkAt(final int x, final int z) {
		return true;
	}

	@Override
	default int minY() {
		return -64;
	}

	/** Defined, not derived: no column of a test world suffocates unless the fixture says so. */
	@Override
	default boolean suffocatesAt(
	    final int cellX, final int cellZ, final double boundingBoxMinY, final double boundingBoxMaxY) {
		return false;
	}

	/** A test world's geometry does not change unless the fixture versions it. */
	@Override
	default long collisionVersion() {
		return 0L;
	}
}
