/**
 * What only the server does to the player: its packet handlers, its tick, its damage, and the writes it delivers
 * back to the client. Internal: this package is not exported, and the root package is the consumer's surface.
 *
 * <h2>One server tick, as vanilla runs it</h2>
 *
 * <ol>
 * <li>Packet drain: {@code MinecraftServer.processPacketsAndTick} runs the client's queued packets through
 * {@code ServerGamePacketListenerImpl}: the input packet, the sprint command, {@code START_FALL_FLYING}, the movement
 * packet, then the end-of-tick marker.</li>
 * <li>Tracker sample: {@code ChunkMap.tick} at the head of {@code ServerLevel.tick} runs
 * {@code ServerEntity.sendChanges}, which samples the copy's dirty metadata, attributes and, when a hit marked it,
 * its velocity.</li>
 * <li>Level tick: {@code ServerPlayer.tick} counts the hit cooldown down and advances the entity tick count.</li>
 * <li>Connection tick: {@code ServerGamePacketListenerImpl.tickPlayer} runs {@code doTick}, the player tick under
 * server authority with the server-only branches live (burning, the void, suffocation, drowning, the glide's wall
 * impact, contact bodies, freezing, food), then restores the position anchor and advances the floating counter.</li>
 * </ol>
 *
 * <p>The composed step ({@code Simulator}) cuts this cycle after the packet drain: one step finishes the server tick
 * that drained the previous action's packets (phases 2 to 4), runs the client tick, drains its packets (phase 1 of
 * the next tick), and delivers the sample and the health packet before the next client tick. That cut is what the
 * bundled captures measured: a write the packet drain caused is applied before client tick N + 2, one the connection
 * tick caused before N + 3 (see VALIDATION.md).
 *
 * <h2>Which method covers which phase</h2>
 *
 * <ul>
 * <li>Phase 1: {@link com.nettarion.stride.simulator.server.ServerTick#handlePackets} for the composed step;
 * {@link com.nettarion.stride.simulator.server.ServerTick#handlePlayerInput},
 * {@link com.nettarion.stride.simulator.server.ServerTick#handlePlayerCommand},
 * {@link com.nettarion.stride.simulator.server.ServerTick#handleStartFallFlying},
 * {@link com.nettarion.stride.simulator.server.ServerTick#handleMovePlayer},
 * {@link com.nettarion.stride.simulator.server.ServerTick#handleAcceptTeleportPacket} and
 * {@link com.nettarion.stride.simulator.server.ServerTick#handleClientTickEnd} for an explicit schedule
 * ({@code ScheduledSimulation}).</li>
 * <li>Phase 2: {@link com.nettarion.stride.simulator.server.ServerTick#collectPublications} for both runners.</li>
 * <li>Phases 3 and 4: {@link com.nettarion.stride.simulator.server.ServerTick#advanceTicks} for the composed step;
 * {@link com.nettarion.stride.simulator.server.ServerTick#levelTick} and
 * {@link com.nettarion.stride.simulator.server.ServerTick#tickConnection} for an explicit schedule.</li>
 * <li>Delivery: {@link com.nettarion.stride.simulator.server.ServerTick#deliverPublications} for the composed step;
 * {@link com.nettarion.stride.simulator.server.ServerTick#entityDataPublication} and
 * {@link com.nettarion.stride.simulator.server.ServerTick#healthPublication} for an explicit schedule, which queues
 * them itself.</li>
 * <li>Phases 1, 3 and 4 together, without a sample:
 * {@link com.nettarion.stride.simulator.server.ServerTick#transact}.</li>
 * </ul>
 *
 * <p>{@link com.nettarion.stride.simulator.server.ServerMovementListener} is the movement admission of phase 1;
 * {@link com.nettarion.stride.simulator.server.ServerEntity} is the sample of phase 2;
 * {@link com.nettarion.stride.simulator.server.ServerPlayerTick} and
 * {@link com.nettarion.stride.simulator.server.FoodData} are the server's parts of phase 4;
 * {@link com.nettarion.stride.simulator.server.ServerDamage} is the damage and fire sink every phase deals through, reached
 * by the tick phases and the block behaviors via {@link com.nettarion.stride.simulator.server.TickAuthority}.
 *
 * <h2>Glossary</h2>
 *
 * <p>A <b>write</b> is one server change to the client's copy, a {@code ServerWrite} in the root package; to
 * <b>deliver</b> a write is to apply it to the client's copy after the action it is bound to. The client's own
 * movement packet is <b>published</b>, never written. An <b>echo</b> is the entity-data write that returns the
 * client's own flags after the tracker sampled them. A <b>confirmation</b> is the value of a write the caller can
 * expect on its connection, which the <b>ledger</b> ({@code WriteLedger}) attributes when the packet arrives. The
 * <b>timeline</b> ({@code ServerWriteTimeline}) is the writes of one action list in delivery order. A hit
 * <b>marks</b> the player when it is full and its source is outside {@code no_impact}; the next sample then
 * republishes the server's velocity. <b>Authority</b> is which copy a tick computes for: the server's copy is
 * authoritative and alone deals damage and changes the world.
 *
 * <h2>Hard-coded facts</h2>
 *
 * <ul>
 * <li>ServerDamage game mode; no creative, spectator, sleeping or passenger movement.</li>
 * <li>A non-peaceful difficulty; peaceful refuses at admission.</li>
 * <li>No enchantments and no status effects: no feather falling, fire resistance, levitation or slow falling.</li>
 * <li>Vanilla 26.2's damage-type tags: {@code no_knockback}, {@code no_impact}, {@code bypasses_invulnerability}
 * and the game-rule tags, as {@code HurtCause} encodes them.</li>
 * <li>{@code SAFE_FALL_DISTANCE} at the player default of three blocks.</li>
 * <li>The world border at its default, so no border damage is reachable inside a captured region.</li>
 * </ul>
 *
 * <p>Whatever the server would decide from a fact the state does not carry refuses: a contact body with no behavior,
 * fire's random re-ignition, freeze damage at an unknown tick count, a farmland trample, a death, an attached
 * rocket's writes, the glider's durability write, and a hit on a player who may fly.
 */
package com.nettarion.stride.simulator.server;
