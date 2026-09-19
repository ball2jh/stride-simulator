package com.nettarion.stride.simulator;

import java.util.Objects;
import java.util.Optional;

/** A sampled entity metadata and movement-speed attribute publication at a client delivery boundary. */
public record EntityDataWrite(int actionIndex, long beforeDigest, int dirty, int sharedFlags, PlayerState.Pose pose,
    int ticksFrozen, int frostSpeedTicks, boolean sprintingAttribute) implements ServerWrite {
	public static final int FLAGS = 1, POSE = 2, FROZEN = 4, MOVEMENT_SPEED = 8;

	public EntityDataWrite {
		if (actionIndex < 0 || dirty == 0 || (dirty & ~15) != 0) {
			throw new IllegalArgumentException("invalid entity publication boundary or dirty mask");
		}
		Objects.requireNonNull(pose, "pose");
	}

	@Override
	public void applyAfterAction(final int completedAction, final PlayerState state) {
		if (completedAction != this.actionIndex) return;
		long actual = StateDigest.state(state);
		if (actual != this.beforeDigest) throw new StaleServerWriteException(this.beforeDigest, actual);
		applyToDigestedState(state);
	}

	/** Apply to the very state {@link #beforeDigest} was taken from, which needs no second digest. */
	public void applyToDigestedState(final PlayerState state) {
		if ((this.dirty & FLAGS) != 0) {
			state.sprinting = (this.sharedFlags & 8) != 0;
			state.swimming = (this.sharedFlags & 16) != 0;
			state.fallFlying = (this.sharedFlags & 128) != 0;
		}
		if ((this.dirty & POSE) != 0) {
			state.pose = this.pose;
			state.placeAt(state.x, state.y, state.z);
		}
		if ((this.dirty & FROZEN) != 0) state.ticksFrozen = this.ticksFrozen;
		if ((this.dirty & MOVEMENT_SPEED) != 0) {
			state.frostSpeedTicks = this.frostSpeedTicks;
			state.sprintingAttribute = this.sprintingAttribute;
		}
	}

	@Override
	public Optional<EntityDataWrite> afterConsuming(final int actions) {
		if (actions < 0) throw new IllegalArgumentException("negative consumed actions");
		return this.actionIndex < actions ? Optional.empty() : Optional.of(at(this.actionIndex - actions));
	}

	@Override
	public EntityDataWrite delayedBy(final int actions) {
		if (actions < 0) throw new IllegalArgumentException("negative delay");
		return at(Math.addExact(this.actionIndex, actions));
	}

	private EntityDataWrite at(final int action) {
		return new EntityDataWrite(action, this.beforeDigest, this.dirty, this.sharedFlags, this.pose, this.ticksFrozen,
		    this.frostSpeedTicks, this.sprintingAttribute);
	}
}
