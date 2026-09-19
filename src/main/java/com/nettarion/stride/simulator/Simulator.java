package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.server.ServerTick;
import com.nettarion.stride.simulator.world.SnapshotView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Advances a client-and-server {@link SimulationState} by one action under the composed schedule.
 *
 * <p>Vanilla's server tick runs, in order, its packet drain, its entity tracker sample, its level tick
 * and its connection tick. One step here cuts that cycle after the drain: it finishes the server tick
 * that drained the previous action's packets (tracker sample, level tick, connection tick), runs the
 * client tick and drains the packets it publishes, and delivers the sample's entity data, any pending
 * hurt velocity and the connection tick's health packet to the client before its next tick. The
 * bundled captures record writes arriving at exactly that phase; a delivery can change the client's
 * trajectory. When the timing of a write is unknown the step refuses rather than assuming.
 *
 * <p>An instance retains scratch and is not thread-safe; use one per stepping thread. A refusal
 * produces no successor and leaves the input boundary untouched.
 */
public final class Simulator {
	private final Transition transition;

	private final ServerTick serverTick;

	/** A simulator under the composed schedule; see {@link #forObservedConnection()} for the alternative. */
	public Simulator() {
		this(new Transition());
	}

	private Simulator(final Transition transition) {
		this.transition = transition;
		this.serverTick = transition.serverTick();
	}

	/**
	 * A simulator for a consumer on a live connection that observes the server's writes itself. The
	 * composed step records the entity-data echo but does not apply it to the client copy, since the
	 * echo's real arrival tick is not the composed schedule's, and the hurt velocity write carries the
	 * server's velocity as of its delivery, after the client's next movement packet was drained, which
	 * is what a live server was observed to send. Health writes are delivered as scheduled.
	 */
	public static Simulator forObservedConnection() {
		return new Simulator(new Transition(ServerTick.observed()));
	}

	/**
	 * Carries this simulator's collision caches across a world republication.
	 *
	 * <p>The workspaces' retained collision spans and the pose-fit cache of each state in {@code kept}
	 * are keyed on the revisions of the sections they read in {@code leaving}; after this call a view
	 * that kept those sections honors them. Nothing is read for a cache entry that is absent, already
	 * carried, or stale in its own view. Call this with the view being left and the boundaries that
	 * will be stepped in its successor; an entry never carried is simply a cache miss there.
	 */
	public void carry(final SnapshotView leaving, final Iterable<SimulationState> kept) {
		Objects.requireNonNull(leaving, "leaving");
		this.transition.carry(leaving);
		for (SimulationState state : kept) {
			state.client.carryPoseFitCache(leaving);
			state.server.carryPoseFitCache(leaving);
		}
	}

	/**
	 * Advances one action under the composed schedule without mutating the input boundary.
	 *
	 * <p>The returned writes and confirmations describe this step's deliveries. A refusal produces no
	 * successor.
	 *
	 * @param before the boundary to step, including its publisher and any pending hurt
	 * @param action the keys held and the view rotation for this tick
	 * @param world compiled world facts for every query the step can reach
	 * @return the successor boundary with the writes and confirmations of its transition
	 * @throws UnimplementedMechanicException when the step leaves the admitted domain
	 * @throws PendingServerWriteException when the supplied state does not determine a server outcome
	 * @throws UnpredictedServerWriteException when the server would correct the reported movement
	 */
	public Step advance(final SimulationState before, final PlayerInput action, final SnapshotView world) {
		Objects.requireNonNull(before, "before");
		Objects.requireNonNull(action, "action");
		Objects.requireNonNull(world, "world");

		PlayerState client = before.clientState();
		Publisher publisher = before.publisher();
		ServerPlayerState server = before.serverState();
		FreezeSnapshot freezeBeforeAction = new FreezeSnapshot(client.ticksFrozen, client.frostSpeedTicks);
		List<ServerWrite> writes = new ArrayList<>(1);
		List<Confirmation> confirmations = new ArrayList<>(4);
		HurtCause pendingAfter = recordStep(client, publisher, server, action, world, before.pendingHurt,
		    before.completedActions, writes, confirmations);
		SimulationState after =
		    SimulationState.takeOwnership(client, publisher, server, before.completedActions + 1, pendingAfter);
		return new Step(after, confirmations, writes, freezeBeforeAction);
	}

