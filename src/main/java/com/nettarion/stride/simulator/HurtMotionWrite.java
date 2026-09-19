package com.nettarion.stride.simulator;

import java.util.Objects;

/**
 * The hurt-marked velocity write on the stream: a {@link HurtMotion} delivered after the action whose packets the
 * sampling server tick handled.
 *
 * <p>The wrapped event stays bound to the client state it was decoded against. Rebasing with
 * {@link #afterConsuming} or {@link #delayedBy} changes only which action in the remaining list reaches that state.
 *
 * @param actionIndex the completed action after which the write is applied
 * @param event the bound velocity write
 */
public record HurtMotionWrite(int actionIndex, HurtMotion event) implements ServerWrite {
	/** Validates the action index and the event. */
	public HurtMotionWrite {
		if (actionIndex < 0) {
			throw new IllegalArgumentException("actionIndex is out of range");
		}
		Objects.requireNonNull(event, "event");
	}

	/** The write at the action index the event itself names, {@link HurtMotion#writeAfterAction()}. */
	public static HurtMotionWrite original(final HurtMotion event) {
		Objects.requireNonNull(event, "event");
		return new HurtMotionWrite(event.writeAfterAction(), event);
	}

	@Override
	public void applyAfterAction(final int completedAction, final PlayerState state) {
		if (completedAction == this.actionIndex) {
			this.event.applyAfterAction(this.event.writeAfterAction(), state);
		}
	}

	@Override
	public HurtMotionWrite withActionIndex(final int index) {
		return new HurtMotionWrite(index, this.event);
	}
}
