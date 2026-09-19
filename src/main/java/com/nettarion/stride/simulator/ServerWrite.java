package com.nettarion.stride.simulator;

import java.util.Optional;

/**
 * One write the server makes to the client's copy of the player, delivered after a named completed action.
 *
 * <p>A write is bound to the client state it changes. {@link #applyAfterAction} applies it only when the completed
 * action is its {@link #actionIndex()}, and the digest-bound kinds ({@link HurtMotionWrite}, {@link EntityDataWrite},
 * {@link HealthWrite}, {@link PlayerPositionWrite}) throw {@link StaleServerWriteException} when the state is not the
 * one they were bound to, instead of silently treating a stale server prediction as movement physics.
 * {@link DamageWrite} is a hit at the action that caused it and changes nothing on the client; {@link ImpulseWrite} is
 * an external actor's velocity operation; {@link BlockUpdateWrite} changes the client's world and refuses a
 * player-only delivery. Writes are immutable values; a {@link ServerWriteTimeline} orders them by action index.
 */
public sealed interface ServerWrite permits PlayerPositionWrite, BlockUpdateWrite, DamageWrite, HurtMotionWrite,
    ImpulseWrite, EntityDataWrite, HealthWrite {
	/** The count of completed actions after which this write is applied. */
	int actionIndex();

	/** Apply this write when {@code completedAction} is its {@link #actionIndex()}; otherwise do nothing. */
	void applyAfterAction(int completedAction, PlayerState state);

	/** The same write delivered after {@code actionIndex} completed actions instead; the bound state is unchanged. */
	ServerWrite withActionIndex(int actionIndex);

	/**
	 * This write relative to the actions remaining after the first {@code actions} completed, or empty when it was
	 * delivered inside them. Refuses a negative count with {@link IllegalArgumentException}.
	 */
	default Optional<ServerWrite> afterConsuming(final int actions) {
		if (actions < 0) {
			throw new IllegalArgumentException("consumed action count is out of range");
		}
		return actionIndex() < actions ? Optional.empty() : Optional.of(withActionIndex(actionIndex() - actions));
	}

	/**
	 * The same write delivered {@code actions} actions later, for one action list appended after another. Refuses a
	 * negative delay with {@link IllegalArgumentException}.
	 */
	default ServerWrite delayedBy(final int actions) {
		if (actions < 0) {
			throw new IllegalArgumentException("delay is out of range");
		}
		return withActionIndex(Math.addExact(actionIndex(), actions));
	}
}
