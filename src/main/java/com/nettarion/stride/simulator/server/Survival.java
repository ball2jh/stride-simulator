package com.nettarion.stride.simulator.server;

import com.nettarion.stride.simulator.DamageEvent;
import com.nettarion.stride.simulator.Refusal;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.tick.PlayerTick;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The server-only survival effects of the transaction in progress on the
 * server's copy: {@code Player.hurtServer} through
 * {@code LivingEntity.hurtServer} and {@code Player.actuallyHurt}, each hit
 * a {@link DamageEvent} on the stream in the order the server dealt it, and
 * the fire ignitions and extinguish the block bodies apply.
 *
 * <p>{@link ServerTick} binds one instance to the server's copy at the start
 * of each transaction and reads the hits off it at the end. The tick's
 * phases and the block behaviours reach it through {@link TickAuthority#survival()}
 * under server authority, so a body deals its hit where vanilla deals it:
 * burning, the void, suffocation, and drowning in {@link com.nettarion.stride.simulator.tick.BaseTick}, the
 * glide's wall impact in {@link com.nettarion.stride.simulator.tick.Travel}, the contact bodies in
 * {@link com.nettarion.stride.simulator.block.BlockEffects}, the freezing hit in {@link com.nettarion.stride.simulator.tick.Freezing}, and the
 * landing in {@link ServerGamePacketListenerImpl}. A hit runs the cooldown branch (a
 * hit inside the cooldown the previous full hit installed is the difference
 * above it, or refused), absorption before health, and the mark: the first
 * full hit of a source outside {@code no_impact} marks the player hurt, and
 * every source is in {@code no_knockback}, so the mark republishes the
 * server's velocity without changing it.
 *
 * <p>Refuses a hit on a player who may fly, whose invulnerability is not a
 * captured fact, unless the source bypasses invulnerability (falling out of
 * the world); a hit that kills; and fire contact whose outcome depends on
 * the server's random increment.
 */
public final class Survival {
	/** Vanilla's {@code SAFE_FALL_DISTANCE} attribute at its player default. */
	static final double SAFE_FALL_DISTANCE = 3.0;
	/** {@code LivingEntity.calculateFallPower} adds this before subtracting the safe distance. */
	private static final double FALL_POWER_EPSILON = 1.0E-6;
	/** {@code LivingEntity.hurtServer}: the cooldown a full hit installs. */
	private static final int HIT_COOLDOWN_TICKS = 20;
	/** {@code BaseFireBlock.fireIgnite}: eight seconds of burning. */
	private static final int FIRE_BLOCK_TICKS = 160;
	/** {@code Entity.lavaIgnite}: fifteen seconds of burning. */
	private static final int LAVA_TICKS = 300;

	/** Every {@link ServerPlayerState} field a hit may write. */
	public static final Set<String> HURT_WRITES = Set.of("health", "absorption", "invulnerableTime", "lastHurt",
	    "exhaustionLevel", "currentImpulseContextResetGraceTime", "currentImpulseImpactPosPresent",
	    "currentImpulseImpactPosX", "currentImpulseImpactPosY", "currentImpulseImpactPosZ");

	private ServerPlayerState server;
	/** The hits of the transaction in progress; copied out only when there were any. */
	private final List<DamageEvent> damage = new ArrayList<>(2);
	private HurtCause marked;

	/** Bind to the server's copy for one transaction and forget the last one's hits. */
	void begin(final ServerPlayerState server) {
		this.server = Objects.requireNonNull(server, "server");
		this.damage.clear();
		this.marked = null;
	}

	/** The hit that marked the player hurt this transaction, if any. */
	public Optional<HurtCause> marked() {
		return Optional.ofNullable(this.marked);
	}

	/** The hits of the transaction in progress, as an immutable list. */
	public List<DamageEvent> dealt() {
		return this.damage.isEmpty() ? List.of() : List.copyOf(this.damage);
	}

	/**
	 * {@code Player.hurtServer} through {@code LivingEntity.hurtServer} and
	 * {@code Player.actuallyHurt} for the bare player: the cooldown branch,
	 * absorption before health, and the mark. Records the event; the first
	 * full hit of a marking source marks.
	 *
	 * @throws PendingServerWriteException when the player may fly and the
	 *         source does not bypass invulnerability, since whether the
	 *         ability came with invulnerability is not a captured fact
	 * @throws UnimplementedMechanicException when the hit kills the player
	 */
	public void hurtServer(final HurtCause cause, final float attempted) {
		ServerPlayerState server = this.server;
		// Player.hurtServer asks isInvulnerableTo first: a disabled damage
		// gamerule drops the hit before the ability, death and cooldown gates.
		if (!cause.permittedBy(server)) {
			return;
		}
		// Player.hurtServer drops the hit for abilities.invulnerable unless the
		// type bypasses invulnerability.
		if (server.mayfly && !cause.bypassesInvulnerability()) {
			throw new PendingServerWriteException(Refusal.PENDING_ABILITY,
			    "the server would deal " + cause + " damage to a player who may fly; whether the ability came with"
			        + " invulnerability is not a captured fact");
		}
		if (server.dead() || attempted <= 0.0F) {
			return;
		}
		boolean full;
		float applied;
		if (server.invulnerableTime > 10) {
			if (attempted <= server.lastHurt) {
				this.damage.add(new DamageEvent(cause, attempted, 0.0F, 0.0F, server.health, false));
				return;
			}
			applied = attempted - server.lastHurt;
			server.lastHurt = attempted;
			full = false;
		} else {
			server.lastHurt = attempted;
			server.invulnerableTime = HIT_COOLDOWN_TICKS;
			applied = attempted;
			full = true;
		}
		float afterAbsorption = actuallyHurt(cause, applied);
		float absorbed = applied - afterAbsorption;
		this.damage.add(new DamageEvent(cause, attempted, absorbed, afterAbsorption, server.health, full));
		if (full && cause.marksHurt() && this.marked == null) {
			this.marked = cause;
		}
	}

	/** Player.actuallyHurt for the admitted bare player, after the cooldown gate. */
	private float actuallyHurt(final HurtCause cause, final float applied) {
		ServerPlayerState server = this.server;
		float afterAbsorption = Math.max(applied - server.absorption, 0.0F);
		float absorbed = applied - afterAbsorption;
		// setAbsorptionAmount clamps to [0, MAX_ABSORPTION]. The float
		// subtraction can land a hair below zero when the hit spends the
		// whole amount; the upper bound is inert, since the amount never
		// exceeds the attribute and the hit only lowers it.
		server.absorption = Math.max(server.absorption - absorbed, 0.0F);
		if (afterAbsorption != 0.0F) {
			PlayerTick.causeFoodExhaustion(server, cause.foodExhaustion());
			server.health -= afterAbsorption;
		}
		if (server.health <= 0.0F) {
			server.health = 0.0F;
			throw UnimplementedMechanicException.deferred(Refusal.UNMODELLED_SESSION_END,
			    () -> "the server kills the player with " + cause + " damage; death and respawn are outside the slice");
		}
		return afterAbsorption;
	}

	/** {@code getBbWidth() * getBbWidth() * getBbHeight()} of the server's copy, in float as vanilla multiplies it. */
	public float bodyVolume() {
		float width = this.server.pose.width;
		return width * width * this.server.pose.height;
	}

	/** {@code Entity.isSuppressingBounce} on the server's copy: the synced shift flag. */
	public boolean suppressingBounce() {
		return this.server.shiftKeyDown;
	}

	/** {@code Player.causeFallDamage}: nothing when the player may fly, else the floored hit. */
	public void causeFallDamage(final double fall, final float multiplier, final HurtCause cause) {
		if (this.server.mayfly) {
			return;
		}
		double effectiveFallDistance = fall;
		if (this.server.currentImpulseImpactPosPresent) {
			effectiveFallDistance = Math.min(fall, this.server.currentImpulseImpactPosY - this.server.y);
			if (effectiveFallDistance <= 0.0)
				this.server.resetCurrentImpulseContext();
			else
				this.server.tryResetCurrentImpulseContext();
		}
		int amount = calculateFallDamage(effectiveFallDistance, multiplier);
		if (amount > 0) {
			this.server.resetCurrentImpulseContext();
			hurtServer(cause, amount);
		}
	}

	/**
	 * {@code BaseFireBlock.fireIgnite} on a server player: one tick through
	 * the immune window, a random one or two while burning, then eight
	 * seconds. The random increment only changes the outcome from the last
	 * tick of the eight seconds upward, which refuses.
	 */
	public void fireIgnite() {
		ServerPlayerState server = this.server;
		if (server.remainingFireTicks < 0) {
			server.remainingFireTicks++;
		} else if (server.remainingFireTicks >= FIRE_BLOCK_TICKS - 1) {
			throw new PendingServerWriteException(Refusal.PENDING_SERVER_RANDOM,
			    "repeated fire contact at " + server.remainingFireTicks + " burning ticks depends on the server's"
			        + " random one-or-two tick increment");
		} else {
			// Either increment lands below the eight seconds, which then win.
			server.remainingFireTicks++;
		}
		if (server.remainingFireTicks >= 0) {
			server.remainingFireTicks = Math.max(server.remainingFireTicks, FIRE_BLOCK_TICKS);
		}
	}

	/**
	 * {@code Entity.lavaIgnite}: {@code igniteForSeconds(15)}. The clear-freeze
	 * half of {@code igniteForTicks} is collected with the visit.
	 */
	public void lavaIgnite() {
		this.server.remainingFireTicks = Math.max(this.server.remainingFireTicks, LAVA_TICKS);
	}

	/** {@code Entity.clearFire}: burning stops, the immune window is kept. */
	public void clearFire() {
		this.server.remainingFireTicks = Math.min(0, this.server.remainingFireTicks);
	}

	/**
	 * {@code LivingEntity.calculateFallDamage} for the bare player on an
	 * ordinary block: multiplier one, no protection, the player's default
	 * safe distance.
	 */
	static int calculateFallDamage(final double fallDistance) {
		return calculateFallDamage(fallDistance, 1.0F);
	}

	/** {@code LivingEntity.calculateFallDamage}: the floored fall power times the block's multiplier. */
	static int calculateFallDamage(final double fallDistance, final float multiplier) {
		return Mth.floor((fallDistance + FALL_POWER_EPSILON - SAFE_FALL_DISTANCE) * (double) multiplier * 1.0);
	}
}
