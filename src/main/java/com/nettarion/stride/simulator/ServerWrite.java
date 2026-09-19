package com.nettarion.stride.simulator;

import java.util.Optional;

/**
 * One server-owned write to the client's movement state, scheduled relative
 * to an issued plan: the one stream every server effect is delivered on.
 *
 * <p>Every event is bound to the exact movement state at its measured delivery
 * boundary: the write is applied after the completed client movement tick named
 * by {@link #actionIndex()}, and applying it on a trajectory that did not
 * produce it fails explicitly instead of silently treating a stale server
 * prediction as movement physics. A {@link DamageWrite} is a hit at the input
 * that caused it; a {@link HurtMotionWrite} republishes the server's velocity
 * after a hit that marked; an {@link ImpulseWrite} is an external actor's
 * velocity operation. {@link EntityDataWrite} and {@link HealthWrite} carry the
 * sampled metadata/attributes and food publication. PlayerPositionWrite carries absolute corrections; BlockUpdateWrite requires world-aware delivery and refuses a player-only replay.
 */
public sealed interface ServerWrite permits PlayerPositionWrite, BlockUpdateWrite, DamageWrite, HurtMotionWrite,
    ImpulseWrite, EntityDataWrite, HealthWrite {
	/** Index of the completed action after which the write is applied. */
	int actionIndex();

	/** Apply the write when this completed action is its delivery boundary. */
	void applyAfterAction(int completedAction, PlayerState state);

	/** Return this event relative to a suffix, or empty when already delivered. */
	Optional<? extends ServerWrite> afterConsuming(int actions);

	/** The same event delivered {@code actions} inputs later, for plans joined end to end. */
	ServerWrite delayedBy(int actions);
}
