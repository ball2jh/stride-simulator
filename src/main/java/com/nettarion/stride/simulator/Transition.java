package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.server.ServerTick;
import com.nettarion.stride.simulator.tick.ClientTick;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.SnapshotView;

import java.util.Objects;

/**
 * The one transition body: every phase of a client-and-server step as a method both runners call, so
 * the composed schedule and an explicit one share every line of arithmetic and differ only in when
 * they call each phase and where packets wait.
 *
 * <p>The phases, in vanilla's order: the tracker sample ({@link #publishServerEntity}),
 * {@code ServerPlayer.tick} ({@link #tickLevel}), the connection's {@code doTick}
 * ({@link #tickConnection}), the client tick with its packet choice ({@link #tickClient}), the packet
 * handlers ({@link #handleInput}, {@link #handleSprint}, {@link #handleGlide}, {@link #handleMove},
 * {@link #handleAcceptTeleport}), the server's writes ({@link #dataPublication},
 * {@link #healthPublication}) and the client's application of one ({@link #deliver}).
 *
 * <p>An instance retains the client and server scratch and the tracker's sample, and is not
 * thread-safe. It sends nothing and queues nothing: the runner that calls a phase owns what it produced.
 */
final class Transition {
	private final ClientTick clientTick = new ClientTick();

	private final Scratch clientScratch = new Scratch();

	private final ServerTick serverTick;

	/** A transition under the fixed publication schedule. */
	Transition() {
		this(new ServerTick());
	}

	/** A transition whose server tick delivers as {@code serverTick} is configured to. */
	Transition(final ServerTick serverTick) {
		this.serverTick = Objects.requireNonNull(serverTick, "serverTick");
	}

	/** The server tick and its scratch, for the runner's world binding and the packet listeners. */
	ServerTick serverTick() {
		return this.serverTick;
	}

	/**
	 * Carry both workspaces' retained spans across a publication, so they
	 * answer again in a view that kept the sections they read; see
	 * {@link Scratch#carry}. Called with the view being left.
	 */
	public void carry(final SnapshotView world) {
		this.clientScratch.carry(world);
		this.serverTick.carry(world);
	}

	/** Whether the last {@link #tickClient} started a glide, which the client tells the server. */
	boolean startedFallFlying() {
		return this.clientScratch.startFallFlying;
	}

	// ---- server phases

	/**
	 * {@code ServerEntity.sendChanges} at the head of the level tick: sample
	 * the dirty metadata, attributes and velocity of the server's copy after
	 * the packet handlers ran and before the player entity ticks.
	 */
	public void publishServerEntity(final ServerPlayerState server) {
		this.serverTick.collectPublications(server);
	}

	/** {@code ServerPlayer.tick} in the level's entity tick. */
	public void tickLevel(final ServerPlayerState server) {
		ServerTick.levelTick(server);
	}

	/**
	 * {@code ServerGamePacketListenerImpl.tickPlayer}: {@code doTick} on the
	 * server's copy under server authority. The hits it deals are on the
	 * damage ledger; the marking one is returned.
	 */
	public ServerTick.Effects tickConnection(final ServerPlayerState server, final SnapshotView world) {
		return this.serverTick.tickConnection(server, world);
	}

	// ---- client phase

	/**
	 * One client tick and the publisher's packet choice on its result. What
	 * the client sends the server is the returned form, the publisher's
	 * changed-input and changed-sprint flags, and {@link #startedFallFlying}.
	 */
	public MovementPacket tickClient(
	    final PlayerState client, final Publisher publisher, final PlayerInput input, final SnapshotView world) {
		this.clientScratch.startFallFlying = false;
		this.clientTick.tick(client, input, world, this.clientScratch);
		return publisher.publish(client);
	}

	// ---- packet handlers, in wire order

	/** Applies one decoded input packet to the authoritative player state. */
	public void handleInput(final ServerPlayerState server, final PlayerInput input) {
		this.serverTick.handlePlayerInput(server, input);
	}

	/** Applies one decoded sprint command to the authoritative player state. */
	public void handleSprint(final ServerPlayerState server, final boolean sprinting) {
		this.serverTick.handlePlayerCommand(server, sprinting);
	}

	/** Applies one decoded request to start gliding under the server's own checks. */
	public void handleGlide(final ServerPlayerState server) {
		this.serverTick.handleStartFallFlying(server);
	}

	/**
	 * {@code handleMovePlayer} on the decoded packet. Under a pending
	 * correction the listener applies the packet's rotation only; the
	 * composed runner refuses that state before it reaches here, since its
	 * transport has no place to hold the acknowledgement, and an explicit
	 * schedule delivers the acknowledgement as its own packet.
	 */
	public ServerTick.Transaction handleMove(final PlayerState payload, final MovementPacket form,
	    final ServerPlayerState server, final SnapshotView world) {
		return this.serverTick.handleMovePlayer(payload, form, server, world);
	}

	/** Applies acknowledgement of the supplied teleport identifier to the server listener. */
	public void handleAcceptTeleport(final ServerPlayerState server, final int teleportId) {
		this.serverTick.handleAcceptTeleportPacket(server, teleportId);
	}

	// ---- clientbound deliveries, bound to the client state they change

	/** The sampled entity data as a write bound to {@code client}, or null when nothing was dirty. */
	public EntityDataWrite dataPublication(final int action, final PlayerState client) {
		return this.serverTick.entityDataPublication(action, client);
	}

	/** The connection tick's health packet as a write bound to {@code client}, or null. */
	public HealthWrite healthPublication(final int action, final PlayerState client) {
		return this.serverTick.healthPublication(action, client);
	}

	/** Apply a bound write to the client copy it was bound to. */
	public void deliver(final ServerWrite write, final int action, final PlayerState client) {
		Objects.requireNonNull(write, "write").applyAfterAction(action, client);
	}
}
