package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.PlayerInput;

import java.util.List;

/**
 * A per-tick record of one implementation executing one scenario: the state before the
 * first tick, then the action applied and the state that resulted, for every
 * tick.
 *
 * <p>Entry {@code i} holds the state <em>after</em> tick {@code i} completed,
 * having applied {@code action}. The state at the top of the first tick is
	 * {@link #initialState()}, so a trace is self-contained: an implementation can be replayed
 * from it without a separate scenario fixture.
 */
public record Trace(TraceHeader header, StateVector initialState, List<TraceEntry> entries) {
	/**
	 * Bumped whenever the file layout or the audited field set changes. A
	 * reader refuses a trace it was not written to understand rather than
	 * misaligning columns. Version 8 replaced anonymous A/B/C implementations with named
	 * producers. Version 9 added retained supporting-block identity.
	 */
	public static final int FORMAT_VERSION = 13;

	/** Copies the entry list so later caller mutations cannot alter the recording. */
	public Trace {
		entries = List.copyOf(entries);
	}

	/** An action and the recorded state after its zero-based tick. */
	public record TraceEntry(int tick, PlayerInput action, StateVector state) {}

	/** Recording metadata identifying the producer, Minecraft version, scenario and wire format. */
	public record TraceHeader(TraceProducer producer, String minecraftVersion, String scenario, int formatVersion) {
		/** Creates a header for the current trace format. */
		public TraceHeader(final TraceProducer producer, final String minecraftVersion, final String scenario) {
			this(producer, minecraftVersion, scenario, FORMAT_VERSION);
		}
	}
}
