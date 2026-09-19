package com.nettarion.stride.simulator.server;

import com.nettarion.stride.simulator.BlockUpdateWrite;
import com.nettarion.stride.simulator.DamageEvent;
import com.nettarion.stride.simulator.DamageWrite;
import com.nettarion.stride.simulator.EntityDataWrite;
import com.nettarion.stride.simulator.HealthWrite;
import com.nettarion.stride.simulator.HurtMotion;
import com.nettarion.stride.simulator.HurtMotionWrite;
import com.nettarion.stride.simulator.ServerWrite;
import com.nettarion.stride.simulator.Refusal;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.MovementPacket;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.Simulator;
import com.nettarion.stride.simulator.StateDigest;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.UnpredictedServerWriteException;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.tick.Travel;
import com.nettarion.stride.simulator.world.SnapshotView;
import java.util.function.IntSupplier;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The server's own step on the server's copy of the player, in the order one
 * {@code MinecraftServer} tick runs it: the packet processor drains the
 * client's queued packets ({@code handlePlayerInput}, the sprint and
 * {@code START_FALL_FLYING} commands, {@code handleMovePlayer}), the entity
 * tracker samples what changed ({@link ServerEntity}, in
 * {@code ChunkMap.tick} at the head of {@code ServerLevel.tick}), the level
 * ticks the player entity, and the connection tick runs {@code doTick}.
 * Every hit either phase deals is reported so the composed step puts each on
 * the write stream at the input that caused it and publishes the hurt-marked
 * velocity at the tracker sample that sees the mark.
 *
 * <p>{@link #handlePackets}, {@link #collectPublications},
 * {@link #advanceTicks} and {@link #deliverPublications} are those phases
 * for the composed transition, which cuts the cycle after the packet drain:
 * a step finishes the server tick that drained the previous action's packets
 * (sample, level tick, connection tick), runs the client tick and drains its
 * packets, and delivers the sample before the next client tick. That
 * delivery is the measured transport phase, not a source fact: the live
 * captures show a packet-caused write applied before client tick N + 2 and a
 * doTick-caused one before N + 3, which is what this cut produces.
 * {@link #transact} runs the handlers and both ticks of one server tick
 * without a sample and returns what the listener decided, and
 * {@link #advanceInPlace} is the same refusing a correction. The level
 * tick is {@code ServerPlayer.tick}: the hit cooldown counts down and the
 * entity tick count advances. The connection tick is
 * {@code ServerGamePacketListenerImpl.tickPlayer}: {@code doTick} on the
 * server's copy, which is the client's phase pipeline run under server
 * authority ({@link com.nettarion.stride.simulator.tick.PlayerTick#serverTick}) with the server-only branches
 * live, so burning, falling out of the world, suffocating in a wall, and
 * drowning are dealt in {@link com.nettarion.stride.simulator.tick.BaseTick}, the glide travel's wall impact in
 * {@link Travel}, the contact bodies the movement reached (hot floor,
 * cactus, berry bush, campfire, fire, lava, water) in {@link com.nettarion.stride.simulator.block.BlockEffects}
 * through each block's {@link com.nettarion.stride.simulator.block.BlockBehaviour}, and the freezing hit in
 * {@link com.nettarion.stride.simulator.tick.Freezing}; then {@link com.nettarion.stride.simulator.server.FoodData} advances the food timer and exhaustion,
 * publishing changed health and food, then the position anchor
 * is restored and the floating counter advances. The movement handler is
 * {@link ServerGamePacketListenerImpl}: {@code handleMovePlayer} on the
 * decoded packet, landing it through the landed block's {@code fallOn}.
 * Every hit runs {@code LivingEntity.hurtServer}'s cooldown and absorption
 * algebra through the one {@link Survival} bound to the copy and is a
 * {@link DamageEvent}.
 *
 * <p>What the slice cannot decide refuses: a contact body with no behaviour,
 * fire's random re-ignition increment, freeze damage at an unknown tick
 * count, a farmland trample, a death, an attached rocket's writes, the
 * glider's durability write, and any hit on a player who may fly, whose
 * invulnerability is not a captured fact. The world border is assumed
 * at its default, so no border damage is reachable inside a captured
 * region. Sleeping, spectator, passenger, and creative movement are outside
 * the slice.
 *
 * <p>An instance retains scratch and is not thread-safe.
 */
public final class ServerTick {
	/** Process an explicit acknowledgement of a correction returned by transact. */
	public void handleAcceptTeleportPacket(final ServerPlayerState server, final int teleportId) {
		ServerGamePacketListenerImpl.handleAcceptTeleportPacket(server, teleportId);
	}

	/** {@code getMaximumFlyingTicks} for ordinary gravity. */
	private static final int MAXIMUM_FLOATING_TICKS = 80;

	private final TickAuthority authority = TickAuthority.server();
	private final Scratch serverScratch = new Scratch(this.authority);

	/** The server copy's workspace, for a runner carrying its retained span across a publication. */
	public Scratch scratch() {
		return this.serverScratch;
	}

	private final ServerEntity serverEntity = new ServerEntity();
	/**
	 * Whether the sampled entity-data publication is applied to the client copy
	 * at the step's delivery. True is the constructed schedule, the phase the
	 * live captures measure (live-pose-echo-boundary, live-elytra-glide,
	 * live-powder-snow-crossing): a write the server's packet drain caused is
	 * applied before client tick N + 2, one its doTick caused before N + 3. A
	 * consumer that observes the echo's arrival instead
	 * ({@link Simulator#observed}) records the write but leaves the client
	 * copy's flags to the client's own tick and to the observed packet.
	 */
	private boolean deliverEchoes = true;
	/**
	 * Whether the hurt-marked velocity write carries the server's velocity as
	 * of its delivery, after the client's next movement packet was drained,
	 * rather than as of the tracker sample. False is the constructed
	 * schedule: {@code ChunkMap.tick} samples the mark and the velocity
	 * together, before the server's own tick. A free-running server was
	 * observed to send the velocity after the next packet
	 * ({@link Simulator#observed}).
	 */
	private boolean hurtMotionAfterNextPacket;

	/** See {@link #deliverEchoes}. */
	public void deliverEchoes(final boolean deliver) {
		this.deliverEchoes = deliver;
	}

	/** See {@link #hurtMotionAfterNextPacket}. */
	public void hurtMotionAfterNextPacket(final boolean after) {
		this.hurtMotionAfterNextPacket = after;
	}
	private boolean healthDirty;
	private float publishedHealth, publishedSaturation;
	private int publishedFood;

	/**
	 * {@code ServerEntity.sendChanges} at the head of the level tick: sample
	 * the dirty metadata, attributes and velocity of the server's copy after
	 * the packet handlers ran and before the player entity ticks.
	 */
	public void collectPublications(final ServerPlayerState server) {
		this.serverEntity.collectChanges(server);
	}

	public void recordPublications(final int action, final List<Simulator.Confirmation> output) {
		this.serverEntity.recordChanges(action, output);
	}

	/**
	 * Deliver what the step's sample and connection tick published, in the
	 * order the client receives it: the entity data, the hurt-marked velocity
	 * when a hit was marked at the sample, then {@code doTick}'s health
	 * packet. Delivered at the end of the step, they are applied before the
	 * next client tick, which is the measured transport phase.
	 *
	 * @param hurt the hit whose mark the sample saw, or {@code null}
	 * @param causeTick the input that caused that hit, the previous action:
	 *     its packet drain or the server tick that followed it dealt the hit
	 */
	public void deliverPublications(final int action, final PlayerState client, final ServerPlayerState server,
	    final HurtCause hurt, final int causeTick, final Consumer<? super ServerWrite> writes) {
		// Each write is bound to the client state it is about to change, and
		// delivered to that very state, so the binding digest is taken once.
		EntityDataWrite data = this.serverEntity.publication(action, client);
		if (data != null) {
			if (this.deliverEchoes) data.applyToDigestedState(client);
			writes.accept(data);
		}
		if (hurt != null) {
			HurtMotion decoded = this.hurtMotionAfterNextPacket
			    ? HurtMotion.decoded(hurt, causeTick, action, client, server.deltaMovementX, server.deltaMovementY,
			          server.deltaMovementZ)
			    : HurtMotion.decoded(hurt, causeTick, action, client, this.serverEntity.motionX,
			          this.serverEntity.motionY, this.serverEntity.motionZ);
			decoded.applyToDigestedState(client);
			writes.accept(new HurtMotionWrite(action, decoded));
		}
		// doTick sends this after the tracker sampled at the head of the level tick.
		if (this.healthDirty) {
			HealthWrite health = new HealthWrite(
			    action, StateDigest.state(client), this.publishedHealth, this.publishedFood, this.publishedSaturation);
			health.applyToDigestedState(client);
			writes.accept(health);
		}
	}

	private int emittedDamageCount;
	public int emittedDamageCount() {
		return this.emittedDamageCount;
	}

	/** Bind the scheduled branch's world writes and declared region-wide permission. */
	public void enableWorldChanges(final SnapshotView ownedWorld, final Boolean mayInteract, final IntSupplier action,
	    final Consumer<ServerWrite> output) {
		this.authority.worldChanges = (world, x, y, z, successor) -> {
			if (world != ownedWorld) throw new IllegalStateException("server world belongs to another branch");
			if (mayInteract == null)
				throw new UnimplementedMechanicException(
				    Refusal.UNMODELLED_WORLD_WRITE, "cauldron interaction permission is unknown");
			if (!mayInteract) return;
			world.requireCauldronUpdateClosure(x, y, z);
			List<DamageEvent> damage = this.authority.survival().dealt();
			while (this.emittedDamageCount < damage.size())
				output.accept(new DamageWrite(action.getAsInt(), damage.get(this.emittedDamageCount++)));
			world.replaceCell(x, y, z, successor);
			output.accept(new BlockUpdateWrite(action.getAsInt(), x, y, z, successor));
		};
	}

	/** One explicitly scheduled connection tick, without a level tick or packet delivery. */
	public Effects tickConnection(final ServerPlayerState server, final SnapshotView world) {
		beginEvent(server);
		connectionTick(server, world);
		return effects();
	}

	/** One decoded movement packet, without advancing either server clock. */
	public Transaction handleMovePlayer(final PlayerState payload, final MovementPacket packet,
	    final ServerPlayerState server, final SnapshotView world) {
		if (packet == MovementPacket.NONE) throw new IllegalArgumentException("NONE is not a packet");
		beginEvent(server);
		return ServerGamePacketListenerImpl.handleMovePlayer(payload, packet, server, world, this.serverScratch, true);
	}

	/**
         * Deliver the native end-of-client-tick marker after its retained
         * packet batch.
         */
	public void handleClientTickEnd(final ServerPlayerState server, final boolean receivedMovementThisTick) {
		ServerGamePacketListenerImpl.handleClientTickEnd(server, receivedMovementThisTick);
	}

	/** The input packet handler, independently scheduled from movement. */
	public void handlePlayerInput(final ServerPlayerState server, final PlayerInput input) {
		ServerGamePacketListenerImpl.handlePlayerInput(server, input);
	}

	/** The START/STOP_SPRINTING command handler. */
	public void handlePlayerCommand(final ServerPlayerState server, final boolean sprinting) {
		ServerGamePacketListenerImpl.handlePlayerCommand(server, sprinting);
	}

	/**
	 * The {@code START_FALL_FLYING} command handler:
	 * {@code Player.tryToStartFallFlying} on the server's copy, which starts
	 * the glide only when the copy is not already gliding, can glide, and is
	 * not in water; otherwise {@code LivingEntity.stopFallFlying} pulses the
	 * shared flag on and off, which dirties it so the next tracker sample
	 * echoes the stop to the client.
	 */
	public void handleStartFallFlying(final ServerPlayerState server) {
		if (!server.fallFlying && Travel.canGlide(server) && !(server.waterHeight > 0.0)) {
			server.setSharedFlag(7, true);
		} else {
			server.setSharedFlag(7, true);
			server.setSharedFlag(7, false);
		}
	}

	private void beginEvent(final ServerPlayerState server) {
		this.emittedDamageCount = 0;
		server.requireValid();
		FoodData.requireSupported(server);
		if (server.attachedRockets > 0)
			throw new PendingServerWriteException(
			    Refusal.UNMODELLED_SCHEDULE, "rocket actor scheduling is not modelled");
		this.authority.begin(server);
	}

	private Effects effects() {
		return new Effects(this.authority.survival().marked(), this.authority.survival().dealt());
	}

	/** Damage and a possible hurt mark caused by one server event. */
	public record Effects(Optional<HurtCause> hurt, List<DamageEvent> damage) {
		public Effects {
			hurt = Objects.requireNonNull(hurt);
			damage = List.copyOf(damage);
		}
	}

	public HealthWrite healthPublication(final int action, final PlayerState client) {
		return this.healthDirty ? new HealthWrite(action, StateDigest.state(client), this.publishedHealth,
		                              this.publishedFood, this.publishedSaturation)
		                        : null;
	}

	public EntityDataWrite entityDataPublication(final int action, final PlayerState client) {
		return this.serverEntity.publication(action, client);
	}

	/**
	 * Vanilla's safe fall distance: a landing's damage is the floor of the
	 * fall distance plus a millionth minus this, so the first damaging fall is
	 * a full block beyond it.
	 */
	public static double safeFallDistance() {
		return Survival.SAFE_FALL_DISTANCE;
	}

	/**
	 * Whether one client tick's landing will hurt when the server handles its
	 * packet: {@code Entity.checkFallDamage} on the client's own fall
	 * accounting, which matches the server's whenever every tick since the
	 * last landing sent its position. The composed transaction decides the
	 * same rule on the server's copy; this form is for a client-only replay.
	 */
	public static boolean landingHurts(final PlayerState before, final PlayerState after) {
		if (!after.onGround || before.onGround || before.mayfly) {
			return false;
		}
		double fall = before.fallDistance;
		double descent = after.y - before.y;
		if (!(after.waterHeight > 0.0) && descent < 0.0) {
			fall -= (float) descent;
		}
		return Survival.calculateFallDamage(fall) > 0;
	}

	/**
	 * The server's step on the caller-owned server state, advanced in place.
	 * Returns the full transaction report. A correction refuses;
	 * {@link #transact} returns it instead.
	 *
	 * @param clientAfter the client's copy after its tick
	 * @param packet the movement packet form the client's publisher selected
	 *     for that result
	 * @param authorityPending legacy caller hint; never suppresses a server tick
	 * @param tick names the completed action boundary in refusals
	 * @return the transaction, with the hits it dealt and the one that marked
	 * @throws UnpredictedServerWriteException when the listener corrects the
	 *         client's movement packet instead of accepting it
	 * @throws PendingServerWriteException when a correction is still pending,
	 *         so the server's view of this tick is undetermined, and for every
	 *         server outcome {@link #transact} refuses
	 */
	public Transaction advanceInPlace(final PlayerState clientAfter, final MovementPacket packet,
	    final ServerPlayerState server, final PlayerInput action, final SnapshotView world,
	    final boolean authorityPending, final int tick) {
		Transaction transaction = transact(clientAfter, packet, server, action, world, authorityPending);
		// The composed boundary follows the complete client tick packet
		// batch, including ClientTickEnd. At most one movement packet
		// is published here; only an accepted one calls
		// handlePlayerKnownMovement in native code.
		ServerGamePacketListenerImpl.handleClientTickEnd(server,
		    packet != MovementPacket.NONE && !(transaction instanceof Corrected)
		        && !(transaction instanceof AwaitingTeleport));
		if (transaction instanceof Corrected corrected) {
			throw new UnpredictedServerWriteException(tick, corrected);
		}
		return transaction;
	}

	/**
	 * The packet handlers for the client tick's packets, then one level tick
	 * and one connection tick, on a caller-owned server state advanced in
	 * place: one server tick without its tracker sample.
	 *
	 * <p>The transaction reports every hit either phase dealt, in the order
	 * the server dealt them, and the one that marked: the first full hit of a
	 * source outside {@code no_impact}. Every source is in the
	 * {@code no_knockback} tag, so the mark republishes the server's velocity
	 * without changing it. A hit inside the cooldown the previous full hit
	 * installed is partial or refused and marks nothing. A correction is
	 * reported with the hits of the whole tick: the server tick that
	 * teleported the client still runs its level tick and {@code doTick} at
	 * the teleport position before the client's acknowledgement can arrive.
	 *
	 * <p>The input and sprint commands are always delivered, and the
	 * {@code START_FALL_FLYING} command is taken from the flag difference: a
	 * client copy that glides while the server's does not is treated as having
	 * started this tick. That difference is also the tick after the server's
	 * own tick stopped the glide, before the echo reaches the client; the
	 * command then only pulses a flag the stop already dirtied, so the
	 * inference changes nothing there. The composed step supplies all three
	 * from the client tick's own evidence through {@link #handlePackets}.
	 *
	 * @throws PendingServerWriteException when a correction is pending, so
	 *         this tick's server view is undetermined; when the floating
	 *         counter reaches the kick; when an attached rocket would write a
	 *         gliding player's velocity; when the server's own tick would
	 *         damage the glider, since whether it stays usable is not a
	 *         captured fact; and when a hit's outcome is decided by a server
	 *         fact the state does not carry
	 * @throws UnimplementedMechanicException when the server reaches a
	 *         contact or landing body the slice does not model, or kills
	 *         the player
	 */
	public Transaction transact(final PlayerState clientAfter, final MovementPacket packet,
	    final ServerPlayerState server, final PlayerInput action, final SnapshotView world,
	    final boolean authorityPending) {
		Objects.requireNonNull(server, "server").requireValid();
		Objects.requireNonNull(clientAfter, "clientAfter");
		return transact(
		    clientAfter, packet, server, action, world, true, true, clientAfter.fallFlying && !server.fallFlying, true);
	}

	/**
	 * Applies the same transition, emitting damage without building a success
	 * report. The server copy is one the composed step owns: it was admitted
	 * at its boundary and every value since is this step's own, so its
	 * structural validity is not re-derived here.
	 */
	public HurtCause advanceEffects(final PlayerState clientAfter, final MovementPacket packet,
	    final ServerPlayerState server, final PlayerInput action, final SnapshotView world, final boolean sendInput,
	    final boolean sendSprint, final int tick, final Consumer<? super ServerWrite> writes) {
		Objects.requireNonNull(clientAfter, "clientAfter");
		Objects.requireNonNull(server, "server");
		Transaction transaction = transact(clientAfter, packet, server, action, world, sendInput, sendSprint,
		    clientAfter.fallFlying && !server.fallFlying, false);
		// The composed boundary follows the complete client tick packet
		// batch, including ClientTickEnd. At most one movement packet
		// is published here; only an accepted one calls
		// handlePlayerKnownMovement in native code.
		ServerGamePacketListenerImpl.handleClientTickEnd(server,
		    packet != MovementPacket.NONE && !(transaction instanceof Corrected)
		        && !(transaction instanceof AwaitingTeleport));
		if (transaction instanceof Corrected corrected) {
			throw new UnpredictedServerWriteException(tick, corrected);
		}
		for (DamageEvent hit : this.authority.survival().dealt()) {
			writes.accept(new DamageWrite(tick, hit));
		}
		return this.authority.survival().marked().orElse(null);
	}

	/**
	 * The packet processor's drain for one client tick, on the composed
	 * step's owned server copy: the input packet when it changed, the sprint
	 * command when the client's flag changed, {@code START_FALL_FLYING} when
	 * the client tick started a glide, then the movement packet. Every hit
	 * the movement handler deals is emitted at this input.
	 *
	 * @return the hit that marked the player hurt in this phase, which the
	 *     tracker sample that follows publishes, or {@code null}
	 * @throws UnpredictedServerWriteException when the listener corrects the
	 *         movement packet
	 * @throws PendingServerWriteException when a correction is pending or the
	 *         server's outcome is not determined by the composed state
	 */
	public HurtCause handlePackets(final PlayerState clientAfter, final MovementPacket packet,
	    final ServerPlayerState server, final PlayerInput action, final SnapshotView world, final boolean sendInput,
	    final boolean sendSprint, final boolean startFallFlying, final int tick,
	    final Consumer<? super ServerWrite> writes) {
		Objects.requireNonNull(clientAfter, "clientAfter");
		Objects.requireNonNull(packet, "packet");
		Objects.requireNonNull(server, "server");
		Objects.requireNonNull(action, "action");
		Objects.requireNonNull(world, "world");
		beginStep(clientAfter, server);
		Transaction transaction =
		    handlers(clientAfter, packet, server, action, world, sendInput, sendSprint, startFallFlying, false);
		// The composed boundary follows the complete client tick packet
		// batch, including ClientTickEnd. At most one movement packet
		// is published here; only an accepted one calls
		// handlePlayerKnownMovement in native code.
		ServerGamePacketListenerImpl.handleClientTickEnd(server,
		    packet != MovementPacket.NONE && !(transaction instanceof Corrected)
		        && !(transaction instanceof AwaitingTeleport));
		if (transaction instanceof Corrected corrected) {
			throw new UnpredictedServerWriteException(tick, corrected);
		}
		List<DamageEvent> dealt = this.authority.survival().dealt();
		for (DamageEvent hit : dealt) {
			writes.accept(new DamageWrite(tick, hit));
		}
		this.emittedDamageCount = dealt.size();
		return this.authority.survival().marked().orElse(null);
	}

	/**
	 * Bind the composed step's owned server copy for the phases of a server
	 * tick that continue past its packet drain: the tracker sample and the
	 * ticks. Hits dealt before are already on the stream.
	 */
	public void beginTick(final PlayerState client, final ServerPlayerState server) {
		beginStep(client, server);
	}

	/**
	 * The level tick and the connection tick after the tracker sample, on the
	 * composed step's owned server copy. Every hit they deal is emitted at
	 * this input.
	 *
	 * @return the first full marking hit of these phases, which the next
	 *     tracker sample publishes, or {@code null}; the sample cleared the
	 *     packet phase's mark
	 */
	public HurtCause advanceTicks(final ServerPlayerState server, final SnapshotView world, final int tick,
	    final Consumer<? super ServerWrite> writes) {
		levelTick(server);
		connectionTick(server, world);
		List<DamageEvent> dealt = this.authority.survival().dealt();
		HurtCause marked = null;
		for (int index = this.emittedDamageCount; index < dealt.size(); index++) {
			DamageEvent hit = dealt.get(index);
			writes.accept(new DamageWrite(tick, hit));
			if (marked == null && hit.full() && hit.cause().marksHurt()) {
				marked = hit.cause();
			}
		}
		this.emittedDamageCount = dealt.size();
		return marked;
	}

	private void beginStep(final PlayerState clientAfter, final ServerPlayerState server) {
		FoodData.requireSupported(server);
		if (server.attachedRockets > 0 && (server.fallFlying || clientAfter.fallFlying)) {
			throw new PendingServerWriteException(Refusal.UNMODELLED_SCHEDULE,
			    server.attachedRockets + " attached firework rocket(s) replace the gliding player's velocity on"
			        + " every actor tick, on both sides; the rocket's server-private lifetime"
			        + " and actor scheduling are outside the slice");
		}
		this.emittedDamageCount = 0;
		this.authority.begin(server);
	}

	/** A correction is always reported; an unrequested success report returns null. */
	private Transaction transact(final PlayerState clientAfter, final MovementPacket packet,
	    final ServerPlayerState server, final PlayerInput action, final SnapshotView world, final boolean sendInput,
	    final boolean sendSprint, final boolean startFallFlying, final boolean reportSuccess) {
		Objects.requireNonNull(packet, "packet");
		Objects.requireNonNull(action, "action");
		Objects.requireNonNull(world, "world");
		beginStep(clientAfter, server);
		// MinecraftServer.processPacketsAndTick drains the client's packets
		// before tickServer; the level tick and the connection tick then run on
		// the copy at the accepted target and restore the packet-owned position.
		Transaction transaction =
		    handlers(clientAfter, packet, server, action, world, sendInput, sendSprint, startFallFlying, reportSuccess);
		levelTick(server);
		connectionTick(server, world);
		Optional<HurtCause> marked = this.authority.survival().marked();
		List<DamageEvent> dealt = this.authority.survival().dealt();
		if (transaction instanceof Corrected corrected) {
			return new Corrected(corrected.packet(), corrected.cause(), corrected.targetX(), corrected.targetY(),
			    corrected.targetZ(), corrected.resolvedX(), corrected.resolvedY(), corrected.resolvedZ(),
			    corrected.residualSquared(), corrected.correctionX(), corrected.correctionY(), corrected.correctionZ(),
			    corrected.correctionYRot(), corrected.correctionXRot(), marked, dealt);
		}
		if (!reportSuccess) {
			return null;
		}
		return switch (transaction) {
			case Unpublished ignored -> new Unpublished(marked, dealt);
			case Accepted accepted ->
				new Accepted(accepted.packet(), accepted.targetX(), accepted.targetY(), accepted.targetZ(),
				    accepted.residualSquared(), accepted.forgivenByOccupiedStart(), accepted.clientIsFloating(),
				    accepted.floatingUnknown(), marked, dealt);
			case AwaitingTeleport awaiting -> new AwaitingTeleport(awaiting.packet(), marked, dealt);
			case Corrected corrected -> corrected;
		};
	}

	/**
	 * The handlers of one client tick's packets in their wire order: input,
	 * sprint command, glide command, movement. LocalPlayer sends the commands
	 * independently of sendPosition's movement choice.
	 */
	private Transaction handlers(final PlayerState clientAfter, final MovementPacket packet,
	    final ServerPlayerState server, final PlayerInput action, final SnapshotView world, final boolean sendInput,
	    final boolean sendSprint, final boolean startFallFlying, final boolean reportSuccess) {
		if (sendInput) ServerGamePacketListenerImpl.handlePlayerInput(server, action);
		if (sendSprint) ServerGamePacketListenerImpl.handlePlayerCommand(server, clientAfter.sprinting);
		if (startFallFlying) handleStartFallFlying(server);
		if (packet == MovementPacket.NONE) {
			return reportSuccess
			    ? new Unpublished(this.authority.survival().marked(), this.authority.survival().dealt())
			    : Unpublished.NONE;
		}
		if (server.correctionPending) {
			throw new PendingServerWriteException(Refusal.PENDING_WRITE,
			    "a correction is pending the client's"
			        + " acknowledgement; movement packets only apply rotation until then");
		}
		Transaction transaction = ServerGamePacketListenerImpl.handleMovePlayer(
		    clientAfter, packet, server, world, this.serverScratch, reportSuccess);
		return transaction == null ? Unpublished.NONE : transaction;
	}

	/**
	 * {@code ServerPlayer.tick} in the level's entity tick: the hit cooldown
	 * counts down and {@code ServerLevel.tickNonPassenger} advances the
	 * entity tick count.
	 */
	public static void levelTick(final ServerPlayerState server) {
		if (server.invulnerableTime > 0) {
			server.invulnerableTime--;
		}
		if (server.tickCount != ServerPlayerState.UNKNOWN_TICK_COUNT) {
			server.tickCount = (int) (server.tickCount + 1);
		}
	}

	/**
	 * {@code ServerGamePacketListenerImpl.tickPlayer}: {@code doTick} on the
	 * server's copy, the position anchor restored afterwards, then the
	 * floating counter, which a packet-set latch keeps advancing until an
	 * accepted packet clears it.
	 */
	private void connectionTick(final ServerPlayerState server, final SnapshotView world) {
		ServerGamePacketListenerImpl.resetPosition(server);
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
				throw new PendingServerWriteException(Refusal.UNMODELLED_SESSION_END,
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

	/** What the listener did with the movement packet the client tick sent. */
	public sealed interface Transaction permits Unpublished, Accepted, Corrected, AwaitingTeleport {
		/** The movement packet form the transaction decoded. */
		MovementPacket packet();

		/** The hit this step marked the player hurt with, if any. */
		Optional<HurtCause> hurt();

		/** Every hit this step dealt, in the order the server dealt them. */
		List<DamageEvent> damage();
	}

	/** A movement packet updated rotation while its teleport acknowledgement was pending. */
	public record AwaitingTeleport(MovementPacket packet, Optional<HurtCause> hurt, List<DamageEvent> damage)
	    implements Transaction {
		public AwaitingTeleport {
			damage = List.copyOf(damage);
		}
	}

	/**
	 * The client tick sent no movement packet; input and command handlers and
	 * the connection tick still run.
	 */
	public record Unpublished(Optional<HurtCause> hurt, List<DamageEvent> damage) implements Transaction {
		/** The unrequested success report, before its hits are collected. */
		static final Unpublished NONE = new Unpublished(Optional.empty(), List.of());

		public Unpublished {
			Objects.requireNonNull(hurt, "hurt");
			damage = List.copyOf(damage);
		}

		@Override
		public MovementPacket packet() {
			return MovementPacket.NONE;
		}
	}

	/**
	 * The listener accepted the decoded target: the server snapped to it and
	 * installed the packet's ground and contact bits.
	 *
	 * @param residualSquared the horizontal residual the moved-wrongly test measured
	 * @param forgivenByOccupiedStart the residual exceeded the quarter block but
	 *     the pre-packet box was already occupied, which vanilla forgives
	 * @param clientIsFloating the floating latch the acceptance computed
	 * @param floatingUnknown the latch could not be decided because its air
	 *     query reached undeclared space; it is held false and the kick is
	 *     refused as undetermined if the counter reaches it
	 * @param hurt the first marking hit of the movement handler or the ticks
	 *     that followed it, whichever came first
	 * @param damage every hit the step dealt, in order
	 */
	public record Accepted(MovementPacket packet, double targetX, double targetY, double targetZ,
	    double residualSquared, boolean forgivenByOccupiedStart, boolean clientIsFloating, boolean floatingUnknown,
	    Optional<HurtCause> hurt, List<DamageEvent> damage) implements Transaction {
		public Accepted {
			Objects.requireNonNull(hurt, "hurt");
			damage = List.copyOf(damage);
		}
	}

	/** Why the listener corrected the movement packet. */
	public enum Cause {
		/** The displacement exceeded the connection interval's packet-scaled movement budget. */
		MOVED_TOO_QUICKLY,
		/** A movement packet triggered the pending teleport's twenty-tick retry. */
		TELEPORT_RETRY,
		/** The horizontal residual after collision exceeded the strict quarter block. */
		MOVED_WRONGLY,
		/** The target box meets a block shape the pre-packet box did not. */
		NEW_COLLISION
	}

	/**
	 * The listener sent the client back to its pre-packet position with the
	 * packet's rotation and zero velocity, and awaits the acknowledgement.
	 */
	public record Corrected(MovementPacket packet, Cause cause, double targetX, double targetY, double targetZ,
	    double resolvedX, double resolvedY, double resolvedZ, double residualSquared, double correctionX,
	    double correctionY, double correctionZ, float correctionYRot, float correctionXRot, Optional<HurtCause> hurt,
	    List<DamageEvent> damage) implements Transaction {
		public Corrected {
			Objects.requireNonNull(hurt, "hurt");
			damage = List.copyOf(damage);
		}
	}
}
