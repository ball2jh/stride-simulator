package com.nettarion.stride.simulator.world;

/**
 * Coarse behavior questions over a closed cell span, one bit per behavior.
 *
 * <p>{@link WorldView} extends this interface. The tick asks these questions
 * of the handful of cells a movement query scans, so a view that knows a span
 * holds no fluid, no climbable and no ice lets the tick skip the per-cell
 * lookups rather than perform them and discard the result. Every default here
 * is the conservative answer: a bit set that the span does not contain costs a
 * lookup, a bit clear that the span does contain silently drops a behavior,
 * so an implementation may leave a bit set and must never clear one
 * speculatively. Bits outside {@code wanted} are never returned.
 */
public interface SpanQueries {
	/** Any cell whose fluid entry is not empty. */
	int PROPERTY_FLUID = 1;

	/** Any cell whose {@link WorldView.BubbleColumnMode} is not {@code NONE}. */
	int PROPERTY_BUBBLE_COLUMN = 1 << 1;

	/** Any cell whose {@link WorldView.Climbability} is not {@code NONE}. */
	int PROPERTY_CLIMBABLE = 1 << 2;

	/** Any cell whose friction differs from the ordinary 0.6. */
	int PROPERTY_FRICTION = 1 << 3;

	/** Any cell whose speed factor differs from 1, or that suppresses the supporting block's. */
	int PROPERTY_SPEED_FACTOR = 1 << 4;

	/** Any cell whose jump factor differs from 1. */
	int PROPERTY_JUMP_FACTOR = 1 << 5;

	/** Any cell whose collision behavior is {@link WorldView.CollisionBehavior#POWDER_SNOW_NO_BOOTS}. */
	int PROPERTY_POWDER_SNOW = 1 << 6;

	/** Any cell whose {@link WorldView.InsideEffect} is not {@code NONE}. */
	int PROPERTY_INSIDE_EFFECT = 1 << 7;

	/** Any cell whose {@code Block.getBounceRestitution()} is non-zero, or that suppresses bounce. */
	int PROPERTY_BOUNCE = 1 << 8;

	/** Any cell with a {@code stepOn} body the simulator runs. */
	int PROPERTY_STEP_ON = 1 << 9;

	/** Any cell with a server-only contact body, including one that refuses. */
	int PROPERTY_CONTACT = 1 << 11;

	/** Every bit above; bit 10 is reserved for a view's internal use. */
	int PROPERTIES_ALL = (1 << 10) - 1 | PROPERTY_CONTACT;

	/**
	 * Which of {@code wanted}'s behaviors any cell of the closed cell span may
	 * have. The default answers every wanted bit, which is always correct.
	 *
	 * <p>Callers ask for the bits they will act on rather than for all of them,
	 * because that is what lets an implementation answer without looking: a view
	 * with no fluid anywhere rejects a fluid question with one field test.
	 */
	default int propertiesIn(
	    final int wanted, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		return wanted;
	}

	/**
	 * Which of {@code wanted}'s behaviors any cell of the whole view may have:
	 * {@link #propertiesIn} over every cell, asked without a span.
	 *
	 * <p>Every span answer must be a subset of this one, so a caller asks it
	 * first and computes the span only when a bit survives. The default answers
	 * every wanted bit, which keeps any {@link #propertiesIn} override exact.
	 */
	default int propertiesAnywhere(final int wanted) {
		return wanted;
	}
}
