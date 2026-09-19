package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.server.ServerTick;
import com.nettarion.stride.simulator.world.SnapshotView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A branch-owned simulation whose client ticks, server phases and packet deliveries are each one
 * explicit event, with FIFO transport in each direction.
 *
 * <p>Vanilla's server tick is, in order, {@link #deliverServerbound} for every queued packet,
 * {@link #publishServerEntity}, {@link #tickLevel} and {@link #tickConnection}; the client applies
 * {@link #deliverClientbound} before {@link #tickClient}. {@link ActionSchedule#composed()} is the
 * order {@link Simulator} runs. Every phase is the same transition body {@code Simulator} runs, so the
 * two share every line of arithmetic and differ only in when each phase runs and where packets wait.
 *
 * <p>A branch owns its client, server, publisher, both world overlays and every queued payload;
 * {@link #fork()} copies all of them. Accessors return copies. One branch serves one thread. After a
 * refusal the branch is partially mutated and must be discarded; fork before a speculative event.
 * Creative ability changes, external actors and an unknown transport schedule refuse.
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

	private final Interaction interaction;

	private int completedActions;

	private HurtCause pendingHurt;

	private int hurtAction;

	private boolean receivedMovementThisTick;

	/** A branch from {@code state} over forks of {@code world}, with world changes {@link Interaction#UNDECLARED}. */
	public ScheduledSimulation(final SimulationState state, final SnapshotView world) {
		this(state, world, Interaction.UNDECLARED);
	}

	/** A branch from {@code state} over forks of {@code world}, with the declared world-change permission. */
	public ScheduledSimulation(final SimulationState state, final SnapshotView world, final Interaction interaction) {
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(world, "world");
		this.interaction = Objects.requireNonNull(interaction, "interaction");
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
		this.interaction = source.interaction;
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

	/** Whether two branches over the same compiled world agree in every retained field and payload. */
	public static boolean sameState(final ScheduledSimulation a, final ScheduledSimulation b) {
		return a.receivedMovementThisTick == b.receivedMovementThisTick && a.completedActions == b.completedActions
		    && a.hurtAction == b.hurtAction && a.pendingHurt == b.pendingHurt && a.interaction == b.interaction
		    && PlayerState.rawEquals(a.client, b.client) && ServerPlayerState.rawEquals(a.server, b.server)
		    && Publisher.rawEquals(a.publisher, b.publisher)
		    && List.copyOf(a.serverbound).equals(List.copyOf(b.serverbound))
		    && List.copyOf(a.clientbound).equals(List.copyOf(b.clientbound)) && a.writes.equals(b.writes)
		    && SnapshotView.sameWorld(a.clientWorld, b.clientWorld)
		    && SnapshotView.sameWorld(a.serverWorld, b.serverWorld);
	}

	private void bindWorldChanges() {
		this.serverTick.enableWorldChanges(this.serverWorld, this.interaction, this::boundary, write -> {
			if (write instanceof BlockUpdateWrite block) {
				this.clientbound.add(new BlockUpdatePacket(block));
			} else {
				this.writes.add(write);
			}
		});
	}

	/** An independent branch, including every packet in transit. */
	public ScheduledSimulation fork() {
		return new ScheduledSimulation(this);
	}

	/** A copy of this branch's client state. */
	public PlayerState clientState() {
		return this.client.copy();
	}

	/** A copy of this branch's server state. */
	public ServerPlayerState serverState() {
		return this.server.copy();
	}

	/** A copy of the client's publisher. */
	public Publisher publisher() {
		return this.publisher.copy();
	}

	/** An independent fork of the world as the client sees it. */
	public SnapshotView clientWorld() {
		return this.clientWorld.fork();
	}

	/** An independent fork of the world as the server sees it. */
	public SnapshotView serverWorld() {
		return this.serverWorld.fork();
	}

	/** The queued payloads and pending-hurt bookkeeping of this branch, as immutable copies. */
	public Transport transportState() {
		return new Transport(List.copyOf(this.serverbound), List.copyOf(this.clientbound), this.pendingHurt,
		    this.hurtAction, this.interaction, this.receivedMovementThisTick);
	}

	/** The number of client ticks this branch has completed. */
	public int completedActions() {
		return this.completedActions;
	}

	/** The number of queued client-to-server packets. */
	public int pendingServerboundPackets() {
		return this.serverbound.size();
	}

	/** The number of queued server-to-client packets. */
	public int pendingClientboundPackets() {
		return this.clientbound.size();
	}

	/** An immutable copy of the server writes this branch has delivered or emitted. */
	public List<ServerWrite> writes() {
		return List.copyOf(this.writes);
	}

	/** Ticks the client and queues the packets it publishes, with the values it published. */
	public void tickClient(final PlayerInput input) {
		if (this.client.mayfly) {
			throw new UnimplementedMechanicException(
			    RefusalCause.UNMODELED_SCHEDULE, "scheduled creative ability packets are not modeled");
		}
		MovementPacket form = this.transition.tickClient(this.client, this.publisher, input, this.clientWorld);
		if (this.transition.startedFallFlying()) {
			this.serverbound.add(new GlidePacket());
		}
		if (this.publisher.inputChanged) {
			this.serverbound.add(new InputPacket(input));
		}
		if (this.publisher.sprintingChanged) {
			this.serverbound.add(new SprintPacket(this.client.sprinting));
		}
		if (form != MovementPacket.NONE) {
			this.serverbound.add(MovePacket.of(form, this.client));
		}
		this.serverbound.add(new ClientTickEnd());
		this.completedActions++;
	}

	/** {@code ServerPlayer.tick}, independent of the connection's {@code doTick}. */
	public void tickLevel() {
		this.transition.tickLevel(this.server);
	}

	/** The connection's {@code doTick}, queuing its health packet ahead of later packet handlers. */
	public void tickConnection() {
		ServerTick.Effects effects = this.transition.tickConnection(this.server, this.serverWorld);
		recordEffects(effects.hurt().orElse(null), effects.damage());
		HealthWrite health = this.transition.healthPublication(boundary(), this.client);
		if (health != null) {
			this.clientbound.add(new HealthPacket(health.health(), health.foodLevel(), health.saturationLevel()));
		}
	}

	/** The entity tracker's sample; later server movement cannot alter the queued payload. */
	public void publishServerEntity() {
		this.transition.publishServerEntity(this.server);
		EntityDataWrite data = this.transition.dataPublication(boundary(), this.client);
		if (data != null) {
			this.clientbound.add(new EntityDataPacket(data));
		}
		if (this.pendingHurt != null) {
			this.clientbound.add(new MotionPacket(this.pendingHurt, this.hurtAction, this.server.deltaMovementX,
			    this.server.deltaMovementY, this.server.deltaMovementZ));
			this.pendingHurt = null;
		}
	}

	/** Delivers exactly the oldest serverbound packet; false when the queue is empty. */
	public boolean deliverServerbound() {
		Serverbound packet = this.serverbound.poll();
		if (packet == null) {
			return false;
		}
		switch (packet) {
			case ClientTickEnd ignored -> {
				this.serverTick.handleClientTickEnd(this.server, this.receivedMovementThisTick);
				this.receivedMovementThisTick = false;
			}
			case InputPacket input -> this.transition.handleInput(this.server, input.input());
			case SprintPacket sprint -> this.transition.handleSprint(this.server, sprint.sprinting());
			case GlidePacket ignored -> this.transition.handleGlide(this.server);
			case AcceptTeleportPacket ack -> this.transition.handleAcceptTeleport(this.server, ack.id());
			case MovePacket move -> {
				ServerTick.Transaction result =
				    this.transition.handleMove(move.payload(), move.form(), this.server, this.serverWorld);
				if (result instanceof ServerTick.Accepted) {
					this.receivedMovementThisTick = true;
				}
				recordEffects(result.hurt().orElse(null), result.damage());
				if (result instanceof ServerTick.Corrected corrected) {
					this.clientbound.add(
					    new PositionPacket(this.server.awaitingTeleport, corrected.correction().teleport().x(),
					        corrected.correction().teleport().y(), corrected.correction().teleport().z(),
					        corrected.correction().teleport().yRot(), corrected.correction().teleport().xRot()));
				}
			}
		}
		return true;
	}

	/** Delivers exactly the oldest clientbound packet, binding the write to the client state it changes. */
	public boolean deliverClientbound() {
		Clientbound packet = this.clientbound.poll();
		if (packet == null) {
			return false;
		}
		ServerWrite write = switch (packet) {
			case EntityDataPacket data ->
				new EntityDataWrite(boundary(), StateDigest.state(this.client), data.value().dirty(),
				    data.value().sharedFlags(), data.value().pose(), data.value().ticksFrozen(),
				    data.value().frostSpeedTicks(), data.value().sprintingAttribute());
			case HealthPacket health ->
				new HealthWrite(
				    boundary(), StateDigest.state(this.client), health.health(), health.food(), health.saturation());
			case MotionPacket motion ->
				new HurtMotionWrite(boundary(),
				    HurtMotion.decoded(
				        motion.cause(), motion.action(), boundary(), this.client, motion.x(), motion.y(), motion.z()));
			case BlockUpdatePacket block -> {
				BlockUpdateWrite delivered = new BlockUpdateWrite(
				    boundary(), block.write().x(), block.write().y(), block.write().z(), block.write().paletteIndex());
				delivered.applyTo(this.clientWorld);
				this.writes.add(delivered);
				yield null;
			}
			case PositionPacket position -> {
				this.serverbound.add(new AcceptTeleportPacket(position.id()));
				// 0.0 + value drops a negative zero, as vanilla's Entity.setPos and
				// setYRot do on the client before it echoes the correction back.
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
		for (int i = this.serverTick.emittedDamageCount(); i < damage.size(); i++) {
			this.writes.add(new DamageWrite(boundary(), damage.get(i)));
		}
		if (hurt != null) {
			this.pendingHurt = hurt;
			this.hurtAction = this.completedActions - 1;
		}
	}

	/**
	 * The queued payloads and pending-hurt bookkeeping of one branch, which an independent observer can
	 * also build from decoded packets.
	 *
	 * @param serverbound the queued client-to-server payloads, oldest first
	 * @param clientbound the queued server-to-client payloads, oldest first
	 * @param pendingHurt the hit whose velocity the next tracker sample publishes, or {@code null}
	 * @param hurtAction the action that caused {@code pendingHurt}
	 * @param interaction the branch's declared world-change permission
	 * @param receivedMovementThisTick whether a movement packet was accepted since the last tick end
	 */
	public record Transport(List<Serverbound> serverbound, List<Clientbound> clientbound, HurtCause pendingHurt,
	    int hurtAction, Interaction interaction, boolean receivedMovementThisTick) {
		/** Copies both payload lists. */
		public Transport {
			serverbound = List.copyOf(serverbound);
			clientbound = List.copyOf(clientbound);
			Objects.requireNonNull(interaction, "interaction");
		}
	}

	/** A queued client-to-server payload, retained as published. */
	public sealed interface Serverbound permits InputPacket, SprintPacket, GlidePacket, AcceptTeleportPacket,
	    MovePacket, ClientTickEnd {}

	/**
	 * Vanilla's marker ending one client tick, sent even by ticks that publish no movement.
	 */
	public record ClientTickEnd() implements Serverbound {}

	/**
	 * A player input packet.
	 *
	 * @param input the action whose keys the packet carries
	 */
	public record InputPacket(PlayerInput input) implements Serverbound {}

	/**
	 * A sprint start or stop command.
	 *
	 * @param sprinting whether the client started sprinting
	 */
	public record SprintPacket(boolean sprinting) implements Serverbound {}

	/** A request to start gliding. */
	public record GlidePacket() implements Serverbound {}

	/**
	 * Acknowledgement of a position correction.
	 *
	 * @param id the correction's teleport id
	 */
	public record AcceptTeleportPacket(int id) implements Serverbound {}

	/**
	 * A movement packet with the values the client published.
	 *
	 * @param form which of position, rotation and status the packet carries
	 * @param x the published X position, in blocks
	 * @param y the published feet height, in blocks
	 * @param z the published Z position, in blocks
	 * @param yRot the published yaw, in degrees
	 * @param xRot the published pitch, in degrees
	 * @param onGround the published ground contact
	 * @param horizontalCollision the published horizontal collision
	 */
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

	/** A queued server-to-client payload, retained as sampled. */
	public sealed interface Clientbound permits EntityDataPacket, HealthPacket, MotionPacket, PositionPacket,
	    BlockUpdatePacket {}

	/**
	 * A block change the server made, to be applied to the client's world overlay.
	 *
	 * @param write the change as the server recorded it
	 */
	public record BlockUpdatePacket(BlockUpdateWrite write) implements Clientbound {}

	/**
	 * An entity-data packet.
	 *
	 * @param value the sampled entity data
	 */
	public record EntityDataPacket(EntityDataWrite value) implements Clientbound {}

	/**
	 * A health packet.
	 *
	 * @param health the health, in health points
	 * @param food the food level
	 * @param saturation the saturation level
	 */
	public record HealthPacket(float health, int food, float saturation) implements Clientbound {}

	/**
	 * A hurt velocity packet, with the server's velocity as sampled.
	 *
	 * @param cause what dealt the hit
	 * @param action the action that caused the hit
	 * @param x the server's X velocity, in blocks per tick
	 * @param y the server's Y velocity, in blocks per tick
	 * @param z the server's Z velocity, in blocks per tick
	 */
	public record MotionPacket(HurtCause cause, int action, double x, double y, double z) implements Clientbound {}

	/**
	 * A position correction.
	 *
	 * @param id the teleport id the client must acknowledge
	 * @param x the corrected X position, in blocks
	 * @param y the corrected feet height, in blocks
	 * @param z the corrected Z position, in blocks
	 * @param yRot the corrected yaw, in degrees
	 * @param xRot the corrected pitch, in degrees
	 */
	public record PositionPacket(int id, double x, double y, double z, float yRot, float xRot) implements Clientbound {}
}
