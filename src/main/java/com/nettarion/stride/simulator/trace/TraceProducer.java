package com.nettarion.stride.simulator.trace;

/** The implementation that observed or computed a movement trace. */
public enum TraceProducer {
	/** A real 26.2 client and its integrated server. */
	LIVE_GAME("live"),
	/** Vanilla client movement stepped without launching the game. */
	HEADLESS_VANILLA("headless"),
	/** The pure movement kernel. */
	PURE_KERNEL("kernel");

	private final String filePrefix;

	TraceProducer(final String filePrefix) {
		this.filePrefix = filePrefix;
	}

	public String filePrefix() {
		return this.filePrefix;
	}
}
