package com.nettarion.stride.simulator;

import java.util.Objects;

/** One atomic event of a {@link ScheduledSimulation}: a client tick, a server phase, or one packet delivery. */
public sealed interface SimulationEvent permits SimulationEvent.ClientTick, SimulationEvent.Phase {
	/** Applies this event to {@code simulation}. */
	default void apply(final ScheduledSimulation simulation) {
		switch (this) {
			case ClientTick tick -> simulation.tickClient(tick.input());
			case Phase phase -> {
				switch (phase) {
					case LEVEL_TICK -> simulation.tickLevel();
					case CONNECTION_TICK -> simulation.tickConnection();
					case PUBLISH_SERVER_ENTITY -> simulation.publishServerEntity();
					case DELIVER_SERVERBOUND -> {
						if (!simulation.deliverServerbound()) {
							throw new IllegalStateException("scheduled serverbound queue is empty");
						}
					}
					case DELIVER_CLIENTBOUND -> {
						if (!simulation.deliverClientbound()) {
							throw new IllegalStateException("scheduled clientbound queue is empty");
						}
					}
				}
			}
		}
	}

	/**
	 * A client tick with its action.
	 *
	 * @param input the keys held and the view rotation for the tick
	 */
	record ClientTick(PlayerInput input) implements SimulationEvent {
		/** Rejects a null input. */
		public ClientTick {
			Objects.requireNonNull(input);
		}
	}

	/** A server phase or exactly one FIFO packet delivery. */
	enum Phase implements SimulationEvent {
		/** {@code ServerPlayer.tick} in the level's entity tick. */
		LEVEL_TICK,
		/** The connection's {@code doTick}, which may queue a health packet. */
		CONNECTION_TICK,
		/** The entity tracker's sample, which may queue entity data and a pending hurt velocity. */
		PUBLISH_SERVER_ENTITY,
		/** Deliver the oldest queued serverbound packet. */
		DELIVER_SERVERBOUND,
		/** Deliver the oldest queued clientbound packet. */
		DELIVER_CLIENTBOUND
	}
}
