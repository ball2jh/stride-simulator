package com.nettarion.stride.simulator;

import java.util.Objects;

/**
 * One {@code LivingEntity.hurtServer} on the server's copy of the player and what it did: the source, the amount
 * attempted, what the hit cooldown let through, and the health it left. Amounts are in health points.
 *
 * <p>A {@link #full()} hit sets the twenty-tick cooldown and, for a source that {@linkplain HurtCause#marksHurt
 * marks}, republishes the server's velocity after the following action. A hit inside the cooldown that is larger than
 * the one owning it applies only the difference and marks nothing; a smaller or equal one is
 * {@linkplain #blockedByCooldown() blocked}. The client learns of every applied hit through a damage-event and a
 * health packet, which the {@link WriteLedger} attributes by cause.
 *
 * @param cause the damage source
 * @param attempted the amount the source dealt before the cooldown and absorption
 * @param absorbed the part absorption took
 * @param healthDamage the part health took
 * @param healthAfter the server's health after the hit
 * @param full whether the hit installed the cooldown, rather than landing inside one
 */
public record
    DamageEvent(HurtCause cause, float attempted, float absorbed, float healthDamage, float healthAfter, boolean full) {
	/** Validates that every amount is finite and not negative. */
	public DamageEvent {
		Objects.requireNonNull(cause, "cause");
		if (!Float.isFinite(attempted) || !Float.isFinite(absorbed) || !Float.isFinite(healthDamage)
		    || !Float.isFinite(healthAfter) || attempted < 0.0F || absorbed < 0.0F || healthDamage < 0.0F
		    || healthAfter < 0.0F) {
			throw new IllegalArgumentException("damage values are out of range");
		}
	}

	/** Whether the hit landed inside the cooldown at or below the owning hit's amount, so nothing got through. */
	public boolean blockedByCooldown() {
		return this.absorbed == 0.0F && this.healthDamage == 0.0F && !this.full;
	}

	/**
	 * Whether this hit called {@code markHurt}: it was full and its cause is outside {@code no_impact}. The server's
	 * velocity is then republished as a {@link HurtMotionWrite} after the next action.
	 */
	public boolean marks() {
		return this.full && this.cause.marksHurt();
	}
}