	/** Runs one transition on owned states and appends its writes and confirmations to the caller's lists. */
	private HurtCause recordStep(final PlayerState client, final Publisher publisher, final ServerPlayerState server,
	    final PlayerInput action, final SnapshotView world, final HurtCause pendingHurt, final int completedActions,
	    final List<ServerWrite> writes, final List<Confirmation> confirmations) {
		FreezeSnapshot.requireValid(server.ticksFrozen, server.frostSpeedTicks);
		int firstWrite = writes.size();
		HurtCause pendingAfter =
		    step(client, publisher, server, action, world, pendingHurt, completedActions, writes::add);

		for (int index = firstWrite; index < writes.size(); index++) {
			if (writes.get(index) instanceof HurtMotionWrite motion) {
				HurtMotion payload = motion.event();
				confirmations.add(
				    new VelocityConfirmation(completedActions, payload.writeX(), payload.writeY(), payload.writeZ()));
			}
		}

		this.serverTick.recordPublications(completedActions, confirmations);
		for (int index = firstWrite; index < writes.size(); index++) {
			ServerWrite write = writes.get(index);
			if (write instanceof DamageWrite hit && hit.event().full()) {
				confirmations.add(new DamageConfirmation(completedActions, hit.event().cause()));
			}
		}

		return pendingAfter;
	}

	/**
	 * The composed transition on caller-owned states, advanced in place; see the class description for
	 * the phase order. A hit marks the server copy once and the next tracker sample publishes its
	 * velocity, so a hit dealt during this step is returned as the hurt the next step delivers.
	 *
	 * @param pendingHurt the hit the previous action caused, whose velocity write this step delivers,
	 *     or {@code null}
	 * @param completedActions actions applied before this one; names writes and causes
	 * @param writes receives every server write delivered during this step
	 * @return the hit this action caused whose write the next step delivers, or {@code null}
	 * @throws UnimplementedMechanicException when the step leaves the admitted domain
	 * @throws UnpredictedServerWriteException when the server would correct the reported movement
	 * @throws PendingServerWriteException when the composed state does not determine a server outcome
	 */
	HurtCause step(final PlayerState client, final Publisher publisher, final ServerPlayerState server,
	    final PlayerInput input, final SnapshotView world, final HurtCause pendingHurt, final int completedActions,
	    final Consumer<? super ServerWrite> writes) {
		// The server tick that drained the previous action's packets continues:
		// ChunkMap.tick's sample, ServerPlayer.tick, then tickPlayer's doTick.
		this.serverTick.beginTick(client, server);
		this.transition.publishServerEntity(server);
		HurtCause tickHurt = this.serverTick.advanceTicks(server, world, completedActions, writes);
		// The client tick, its packets, and the next server tick's packet drain.
		MovementPacket published = this.transition.tickClient(client, publisher, input, world);
		HurtCause packetHurt =
		    this.serverTick.handlePackets(client, published, server, input, world, publisher.inputChanged,
		        publisher.sprintingChanged, this.transition.startedFallFlying(), completedActions, writes);
		// Transport: the sample and the health packet reach the client before its
		// next tick, the phase the bundled captures record.
		this.serverTick.deliverPublications(
		    completedActions, client, server, pendingHurt, completedActions - 1, writes);
		// hurtMarked is one flag the next sample clears; a hit inside the
		// cooldown the earlier one installed is partial and marks nothing.
		return tickHurt != null ? tickHurt : packetHurt;
	}

	/**
	 * Runs a fixed action sequence from {@code start}, which carries any pending hurt.
	 *
	 * <p>The run owns one working client, publisher and server for the whole sequence and records the
	 * same writes and confirmations {@link #advance} would, without building intermediate boundaries.
	 *
	 * @throws PendingServerWriteException when the sequence ends while a hurt write is still pending
	 */
	public Run run(final SimulationState start, final List<PlayerInput> actions, final SnapshotView world) {
		Objects.requireNonNull(start, "start");
		Objects.requireNonNull(actions, "actions");
		Objects.requireNonNull(world, "world");
		if (actions.stream().anyMatch(Objects::isNull)) {
			throw new NullPointerException("actions contains null");
		}
		PlayerState client = start.clientState();
		Publisher publisher = start.publisher();
		ServerPlayerState server = start.serverState();
		HurtCause pending = start.pendingHurt;
		List<Confirmation> confirmations = new ArrayList<>();
		List<ServerWrite> events = new ArrayList<>();
		int completedActions = start.completedActions;
		for (PlayerInput action : actions) {
			pending = recordStep(
			    client, publisher, server, action, world, pending, completedActions++, events, confirmations);
		}
		if (pending != null) {
			throw new PendingServerWriteException();
		}
		return new Run(ServerWriteTimeline.of(events), confirmations, client, server);
	}

