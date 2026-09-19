package com.nettarion.stride.simulator;

import java.util.Objects;
import java.util.Optional;

/**
 * What the simulator steps: the client's copy of the player with its
 * retained publisher, the server's copy with its survival and listener
 * facts, how many inputs have been applied, and the hit the previous input
 * caused whose hurt-marked velocity write the next step publishes.
 *
 * <p>Search and pilot code hold this type rather than loose player states,
 * so a pending server write and the packet bookkeeping are never lost
 * between them. Constructors copy their inputs and ordinary accessors return copies.
 * {@link #viewClient()} is an explicitly borrowed read-only view: mutating it
 * violates the boundary contract and can invalidate retained simulations.
 *
 * <p>A {@link ServerPlayerState#atBoundary constructed} server copy is the
 * packet boundary: the server holds exactly the client's position, rotation,
 * and contacts at full health, the reminder restarts, and nothing is
 * floating or pending. That is the meaning of starting a composed transition
 * from two constructed states, not an inference from them; a caller that
 * observed the real publisher and server facts passes them instead.
 */
public final class SimulationState {
	/** The owned copies, read in place by the simulator's own readers and never mutated. */
	final PlayerState client;
	final Publisher publisher;
	final ServerPlayerState server;
	final int completedActions;
	/** The hit the previous input caused, or {@code null} when nothing pends. */
	final HurtCause pendingHurt;

	/** The state at a packet boundary with nothing pending; see the class description. */
	public SimulationState(final PlayerState client, final ServerPlayerState server, final int completedActions) {
		this(client, Publisher.atBoundary(client), server, completedActions, null);
	}

	/**
	 * The state at a packet boundary whose previous input caused
	 * {@code pendingHurt}, so the next step publishes its write. The count
	 * may be zero: a run can start right after the hit.
	 */
	public SimulationState(final PlayerState client, final ServerPlayerState server, final int completedActions,
	    final HurtCause pendingHurt) {
		this(client, Publisher.atBoundary(client), server, completedActions,
		    Objects.requireNonNull(pendingHurt, "pendingHurt"));
	}

	/**
	 * The full composed state.
	 *
	 * @param pendingHurt the hit the previous input caused, or {@code null}
	 *     when no write pends
	 */
	public SimulationState(final PlayerState client, final Publisher publisher, final ServerPlayerState server,
	    final int completedActions, final HurtCause pendingHurt) {
		this(client, publisher, server, completedActions, pendingHurt, false);
	}

	/**
	 * Publish the completed transition's working states without copying them again.
	 * The caller must never mutate them after this transfer. Retained stepping
	 * workspace must rebind to another state before any subsequent mutation.
	 */
	static SimulationState takeOwnership(final PlayerState client, final Publisher publisher,
	    final ServerPlayerState server, final int completedActions, final HurtCause pendingHurt) {
		return new SimulationState(client, publisher, server, completedActions, pendingHurt, true);
	}

	private SimulationState(final PlayerState client, final Publisher publisher, final ServerPlayerState server,
	    final int completedActions, final HurtCause pendingHurt, final boolean owned) {
		Objects.requireNonNull(client, "client");
		Objects.requireNonNull(publisher, "publisher");
		Objects.requireNonNull(server, "server");
		this.client = owned ? client : client.copy();
		this.publisher = owned ? publisher : publisher.copy();
		this.server = owned ? server : server.copy();
		if (!owned) {
			// Admission at the boundary; a step trusts the states it produced.
			this.server.requireValid();
		}
		if (completedActions < 0) {
			throw new IllegalArgumentException("completed actions are out of range");
		}
		this.completedActions = completedActions;
		this.pendingHurt = pendingHurt;
	}

	/** Returns a defensive copy of the client state; mutating it cannot change this boundary. */
	public PlayerState clientState() {
		return this.client.copy();
	}

	/**
	 * The owned client state at this boundary, borrowed for reading only. Never mutate
	 * this object or pass it to an in-place tick. Prefer {@link #clientState()}
	 * unless avoiding its copy matters. A boundary never
	 * changes after construction, so a reader that neither mutates nor steps the
	 * returned instance observes exactly what {@link #clientState()} would copy.
	 */
	public PlayerState viewClient() {
		return this.client;
	}

	/** Whether the server's player is dead at this boundary, without exposing mutable state. */
	public boolean serverDead() {
		return this.server.dead();
	}

	/** Raw-bit digest of this boundary; hash equality alone does not establish state equality. */
	public long digest() {
		return StateDigest.boundary(this);
	}

	/** Whether two complete boundaries agree in every raw field, without copying their owned state. */
	public static boolean rawEquals(final SimulationState a, final SimulationState b) {
		return a == b
		    || a != null && b != null && a.completedActions == b.completedActions && a.pendingHurt == b.pendingHurt
		    && PlayerState.rawEquals(a.client, b.client) && Publisher.rawEquals(a.publisher, b.publisher)
		    && ServerPlayerState.rawEquals(a.server, b.server);
	}

	/** The client's retained publisher at this boundary. */
	public Publisher publisher() {
		return this.publisher.copy();
	}

	/** The server's copy with its survival and listener facts at this boundary. */
	public ServerPlayerState serverState() {
		return this.server.copy();
	}

	/** Returns the absolute count of completed client actions carried by this boundary. */
	public int completedActions() {
		return this.completedActions;
	}

	/** Whether the previous input caused a hit whose write the next step publishes. */
	public boolean hasPendingEffect() {
		return this.pendingHurt != null;
	}

	/**
	 * The hit the previous input caused, whose hurt-marked velocity write the
	 * next step publishes; empty at an ordinary boundary.
	 */
	public Optional<HurtCause> pendingHurt() {
		return Optional.ofNullable(this.pendingHurt);
	}
}
