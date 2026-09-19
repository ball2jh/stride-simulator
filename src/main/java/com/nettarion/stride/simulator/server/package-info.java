/**
 * What only the server does: its player tick, its packet listener, survival, and the one stream of
 * writes it publishes back to the client.
 *
 * <p>{@link com.nettarion.stride.simulator.server.ServerTick} runs one level tick and one
 * connection tick with its packet handlers under a declared schedule;
 * {@link com.nettarion.stride.simulator.server.ServerMovementListener} is the listener's
 * movement admission with retained baselines and packet counts;
 * {@link com.nettarion.stride.simulator.server.ServerEntity} samples dirty metadata, attributes and
 * hurt motion for publication. {@link com.nettarion.stride.simulator.server.Survival} and
 * {@link com.nettarion.stride.simulator.server.FoodData} are the source's damage, food and healing
 * arithmetic over a fixed admitted damage-source table, reached only through
 * {@link com.nettarion.stride.simulator.server.TickAuthority}. Every server-owned change is a
 * {@link com.nettarion.stride.simulator.ServerWrite} on one ordered stream
 * ({@link com.nettarion.stride.simulator.ServerWriteTimeline}), matched to observed packets by
 * {@link com.nettarion.stride.simulator.WriteLedger}; the writes and their payloads live in the
 * root package, since they are what a consumer receives, and this package is what produces them.
 *
 * <p>There is no damage path beside this stream. Unscheduled actors, unknown authoritative facts,
 * datapack damage sources and death refuse rather than receiving a guessed effect. Confirmations
 * record published values for reconciliation, not permission to suppress late packets.
 */
package com.nettarion.stride.simulator.server;
import com.nettarion.stride.simulator.ServerWrite;
import com.nettarion.stride.simulator.ServerWriteTimeline;
import com.nettarion.stride.simulator.WriteLedger;
