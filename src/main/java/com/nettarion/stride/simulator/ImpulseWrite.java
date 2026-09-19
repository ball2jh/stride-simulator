package com.nettarion.stride.simulator;

import java.util.Objects;
import java.util.Optional;

/**
 * One external actor's velocity operation on the player, delivered after the
 * named input: the event shape every impulse shares, whether observed on the
 * wire or, once an actor can be scheduled, predicted.
 *
 * <p>The fold is noncommutative and equal payloads are distinct events, so an
 * exact event needs the actor, its receive sequence, the phase it lands in,
 * and the operation kind; the vector alone is not an identity. Vanilla
 * distinguishes at least three operations, and only two reduce to a velocity
 * write; the third is a collision-resolved displacement and refuses.
 */
public record ImpulseWrite(int actionIndex, ExternalActor actor, long sequence, Phase phase, Operation operation,
    double x, double y, double z) implements ServerWrite {
	/** What the operation does to the player's velocity {@code v}. */
	public enum Operation {
		/** {@code Entity.addDeltaMovement}: {@code v := v + j}, as the explode packet's handler does. */
		ADD,
		/** {@code Entity.setDeltaMovement}: {@code v := r}, as an attached rocket's tick and an owner motion packet do. */
		REPLACE,
		/**
		 * A non-{@code SELF} {@code Entity.move}: collision-resolve an external
		 * displacement through the world. Not reducible to a velocity write.
		 */
		MOVE
	}

	/** Where in the client's frame the write lands. */
	public enum Phase {
		/** A packet handler on the main thread between two client ticks. */
		PACKET,
		/** An entity's own tick in the level's entity pass, after the player's. */
		ACTOR_TICK
	}

	public ImpulseWrite {
		if (actionIndex < 0) {
			throw new IllegalArgumentException("actionIndex is out of range");
		}
		Objects.requireNonNull(actor, "actor");
		Objects.requireNonNull(phase, "phase");
		Objects.requireNonNull(operation, "operation");
		if (sequence < 0L) {
			throw new IllegalArgumentException("sequence is out of range");
		}
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
			throw new IllegalArgumentException("impulse must be finite");
		}
	}

	@Override
	public void applyAfterAction(final int completedAction, final PlayerState state) {
		Objects.requireNonNull(state, "state");
		if (completedAction != this.actionIndex) {
			return;
		}
		switch (this.operation) {
			case ADD -> {
				// Entity.addDeltaMovement delegates the complete sum to
				// setDeltaMovement, which ignores a non-finite vector as a whole.
				double nextX = state.deltaMovementX + this.x;
				double nextY = state.deltaMovementY + this.y;
				double nextZ = state.deltaMovementZ + this.z;
				if (Double.isFinite(nextX) && Double.isFinite(nextY) && Double.isFinite(nextZ)) {
					state.deltaMovementX = nextX;
					state.deltaMovementY = nextY;
					state.deltaMovementZ = nextZ;
				}
			}
			case REPLACE -> {
				state.deltaMovementX = this.x;
				state.deltaMovementY = this.y;
				state.deltaMovementZ = this.z;
			}
			case MOVE ->
				throw UnimplementedMechanicException.deferred(RefusalCause.UNMODELED_IMPULSE,
				    ()
				        -> "a collision-resolved external displacement by " + this.actor
				        + " is not reducible to a velocity write");
		}
	}

	@Override
	public Optional<ImpulseWrite> afterConsuming(final int actions) {
		if (actions < 0) {
			throw new IllegalArgumentException("consumed action count is out of range");
		}
		return this.actionIndex < actions ? Optional.empty() : Optional.of(withActionIndex(this.actionIndex - actions));
	}

	@Override
	public ImpulseWrite delayedBy(final int actions) {
		if (actions < 0) {
			throw new IllegalArgumentException("delay is out of range");
		}
		return withActionIndex(Math.addExact(this.actionIndex, actions));
	}

	private ImpulseWrite withActionIndex(final int index) {
		return new ImpulseWrite(index, this.actor, this.sequence, this.phase, this.operation, this.x, this.y, this.z);
	}
}
