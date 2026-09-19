package com.nettarion.stride.simulator;

import java.util.Objects;

/**
 * One external actor's velocity operation on the player, delivered after the named action.
 *
 * <p>Modeled: applying an {@link Operation#ADD} or {@link Operation#REPLACE} a caller observed on the wire, and
 * attributing it in the {@link WriteLedger}. Not modeled: predicting an impulse. The simulator schedules no external
 * actor; a rocket refuses at admission and an explosion is only ever supplied by the caller. {@link Operation#MOVE}
 * refuses when applied. Equal payloads are distinct events, so identity is the actor, sequence, phase and operation.
 *
 * @param actionIndex the completed action after which the write is applied
 * @param actor who wrote the velocity
 * @param sequence the caller's receive order among impulses of the same actor, not negative
 * @param phase where in the client's frame the write lands
 * @param operation what the write does to the velocity
 * @param x the x component, in blocks per tick
 * @param y the y component, in blocks per tick
 * @param z the z component, in blocks per tick
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
		 * A non-{@code SELF} {@code Entity.move}: collision-resolve an external displacement through the world. Not
		 * reducible to a velocity write.
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

	/** Validates the action index, the sequence, and that the vector is finite. */
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

	/**
	 * Apply the operation to the state's velocity when {@code completedAction} is this write's action index.
	 *
	 * @throws UnimplementedMechanicException for {@link Operation#MOVE}
	 */
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
	public ImpulseWrite withActionIndex(final int index) {
		return new ImpulseWrite(index, this.actor, this.sequence, this.phase, this.operation, this.x, this.y, this.z);
	}
}
