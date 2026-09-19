package com.nettarion.stride.simulator.trace;

/**
 * Identifies which kind of producer wrote a trace.
 *
 * <p>Captures store the constant name in their {@code #producer} header, so the names are part
 * of the trace format and never change. This library reads files from every producer listed
 * here; it writes only as {@link #PURE_KERNEL}.
 */
public enum TraceProducer {
	/** A real 26.2 client with its integrated server. The bundled captures carry this producer. */
	LIVE_GAME("live"),
	/** Vanilla client movement code driven without launching the game. */
	HEADLESS_VANILLA("headless"),
	/** This simulator. */
	PURE_KERNEL("kernel");

	private final String filePrefix;

	TraceProducer(final String filePrefix) {
		this.filePrefix = filePrefix;
	}

	/** The prefix of this producer's capture file names, as in {@code live-<scenario>.tsv}. */
	public String filePrefix() {
		return this.filePrefix;
	}
}
