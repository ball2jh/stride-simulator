package com.nettarion.stride.simulator;

import java.util.Objects;
import java.util.Optional;

/**
 * A boundary between two actions: the client's {@link PlayerState} with its {@link Publisher}, the
 * server's {@link ServerPlayerState}, the count of completed actions, and the hit the previous action
 * caused whose velocity write the next step delivers.
 *
 * <p>Hold this type rather than loose player states so a pending hurt and the packet bookkeeping are
 * never lost between steps. Every public constructor copies its arguments and validates the server
 * copy; every accessor returns a copy. Instances are immutable and safe to share across threads.
 *
 * <p>A server copy built by {@link ServerPlayerState#atBoundary} means: the server holds exactly the
 * client's position, rotation and contacts at full health, the publisher's reminder restarts, and
 * nothing is floating or pending. A caller that observed the real publisher and server facts passes
 * those instead.
 */
public final class SimulationState {
	/** The owned client copy, read in place by the simulator and never mutated. */
	final PlayerState client;

	/** The owned publisher, read in place by the simulator and never mutated. */
	final Publisher publisher;

	/** The owned server copy, read in place by the simulator and never mutated. */
	final ServerPlayerState server;

	/** The count of completed actions. */
	final int completedActions;

	/** The hit the previous action caused, or {@code null} when nothing is pending. */
	final HurtCause pendingHurt;

	/**
	 * A boundary with nothing pending and a publisher built by {@link Publisher#atBoundary}.
	 *
	 * @throws IllegalStateException when the server copy is outside the admitted domain
	 */
	public SimulationState(final PlayerState client, final ServerPlayerState server, final int completedActions) {
		this(client, Publisher.atBoundary(client), server, completedActions, null);
	}

	/**
	 * A boundary whose previous action caused {@code pendingHurt}, so the next step delivers its
	 * velocity write; {@code completedActions} may be zero. Null contract: this constructor requires a
	 * non-null hurt; the five-argument constructor accepts {@code null} for "nothing pending".
	 *
	 * @throws IllegalStateException when the server copy is outside the admitted domain
	 */
	public SimulationState(final PlayerState client, final ServerPlayerState server, final int completedActions,
	    final HurtCause pendingHurt) {
		this(client, Publisher.atBoundary(client), server, completedActions,
		    Objects.requireNonNull(pendingHurt, "pendingHurt"));
	}

	/**
	 * The full boundary with an explicit publisher.
	 *
	 * @param pendingHurt the hit the previous action caused, or {@code null} when nothing is pending
	 * @throws IllegalStateException when the server copy is outside the admitted domain
	 */
	public SimulationState(final PlayerState client, final Publisher publisher, final ServerPlayerState server,
	    final int completedActions, final HurtCause pendingHurt) {
		this(client, publisher, server, completedActions, pendingHurt, false);
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
			// Validate at the boundary; a step trusts the states it produced.
			this.server.requireValid();
		}
		if (completedActions < 0) {
			throw new IllegalArgumentException("completed actions are out of range");
		}
		this.completedActions = completedActions;
		this.pendingHurt = pendingHurt;
	}

	/**
	 * Wraps a completed transition's working states without copying them again. The caller must never
	 * mutate them afterwards; retained stepping workspace must rebind to another state first.
	 */
	static SimulationState takeOwnership(final PlayerState client, final Publisher publisher,
	    final ServerPlayerState server, final int completedActions, final HurtCause pendingHurt) {
		return new SimulationState(client, publisher, server, completedActions, pendingHurt, true);
	}

	/** Whether two boundaries agree in every raw field, without copying their owned state. */
	public static boolean rawEquals(final SimulationState a, final SimulationState b) {
		return a == b
		    || a != null && b != null && a.completedActions == b.completedActions && a.pendingHurt == b.pendingHurt
		    && PlayerState.rawEquals(a.client, b.client) && Publisher.rawEquals(a.publisher, b.publisher)
		    && ServerPlayerState.rawEquals(a.server, b.server);
	}

	/** A copy of the client state; mutating it cannot change this boundary. */
	public PlayerState clientState() {
		return this.client.copy();
	}

	/** A copy of the client's publisher at this boundary. */
	public Publisher publisher() {
		return this.publisher.copy();
	}

	/** A copy of the server's state at this boundary. */
	public ServerPlayerState serverState() {
		return this.server.copy();
	}

	/** The count of completed actions carried by this boundary. */
	public int completedActions() {
		return this.completedActions;
	}

	/** Whether the previous action caused a hit whose velocity write the next step delivers. */
	public boolean hasPendingHurt() {
		return this.pendingHurt != null;
	}

	/** The hit the previous action caused, whose velocity write the next step delivers; empty otherwise. */
	public Optional<HurtCause> pendingHurt() {
		return Optional.ofNullable(this.pendingHurt);
	}

	/** The digest of this boundary; equal digests alone do not establish equal states. */
	public long digest() {
		return StateDigest.boundary(this);
	}
}
