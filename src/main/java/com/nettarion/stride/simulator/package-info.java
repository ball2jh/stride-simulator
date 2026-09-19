/**
 * Deterministic Minecraft Java Edition 26.2 player movement and the server mechanics that affect it,
 * with no dependency on the game.
 *
 * <p>A {@link com.nettarion.stride.simulator.Simulator} advances a
 * {@link com.nettarion.stride.simulator.SimulationState} (the client's
 * {@link com.nettarion.stride.simulator.PlayerState} and {@link com.nettarion.stride.simulator.Publisher},
 * the server's {@link com.nettarion.stride.simulator.ServerPlayerState}, the action count and any
 * pending hurt) by one {@link com.nettarion.stride.simulator.PlayerInput} over a compiled
 * {@link com.nettarion.stride.simulator.world.SnapshotView}, transcribing vanilla's arithmetic in order.
 *
 * <h2>Glossary</h2>
 * <ul>
 * <li><b>vanilla</b>: Minecraft Java Edition 26.2's own implementation. A class named for a vanilla
 * method transcribes it; {@code xxa}, {@code yya}, {@code zza}, {@code yRot} and {@code xRot} are
 * vanilla's field names (sideways, vertical and forward input; yaw and pitch).</li>
 * <li><b>admitted domain</b>: the states, worlds and mechanics the simulator computes. Anything else
 * <b>refuses</b> with a {@link com.nettarion.stride.simulator.RefusalException} naming one
 * {@link com.nettarion.stride.simulator.RefusalCause}; the simulator never guesses.</li>
 * <li><b>action</b>: one {@code PlayerInput}. <b>Action index</b>: the count of completed actions.
 * <b>Step</b>: one {@code Simulator.advance}. <b>Transition</b>: the composed sequence of client and
 * server phases inside a step. <b>Tick</b>: one client or server game tick.</li>
 * <li><b>boundary</b>: a {@code SimulationState} between two actions.</li>
 * <li><b>publish</b>: the client sending its movement packet. <b>Deliver</b> or <b>write</b>: the
 * server changing the client through a {@link com.nettarion.stride.simulator.ServerWrite}.</li>
 * <li><b>digest</b>: the raw-bit hash of a state ({@link com.nettarion.stride.simulator.StateDigest}).</li>
 * <li><b>pose-fit cache</b>: the memoized collision result a {@code PlayerState} keeps for its pose.</li>
 * <li><b>unmodeled</b>: a mechanic vanilla has and this library does not. <b>Unknown</b>: a fact the
 * capture did not record. <b>Undeclared</b>: a fact the caller did not supply. <b>Missing</b>: a
 * section hole in the world.</li>
 * </ul>
 *
 * <h2>Entry points</h2>
 * <p>{@code Simulator.advance} keeps an immutable boundary per action; use it when successors are
 * retained. {@link com.nettarion.stride.simulator.Rollout} steps one owned working copy in place; use it
 * for many short sequences whose intermediate states are read and discarded.
 * {@link com.nettarion.stride.simulator.ScheduledSimulation} with an
 * {@link com.nettarion.stride.simulator.ActionSchedule} runs each client tick, server tick and packet
 * delivery as its own event; use it to reproduce a transport schedule other than the composed one.
 *
 * <h2>Ownership and refusal</h2>
 * <p>Constructors copy their arguments and accessors return copies. The mutable states and the three
 * entry points each belong to one thread. A refused operation leaves in-place state partially mutated;
 * reload a boundary before stepping again. The exported API is this package plus {@code world},
 * {@code block}, {@code geometry} and {@code trace}; {@code tick} and {@code server} are internal.
 */
package com.nettarion.stride.simulator;
