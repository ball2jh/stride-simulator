/**
 * Minecraft 26.2 movement and admitted server mechanics, composed without Minecraft on the classpath.
 *
 * <p>This package is the composition. {@link com.nettarion.stride.simulator.Simulator} advances a
 * {@link com.nettarion.stride.simulator.SimulationState}, the client's
 * {@link com.nettarion.stride.simulator.PlayerState} with its
 * {@link com.nettarion.stride.simulator.Publisher} and the server's
 * {@link com.nettarion.stride.simulator.ServerPlayerState}, by one
 * {@link com.nettarion.stride.simulator.PlayerInput} under a fixed schedule: finish the server tick
 * that drained the previous action's packets (entity tracker sample, level tick, connection tick,
 * in the pinned source's order), run the client tick and choose outbound packets, drain them on
 * the server, then deliver the sample and the health packet before the next client tick, the
 * transport phase the live captures measure.
 * {@link com.nettarion.stride.simulator.ScheduledSimulation} exposes the same events individually,
 * with FIFO transport in each direction and forks that own both worlds and all queued payloads;
 * {@link com.nettarion.stride.simulator.SimulationEvent} names each atomic event and
 * {@link com.nettarion.stride.simulator.ActionSchedule} declares repeating action windows. Neither
 * infers transport timing. {@link com.nettarion.stride.simulator.Rollout} is the same
 * transition on one owned working boundary, advanced in place, for a consumer that ticks many
 * short sequences and reads a few fields of each; {@code advance} publishes an immutable boundary
 * per tick and copies what it hands out, which a rollout does not. The client tick alone
 * ({@code tick.ClientTick}) is the composed step's client copy until a delivered server write
 * changes it, a hurt-marked velocity, a health packet or a stale entity-data echo, so a kinematic
 * question can be asked of the client tick and certified by one composed run. {@link com.nettarion.stride.simulator.StateDigest} is the raw-bit comparison, of
 * each copy and of a whole boundary; {@link com.nettarion.stride.simulator.MovementClasses} the
 * source-hook inventory.
 *
 * <p>This package is also the whole surface a consumer needs. What the server publishes back to
 * the client is a {@link com.nettarion.stride.simulator.ServerWrite} on one ordered
 * {@link com.nettarion.stride.simulator.ServerWriteTimeline}, matched to observed packets by a
 * {@link com.nettarion.stride.simulator.WriteLedger}; the write kinds
 * ({@link com.nettarion.stride.simulator.DamageWrite}, {@link com.nettarion.stride.simulator.ImpulseWrite},
 * {@link com.nettarion.stride.simulator.HurtMotionWrite}, {@link com.nettarion.stride.simulator.HealthWrite},
 * {@link com.nettarion.stride.simulator.EntityDataWrite}, {@link com.nettarion.stride.simulator.BlockUpdateWrite},
 * {@link com.nettarion.stride.simulator.PlayerPositionWrite}) and their payloads are here with
 * them. {@link com.nettarion.stride.simulator.AABB} is the box every layer measures with and
 * {@link com.nettarion.stride.simulator.FluidSample} the fluid answer a world gives. The
 * subpackages are mechanism: a consumer that imports from {@code tick}, {@code server},
 * {@code block} or {@code geometry} has coupled itself to how the tick is built, not to what it
 * promises, and {@code world} is the one subpackage a consumer reaches into, for the captured
 * world itself.
 *
 * <p>The subpackages are the mechanics. {@code tick} is the player tick as a phase pipeline,
 * shared by both copies; {@code block} is the block behaviors keyed by palette entry and the
 * traversal that applies them; {@code server} is what only the server does and its one stream of
 * writes; {@code world} is the captured world and its compiled views; {@code geometry} is boxes,
 * pinned math and collision buffers; {@code trace} is the simulator's own serialization. They
 * reference each other in the cycles the source has between level, block and entity.
 *
 * <p>Two laws bind everything here. The arithmetic and its order are a transcription of the pinned
 * source and stay one; static composition, flat storage, per-thread scratch and fused queries are
 * the retained performance choices around it, and each further structural departure needs a
 * measured comparison. An unimplemented mechanic refuses through a typed exception
 * ({@link com.nettarion.stride.simulator.UnimplementedMechanicException} and the server-write
 * refusals); it never guesses, so a refused transition is intentional and an admitted wrong
 * result is a bug. Every refusal is a {@link com.nettarion.stride.simulator.RefusalException} naming one
 * {@link com.nettarion.stride.simulator.RefusalCause} boundary beside its message, records no
 * stack trace unless {@code stride.refusal.trace} is set, and builds a message that names
 * coordinates or values only when something reads it: a refusal is a signal on the search's
 * hot path, and its cause is what a consumer counts.
 *
 * <p>The admitted slice is a bare non-peaceful survival player at normal tick rate with declared
 * food facts. Unsupported equipment, external actors without a declared
 * {@link com.nettarion.stride.simulator.ExternalActor} schedule, unmodeled world mutation, fluid
 * evolution, paused tick rates, creative ability packets and death refuse at their first required
 * transition. Captures and headless comparisons are evidence for this slice, not a proof over every
 * Minecraft state, and no asynchronous live-equivalence claim is made.
 *
 * <p>Run the standalone regression suite with {@code ./gradlew test}, build the library with
 * {@code ./gradlew build}, and generate this API reference with {@code ./gradlew javadoc}.
 * The bundled capture fixtures check a small recorded slice; native game capture and broader
 * differential validation require external tooling. This library owns no goals, planning or
 * live networking.
 */
package com.nettarion.stride.simulator;
