package com.nettarion.stride.simulator;

import java.util.Objects;

/**
 * One server hit on the player, delivered at the action that caused it.
 *
 * <p>The health it costs is already applied to the server's copy inside the tick, and the client's movement vector
 * does not change, so applying this write to a client state is the identity. It is on the stream so a consumer sees
 * every hit the server deals at the action that caused it, and so the {@link WriteLedger} can attribute the
 * damage-event packet that follows. A hit that marks the player hurt is followed by a {@link HurtMotionWrite} at the
 * next action.
 *
 * @param actionIndex the completed action after which the write is applied
 * @param event the hit as the server dealt it
 */
public record DamageWrite(int actionIndex, DamageEvent event) implements ServerWrite {
	/** Validates the action index and the event. */
	public DamageWrite {
		if (actionIndex < 0) {
			throw new IllegalArgumentException("actionIndex is out of range");
		}
		Objects.requireNonNull(event, "event");
	}

	/** The identity: a hit changes nothing in the client's movement vector. */
	@Override
	public void applyAfterAction(final int completedAction, final PlayerState state) {
		Objects.requireNonNull(state, "state");
	}

	@Override
	public DamageWrite withActionIndex(final int index) {
		return new DamageWrite(index, this.event);
	}
}
