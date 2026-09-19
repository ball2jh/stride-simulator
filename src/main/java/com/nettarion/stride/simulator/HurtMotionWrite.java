package com.nettarion.stride.simulator;

import java.util.Objects;
import java.util.Optional;

/**
 * One predicted hurt-marked velocity publication scheduled relative to an
 * issued action list: delivered after the action whose packets the sampling
 * server tick handled.
 *
 * <p>The wrapped event remains bound to its original exact delivery state.
 * Rebasing changes only which action in a remaining suffix reaches that state.
 */
public record HurtMotionWrite(int actionIndex, HurtMotion event) implements ServerWrite {
	public HurtMotionWrite {
		if (actionIndex < 0) {
			throw new IllegalArgumentException("actionIndex is out of range");
		}
		Objects.requireNonNull(event, "event");
	}

	/** Schedule an event against the plan from which it was predicted. */
	public static HurtMotionWrite original(final HurtMotion event) {
		Objects.requireNonNull(event, "event");
		return new HurtMotionWrite(event.writeAfterAction(), event);
	}

	/** Apply the write when this completed action is its delivery boundary. */
	@Override
	public void applyAfterAction(final int completedAction, final PlayerState state) {
		if (completedAction == this.actionIndex) {
			this.event.applyAfterTick(this.event.writeAfterAction(), state);
		}
	}

	@Override
	public Optional<HurtMotionWrite> afterConsuming(final int actions) {
		if (actions < 0) {
			throw new IllegalArgumentException("consumed action count is out of range");
		}
		return this.actionIndex < actions ? Optional.empty()
		                                  : Optional.of(new HurtMotionWrite(this.actionIndex - actions, this.event));
	}

	@Override
	public HurtMotionWrite delayedBy(final int actions) {
		if (actions < 0) {
			throw new IllegalArgumentException("delay is out of range");
		}
		return new HurtMotionWrite(Math.addExact(this.actionIndex, actions), this.event);
	}
}
