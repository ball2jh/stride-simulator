package com.nettarion.stride.simulator;

import java.util.Optional;

/** A ServerPlayer.doTick health and food packet, with its sampled values and delivery boundary. */
public record HealthWrite(int actionIndex, long beforeDigest, float health, int foodLevel, float saturationLevel)
    implements ServerWrite {
	public HealthWrite {
		if (actionIndex < 0 || !Float.isFinite(health) || health <= 0 || foodLevel < 0 || foodLevel > 20
		    || !Float.isFinite(saturationLevel) || saturationLevel < 0)
			throw new IllegalArgumentException("unsupported health publication");
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
		state.foodLevel = this.foodLevel;
	}

	@Override
	public Optional<HealthWrite> afterConsuming(final int actions) {
		if (actions < 0) throw new IllegalArgumentException("negative consumed actions");
		return this.actionIndex < actions ? Optional.empty() : Optional.of(at(this.actionIndex - actions));
	}

	@Override
	public HealthWrite delayedBy(final int actions) {
		if (actions < 0) throw new IllegalArgumentException("negative delay");
		return at(Math.addExact(this.actionIndex, actions));
	}

	private HealthWrite at(final int action) {
		return new HealthWrite(action, this.beforeDigest, this.health, this.foodLevel, this.saturationLevel);
	}
}
