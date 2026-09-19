package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.server.ServerTick;
import com.nettarion.stride.simulator.world.SnapshotView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A branch-owned simulation with explicitly ordered client ticks, server ticks and packet delivery.
 *
 * <p>Each method is one scheduled event. Transport preserves FIFO order in each direction;
 * a caller advances neither clock by delivering a packet. The pinned server tick is, in order,
 * {@link #deliverServerbound} for every queued packet, {@link #publishServerEntity},
 * {@link #tickLevel}, {@link #tickConnection}; the client applies {@link #deliverClientbound}
 * before {@link #tickClient}. The composed {@link Simulator} is the schedule
 * {@link #publishServerEntity}, {@link #tickLevel}, {@link #tickConnection}, {@link #tickClient},
 * drain serverbound, drain clientbound, which the live captures measure. Forks retain exact payloads,
 * listener baselines, publisher state and independent client/server world overlays.
 * Every phase is a {@link Transition} method, the same body {@link Simulator} runs fused, so
 * the two runners share every line of arithmetic; the ordinary {@link Simulator} remains the
 * allocation-conscious fixed-schedule entry point.
 * This runner admits a loaded survival player at normal server tick rate; external actors,
 * creative ability changes and unknown authoritative boundaries still refuse. After an exception the caller must discard the mutated branch; retain a fork before speculative events.
 */
public final class ScheduledSimulation {
	private final PlayerState client;
	private final ServerPlayerState server;
	private final Publisher publisher;
	private final SnapshotView clientWorld;
	private final SnapshotView serverWorld;
	private final Transition transition = new Transition();
	private final ServerTick serverTick = this.transition.serverTick();
	private final ArrayDeque<Serverbound> serverbound = new ArrayDeque<>();
	private final ArrayDeque<Clientbound> clientbound = new ArrayDeque<>();
	private final ArrayList<ServerWrite> writes = new ArrayList<>();
	private final Boolean mayInteract;
	private int completedActions;
	private HurtCause pendingHurt;
	private int hurtAction = -1;
	private boolean receivedMovementThisTick;

	/** Construct from explicit player/publisher facts and the world's observed boundary. */
	public ScheduledSimulation(final SimulationState state, final SnapshotView world) {
		this(state, world, null);
	}

	/** Construct with an explicit uniform interaction permission over this captured region. */
	public ScheduledSimulation(final SimulationState state, final SnapshotView world, final Boolean mayInteract) {
		this.mayInteract = mayInteract;
		Objects.requireNonNull(state);
		this.client = state.clientState();
		this.server = state.serverState();
		this.publisher = state.publisher();
		this.completedActions = state.completedActions();
		this.pendingHurt = state.pendingHurt().orElse(null);
		this.hurtAction = this.completedActions - 1;
		this.clientWorld = world.fork();
		this.serverWorld = world.fork();
		bindWorldChanges();
	}

	private ScheduledSimulation(final ScheduledSimulation source) {
		this.mayInteract = source.mayInteract;
		this.client = source.client.copy();
		this.server = source.server.copy();
		this.publisher = source.publisher.copy();
		this.clientWorld = source.clientWorld.fork();
		this.serverWorld = source.serverWorld.fork();
		this.completedActions = source.completedActions;
		this.pendingHurt = source.pendingHurt;
		this.hurtAction = source.hurtAction;
		this.receivedMovementThisTick = source.receivedMovementThisTick;
		this.serverbound.addAll(source.serverbound);
		this.clientbound.addAll(source.clientbound);
		this.writes.addAll(source.writes);
		bindWorldChanges();
	}

	private void bindWorldChanges() {
		this.serverTick.enableWorldChanges(this.serverWorld, this.mayInteract, this::boundary, write -> {
			if (write instanceof BlockUpdateWrite block)
				this.clientbound.add(new BlockUpdate(block));
			else
				this.writes.add(write);
		});
	}

	/** An independent branch, including every packet already in transit. */
	public ScheduledSimulation fork() {
		return new ScheduledSimulation(this);
	}
	/** Exact retained branch equality on a shared compiled world, without serializing its dense cells. */
	public static boolean sameState(final ScheduledSimulation a, final ScheduledSimulation b) {
		return a.receivedMovementThisTick == b.receivedMovementThisTick && a.completedActions == b.completedActions
		    && a.hurtAction == b.hurtAction && a.pendingHurt == b.pendingHurt
		    && Objects.equals(a.mayInteract, b.mayInteract) && PlayerState.rawEquals(a.client, b.client)
		    && ServerPlayerState.rawEquals(a.server, b.server) && Publisher.rawEquals(a.publisher, b.publisher)
		    && List.copyOf(a.serverbound).equals(List.copyOf(b.serverbound))
		    && List.copyOf(a.clientbound).equals(List.copyOf(b.clientbound)) && a.writes.equals(b.writes)
		    && SnapshotView.sameWorld(a.clientWorld, b.clientWorld)
		    && SnapshotView.sameWorld(a.serverWorld, b.serverWorld);
	}
	/** Returns a defensive copy of this branch's client state. */
	public PlayerState clientState() {
		return this.client.copy();
	}
	/** Returns a defensive copy of this branch's authoritative server state. */
	public ServerPlayerState serverState() {
		return this.server.copy();
	}
	/** Returns a defensive copy of the client's packet-publication history. */
	public Publisher publisher() {
		return this.publisher.copy();
	}
	/** Returns an independent overlay fork of the world visible to the client. */
	public SnapshotView clientWorld() {
		return this.clientWorld.fork();
	}
	/** Returns an independent overlay fork of the world visible to the server. */
	public SnapshotView serverWorld() {
		return this.serverWorld.fork();
	}
	/** Immutable transport payloads and attribution retained by this branch, for exact evidence. */
	public Transport transportState() {
		return new Transport(List.copyOf(this.serverbound), List.copyOf(this.clientbound), this.pendingHurt,
		    this.hurtAction, this.mayInteract, this.receivedMovementThisTick);
	}
	/** Immutable transport evidence that an independent observer can also construct from decoded packets. */
	public record Transport(List<Serverbound> serverbound, List<Clientbound> clientbound, HurtCause pendingHurt,
	    int hurtAction, Boolean mayInteract, boolean receivedMovementThisTick) {
		public Transport {
			serverbound = List.copyOf(serverbound);
			clientbound = List.copyOf(clientbound);
		}
	}
	/** Returns the absolute number of client ticks completed by this branch. */
	public int completedActions() {
		return this.completedActions;
	}
	/** Returns the number of queued client-to-server messages. */
	public int pendingServerboundPackets() {
		return this.serverbound.size();
	}
	/** Returns the number of queued server-to-client messages. */
	public int pendingClientboundPackets() {
		return this.clientbound.size();
	}
	/** Returns an immutable snapshot of server writes recorded by this branch. */
	public List<ServerWrite> writes() {
		return List.copyOf(this.writes);
	}

	/** Advance the client and queue its commands and movement, retaining publication-time values. */
	public void tickClient(final PlayerInput input) {
		if (this.client.mayfly)
			throw new UnimplementedMechanicException(
			    RefusalCause.UNMODELED_SCHEDULE, "scheduled creative ability packets are not modeled");
		MovementPacket form = this.transition.tickClient(this.client, this.publisher, input, this.clientWorld);
		if (this.transition.startedFallFlying()) this.serverbound.add(new Glide());
		if (this.publisher.inputChanged) this.serverbound.add(new Input(input));
		if (this.publisher.sprintingChanged) this.serverbound.add(new Sprint(this.client.sprinting));
		if (form != MovementPacket.NONE) this.serverbound.add(MovePacket.of(form, this.client));
		this.serverbound.add(new ClientTickEnd());
		this.completedActions++;
	}

	/** Advance ServerPlayer.tick independently of the connection's doTick. */
	public void tickLevel() {
		this.transition.tickLevel(this.server);
	}

	/** Advance the connection, queuing its health publication before subsequent packet handlers. */
	public void tickConnection() {
		ServerTick.Effects effects = this.transition.tickConnection(this.server, this.serverWorld);
		recordEffects(effects.hurt().orElse(null), effects.damage());
		HealthWrite health = this.transition.healthPublication(boundary(), this.client);
		if (health != null)
			this.clientbound.add(new Health(health.health(), health.foodLevel(), health.saturationLevel()));
	}

	/** Sample ServerEntity now; subsequent server movement cannot alter the queued payload. */
	public void publishServerEntity() {
		this.transition.publishServerEntity(this.server);
		EntityDataWrite data = this.transition.dataPublication(boundary(), this.client);
		if (data != null) this.clientbound.add(new Data(data));
		if (this.pendingHurt != null) {
			this.clientbound.add(new Motion(this.pendingHurt, this.hurtAction, this.server.deltaMovementX,
			    this.server.deltaMovementY, this.server.deltaMovementZ));
			this.pendingHurt = null;
		}
	}

	/** Deliver exactly the oldest serverbound packet; return false when the queue is empty. */
	public boolean deliverServerbound() {
		Serverbound packet = this.serverbound.poll();
		if (packet == null) return false;
		switch (packet) {
			case ClientTickEnd ignored -> {
				this.serverTick.handleClientTickEnd(this.server, this.receivedMovementThisTick);
				this.receivedMovementThisTick = false;
			}
			case Input input -> this.transition.handleInput(this.server, input.input());
			case Sprint sprint -> this.transition.handleSprint(this.server, sprint.sprinting());
			case Glide ignored -> this.transition.handleGlide(this.server);
			case AcceptTeleport ack -> this.transition.handleAcceptTeleport(this.server, ack.id());
			case MovePacket move -> {
				ServerTick.Transaction result =
				    this.transition.handleMove(move.payload(), move.form(), this.server, this.serverWorld);
				if (result instanceof ServerTick.Accepted) this.receivedMovementThisTick = true;
				recordEffects(result.hurt().orElse(null), result.damage());
				if (result instanceof ServerTick.Corrected corrected)
					this.clientbound.add(
					    new Position(this.server.awaitingTeleport, corrected.correctionX(), corrected.correctionY(),
					        corrected.correctionZ(), corrected.correctionYRot(), corrected.correctionXRot()));
			}
		}
		return true;
	}

	/** Deliver exactly the oldest clientbound packet, binding writes to the actual delivery state. */
	public boolean deliverClientbound() {
		Clientbound packet = this.clientbound.poll();
		if (packet == null) return false;
		ServerWrite write = switch (packet) {
			case Data data ->
				new EntityDataWrite(boundary(), StateDigest.state(this.client), data.value().dirty(),
				    data.value().sharedFlags(), data.value().pose(), data.value().ticksFrozen(),
				    data.value().frostSpeedTicks(), data.value().sprintingAttribute());
			case Health health ->
				new HealthWrite(
				    boundary(), StateDigest.state(this.client), health.health(), health.food(), health.saturation());
			case Motion motion ->
				new HurtMotionWrite(boundary(),
				    HurtMotion.decoded(
				        motion.cause(), motion.action(), boundary(), this.client, motion.x(), motion.y(), motion.z()));
			case BlockUpdate block -> {
				BlockUpdateWrite delivered = new BlockUpdateWrite(
				    boundary(), block.write().x(), block.write().y(), block.write().z(), block.write().paletteIndex());
				delivered.applyTo(this.clientWorld);
				this.writes.add(delivered);
				yield null;
			}
			case Position position -> {
				this.serverbound.add(new AcceptTeleport(position.id()));
				this.serverbound.add(
				    new MovePacket(MovementPacket.POS_ROT, 0.0 + position.x(), 0.0 + position.y(), 0.0 + position.z(),
				        0.0F + position.yRot(), Mth.clamp(0.0F + position.xRot(), -90.0F, 90.0F), false, false));
				yield new PlayerPositionWrite(boundary(), StateDigest.state(this.client), position.id(), position.x(),
				    position.y(), position.z(), position.yRot(), position.xRot());
			}
		};
		if (write != null) {
			this.transition.deliver(write, boundary(), this.client);
			this.writes.add(write);
		}
		return true;
	}

	private int boundary() {
		return Math.max(0, this.completedActions - 1);
	}
	private void recordEffects(final HurtCause hurt, final List<DamageEvent> damage) {
		for (int i = this.serverTick.emittedDamageCount(); i < damage.size(); i++)
			this.writes.add(new DamageWrite(boundary(), damage.get(i)));
		if (hurt != null) {
			this.pendingHurt = hurt;
			this.hurtAction = this.completedActions - 1;
		}
	}

	/**
         * The native marker ending one client tick, including ticks that
         * publish no movement.
         */
	public record ClientTickEnd() implements Serverbound {}

	/** A retained serverbound payload. */
	public sealed interface Serverbound permits Input, Sprint, Glide, AcceptTeleport, MovePacket, ClientTickEnd {}
	/** An immutable Input packet payload captured at publication. */
	public record Input(PlayerInput input) implements Serverbound {}
	/** An immutable Sprint packet payload captured at publication. */
	public record Sprint(boolean sprinting) implements Serverbound {}
	/** An immutable Glide packet payload captured at publication. */
	public record Glide() implements Serverbound {}
	/** An immutable AcceptTeleport packet payload captured at publication. */
	public record AcceptTeleport(int id) implements Serverbound {}
	/** An immutable MovePacket packet payload captured at publication. */
	public record MovePacket(MovementPacket form, double x, double y, double z, float yRot, float xRot,
	    boolean onGround, boolean horizontalCollision) implements Serverbound {
		static MovePacket of(final MovementPacket form, final PlayerState state) {
			return new MovePacket(
			    form, state.x, state.y, state.z, state.yRot, state.xRot, state.onGround, state.horizontalCollision);
		}
		PlayerState payload() {
			PlayerState result = new PlayerState();
			result.placeAt(this.x, this.y, this.z);
			result.yRot = this.yRot;
			result.xRot = this.xRot;
			result.onGround = this.onGround;
			result.horizontalCollision = this.horizontalCollision;
			return result;
		}
	}
	/** A retained clientbound payload. */
	public sealed interface Clientbound permits Data, Health, Motion, Position, BlockUpdate {}
	/** An immutable BlockUpdate packet payload captured at publication. */
	public record BlockUpdate(BlockUpdateWrite write) implements Clientbound {}
	/** An immutable Data packet payload captured at publication. */
	public record Data(EntityDataWrite value) implements Clientbound {}
	/** An immutable Health packet payload captured at publication. */
	public record Health(float health, int food, float saturation) implements Clientbound {}
	/** An immutable Motion packet payload captured at publication. */
	public record Motion(HurtCause cause, int action, double x, double y, double z) implements Clientbound {}
	/** An immutable Position packet payload captured at publication. */
	public record Position(int id, double x, double y, double z, float yRot, float xRot) implements Clientbound {}
}
