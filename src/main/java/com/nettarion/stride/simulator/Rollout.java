package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.world.SnapshotView;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The composed transition on one owned working boundary, advanced in place:
 * the stepping form a consumer drives when it ticks many short sequences
 * from many roots and reads a few fields of each result.
 *
 * <p>{@link Simulator#advance} publishes an immutable boundary per tick and
 * copies every state it hands out. That is right for a pilot that keeps
 * boundaries, and it is the cost a shooting loop, a reachability sweep or a
 * policy rollout pays for nothing: those read the working state and move on.
 * A rollout holds one client, one publisher and one server copy, loads a
 * boundary into them by copy, and steps them with the very transition
 * {@code advance} and the planner's search run. Nothing about the physics or
 * the schedule differs; only the ownership does.
 *
 * <p>The working copies are this rollout's, read in place through
 * {@link #client()}, {@link #publisher()} and {@link #server()}, and
 * overwritten by the next {@link #load} or {@link #tick}. After a refusal
 * the copies are partially mutated and hold no successor; a caller reloads
 * before stepping again. {@link #boundary()} publishes the current working
 * state as an immutable boundary when one is needed. One rollout serves one
 * thread.
 */
public final class Rollout {
	private final Simulator simulator;
	private final PlayerState client = new PlayerState();
	private final Publisher publisher = new Publisher();
	private final ServerPlayerState server = new ServerPlayerState();
	private HurtCause pendingHurt;
	private int completedActions;
	private boolean loaded;

	/** A rollout under the constructed schedule; see {@link Simulator#Simulator()}. */
	public Rollout() {
		this(new Simulator());
	}

	/** A rollout stepping through {@code simulator}, which it then owns for its lifetime. */
	public Rollout(final Simulator simulator) {
		this.simulator = Objects.requireNonNull(simulator, "simulator");
	}

	/** Load a boundary into the working copies by copy; the boundary is untouched. */
	public Rollout load(final SimulationState boundary) {
		Objects.requireNonNull(boundary, "boundary");
		boundary.client.copyInto(this.client);
		boundary.publisher.copyInto(this.publisher);
		boundary.server.copyInto(this.server);
		this.pendingHurt = boundary.pendingHurt;
		this.completedActions = boundary.completedActions;
		this.loaded = true;
		return this;
	}

	/**
	 * Advance the working copies by one action in place, handing each server
	 * write to {@code writes} when it is not null.
	 *
	 * @return whether the previous input caused a hit whose write the next
	 *     tick publishes, which is what {@link SimulationState#hasPendingEffect}
	 *     reports of the boundary this rollout now holds
	 * @throws IllegalStateException when nothing is loaded, or the last tick
	 *     refused and left no successor
	 * @throws UnimplementedMechanicException when the client tick leaves the
	 *     modelled slice or the server reaches a body it does not model
	 * @throws UnpredictedServerWriteException when the server would correct
	 *     the client instead of accepting the reported movement
	 * @throws PendingServerWriteException when the server's outcome is not
	 *     determined by the composed state
	 */
	public boolean tick(
	    final PlayerInput action, final SnapshotView world, final Consumer<? super ServerWrite> writes) {
		Objects.requireNonNull(action, "action");
		Objects.requireNonNull(world, "world");
		if (!this.loaded) {
			throw new IllegalStateException("the rollout holds no boundary to step");
		}
		this.loaded = false;
		this.pendingHurt = this.simulator.step(this.client, this.publisher, this.server, action, world,
		    this.pendingHurt, this.completedActions, writes == null ? DISCARD : writes);
		this.completedActions++;
		this.loaded = true;
		return this.pendingHurt != null;
	}

	/** {@link #tick(PlayerInput, SnapshotView, Consumer)} discarding the server writes. */
	public boolean tick(final PlayerInput action, final SnapshotView world) {
		return tick(action, world, null);
	}

	/** {@link Simulator#carry} for this rollout's simulator and its working boundary. */
	public void carry(final SnapshotView leaving) {
		this.simulator.carry(leaving, List.of());
		if (this.loaded) {
			this.client.carryPoseFitCertificate(leaving);
			this.server.carryPoseFitCertificate(leaving);
		}
	}

	/** The working client copy, read in place; the next load or tick overwrites it. */
	public PlayerState client() {
		return this.client;
	}

	/** The working publisher, read in place; the next load or tick overwrites it. */
	public Publisher publisher() {
		return this.publisher;
	}

	/** The working server copy, read in place; the next load or tick overwrites it. */
	public ServerPlayerState server() {
		return this.server;
	}

	/** Absolute action count: the loaded boundary's count plus successful ticks on this rollout. */
	public int completedActions() {
		return this.completedActions;
	}

	/** Whether the working copies hold a successor rather than a refused tick's remains. */
	public boolean holdsBoundary() {
		return this.loaded;
	}

	/** The working state as an immutable boundary, by copy; the rollout keeps stepping its own. */
	public SimulationState boundary() {
		if (!this.loaded) {
			throw new IllegalStateException("the rollout holds no boundary to publish");
		}
		// The step admitted what it loaded and trusts what it produced.
		return SimulationState.takeOwnership(
		    this.client.copy(), this.publisher.copy(), this.server.copy(), this.completedActions, this.pendingHurt);
	}

	/** Raw-bit identity of the working boundary, without publishing it; see {@link StateDigest#boundary}. */
	public long digest() {
		if (!this.loaded) {
			throw new IllegalStateException("the rollout holds no boundary to digest");
		}
		return StateDigest.boundary(this.client, this.publisher, this.server, this.completedActions, this.pendingHurt);
	}

	private static final Consumer<ServerWrite> DISCARD = write -> {};
}
