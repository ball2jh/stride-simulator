package com.nettarion.stride.simulator.server;

import com.nettarion.stride.simulator.BlockUpdateWrite;
import com.nettarion.stride.simulator.CorrectionReason;
import com.nettarion.stride.simulator.DamageEvent;
import com.nettarion.stride.simulator.DamageWrite;
import com.nettarion.stride.simulator.EntityDataWrite;
import com.nettarion.stride.simulator.HealthWrite;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.HurtMotion;
import com.nettarion.stride.simulator.HurtMotionWrite;
import com.nettarion.stride.simulator.Interaction;
import com.nettarion.stride.simulator.MovementCorrection;
import com.nettarion.stride.simulator.MovementPacket;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.ServerWrite;
import com.nettarion.stride.simulator.Simulator;
import com.nettarion.stride.simulator.StateDigest;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.UnpredictedServerWriteException;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.tick.Travel;
import com.nettarion.stride.simulator.world.SnapshotView;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * One server tick on the server's copy of the player, cut into the phases the two runners call; the package
 * documentation gives the phase model, which method covers which phase, and the glossary.
 *
 * <p>The composed step ({@code Simulator}) calls {@link #beginTick}, {@link #collectPublications},
 * {@link #advanceTicks}, {@link #handlePackets} and {@link #deliverPublications} in that order, all on one
 * transaction. An explicit schedule ({@code ScheduledSimulation}) calls one handler or tick per event, each its own
 * transaction. {@link #transact} is the whole server tick without a tracker sample, for tests and callers that hold
 * no schedule. Every refusal is a {@code RefusalException}.
 *
 * <p>Methods on the composed path return {@code null} for "no hit" and "no write" so a step allocates nothing in
 * the common case; the report records use {@link Optional}. An instance retains the server's scratch, the tracker's
 * sample and the last connection tick's health packet, and is not thread-safe.
 */
public final class ServerTick {
	/**
	 * {@code Entity.getMaximumFlyingTicks} for ordinary gravity: the connection kicks a floating client after this
	 * many connection ticks. Levitation and slow falling, which raise it, are not modeled.
	 */
	private static final int MAXIMUM_FLOATING_TICKS = 80;

	private final TickAuthority authority = TickAuthority.server();

	private final Scratch serverScratch = new Scratch(this.authority);

	private final ServerEntity serverEntity = new ServerEntity();

	private boolean deliverEchoes;

	private boolean hurtMotionAfterNextPacket;

	private boolean healthDirty;

	private float publishedHealth;

	private int publishedFood;

	private float publishedSaturation;

	private int emittedDamageCount;

	/** A server tick under the fixed schedule; {@link #observed()} is the alternative. */
	public ServerTick() {
		this(true, false);
	}

	private ServerTick(final boolean deliverEchoes, final boolean hurtMotionAfterNextPacket) {
		this.deliverEchoes = deliverEchoes;
		this.hurtMotionAfterNextPacket = hurtMotionAfterNextPacket;
	}

	/**
	 * A server tick for a caller on a free-running connection, which observes the server's writes rather than
	 * taking the fixed schedule's word for them. The entity-data echo is recorded but not applied to the client copy,
	 * since its arrival tick is not the schedule's there, and the hurt-marked velocity write carries the server's
	 * velocity as of its delivery, after the client's next movement packet was drained, which is what such a server
	 * was observed to send. Health writes are delivered as scheduled.
	 */
	public static ServerTick observed() {
		return new ServerTick(false, true);
	}

	// ---- the composed step, in call order

	/**
	 * Begin the composed step's transaction on the server copy that step owns. Admission is
	 * {@link ServerPlayerState#requireValid()}, a declared difficulty, and no attached firework rocket while either
	 * copy glides. The last step's tracker sample and health packet are forgotten.
	 *
	 * @throws PendingServerWriteException when the difficulty is undeclared or a rocket would write the velocity
	 */
	public void beginTick(final PlayerState client, final ServerPlayerState server) {
		begin(server, Objects.requireNonNull(client, "client").fallFlying);
	}

	/**
	 * {@code ServerEntity.sendChanges} at the head of the level tick: sample the dirty metadata, attributes and
	 * velocity of the server's copy after the packet handlers ran and before the player entity ticks.
	 *
	 * @throws PendingServerWriteException when the sample cannot be decided; see {@link ServerEntity}
	 */
	public void collectPublications(final ServerPlayerState server) {
		this.serverEntity.collectChanges(server);
	}

	/**
	 * The level tick and the connection tick after the tracker sample. Every hit they deal is written at
	 * {@code tick} as a {@link DamageWrite}.
	 *
	 * @return the first marking hit of these phases, which the next tracker sample publishes, or {@code null}
	 * @throws PendingServerWriteException when the floating counter reaches the kick, or the tick's outcome depends
	 *     on a server fact the state does not carry
	 * @throws UnimplementedMechanicException when the tick reaches a body the admitted domain does not model, or
	 *     kills the player
	 */
	public HurtCause advanceTicks(final ServerPlayerState server, final SnapshotView world, final int tick,
	    final Consumer<? super ServerWrite> writes) {
		levelTick(server);
		connectionTick(server, world);
		return writeNewHits(tick, writes);
	}

	/**
	 * The packet processor's drain for one client tick: the input packet when it changed, the sprint command when
	 * the client's flag changed, {@code START_FALL_FLYING} when the client tick started a glide, the movement packet,
	 * then the end-of-tick marker. Every hit the movement handler deals is written at {@code tick}.
	 *
	 * @param clientAfter the client's copy after its tick
	 * @param kind the movement packet kind the client's publisher selected for that tick
	 * @return the first marking hit of this phase, which the tracker sample that follows publishes, or {@code null}
	 * @throws UnpredictedServerWriteException when the listener corrects the movement packet
	 * @throws PendingServerWriteException when a correction is pending, or a rocket would write the velocity
	 */
	public HurtCause handlePackets(final PlayerState clientAfter, final MovementPacket kind,
	    final ServerPlayerState server, final PlayerInput action, final SnapshotView world, final boolean sendInput,
	    final boolean sendSprint, final boolean startFallFlying, final int tick,
	    final Consumer<? super ServerWrite> writes) {
		Objects.requireNonNull(clientAfter, "clientAfter");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(server, "server");
		Objects.requireNonNull(action, "action");
		Objects.requireNonNull(world, "world");
		// The client tick may have started the glide a rocket would write.
		requireNoRocketWrites(server, clientAfter.fallFlying);
		Transaction transaction =
		    handlers(clientAfter, kind, server, action, world, sendInput, sendSprint, startFallFlying);
		endClientTick(server, transaction, tick);
		return writeNewHits(tick, writes);
	}

	/**
	 * Deliver what the step's sample and connection tick wrote, in the order the client receives it: the entity
	 * data, the hurt-marked velocity when a hit was marked at the sample, then {@code doTick}'s health packet. Each
	 * write is bound to the client state it is about to change and applied to that very state, so the binding digest
	 * is taken once.
	 *
	 * @param hurt the hit whose mark the sample saw, or {@code null}
	 * @param causeAction the action that caused that hit: the previous action, whose packet drain or server tick
	 *     dealt it
	 */
	public void deliverPublications(final int action, final PlayerState client, final ServerPlayerState server,
	    final HurtCause hurt, final int causeAction, final Consumer<? super ServerWrite> writes) {
		EntityDataWrite data = this.serverEntity.write(action, client);
		if (data != null) {
			if (this.deliverEchoes) {
				data.applyUnchecked(client);
			}
			writes.accept(data);
		}
		if (hurt != null) {
			HurtMotion decoded = this.hurtMotionAfterNextPacket
			    ? HurtMotion.decoded(hurt, causeAction, action, client, server.deltaMovementX, server.deltaMovementY,
			          server.deltaMovementZ)
			    : HurtMotion.decoded(hurt, causeAction, action, client, this.serverEntity.motionX,
			          this.serverEntity.motionY, this.serverEntity.motionZ);
			decoded.applyUnchecked(client);
			writes.accept(new HurtMotionWrite(action, decoded));
		}
		// doTick sends this after the tracker sampled at the head of the level tick.
		if (this.healthDirty) {
			HealthWrite health = new HealthWrite(
			    action, StateDigest.state(client), this.publishedHealth, this.publishedFood, this.publishedSaturation);
			health.applyUnchecked(client);
			writes.accept(health);
		}
	}

	/** Append the confirmations of the step's tracker sample, one per value the client may see change. */
	public void recordPublications(final int action, final List<Simulator.Confirmation> output) {
		this.serverEntity.recordChanges(action, output);
	}

	/**
	 * Carry the server scratch's retained collision span across a world change, so it answers again in a view that
	 * kept the sections it read; see {@link Scratch#carry}. Called with the view being left.
	 */
	public void carry(final SnapshotView world) {
		this.serverScratch.carry(world);
	}

	// ---- an explicit schedule, one event per call

	/**
	 * One connection tick as its own transaction, without a level tick or packet delivery. With no client copy to
	 * judge, any attached rocket refuses.
	 *
	 * @throws PendingServerWriteException as {@link #advanceTicks}, and when a rocket is attached
	 */
	public Effects tickConnection(final ServerPlayerState server, final SnapshotView world) {
		begin(server, true);
		connectionTick(server, world);
		return this.authority.survival().effects();
	}

	/**
	 * One decoded movement packet as its own transaction, without advancing either server clock. Under a pending
	 * correction the packet installs rotation only and the report is {@link AwaitingTeleport}.
	 *
	 * @param payload the client state the packet was encoded from
	 * @param kind the packet kind, never {@link MovementPacket#NONE}
	 */
	public Transaction handleMovePlayer(final PlayerState payload, final MovementPacket kind,
	    final ServerPlayerState server, final SnapshotView world) {
		if (kind == MovementPacket.NONE) {
			throw new IllegalArgumentException("NONE is not a packet");
		}
		begin(server, Objects.requireNonNull(payload, "payload").fallFlying);
		return ServerMovementListener.handleMovePlayer(payload, kind, server, world, this.serverScratch);
	}

	/** {@code handleClientTickEnd}: the end-of-tick marker after the client tick's packet batch. */
	public void handleClientTickEnd(final ServerPlayerState server, final boolean receivedMovementThisTick) {
		ServerMovementListener.handleClientTickEnd(server, receivedMovementThisTick);
	}

	/** {@code handlePlayerInput}: the input packet, sent independently of movement. */
	public void handlePlayerInput(final ServerPlayerState server, final PlayerInput input) {
		ServerMovementListener.handlePlayerInput(server, input);
	}

	/** {@code handlePlayerCommand}'s {@code START_SPRINTING} and {@code STOP_SPRINTING}. */
	public void handlePlayerCommand(final ServerPlayerState server, final boolean sprinting) {
		ServerMovementListener.handlePlayerCommand(server, sprinting);
	}

	/**
	 * {@code handlePlayerCommand}'s {@code START_FALL_FLYING}: {@code Player.tryToStartFallFlying} starts the glide
	 * only when the copy is not already gliding, can glide, and is not in water; otherwise
	 * {@code LivingEntity.stopFallFlying} pulses the shared flag on and off, which dirties it so the next tracker
	 * sample echoes the stop to the client.
	 */
	public void handleStartFallFlying(final ServerPlayerState server) {
		if (!server.fallFlying && Travel.canGlide(server) && !(server.waterHeight > 0.0)) {
			server.setSharedFlag(ServerSharedFlags.FALL_FLYING, true);
		} else {
			server.setSharedFlag(ServerSharedFlags.FALL_FLYING, true);
			server.setSharedFlag(ServerSharedFlags.FALL_FLYING, false);
		}
	}

	/** {@code handleAcceptTeleportPacket}: the client's acknowledgement of a correction by its id. */
	public void handleAcceptTeleportPacket(final ServerPlayerState server, final int teleportId) {
		ServerMovementListener.handleAcceptTeleportPacket(server, teleportId);
	}

	/** The last connection tick's health packet bound to {@code client}, or {@code null} when nothing changed. */
	public HealthWrite healthPublication(final int action, final PlayerState client) {
		return this.healthDirty ? new HealthWrite(action, StateDigest.state(client), this.publishedHealth,
		                              this.publishedFood, this.publishedSaturation)
		                        : null;
	}

	/** The last tracker sample bound to {@code client}, or {@code null} when nothing was dirty. */
	public EntityDataWrite entityDataPublication(final int action, final PlayerState client) {
		return this.serverEntity.write(action, client);
	}

	/**
	 * How many of the current transaction's hits have already been written, so a caller that reads
	 * {@link Transaction#damage()} or {@link Effects#damage()} writes only the rest.
	 */
	public int emittedDamageCount() {
		return this.emittedDamageCount;
	}

	/**
	 * Bind the branch that owns the server's world, so the one admitted world write, lowering a cauldron, reaches
	 * it. Hits dealt before the write are written first, so the stream keeps the server's order.
	 *
	 * @param ownedWorld the server's world; a write to any other view is a programming error
	 * @param interaction the caller's declared permission; {@link Interaction#UNDECLARED} refuses at the write
	 * @param action the current action index, read when a write happens
	 * @param output where the damage and block writes go
	 */
	public void enableWorldChanges(final SnapshotView ownedWorld, final Interaction interaction,
	    final IntSupplier action, final Consumer<ServerWrite> output) {
		Objects.requireNonNull(ownedWorld, "ownedWorld");
		Objects.requireNonNull(interaction, "interaction");
		Objects.requireNonNull(action, "action");
		Objects.requireNonNull(output, "output");
		this.authority.worldChanges = (world, x, y, z, successor) -> {
			if (world != ownedWorld) {
				throw new IllegalStateException("server world belongs to another branch");
			}
			if (interaction == Interaction.UNDECLARED) {
				throw new UnimplementedMechanicException(
				    RefusalCause.UNMODELED_WORLD_WRITE, "cauldron interaction permission is undeclared");
			}
			if (interaction == Interaction.DENIED) {
				return;
			}
			world.requireCauldronUpdateClosure(x, y, z);
			List<DamageEvent> damage = this.authority.survival().dealt();
			while (this.emittedDamageCount < damage.size()) {
				output.accept(new DamageWrite(action.getAsInt(), damage.get(this.emittedDamageCount++)));
			}
			world.replaceCell(x, y, z, successor);
			output.accept(new BlockUpdateWrite(action.getAsInt(), x, y, z, successor));
		};
	}

	// ---- the whole server tick

	/**
	 * One server tick without its tracker sample: the packet handlers for the client tick's packets, then the level
	 * tick and the connection tick, on a caller-owned server state advanced in place. The report carries every hit
	 * either phase dealt, in order, and the one that marked; a correction is reported with the hits of the whole
	 * tick, since the server still ticks at the teleport position before the acknowledgement can arrive. The input
	 * and sprint commands are always delivered; {@code START_FALL_FLYING} is inferred from a client copy that glides
	 * while the server's does not.
	 *
	 * @param clientAfter the client's copy after its tick
	 * @param kind the movement packet kind the client's publisher selected for that tick
	 * @throws PendingServerWriteException when a correction is pending, the floating counter reaches the kick, a
	 *     rocket would write the velocity, or a hit's outcome is decided by a server fact the state does not carry
	 * @throws UnimplementedMechanicException when the server reaches a body outside the admitted domain, or kills
	 *     the player
	 */
	public Transaction transact(final PlayerState clientAfter, final MovementPacket kind,
	    final ServerPlayerState server, final PlayerInput action, final SnapshotView world) {
		Objects.requireNonNull(clientAfter, "clientAfter");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(action, "action");
		Objects.requireNonNull(world, "world");
		begin(server, clientAfter.fallFlying);
		// MinecraftServer.processPacketsAndTick drains the client's packets
		// before tickServer; the level tick and the connection tick then run on
		// the copy at the accepted target and restore the packet-owned position.
		Transaction transaction = handlers(
		    clientAfter, kind, server, action, world, true, true, clientAfter.fallFlying && !server.fallFlying);
		levelTick(server);
		connectionTick(server, world);
		return transaction.withEffects(this.authority.survival().effects());
	}

	/**
	 * {@code ServerPlayer.tick} in the level's entity tick: the hit cooldown counts down and
	 * {@code ServerLevel.tickNonPassenger} advances the entity tick count.
	 */
	public static void levelTick(final ServerPlayerState server) {
		if (server.invulnerableTime > 0) {
			server.invulnerableTime--;
		}
		if (server.tickCount != ServerPlayerState.UNKNOWN_TICK_COUNT) {
			// Vanilla's tickCount is an int; the state holds a long to carry the
			// unknown sentinel, so the cast reproduces the int wrap-around.
			server.tickCount = (int) (server.tickCount + 1);
		}
	}

	// ---- private

	/**
	 * The one admission rule of a transaction: a valid copy with a declared difficulty and no attached firework
	 * rocket while either copy glides. Forgets the last transaction's hits, sample and health packet.
	 */
	private void begin(final ServerPlayerState server, final boolean clientGlides) {
		Objects.requireNonNull(server, "server").requireValid();
		FoodData.requireSupported(server);
		requireNoRocketWrites(server, clientGlides);
		this.emittedDamageCount = 0;
		this.healthDirty = false;
		this.serverEntity.clear();
		this.authority.begin(server);
	}

	private static void requireNoRocketWrites(final ServerPlayerState server, final boolean clientGlides) {
		if (server.attachedRockets > 0 && (server.fallFlying || clientGlides)) {
			throw new PendingServerWriteException(RefusalCause.UNMODELED_SCHEDULE,
			    server.attachedRockets + " attached firework rocket(s) replace the gliding player's velocity on"
			        + " every actor tick, on both sides; the rocket's server-private lifetime"
			        + " and actor scheduling are outside the admitted domain");
		}
	}

	/**
	 * The handlers of one client tick's packets in their wire order: input, sprint command, glide command,
	 * movement. {@code LocalPlayer} sends the commands independently of {@code sendPosition}'s movement choice.
	 *
	 * @throws PendingServerWriteException when a movement packet arrives during a pending correction: the composed
	 *     step has no place to hold the acknowledgement, so the server's view of the tick is undetermined
	 */
	private Transaction handlers(final PlayerState clientAfter, final MovementPacket kind,
	    final ServerPlayerState server, final PlayerInput action, final SnapshotView world, final boolean sendInput,
	    final boolean sendSprint, final boolean startFallFlying) {
		if (sendInput) {
			ServerMovementListener.handlePlayerInput(server, action);
		}
		if (sendSprint) {
			ServerMovementListener.handlePlayerCommand(server, clientAfter.sprinting);
		}
		if (startFallFlying) {
			handleStartFallFlying(server);
		}
		if (kind == MovementPacket.NONE) {
			return new Unpublished(this.authority.survival().effects());
		}
		if (server.correctionPending) {
			throw new PendingServerWriteException(RefusalCause.PENDING_WRITE,
			    "a correction is pending the client's"
			        + " acknowledgement; movement packets only apply rotation until then");
		}
		return ServerMovementListener.handleMovePlayer(clientAfter, kind, server, world, this.serverScratch);
	}

	/**
	 * The end-of-tick marker after the packet batch, then the composed step's refusal of a correction: it has no
	 * transport to hold the acknowledgement, so the tick is reported as unpredicted.
	 */
	private static void endClientTick(final ServerPlayerState server, final Transaction transaction, final int tick) {
		// At most one movement packet is published per client tick, and only
		// an accepted one calls handlePlayerKnownMovement in vanilla.
		ServerMovementListener.handleClientTickEnd(server, transaction instanceof Accepted);
		if (transaction instanceof Corrected corrected) {
			throw new UnpredictedServerWriteException(tick, corrected.correction());
		}
	}

	/** Write the transaction's hits not yet written, at {@code tick}, and return the first marking one or null. */
	private HurtCause writeNewHits(final int tick, final Consumer<? super ServerWrite> writes) {
		List<DamageEvent> dealt = this.authority.survival().dealt();
		HurtCause marked = null;
		for (int index = this.emittedDamageCount; index < dealt.size(); index++) {
			DamageEvent hit = dealt.get(index);
			writes.accept(new DamageWrite(tick, hit));
			if (marked == null && hit.marks()) {
				marked = hit.cause();
			}
		}
		this.emittedDamageCount = dealt.size();
		return marked;
	}

	/**
	 * {@code ServerGamePacketListenerImpl.tickPlayer}: {@code doTick} on the server's copy, the position anchor
	 * restored afterwards, then the floating counter, which a packet-set latch keeps advancing until an accepted
	 * packet clears it.
	 */
	private void connectionTick(final ServerPlayerState server, final SnapshotView world) {
		ServerMovementListener.resetPosition(server);
		double anchorX = server.firstGoodX;
		double anchorY = server.firstGoodY;
		double anchorZ = server.firstGoodZ;
		ServerPlayerTick.doTick(server, world, this.serverScratch);
		this.healthDirty = server.health != server.lastSentHealth || server.foodLevel != server.lastSentFood
		    || (server.saturationLevel == 0.0F) != server.lastFoodSaturationZero;
		if (this.healthDirty) {
			this.publishedHealth = server.lastSentHealth = server.health;
			this.publishedFood = server.lastSentFood = server.foodLevel;
			this.publishedSaturation = server.saturationLevel;
			server.lastFoodSaturationZero = server.saturationLevel == 0.0F;
		}
		server.placeAt(anchorX, anchorY, anchorZ);
		server.connectionTickCount++;
		server.knownMovePacketCount = server.receivedMovePacketCount;
		if (server.clientIsFloating || server.floatingUnknown) {
			if (++server.aboveGroundTickCount > MAXIMUM_FLOATING_TICKS) {
				throw new PendingServerWriteException(RefusalCause.UNMODELED_SESSION_END,
				    server.clientIsFloating ? "the server kicks the client for floating after " + MAXIMUM_FLOATING_TICKS
				            + " connection ticks"
				                            : "the floating kick after " + MAXIMUM_FLOATING_TICKS
				            + " connection ticks cannot be decided: the last accepted packet's"
				            + " air query reached space the capture does not declare");
			}
		} else {
			server.aboveGroundTickCount = 0;
		}
	}

	// ---- reports

	/**
	 * The mark and the hits of one transaction.
	 *
	 * @param hurt the hit that marked the player hurt, if any
	 * @param damage every hit dealt, in the order the server dealt them
	 */
	public record Effects(Optional<HurtCause> hurt, List<DamageEvent> damage) {
		/** Validates and copies. */
		public Effects {
			Objects.requireNonNull(hurt, "hurt");
			damage = List.copyOf(damage);
		}
	}

	/**
	 * What the listener did with the client tick's movement packet, with the effects of the transaction that
	 * reported it: the packet phase alone from {@link #handleMovePlayer}, the whole tick from {@link #transact}.
	 */
	public sealed interface Transaction permits Unpublished, Accepted, Corrected, AwaitingTeleport {
		/** The movement packet kind the transaction decoded. */
		MovementPacket packet();

		/** The mark and the hits of the transaction. */
		Effects effects();

		/** The hit that marked the player hurt, if any. */
		default Optional<HurtCause> hurt() {
			return effects().hurt();
		}

		/** Every hit dealt, in the order the server dealt them. */
		default List<DamageEvent> damage() {
			return effects().damage();
		}

		/** The same decision with the effects of the whole tick, once its later phases ran. */
		Transaction withEffects(Effects effects);
	}

	/**
	 * The client tick sent no movement packet; the input and command handlers and the ticks still ran.
	 *
	 * @param effects the transaction's mark and hits
	 */
	public record Unpublished(Effects effects) implements Transaction {
		/** Validates. */
		public Unpublished {
			Objects.requireNonNull(effects, "effects");
		}

		@Override
		public MovementPacket packet() {
			return MovementPacket.NONE;
		}

		@Override
		public Unpublished withEffects(final Effects effects) {
			return new Unpublished(effects);
		}
	}

	/**
	 * The listener accepted the decoded target: the server snapped to it and installed the packet's ground and
	 * contact bits.
	 *
	 * @param packet the packet kind
	 * @param targetX the decoded target x, in blocks
	 * @param targetY the decoded target y, in blocks
	 * @param targetZ the decoded target z, in blocks
	 * @param residualSquared the horizontal residual the moved-wrongly test measured, in blocks squared
	 * @param forgivenByOccupiedStart the residual exceeded the quarter block but the pre-packet box was already
	 *     occupied, which vanilla forgives
	 * @param clientIsFloating the floating latch the acceptance computed
	 * @param floatingUnknown the latch could not be decided because its air query reached undeclared space; it is
	 *     held false and the kick is refused as undetermined if the counter reaches it
	 * @param effects the transaction's mark and hits
	 */
	public record Accepted(MovementPacket packet, double targetX, double targetY, double targetZ,
	    double residualSquared, boolean forgivenByOccupiedStart, boolean clientIsFloating, boolean floatingUnknown,
	    Effects effects) implements Transaction {
		/** Validates. */
		public Accepted {
			Objects.requireNonNull(packet, "packet");
			Objects.requireNonNull(effects, "effects");
		}

		@Override
		public Accepted withEffects(final Effects effects) {
			return new Accepted(this.packet, this.targetX, this.targetY, this.targetZ, this.residualSquared,
			    this.forgivenByOccupiedStart, this.clientIsFloating, this.floatingUnknown, effects);
		}
	}

	/**
	 * The listener sent the client back to its pre-packet position with the packet's rotation and zero velocity,
	 * and awaits the acknowledgement.
	 *
	 * @param correction what was asked, what collision made of it, and where the client was sent
	 * @param effects the transaction's mark and hits
	 */
	public record Corrected(MovementCorrection correction, Effects effects) implements Transaction {
		/** Validates. */
		public Corrected {
			Objects.requireNonNull(correction, "correction");
			Objects.requireNonNull(effects, "effects");
		}

		@Override
		public MovementPacket packet() {
			return this.correction.packet();
		}

		/** Why the packet was corrected. */
		public CorrectionReason reason() {
			return this.correction.reason();
		}

		@Override
		public Corrected withEffects(final Effects effects) {
			return new Corrected(this.correction, effects);
		}

		// Retained for a caller in the root package; the merge replaces them
		// with correction().teleport().x() and its siblings.





	}

	/**
	 * A movement packet arrived while a correction's acknowledgement was pending, and installed rotation only.
	 * Reachable through {@link #handleMovePlayer}; {@link #transact} and the composed step refuse the pending
	 * correction before the listener runs, since they have no transport to hold the acknowledgement.
	 *
	 * @param packet the packet kind
	 * @param effects the transaction's mark and hits
	 */
	public record AwaitingTeleport(MovementPacket packet, Effects effects) implements Transaction {
		/** Validates. */
		public AwaitingTeleport {
			Objects.requireNonNull(packet, "packet");
			Objects.requireNonNull(effects, "effects");
		}

		@Override
		public AwaitingTeleport withEffects(final Effects effects) {
			return new AwaitingTeleport(this.packet, effects);
		}
	}
}
