package com.nettarion.stride.simulator;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A repeating sequence of action windows for a {@link ScheduledSimulation}, each holding exactly one
 * client tick. Packets queued in one window stay queued into the next.
 *
 * @param windows the windows, applied in turn by action index; copied on construction
 */
public record ActionSchedule(List<List<Operation>> windows) {
	/** Copies the windows and requires a nonempty schedule with exactly one client tick per window. */
	public ActionSchedule {
		windows = windows.stream().map(List::copyOf).toList();
		if (windows.isEmpty()) {
			throw new IllegalArgumentException("empty action schedule");
		}
		for (List<Operation> window : windows) {
			if (window.stream().filter(op -> op == Operation.CLIENT_TICK).count() != 1) {
				throw new IllegalArgumentException("each action window must contain exactly one client tick");
			}
		}
	}

	/**
	 * The window {@link Simulator} composes: tracker sample, level tick, connection tick, client tick,
	 * then every queued serverbound and clientbound packet in FIFO order. This declares a transport
	 * timing; it does not infer a live connection's. A correction's acknowledgement stays queued for the
	 * next serverbound drain.
	 */
	public static ActionSchedule composed() {
		return new ActionSchedule(
		    List.of(List.of(Operation.PUBLISH_SERVER_ENTITY, Operation.LEVEL_TICK, Operation.CONNECTION_TICK,
		        Operation.CLIENT_TICK, Operation.DRAIN_SERVERBOUND, Operation.DRAIN_CLIENTBOUND)));
	}

	/** Runs the next window, reporting each atomic event to {@code observer}, one per drained packet. */
	public void advance(
	    final ScheduledSimulation state, final PlayerInput input, final Consumer<SimulationEvent> observer) {
		Objects.requireNonNull(observer);
		List<Operation> window = this.windows.get(Math.floorMod(state.completedActions(), this.windows.size()));
		for (Operation operation : window) {
			switch (operation) {
				case CLIENT_TICK -> apply(state, new SimulationEvent.ClientTick(input), observer);
				case DRAIN_SERVERBOUND -> {
					while (state.pendingServerboundPackets() > 0) {
						apply(state, SimulationEvent.Phase.DELIVER_SERVERBOUND, observer);
					}
				}
				case DRAIN_CLIENTBOUND -> {
					while (state.pendingClientboundPackets() > 0) {
						apply(state, SimulationEvent.Phase.DELIVER_CLIENTBOUND, observer);
					}
				}
				default -> apply(state, operation.phase, observer);
			}
		}
	}

	/** Runs the next window without observing its events. */
	public void advance(final ScheduledSimulation state, final PlayerInput input) {
		advance(state, input, ignored -> {});
	}

	private static void apply(
	    final ScheduledSimulation state, final SimulationEvent event, final Consumer<SimulationEvent> observer) {
		event.apply(state);
		observer.accept(event);
	}

	/** One entry of a window: the client tick, a server phase, one packet delivery, or a FIFO drain. */
	public enum Operation {
		/** The client tick with the window's input. */
		CLIENT_TICK(null),
		/** {@link SimulationEvent.Phase#LEVEL_TICK}. */
		LEVEL_TICK(SimulationEvent.Phase.LEVEL_TICK),
		/** {@link SimulationEvent.Phase#CONNECTION_TICK}. */
		CONNECTION_TICK(SimulationEvent.Phase.CONNECTION_TICK),
		/** {@link SimulationEvent.Phase#PUBLISH_SERVER_ENTITY}. */
		PUBLISH_SERVER_ENTITY(SimulationEvent.Phase.PUBLISH_SERVER_ENTITY),
		/** Exactly one {@link SimulationEvent.Phase#DELIVER_SERVERBOUND}; fails when the queue is empty. */
		DELIVER_SERVERBOUND(SimulationEvent.Phase.DELIVER_SERVERBOUND),
		/** Exactly one {@link SimulationEvent.Phase#DELIVER_CLIENTBOUND}; fails when the queue is empty. */
		DELIVER_CLIENTBOUND(SimulationEvent.Phase.DELIVER_CLIENTBOUND),
		/** Every queued serverbound packet, in FIFO order. */
		DRAIN_SERVERBOUND(null),
		/** Every queued clientbound packet, in FIFO order. */
		DRAIN_CLIENTBOUND(null);

		/** The single phase this operation applies, or {@code null} for the client tick and the drains. */
		private final SimulationEvent.Phase phase;

		Operation(final SimulationEvent.Phase phase) {
			this.phase = phase;
		}
	}
}
