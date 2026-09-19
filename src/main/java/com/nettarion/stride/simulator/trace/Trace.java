package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.PlayerInput;

import java.util.List;

/**
 * A per-tick record of one producer executing one scenario: the state before the first tick,
 * then the action applied and the state that resulted, for every tick.
 *
 * <p>Entry {@code i} holds the state after tick {@code i} completed, having applied its action.
 * The state at the top of the first tick is {@link #initialState()}, so a trace is
 * self-contained: a consumer can replay it without a separate scenario description. Instances
 * are immutable.
 *
 * @param header the producer, Minecraft version and scenario name
 * @param initialState the state before the first recorded tick
 * @param entries one entry per recorded tick, in tick order
 */
public record Trace(TraceHeader header, StateVector initialState, List<TraceEntry> entries) {
	/**
	 * The trace file format this library reads and writes. Bumped whenever the file layout or the
	 * recorded field set changes; the reader refuses every other version rather than misaligning
	 * columns.
	 */
	public static final int FORMAT_VERSION = 13;

	/** Copies the entry list so later caller mutations cannot alter the trace. */
	public Trace {
		entries = List.copyOf(entries);
	}

	/**
	 * An action and the recorded state after its zero-based tick.
	 *
	 * @param tick the zero-based client tick index
	 * @param action the input applied during the tick
	 * @param state the state after the tick completed
	 */
	public record TraceEntry(int tick, PlayerInput action, StateVector state) {}

	/**
	 * Metadata identifying the producer, Minecraft version and scenario of a trace.
	 *
	 * @param producer who wrote the trace
	 * @param minecraftVersion the Minecraft version the producer ran, for example {@code 26.2}
	 * @param scenario the scenario name shared by every file of a capture
	 */
	public record TraceHeader(TraceProducer producer, String minecraftVersion, String scenario) {}
}
