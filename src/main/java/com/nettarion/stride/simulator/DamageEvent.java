package com.nettarion.stride.simulator;

import java.util.Objects;

/**
 * One {@code LivingEntity.hurtServer} on the server's copy of the player
 * and what it did: the source, the amount attempted, what the hit cooldown
 * let through, and the health it left.
 *
 * <p>A {@link #full()} hit sets the twenty-tick cooldown and, for a source
 * that {@linkplain HurtCause#marksHurt marks}, republishes the server's
 * velocity after the following input. A hit inside the cooldown that is
 * larger than the one owning it applies only the difference and marks
 * nothing; a smaller or equal one is {@linkplain #refused() refused}. The
 * client learns of every applied hit through a damage-event and a health
 * packet, which the {@link WriteLedger} attributes by cause.
 */
public record
    DamageEvent(HurtCause cause, float attempted, float absorbed, float healthDamage, float healthAfter, boolean full) {
	public DamageEvent {
		Objects.requireNonNull(cause, "cause");
		if (!Float.isFinite(attempted) || !Float.isFinite(absorbed) || !Float.isFinite(healthDamage)
		    || !Float.isFinite(healthAfter) || attempted < 0.0F || absorbed < 0.0F || healthDamage < 0.0F
		    || healthAfter < 0.0F) {
			throw new IllegalArgumentException("damage values are out of range");
		}
	}

	/** Whether the cooldown let nothing through. */
	public boolean refused() {
		return this.absorbed == 0.0F && this.healthDamage == 0.0F && !this.full;
	}

	/** Whether this hit republishes the server's velocity after the next input. */
	public boolean marks() {
		return this.full && this.cause.marksHurt();
	}
}
