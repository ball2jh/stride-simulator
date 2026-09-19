package com.nettarion.stride.simulator;

/**
 * The health and food packet {@code ServerPlayer.doTick} sends when any of the three values changed, bound to the
 * client state it is delivered to.
 *
 * <p>The client's copy of the player carries food only: vanilla's {@code LocalPlayer} keeps its health and saturation
 * outside the movement state, so delivering this write sets {@link PlayerState#foodLevel} and nothing else. The
 * health and saturation are carried for the consumer and for ledger attribution.
 *
 * @param actionIndex the completed action after which the write is applied
 * @param beforeDigest the digest of the client state the write is bound to
 * @param health the server's health, in health points, above zero
 * @param foodLevel the server's food level, in food points from 0 to 20
 * @param saturationLevel the server's saturation, in food points, not negative
 */
public record HealthWrite(int actionIndex, long beforeDigest, float health, int foodLevel, float saturationLevel)
    implements ServerWrite {
	/** Validates the ranges above; a dead player's packet is outside the admitted domain. */
	public HealthWrite {
		if (actionIndex < 0 || !Float.isFinite(health) || health <= 0 || foodLevel < 0 || foodLevel > 20
		    || !Float.isFinite(saturationLevel) || saturationLevel < 0) {
			throw new IllegalArgumentException("unsupported health write");
		}
	}

	@Override
	public void applyAfterAction(final int completedAction, final PlayerState state) {
		if (completedAction != this.actionIndex) {
			return;
		}
		long actual = StateDigest.state(state);
		if (actual != this.beforeDigest) {
			throw new StaleServerWriteException(this.beforeDigest, actual);
		}
		applyUnchecked(state);
	}

	/**
	 * Apply without checking the action or the digest. Only for the very state {@link #beforeDigest} was taken
	 * from, by the code that took it; every other caller uses {@link #applyAfterAction}.
	 */
	public void applyUnchecked(final PlayerState state) {
		state.foodLevel = this.foodLevel;
	}

	@Override
	public HealthWrite withActionIndex(final int index) {
		return new HealthWrite(index, this.beforeDigest, this.health, this.foodLevel, this.saturationLevel);
	}
}
