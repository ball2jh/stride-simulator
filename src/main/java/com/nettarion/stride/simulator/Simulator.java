package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.server.ServerTick;
import com.nettarion.stride.simulator.world.SnapshotView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One deterministic client-and-server transition under the constructed publication schedule.
 *
 * <p>One {@code MinecraftServer} tick runs, in the pinned source's order, its packet drain, its
 * entity tracker sample, its level tick and its connection tick. The composed step cuts that
 * cycle after the drain: it finishes the server tick that drained the previous action's packets
 * (sample, level tick, doTick), runs the client tick and drains its packets, and delivers the
 * sample and the doTick's health packet before the next client tick. Where the client applies a
 * server write is a measured transport fact, not a source one: the live captures
 * (live-pose-echo-boundary, live-elytra-glide, live-powder-snow-crossing) show a write the
 * packet drain caused applied before client tick N + 2 and one the doTick caused before N + 3,
 * which is exactly this cut. Delivery can change the client trajectory. Unknown timing refuses
 * rather than assuming identity writes. An instance retains scratch and is not thread-safe. See
 * the package's admission contract.
 */
public final class Simulator {
	/** A simulator under the fixed publication schedule; see {@link #forObservedConnection()} for the alternative. */
	public Simulator() {}

	private final Transition transition = new Transition();
	private final ServerTick serverTick = this.transition.serverTick();
	/** The packet form the last {@link #step} published. */
	private MovementPacket published = MovementPacket.NONE;

	/**
	 * A simulator for a consumer on a free-running connection, which observes
	 * the server's publications rather than taking the constructed schedule's
	 * word for them: the composed step records the entity-data echo but does
	 * not apply it to the client copy, since the echo's arrival tick is not the
	 * schedule's there, and the hurt-marked velocity write carries the server's
	 * velocity as of its delivery, after the client's next movement packet was
	 * drained, which is what a free-running server was observed to send. Health
	 * publications are delivered as scheduled.
	 */
	public static Simulator forObservedConnection() {
		Simulator simulator = new Simulator();
		simulator.serverTick.deliverEchoes(false);
		simulator.serverTick.hurtMotionAfterNextPacket(true);
		return simulator;
	}

	/**
	 * Carry this simulator's retained proofs across a publication: its
	 * workspaces' collision spans, and the pose-fit certificate of each state
	 * in {@code kept}, are keyed on the revisions of the sections they read in
	 * {@code leaving}, so a view that kept those sections honors them. A
	 * pilot calls this with the view it is leaving and the boundaries it
	 * keeps; nothing is read for a proof that is absent, already carried, or
	 * stale in its own view. A proof never carried is a miss elsewhere.
	 */
	public void carry(final SnapshotView leaving, final Iterable<SimulationState> kept) {
		Objects.requireNonNull(leaving, "leaving");
		this.transition.carry(leaving);
		for (SimulationState state : kept) {
			state.client.carryPoseFitCache(leaving);
			state.server.carryPoseFitCache(leaving);
		}
	}

	/** The composed state at a packet boundary whose server copy is constructed from {@code server}. */
	public SimulationState start(final PlayerState client, final PlayerState server) {
		return new SimulationState(client, ServerPlayerState.atBoundary(server), 0);
	}

	/** The composed state at a packet boundary whose server facts were observed. */
	public SimulationState start(final PlayerState client, final ServerPlayerState server) {
		return new SimulationState(client, server, 0);
	}

