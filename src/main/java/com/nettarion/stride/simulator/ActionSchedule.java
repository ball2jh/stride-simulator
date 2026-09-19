package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.tick.ClientTick;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** An explicit repeating sequence of action windows, retaining packets between windows. */
public record ActionSchedule(List<List<Operation>> windows) {
	/**
	 * The nominal action window used by {@link Simulator}, with FIFO delivery
	 * before the next client action. This declares transport timing; it does
	 * not infer a live connection's schedule. Correction responses remain
	 * queued for the next serverbound drain.
	 */
	public static ActionSchedule composed() {
		return new ActionSchedule(
		    List.of(List.of(Operation.PUBLISH_SERVER_ENTITY, Operation.LEVEL_TICK, Operation.CONNECTION_TICK,
		        Operation.CLIENT_TICK, Operation.DRAIN_SERVERBOUND, Operation.DRAIN_CLIENTBOUND)));
	}
	/** One declared phase, client action, single delivery or explicit FIFO drain. */
	public enum Operation {
		CLIENT_TICK,
		LEVEL_TICK,
		CONNECTION_TICK,
		PUBLISH_SERVER_ENTITY,
		DELIVER_SERVERBOUND,
		DELIVER_CLIENTBOUND,
		DRAIN_SERVERBOUND,
		DRAIN_CLIENTBOUND
	}
	/** Copies the windows and requires a nonempty schedule with exactly one client tick per window. */
	public ActionSchedule {
		windows = windows.stream().map(List::copyOf).toList();
		if (windows.isEmpty()) throw new IllegalArgumentException("empty action schedule");
		for (var window : windows) {
			if (window.stream().filter(op -> op == Operation.CLIENT_TICK).count() != 1)
				throw new IllegalArgumentException("each action window must contain exactly one client tick");
		}
	}
	/** Run one window, reporting the actual atomic events, including every drained packet. */
	public void advance(
	    final ScheduledSimulation state, final PlayerInput input, final Consumer<SimulationEvent> observer) {
		Objects.requireNonNull(observer);
		var window = this.windows.get(Math.floorMod(state.completedActions(), this.windows.size()));
		for (Operation operation : window) {
			if (operation == Operation.DRAIN_SERVERBOUND || operation == Operation.DRAIN_CLIENTBOUND) {
				boolean outbound = operation == Operation.DRAIN_SERVERBOUND;
				while ((outbound ? state.pendingServerboundPackets() : state.pendingClientboundPackets()) > 0)
					apply(state,
					    outbound ? SimulationEvent.Phase.DELIVER_SERVERBOUND
					             : SimulationEvent.Phase.DELIVER_CLIENTBOUND,
					    observer);
			} else
				apply(state,
				    operation == Operation.CLIENT_TICK ? new SimulationEvent.ClientTick(input)
				                                       : SimulationEvent.Phase.valueOf(operation.name()),
				    observer);
		}
	}
	/** Runs the next action window without retaining an event observer. */
	public void advance(final ScheduledSimulation state, final PlayerInput input) {
		advance(state, input, ignored -> {});
	}
	private static void apply(
	    final ScheduledSimulation state, final SimulationEvent event, final Consumer<SimulationEvent> observer) {
		event.apply(state);
		observer.accept(event);
	}
}
