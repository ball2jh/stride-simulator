/**
 * Deterministic Minecraft Java Edition 26.2 player movement and the server mechanics that
 * affect it, with no dependencies beyond the JDK.
 *
 * <p>The exported packages are the supported API: the root package for stepping and refusals,
 * {@code world}, {@code block} and {@code geometry} for describing and querying the world, and
 * {@code trace} for reading and comparing recorded traces. The {@code tick} and {@code server}
 * packages are the mechanics behind them and are not exported.
 */
module com.nettarion.stride.simulator {
	exports com.nettarion.stride.simulator;
	exports com.nettarion.stride.simulator.world;
	exports com.nettarion.stride.simulator.block;
	exports com.nettarion.stride.simulator.geometry;
	exports com.nettarion.stride.simulator.trace;
}
