/**
 * File formats for recorded player movement: what a producer observed the player doing, tick by
 * tick, written so that any other producer's recording can be compared with it bit for bit.
 *
 * <p>A {@link com.nettarion.stride.simulator.trace.Capture} bundles the files of one recorded
 * session behind one base path. The formats, each with its own version:
 * <ul>
 * <li>trace, {@code .tsv}, magic {@code #stride-trace}, version 13, read and written by
 * {@link com.nettarion.stride.simulator.trace.TraceCodec}: one
 * {@link com.nettarion.stride.simulator.trace.StateVector} of raw-bit
 * {@link com.nettarion.stride.simulator.trace.StateField} columns per tick;</li>
 * <li>world, {@code .world}, magic {@code #stride-world}, version 15 (reads 13 and up), by
 * {@link com.nettarion.stride.simulator.trace.WorldSnapshotCodec};</li>
 * <li>world events, {@code .events}, magic {@code #stride-world-events}, version 3 (reads 1 and
 * up), by {@link com.nettarion.stride.simulator.trace.WorldEventTrace};</li>
 * <li>state events, {@code .state-events}, magic {@code #stride-state-events}, version 2, by
 * {@link com.nettarion.stride.simulator.trace.StateEventTrace};</li>
 * <li>block catalog, {@code .catalog}, binary, magic {@code stride-block-source}, version 1, by
 * {@link com.nettarion.stride.simulator.trace.BlockStateCatalogCodec};</li>
 * <li>simulation state, binary, schema 2 (reads 1), by
 * {@link com.nettarion.stride.simulator.trace.SimulationStateCodec}, embedded in checkpoints and
 * recordings;</li>
 * <li>scheduled recording, gzip, magic {@code stride-scheduled-recording}, version 3, by
 * {@link com.nettarion.stride.simulator.trace.ScheduledRecording}, holding one
 * {@link com.nettarion.stride.simulator.trace.SimulationCheckpoint} per event.</li>
 * </ul>
 * Every layout is specified for readers in other languages in {@code docs/trace-formats.md}.
 *
 * <p>Every scalar is stored as raw bits, never as a decimal rendering: floating-point columns are
 * hexadecimal IEEE 754 bits, and {@link com.nettarion.stride.simulator.trace.TraceComparison}
 * compares those bits. Nothing rounds, widens or normalizes, so {@code -0.0} and NaN payloads
 * survive a round trip and a comparison.
 *
 * <p>Producers are identified by {@link com.nettarion.stride.simulator.trace.TraceProducer}. This
 * library reads every producer's files and writes its own; it ships no tool that records a live
 * game. The two bundled captures under {@code src/test/resources/captures} were recorded by a
 * live 26.2 client and are the reference for the trace, world, state-event and catalog formats.
 *
 * <p>{@link com.nettarion.stride.simulator.trace.CaptureFormat} reads the version of each file
 * without decoding it, so a consumer can refuse a capture recorded before a column was added
 * rather than compare it with that column missing.
 */
package com.nettarion.stride.simulator.trace;
