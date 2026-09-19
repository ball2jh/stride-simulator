package com.nettarion.stride.simulator;

import java.util.Objects;
import java.util.Optional;

/**
 * One server hit on the player, emitted at the input that caused it.
 *
 * <p>The health it costs is already applied to the server's copy inside the
 * tick; the client's movement vector does not change, so applying this
 * write to a client state is the identity. It is on the stream so a plan
 * carries every hit the server will deal, the ledger attributes the
 * damage-event packet that follows, and route policy can read damage
 * without computing it. A hit that marks the player hurt is followed by a
 * {@link HurtMotionWrite} at the next input.
 */
public record DamageWrite(int actionIndex, DamageEvent event) implements ServerWrite {
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
	public Optional<DamageWrite> afterConsuming(final int actions) {
		if (actions < 0) {
			throw new IllegalArgumentException("consumed action count is out of range");
		}
		return this.actionIndex < actions ? Optional.empty()
		                                  : Optional.of(new DamageWrite(this.actionIndex - actions, this.event));
	}

	@Override
	public DamageWrite delayedBy(final int actions) {
		if (actions < 0) {
			throw new IllegalArgumentException("delay is out of range");
		}
		return new DamageWrite(Math.addExact(this.actionIndex, actions), this.event);
	}
}