	/**
     * Advances one action under the fixed publication schedule without mutating the input boundary.
     *
     * <p>The returned writes and confirmations describe this transition's publications. A refusal
     * produces no successor. Use a separate simulator instance on each stepping thread.
     *
     * @param before complete causal boundary, including the publisher and pending server effect
     * @param action input buttons and view rotation for this action
     * @param world compiled world facts for every query the transition can reach
     * @return successor boundary and the writes/confirmations observed during its transition
     * @throws UnimplementedMechanicException if required mechanics or world facts are unsupported
     * @throws PendingServerWriteException if supplied state cannot determine a server outcome
     * @throws UnpredictedServerWriteException if the server would correct the reported movement
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
		    before.completedActions, writes, writes::add, confirmations);
		SimulationState after =
		    SimulationState.takeOwnership(client, publisher, server, before.completedActions + 1, pendingAfter);
		return new Step(after, confirmations, writes, freezeBeforeAction);
	}

	/** Records one transition into the caller's accumulated evidence, on owned states. */
	private HurtCause recordStep(final PlayerState client, final Publisher publisher, final ServerPlayerState server,
	    final PlayerInput action, final SnapshotView world, final HurtCause pendingHurt, final int completedActions,
	    final List<ServerWrite> writes, final Consumer<ServerWrite> appendWrite,
	    final List<Confirmation> confirmations) {
		FreezeSnapshot.requireValid(server.ticksFrozen, server.frostSpeedTicks);
		int firstWrite = writes.size();
		HurtCause pendingAfter =
		    step(client, publisher, server, action, world, pendingHurt, completedActions, appendWrite);

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
	 * The composed step on caller-owned states, advanced in place. The server
	 * tick that drained the previous action's packets continues: its entity
	 * tracker samples the copy (nothing touched it since the drain, so the
	 * sample taken here is {@code ChunkMap.tick}'s), its level tick counts the
	 * hit cooldown down, its connection tick runs {@code doTick}. Then the
	 * client ticks and its publisher selects the movement packet, and the next
	 * server tick's packet processor drains the input, sprint, glide and
	 * movement packets. Finally the sample's entity data, the hurt-marked
	 * velocity of a hit the sample saw, and the doTick's health packet are
	 * delivered to the client, before its next tick: the transport phase the
	 * live captures measure. Every hit the server dealt is emitted at this
	 * input. This is the one transition; {@link #advance} records it and the
	 * planner's search expands it, and neither has a rule of its own.
	 *
	 * <p>A hit marks the copy once; the next sample sees the mark and
	 * publishes the velocity. A hit the previous action's packet drain or the
	 * server tick after it dealt is therefore the pending hit this step
	 * publishes.
	 *
	 * @param pendingHurt the hit the previous action caused, in its packet
	 *     drain or the server tick that followed, whose write this step
	 *     publishes, or {@code null}
	 * @param server the server's copy, admitted through
	 *     {@link ServerPlayerState#requireValid()} at its boundary; the step
	 *     trusts what it produced itself and does not re-derive that admission
	 * @param completedActions inputs applied before this one; names the write
	 *     and the cause in refusals
	 * @param writes receives the server writes delivered during this step:
	 *     each hit as a {@link DamageWrite}, the sampled metadata, the
	 *     published {@link HurtMotionWrite}, and the health packet
	 * @return the hit this action caused that marked, in the server tick
	 *     finished here or in its own packet drain, whose write the next step
	 *     publishes, or {@code null}. While a write pends the server is inside
	 *     the hit cooldown, and a second hit there does not mark again.
	 * @throws UnimplementedMechanicException when the client tick leaves the
	 *     modeled slice or the server reaches a body it does not model
	 * @throws UnpredictedServerWriteException when the server would correct
	 *     the client instead of accepting the reported movement
	 * @throws PendingServerWriteException when the server's outcome is not
	 *     determined by the composed state
	 */
	public HurtCause step(final PlayerState client, final Publisher publisher, final ServerPlayerState server,
	    final PlayerInput input, final SnapshotView world, final HurtCause pendingHurt, final int completedActions,
	    final Consumer<? super ServerWrite> writes) {
		// The server tick that drained the previous action's packets continues:
		// ChunkMap.tick's sample, ServerPlayer.tick, then tickPlayer's doTick.
		this.serverTick.beginTick(client, server);
		this.transition.publishServerEntity(server);
		HurtCause tickHurt = this.serverTick.advanceTicks(server, world, completedActions, writes);
		// The client tick, its packets, and the next server tick's packet drain.
		this.published = this.transition.tickClient(client, publisher, input, world);
		HurtCause packetHurt =
		    this.serverTick.handlePackets(client, this.published, server, input, world, publisher.inputChanged,
		        publisher.sprintingChanged, this.transition.startedFallFlying(), completedActions, writes);
		// Transport: the sample and the health packet reach the client before its
		// next tick, the phase the live captures measure.
		this.serverTick.deliverPublications(
		    completedActions, client, server, pendingHurt, completedActions - 1, writes);
		// hurtMarked is one flag the next sample clears; a hit inside the
		// cooldown the earlier one installed is partial and marks nothing.
		return tickHurt != null ? tickHurt : packetHurt;
	}

	/** Run one fixed action sequence from a constructed boundary. */
	public Run run(final PlayerState clientStart, final PlayerState serverStart, final List<PlayerInput> actions,
	    final SnapshotView world) {
		return run(clientStart, ServerPlayerState.atBoundary(serverStart), Optional.empty(), actions, world);
	}

	/** Run one fixed action sequence from observed server facts. */
	public Run run(final PlayerState clientStart, final ServerPlayerState serverStart, final List<PlayerInput> actions,
	    final SnapshotView world) {
		return run(clientStart, serverStart, Optional.empty(), actions, world);
	}

	/** {@link #run(PlayerState, ServerPlayerState, Optional, List, SnapshotView)} from a constructed boundary. */
	public Run run(final PlayerState clientStart, final PlayerState serverStart, final Optional<HurtCause> pendingHurt,
	    final List<PlayerInput> actions, final SnapshotView world) {
		return run(clientStart, ServerPlayerState.atBoundary(serverStart), pendingHurt, actions, world);
	}

	/**
	 * Run from an explicit composed boundary.
	 *
	 * <p>A present {@code pendingHurt} means the preceding action already
	 * caused that hit, whose velocity publication belongs after the first
	 * action in {@code actions}. This fact is part of the causal boundary;
	 * reconstructing from the two movement states alone would erase it.
	 *
	 * <p>The run owns one working client, publisher, and server for the sequence.
	 * It records the same transition and evidence as {@link #advance} without
	 * constructing immutable intermediate states.
	 */
	public Run run(final PlayerState clientStart, final ServerPlayerState serverStart,
	    final Optional<HurtCause> pendingHurt, final List<PlayerInput> actions, final SnapshotView world) {
		Objects.requireNonNull(clientStart, "clientStart");
		Objects.requireNonNull(serverStart, "serverStart");
		Objects.requireNonNull(actions, "actions");
		Objects.requireNonNull(world, "world");
		if (actions.stream().anyMatch(Objects::isNull)) {
			throw new NullPointerException("actions contains null");
		}
		Objects.requireNonNull(pendingHurt, "pendingHurt");
		PlayerState client = clientStart.copy();
		Publisher publisher = Publisher.atBoundary(clientStart);
		ServerPlayerState server = serverStart.copy();
		// Admission once at the boundary; every later value is this run's own.
		server.requireValid();
		HurtCause pending = pendingHurt.orElse(null);
		List<Confirmation> confirmations = new ArrayList<>();
		List<ServerWrite> events = new ArrayList<>();
		Consumer<ServerWrite> appendWrite = events::add;
		int completedActions = 0;
		for (PlayerInput action : actions) {
			pending = recordStep(client, publisher, server, action, world, pending, completedActions++, events,
			    appendWrite, confirmations);
		}
		if (pending != null) {
			throw new PendingServerWriteException();
		}
		return new Run(ServerWriteTimeline.of(events), confirmations, client, server);
	}

	/**
	 * One scheduled composite successor, the publication evidence caused by
	 * its action, and the server writes it delivered. All lists are immutable
	 * copies.
	 */
	public record Step(SimulationState state, List<Confirmation> confirmations, List<ServerWrite> writes,
	    FreezeSnapshot freezeBeforeAction) {
		public Step {
			Objects.requireNonNull(state, "state");
			confirmations = List.copyOf(confirmations);
			writes = List.copyOf(writes);
			Objects.requireNonNull(freezeBeforeAction, "freezeBeforeAction");
		}

		/** The hurt-marked velocity writes among {@link #writes()}. */
		public List<HurtMotionWrite> hurtMotion() {
			return this.writes.stream()
			    .filter(HurtMotionWrite.class ::isInstance)
			    .map(HurtMotionWrite.class ::cast)
			    .toList();
		}

		/** The hits among {@link #writes()}, in the order the server dealt them. */
		public List<DamageWrite> damage() {
			return this.writes.stream().filter(DamageWrite.class ::isInstance).map(DamageWrite.class ::cast).toList();
		}

		/** Whether the server dealt a hit, without allocating a filtered event list. */
		public boolean hasDamage() {
			for (int index = 0; index < this.writes.size(); index++) {
				if (this.writes.get(index) instanceof DamageWrite) return true;
			}
			return false;
		}
	}

	/** Server-owned freeze values projected before one client movement tick. */
	public record FreezeSnapshot(int ticksFrozen, int frostSpeedTicks) {
		public FreezeSnapshot {
			requireValid(ticksFrozen, frostSpeedTicks);
		}

		private static void requireValid(final int ticksFrozen, final int frostSpeedTicks) {
			if (ticksFrozen < 0 || ticksFrozen > 140 || frostSpeedTicks < 0 || frostSpeedTicks > 140) {
				throw new IllegalArgumentException("freeze projection is out of range");
			}
		}
	}

	/** Complete fixed-plan result. Entity-data confirmations are not writes. */
	public record Run(ServerWriteTimeline timeline, List<Confirmation> confirmations, PlayerState clientState,
	    ServerPlayerState serverState) {
		public Run {
			Objects.requireNonNull(timeline, "timeline");
			confirmations = List.copyOf(confirmations);
			clientState = Objects.requireNonNull(clientState, "clientState").copy();
			serverState = Objects.requireNonNull(serverState, "serverState").copy();
		}

		@Override
		public PlayerState clientState() {
			return this.clientState.copy();
		}

		@Override
		public ServerPlayerState serverState() {
			return this.serverState.copy();
		}
	}

	/**
	 * A value published at the declared boundary, retained as reconciliation evidence.
	 * The index names its scheduled action. Matching a later observation does not
	 * prove that applying it at a different time would preserve the trajectory.
	 */
	public sealed interface Confirmation permits SprintConfirmation, SwimmingConfirmation, PoseConfirmation,
	    VelocityConfirmation, FreezeConfirmation, ImpulseConfirmation, DamageConfirmation {
		int causeAction();
	}

	/**
	 * A full hit the server dealt at the causing action, whose damage-event
	 * packet names the cause. A partial hit sends no damage event and only
	 * a health packet, so it is not confirmed here.
	 */
	public record DamageConfirmation(int causeAction, HurtCause cause) implements Confirmation {
		public DamageConfirmation {
			Objects.requireNonNull(cause, "cause");
		}
	}

	/** Owner-local sprint value after the causing action. */
	public record SprintConfirmation(int causeAction, boolean sprinting) implements Confirmation {}

	/** Owner-local swimming value after the causing action. */
	public record SwimmingConfirmation(int causeAction, boolean swimming) implements Confirmation {}

	/** Owner-local pose value after the causing action. */
	public record PoseConfirmation(int causeAction, PlayerState.Pose pose) implements Confirmation {
		public PoseConfirmation {
			Objects.requireNonNull(pose, "pose");
		}
	}

	/** Decoded server velocity that may publish one caused hit. */
	public record VelocityConfirmation(int causeAction, double x, double y, double z) implements Confirmation {}

	/** Server-owned frozen-tick value projected into later client movement. */
	public record FreezeConfirmation(int causeAction, int ticksFrozen) implements Confirmation {
		public FreezeConfirmation {
			if (ticksFrozen < 0 || ticksFrozen > 140) {
				throw new IllegalArgumentException("frozen ticks are out of range");
			}
		}
	}

	/**
	 * An external actor's velocity operation predicted at its causal action,
	 * which its later packet or actor tick confirms rather than applies again.
	 * An add carries the packet's full doubles; a replacement is a decoded
	 * motion packet. Nothing predicts one yet; the ledger names the shape so an
	 * observed impulse with no prediction is unattributed rather than unknown.
	 */
	public record ImpulseConfirmation(int causeAction, ExternalActor actor, ImpulseWrite.Operation operation, double x,
	    double y, double z) implements Confirmation {
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
