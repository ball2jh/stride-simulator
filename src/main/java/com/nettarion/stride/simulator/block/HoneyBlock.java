package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.server.Survival;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * A honey block: {@code HoneyBlock.entityInside}, the wall slide, and {@code HoneyBlock.fallOn}, a landing at one
 * fifth of the fall.
 *
 * <p>Its speed and jump factors and its bounce suppression are coefficients the palette carries beside the
 * behavior.
 */
final class HoneyBlock extends BlockBehavior {
	static final HoneyBlock INSTANCE = new HoneyBlock();

	private HoneyBlock() {}

	@Override
	int families() {
		return INSIDE | LANDING;
	}

	@Override
	boolean hasEntityInside() {
		return true;
	}

	/**
	 * {@code HoneyBlock.entityInside}: the wall slide a falling player gets against its side.
	 *
	 * <p>Unlike the two stuck blocks this reads the visited cell's coordinates rather than only the player's,
	 * because {@code isSlidingDown} measures the player against that cell's center and its top face. It also reads
	 * {@code onGround}, which is this tick's post-move value since the traversal runs after {@code move}, so a
	 * player who has just landed on honey does not slide, and one still falling past it does. Not gated on flying:
	 * {@code doSlideMovement} calls {@code setDeltaMovement} directly, so a flying player descending past a honey
	 * wall slides.
	 *
	 * @implNote {@code getOldDeltaY} inverts the gravity step {@code travel} has already applied and
	 *     {@code getNewDeltaY} re-applies it, both with {@code 0.98F} widened to double rather than {@code 0.98}.
	 *     The round trip is not the identity, which is why the constants are written as vanilla writes them. The
	 *     advancement trigger is {@code ServerPlayer}-gated, and the particles and sounds consume
	 *     {@code level.getRandom()} without touching movement, so neither is modeled.
	 */
	@Override
	void entityInside(final PlayerState state, final int x, final int y, final int z, final boolean isPrecise,
	    final WorldView world, final Scratch scratch) {
		if (isSlidingDown(state, x, y, z)) {
			doSlideMovement(state);
		}
	}

	private static boolean isSlidingDown(final PlayerState state, final int x, final int y, final int z) {
		if (state.onGround || state.y > y + 0.9375 - 1.0E-7 || getOldDeltaY(state.deltaMovementY) >= -0.08) {
			return false;
		}
		double dx = Math.abs(x + 0.5 - state.x);
		double dz = Math.abs(z + 0.5 - state.z);
		double overlap = 0.4375 + state.pose.width / 2.0F;
		return dx + 1.0E-7 > overlap || dz + 1.0E-7 > overlap;
	}

	private static void doSlideMovement(final PlayerState state) {
		double oldDeltaY = getOldDeltaY(state.deltaMovementY);
		if (oldDeltaY < -0.13) {
			double reduction = -0.05 / oldDeltaY;
			state.deltaMovementX *= reduction;
			state.deltaMovementZ *= reduction;
		}
		state.deltaMovementY = getNewDeltaY(-0.05);
		state.fallDistance = 0.0;
	}

	private static double getOldDeltaY(final double movementY) {
		return movementY / (double) 0.98F + 0.08;
	}

	private static double getNewDeltaY(final double movementY) {
		return (movementY - 0.08) * (double) 0.98F;
	}

	/** {@code HoneyBlock.fallOn}: the fall at multiplier one fifth. */
	@Override
	public void fallOn(final double fallDistance, final int x, final int y, final int z, final Survival survival) {
		survival.causeFallDamage(fallDistance, 0.2F, HurtCause.FALL);
	}

	@Override
	public String toString() {
		return "honey";
	}
}
