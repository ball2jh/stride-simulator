package com.nettarion.stride.simulator;

import java.util.Objects;

/** One atomic event in a declared client/server execution order. */
public sealed interface SimulationEvent permits SimulationEvent.ClientTick, SimulationEvent.Phase {
	/** A client tick with its exact input. */
	record ClientTick(PlayerInput input) implements SimulationEvent {
		public ClientTick {
			Objects.requireNonNull(input);
		}
	}
	/** An independently clocked phase or exactly one FIFO packet delivery. */
	enum Phase implements SimulationEvent {
		LEVEL_TICK,
		CONNECTION_TICK,
		PUBLISH_SERVER_ENTITY,
		DELIVER_SERVERBOUND,
		DELIVER_CLIENTBOUND
	}
	default void apply(final ScheduledSimulation simulation) {
		switch (this) {
			case ClientTick tick -> simulation.tickClient(tick.input());
			case Phase phase -> {
				switch (phase) {
					case LEVEL_TICK -> simulation.tickLevel();
					case CONNECTION_TICK -> simulation.tickConnection();
					case PUBLISH_SERVER_ENTITY -> simulation.publishServerEntity();
					case DELIVER_SERVERBOUND -> {
						if (!simulation.deliverServerbound())
							throw new IllegalStateException("scheduled serverbound queue is empty");
					}
					case DELIVER_CLIENTBOUND -> {
						if (!simulation.deliverClientbound())
							throw new IllegalStateException("scheduled clientbound queue is empty");
					}
				}
			}
		}
	}
}