	/**
	 * {@link #run(SimulationState, List, SnapshotView)} from a boundary with nothing pending, built as
	 * {@code new SimulationState(client, server, 0)}.
	 */
	public Run run(final PlayerState client, final ServerPlayerState server, final List<PlayerInput> actions,
	    final SnapshotView world) {
		return run(new SimulationState(client, server, 0), actions, world);
	}

	/**
	 * One successor boundary with the confirmations its action caused and the server writes delivered
	 * during its transition. Both lists are immutable copies.
	 *
	 * @param state the successor boundary
	 * @param confirmations values the server published during this step, for matching observed packets
	 * @param writes every server write delivered during this step, in delivery order
	 * @param freezeBeforeAction the client's freeze values before the client tick
	 */
	public record Step(SimulationState state, List<Confirmation> confirmations, List<ServerWrite> writes,
	    FreezeSnapshot freezeBeforeAction) {
		/** Copies both lists and rejects null components. */
		public Step {
			Objects.requireNonNull(state, "state");
			confirmations = List.copyOf(confirmations);
			writes = List.copyOf(writes);
			Objects.requireNonNull(freezeBeforeAction, "freezeBeforeAction");
		}

		/** The hurt velocity writes among {@link #writes()}. */
		public List<HurtMotionWrite> hurtMotion() {
			List<HurtMotionWrite> result = new ArrayList<>();
			for (ServerWrite write : this.writes) {
				if (write instanceof HurtMotionWrite motion) {
					result.add(motion);
				}
			}
			return List.copyOf(result);
		}

		/** The hits among {@link #writes()}, in the order the server dealt them. */
		public List<DamageWrite> damage() {
			List<DamageWrite> result = new ArrayList<>();
			for (ServerWrite write : this.writes) {
				if (write instanceof DamageWrite hit) {
					result.add(hit);
				}
			}
			return List.copyOf(result);
		}

		/** Whether the server dealt a hit, without allocating a filtered list. */
		public boolean hasDamage() {
			for (int index = 0; index < this.writes.size(); index++) {
				if (this.writes.get(index) instanceof DamageWrite) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * The client's freeze values before one client tick, in ticks.
	 *
	 * @param ticksFrozen the client's {@code Entity.ticksFrozen}
	 * @param frostSpeedTicks the client's frost speed modifier, also in ticks
	 */
	public record FreezeSnapshot(int ticksFrozen, int frostSpeedTicks) {
		/** Rejects values outside {@code [0, DEFAULT_TICKS_REQUIRED_TO_FREEZE]}. */
		public FreezeSnapshot {
			requireValid(ticksFrozen, frostSpeedTicks);
		}

		private static void requireValid(final int ticksFrozen, final int frostSpeedTicks) {
			if (ticksFrozen < 0 || ticksFrozen > ServerPlayerState.DEFAULT_TICKS_REQUIRED_TO_FREEZE
			    || frostSpeedTicks < 0 || frostSpeedTicks > ServerPlayerState.DEFAULT_TICKS_REQUIRED_TO_FREEZE) {
				throw new IllegalArgumentException("freeze projection is out of range");
			}
		}
	}

	/**
	 * The result of {@link Simulator#run}: the ordered writes, the confirmations and the final states.
	 * Entity-data confirmations are not writes. The state accessors return copies.
	 *
	 * @param timeline every server write of the run, in delivery order
	 * @param confirmations every confirmation of the run, in action order
	 * @param clientState the client's copy after the last action
	 * @param serverState the server's copy after the last action
	 */
	public record Run(ServerWriteTimeline timeline, List<Confirmation> confirmations, PlayerState clientState,
	    ServerPlayerState serverState) {
		/** Copies the confirmations and both states. */
		public Run {
			Objects.requireNonNull(timeline, "timeline");
			confirmations = List.copyOf(confirmations);
			clientState = Objects.requireNonNull(clientState, "clientState").copy();
			serverState = Objects.requireNonNull(serverState, "serverState").copy();
		}

		/** A copy of the client's final state. */
		@Override
		public PlayerState clientState() {
			return this.clientState.copy();
		}

		/** A copy of the server's final state. */
		@Override
		public ServerPlayerState serverState() {
			return this.serverState.copy();
		}
	}

	/**
	 * A value the server published during a step, recorded so a consumer can match it against the
	 * packets it later observes. {@link #causeAction()} names the action that caused it. Matching a
	 * later observation does not show that applying it at a different time would preserve the trajectory.
	 */
	public sealed interface Confirmation permits SprintConfirmation, SwimmingConfirmation, PoseConfirmation,
	    VelocityConfirmation, FreezeConfirmation, ImpulseConfirmation, DamageConfirmation {
		/** The index of the action that caused this publication. */
		int causeAction();
	}

	/**
	 * A full hit the server dealt at the causing action, whose damage-event packet names the cause. A
	 * partial hit sends only a health packet and is not confirmed here.
	 *
	 * @param causeAction the action whose transition dealt the hit
	 * @param cause what dealt it
	 */
	public record DamageConfirmation(int causeAction, HurtCause cause) implements Confirmation {
		/** Rejects a null cause. */
		public DamageConfirmation {
			Objects.requireNonNull(cause, "cause");
		}
	}

	/**
	 * The server's sprint flag as its tracker published it after the causing action.
	 *
	 * @param causeAction the action after which the tracker sampled the flag
	 * @param sprinting the published flag
	 */
	public record SprintConfirmation(int causeAction, boolean sprinting) implements Confirmation {}

	/**
	 * The server's swimming flag as its tracker published it after the causing action.
	 *
	 * @param causeAction the action after which the tracker sampled the flag
	 * @param swimming the published flag
	 */
	public record SwimmingConfirmation(int causeAction, boolean swimming) implements Confirmation {}

	/**
	 * The server's pose as its tracker published it after the causing action.
	 *
	 * @param causeAction the action after which the tracker sampled the pose
	 * @param pose the published pose
	 */
	public record PoseConfirmation(int causeAction, PlayerState.Pose pose) implements Confirmation {
		/** Rejects a null pose. */
		public PoseConfirmation {
			Objects.requireNonNull(pose, "pose");
		}
	}

	/**
	 * The decoded velocity of a hurt velocity packet, in blocks per tick.
	 *
	 * @param causeAction the action whose hit the packet publishes
	 * @param x the decoded X component
	 * @param y the decoded Y component
	 * @param z the decoded Z component
	 */
	public record VelocityConfirmation(int causeAction, double x, double y, double z) implements Confirmation {}

	/**
	 * The server's frozen tick count as its tracker published it after the causing action.
	 *
	 * @param causeAction the action after which the tracker sampled the count
	 * @param ticksFrozen the published count, in ticks
	 */
	public record FreezeConfirmation(int causeAction, int ticksFrozen) implements Confirmation {
		/** Rejects a count outside {@code [0, DEFAULT_TICKS_REQUIRED_TO_FREEZE]}. */
		public FreezeConfirmation {
			if (ticksFrozen < 0 || ticksFrozen > ServerPlayerState.DEFAULT_TICKS_REQUIRED_TO_FREEZE) {
				throw new IllegalArgumentException("frozen ticks are out of range");
			}
		}
	}

	/**
	 * An external actor's velocity operation predicted at its causal action, which its later packet or
	 * actor tick confirms rather than applies again. An add carries the packet's full doubles; a
	 * replacement is a decoded motion packet. Nothing predicts one yet; the shape exists so an observed
	 * impulse with no prediction is unattributed rather than unknown.
	 *
	 * @param causeAction the action at which the operation was predicted
	 * @param actor the actor that applies it
	 * @param operation the operation, never {@code MOVE}
	 * @param x the X component, in blocks per tick
	 * @param y the Y component, in blocks per tick
	 * @param z the Z component, in blocks per tick
	 */
	public record ImpulseConfirmation(int causeAction, ExternalActor actor, ImpulseWrite.Operation operation, double x,
	    double y, double z) implements Confirmation {
		/** Rejects a displacement operation or a non-finite component. */
		public ImpulseConfirmation {
			Objects.requireNonNull(actor, "actor");
			Objects.requireNonNull(operation, "operation");
			if (operation == ImpulseWrite.Operation.MOVE) {
				throw new IllegalArgumentException("a collision-resolved displacement has no velocity to confirm");
			}
			if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
				throw new IllegalArgumentException("impulse must be finite");
			}
		}
	}
}
