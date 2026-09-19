/**
 * The simulator's own serialization: what one implementation observed the
 * player doing, tick by tick, written so any other implementation can be held
 * to it.
 *
 * <p>This package claims the audited state vector
 * ({@link StateVector} over
 * {@link com.nettarion.stride.simulator.trace.StateField}), the per-tick
 * {@link com.nettarion.stride.simulator.trace.Trace}, its tab-separated raw-bit
 * codec and raw-bit comparison, the world snapshot codec, the block and
 * server-state event sidecars, and the
 * {@link com.nettarion.stride.simulator.trace.Capture} bundle that ties one
 * recording's files together. A live capture, a headless replay, and the
 * simulator all write this format, which is what makes the differential gate
 * a bit-exact assertion rather than a judgment.
 *
 * <p>It refuses to know where captures are kept or which are required; that
 * is validation's.
 *
 * <p>Traces before version 13 carry no sprint speed modifier and no client
 * food level; readers admit them under the prior assumptions, the sprint
 * modifier following the sprint flag and food sufficient (20). World tuples
 * must identify one admitted block behavior; incompatible legacy tuples
 * require explicit migration or recapture.
 *
 * <p>{@link Capture}, {@link CaptureFormat}, {@link Trace}, {@link TraceCodec},
 * {@link TraceComparison}, {@link TraceProducer}, {@link StateVector},
 * {@link StateField}, {@link StateEventTrace}, {@link WorldEventTrace} and
 * {@link WorldSnapshotCodec} are portable conformance artifacts, not
 * replacements for vanilla entities, save formats, packet classes or codecs.
 * Their raw-bit encoding and explicit field inventory are what let independent
 * implementations be compared. A schema field keeps an explicit upstream
 * mapping when the production representation is renamed; changing a wire name
 * is a deliberate capture migration. {@link SharedFlagBits} maps the admitted
 * Entity.DATA_SHARED_FLAGS_ID bits and preserves residual bits rather than
 * clearing unmodeled flags; unknown movement-affecting flag combinations
 * still require admission checks.
 *
 * <p>{@link com.nettarion.stride.simulator.trace.ScheduledRecording} stores initial state/world,
 * atomic events and exact {@link com.nettarion.stride.simulator.trace.SimulationCheckpoint} evidence.
 * {@link com.nettarion.stride.simulator.trace.BoundaryCodec} preserves raw scalar schema and retained movements.
 * {@link com.nettarion.stride.simulator.trace.BlockStateCatalogCodec} persists independently extracted
 * source evidence. Normal Capture imports require its catalog; synthetic/legacy imports must explicitly
 * choose readUnverified. Recorded simulator checkpoints prove consistency; only independently observed
 * checkpoints can support Minecraft fidelity. Verified scheduled imports require the pinned source.
 */
package com.nettarion.stride.simulator.trace;
