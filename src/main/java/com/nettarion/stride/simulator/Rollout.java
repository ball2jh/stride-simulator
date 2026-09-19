package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.world.SnapshotView;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The composed transition on one owned working boundary, advanced in place.
 *
 * <p>{@link Simulator#advance} builds an immutable boundary per action and copies every state it hands
 * out. A consumer that ticks many short sequences and reads a few fields of each result pays for
 * those copies without using them. A rollout instead holds one client, one publisher and one server
 * copy, loads a boundary into them by copy, and steps them with the very transition {@code advance}
 * runs. Nothing about the physics or the schedule differs; only the ownership does.
 *
 * <p>The working copies belong to this rollout: {@link #client()}, {@link #publisher()} and
 * {@link #server()} return them in place, and the next {@link #load} or {@link #tick} overwrites them.
 * After a refusal the copies are partially mutated and hold no successor; reload before stepping
 * again. {@link #boundary()} copies the working state into an immutable boundary. One rollout serves
 * one thread.
 */
public final class Rollout {
	private static final Consumer<ServerWrite> DISCARD = write -> {};

	private final Simulator simulator;

	private final PlayerState client = new PlayerState();

	private final Publisher publisher = new Publisher();

	private final ServerPlayerState server = new ServerPlayerState();

	private HurtCause pendingHurt;

	private int completedActions;

	private boolean loaded;

	/** A rollout under the composed schedule; see {@link Simulator#Simulator()}. */
	public Rollout() {
		this(new Simulator());
	}

	/** A rollout stepping through {@code simulator}, which it then owns for its lifetime. */
	public Rollout(final Simulator simulator) {
		this.simulator = Objects.requireNonNull(simulator, "simulator");
	}

	/** Loads {@code boundary} into the working copies by copy; the boundary is untouched. */
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
	 * Advances the working copies by one action in place, handing each server write to {@code writes}
	 * when it is not null.
	 *
	 * @return whether this action caused a hit whose write the next tick delivers, which is what
	 *     {@link SimulationState#hasPendingHurt} reports of the boundary this rollout now holds
	 * @throws IllegalStateException when nothing is loaded, or the last tick refused and left no successor
	 * @throws UnimplementedMechanicException when the step leaves the admitted domain
	 * @throws UnpredictedServerWriteException when the server would correct the reported movement
	 * @throws PendingServerWriteException when the composed state does not determine a server outcome
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
			this.client.carryPoseFitCache(leaving);
			this.server.carryPoseFitCache(leaving);
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

	/** The loaded boundary's action count plus the successful ticks since. */
	public int completedActions() {
		return this.completedActions;
	}

	/** Whether the working copies hold a successor rather than a refused tick's remains. */
	public boolean isLoaded() {
		return this.loaded;
	}

	/** The working state as an immutable boundary, by copy; the rollout keeps stepping its own. */
	public SimulationState boundary() {
		if (!this.loaded) {
			throw new IllegalStateException("the rollout holds no boundary to publish");
		}
		// The step validated what it loaded and trusts what it produced.
		return SimulationState.takeOwnership(
		    this.client.copy(), this.publisher.copy(), this.server.copy(), this.completedActions, this.pendingHurt);
	}

	/** The digest of the working boundary, without copying it; see {@link StateDigest#boundary}. */
	public long digest() {
		if (!this.loaded) {
			throw new IllegalStateException("the rollout holds no boundary to digest");
		}
		return StateDigest.boundary(this.client, this.publisher, this.server, this.completedActions, this.pendingHurt);
	}
}
