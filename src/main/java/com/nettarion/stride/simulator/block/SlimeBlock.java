package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.server.ServerDamage;
import com.nettarion.stride.simulator.tick.PlayerTick;
import com.nettarion.stride.simulator.tick.Scratch;

/**
 * A slime block: {@code SlimeBlock.stepOn}, the damping of a player who walks on it, and
 * {@code SlimeBlock.fallOn}, a landing at multiplier zero.
 *
 * <p>The landing is never a hit but still settles the wind-charge impulse context, and is skipped entirely while
 * sneaking. Its friction and its bounce are coefficients the palette carries beside the behavior; the bounce is
 * {@code updateEntityMovementAfterFallOn}, which {@code Move}'s restitution reads as the block's restitution.
 */
final class SlimeBlock extends BlockBehavior {
	static final SlimeBlock INSTANCE = new SlimeBlock();

	private SlimeBlock() {}

	@Override
	int families() {
		return STEP_ON | LANDING;
	}

	/**
	 * {@code SlimeBlock.stepOn}: scales the horizontal velocity down while the vertical is small, unless the
	 * player is sneaking.
	 *
	 * <p>Only {@code SlimeBlock} has a {@code stepOn} that moves a client player. Magma, sculk sensor and sculk
	 * shrieker override the method too but are damage-only or side-gated, so the palette calls them {@code NONE}:
	 * the fact names what has to run, not what overrides the method. {@code isSteppingCarefully()} is
	 * {@code PlayerTick.isShiftKeyDown()}, which by this point in the tick is the current input on the client and
	 * the synced flag on the server's copy, the same value the pose probe reads; a sneaking player is not damped.
	 */
	@Override
	void stepOn(final PlayerState state, final int x, final int y, final int z, final Scratch scratch) {
		double absDeltaY = Math.abs(state.deltaMovementY);
		if (absDeltaY < 0.1 && !PlayerTick.isShiftKeyDown(state, scratch)) {
			double scale = 0.4 + absDeltaY * 0.2;
			state.deltaMovementX *= scale;
			state.deltaMovementZ *= scale;
		}
	}

	/**
	 * {@code SlimeBlock.fallOn}: unless the player suppresses the bounce, the fall at multiplier zero, which is
	 * never a hit but runs {@code causeFallDamage}'s impulse-context bookkeeping; a sneaking player lands with no
	 * fall handling at all.
	 */
	@Override
	public void fallOn(final double fallDistance, final int x, final int y, final int z, final ServerDamage damage) {
		if (!damage.suppressingBounce()) {
			damage.causeFallDamage(fallDistance, 0.0F, HurtCause.FALL);
		}
	}

	@Override
	public String toString() {
		return "slime";
	}
}
