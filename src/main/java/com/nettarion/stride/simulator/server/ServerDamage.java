package com.nettarion.stride.simulator.server;

import com.nettarion.stride.simulator.DamageEvent;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.RefusalCause;
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
 * The damage and fire sink of one server transaction: every hit and ignition on the server's copy goes through the
 * one instance {@link ServerTick} bound to it, which records each hit as a {@link DamageEvent} in the order dealt.
 *
 * <p>A hit is {@code Player.hurtServer} through {@code LivingEntity.hurtServer} and {@code Player.actuallyHurt}: the
 * game rules, the ability gate, the cooldown branch, absorption before health, and the mark. The tick phases and the
 * block behaviors reach the instance through {@link TickAuthority#damage()}, so a body deals its hit where vanilla
 * deals it; the block package also reads {@link #bodyVolume()} and {@link #suppressingBounce()} here because this is
 * the only server-side object a block behavior is handed. Amounts are in health points. Not thread-safe.
 *
 * <p>Refuses a hit on a player who may fly unless the source bypasses invulnerability, since whether the ability
 * came with invulnerability is not a captured fact; a hit that kills; and a fire contact whose outcome depends on the
 * server's random increment.
 */
public final class ServerDamage {
	/** Vanilla's {@code SAFE_FALL_DISTANCE} attribute at its player default, in blocks; no enchantment or effect. */
	static final double SAFE_FALL_DISTANCE = 3.0;

	/** {@code LivingEntity.calculateFallPower} adds this before subtracting the safe distance. */
	private static final double FALL_POWER_EPSILON = 1.0E-6;

	/** {@code LivingEntity.hurtServer}: the cooldown a full hit installs, in ticks. */
	private static final int HIT_COOLDOWN_TICKS = 20;

	/** Above this many cooldown ticks a hit lands inside the cooldown. */
	private static final int COOLDOWN_GATE_TICKS = 10;

	/** {@code BaseFireBlock.fireIgnite}: eight seconds of burning, in ticks. */
	private static final int FIRE_BLOCK_TICKS = 160;

	/** {@code Entity.lavaIgnite}: fifteen seconds of burning, in ticks. */
	private static final int LAVA_TICKS = 300;

	/**
	 * Every {@link ServerPlayerState} field a hit may write. Public so the declared write sets of the tick phases
	 * can be checked against the fields they touch ({@code PhaseWriteSetTest}).
	 */
	public static final Set<String> HURT_WRITES = Set.of("health", "absorption", "invulnerableTime", "lastHurt",
	    "exhaustionLevel", "currentImpulseContextResetGraceTime", "currentImpulseImpactPosPresent",
	    "currentImpulseImpactPosX", "currentImpulseImpactPosY", "currentImpulseImpactPosZ");

	/** The hits of the transaction in progress; copied out only when there were any. */
	private final List<DamageEvent> damage = new ArrayList<>(2);

	private ServerPlayerState server;

	private HurtCause marked;

	/** An unbound sink; {@link #begin} binds it. */
	public ServerDamage() {}

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

	/** The mark and the hits together, as a transaction reports them. */
	ServerTick.Effects effects() {
		return new ServerTick.Effects(marked(), dealt());
	}

	/**
	 * {@code Player.hurtServer} on the bare player: the game rules, the ability gate, the cooldown branch,
	 * absorption before health, and the mark. Records the event; the first full hit of a marking source marks.
	 *
	 * @param cause the damage source
	 * @param attempted the amount before the cooldown and absorption, in health points
	 * @throws PendingServerWriteException when the player may fly and the source does not bypass invulnerability
	 * @throws UnimplementedMechanicException when the hit kills the player
	 * @throws IllegalStateException when no transaction is bound
	 */
	public void hurtServer(final HurtCause cause, final float attempted) {
		ServerPlayerState server = requireBound();
		// Player.hurtServer asks isInvulnerableTo first: a disabled damage
		// game rule drops the hit before the ability, death and cooldown gates.
		if (!cause.allowedByGameRules(server)) {
			return;
		}
		// Player.hurtServer drops the hit for abilities.invulnerable unless the
		// type bypasses invulnerability.
		if (server.mayfly && !cause.bypassesInvulnerability()) {
			throw new PendingServerWriteException(RefusalCause.PENDING_ABILITY,
			    "the server would deal " + cause + " damage to a player who may fly; whether the ability came with"
			        + " invulnerability is not a captured fact");
		}
		// Every admitted source deals at least one point, so the amount gate is
		// never taken; it is not a vanilla step. Vanilla clamps a negative amount
		// to zero and still installs the cooldown and the mark for a zero hit.
		if (server.dead() || attempted <= 0.0F) {
			return;
		}
		boolean full;
		float applied;
		if (server.invulnerableTime > COOLDOWN_GATE_TICKS) {
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

	/** {@code getBbWidth() * getBbWidth() * getBbHeight()} of the server's copy, in float as vanilla multiplies it. */
	public float bodyVolume() {
		float width = requireBound().pose.width;
		return width * width * this.server.pose.height;
	}

	/** {@code Entity.isSuppressingBounce} on the server's copy: the synced shift flag. */
	public boolean suppressingBounce() {
		return requireBound().shiftKeyDown;
	}

	/**
	 * {@code Player.causeFallDamage}: nothing when the player may fly, else the floored fall power times the block's
	 * multiplier as one {@link HurtCause#FALL}-family hit, with the wind-charge impact height capping the fall.
	 *
	 * @param fall the accumulated fall, in blocks
	 * @param multiplier the landed block's damage multiplier
	 * @param cause {@link HurtCause#FALL} or {@link HurtCause#STALAGMITE}
	 */
	public void causeFallDamage(final double fall, final float multiplier, final HurtCause cause) {
		ServerPlayerState server = requireBound();
		if (server.mayfly) {
			return;
		}
		double effectiveFallDistance = fall;
		// Vanilla gates this on currentImpulseImpactPos != null &&
		// ignoreFallDamageFromCurrentImpulse; the state folds both into one
		// boolean, so an impact position retained after the ignore flag was
		// cleared still caps the fall here. See the ServerPlayerState request
		// to split the pair.
		if (server.currentImpulseImpactPosPresent) {
			effectiveFallDistance = Math.min(fall, server.currentImpulseImpactPosY - server.y);
			if (effectiveFallDistance <= 0.0) {
				server.resetCurrentImpulseContext();
			} else {
				server.tryResetCurrentImpulseContext();
			}
		}
		int amount = calculateFallDamage(effectiveFallDistance, multiplier);
		if (amount > 0) {
			server.resetCurrentImpulseContext();
			hurtServer(cause, amount);
		}
	}

	/**
	 * {@code BaseFireBlock.fireIgnite} on a server player: one tick through the immune window, a random one or two
	 * while burning, then eight seconds. The random increment only changes the outcome from the last tick of the
	 * eight seconds upward, which refuses.
	 *
	 * @throws PendingServerWriteException when the outcome depends on the random increment
	 */
	public void fireIgnite() {
		ServerPlayerState server = requireBound();
		if (server.remainingFireTicks < 0) {
			server.remainingFireTicks++;
		} else if (server.remainingFireTicks >= FIRE_BLOCK_TICKS - 1) {
			throw new PendingServerWriteException(RefusalCause.PENDING_SERVER_RANDOM,
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
	 * {@code Entity.lavaIgnite}: {@code igniteForSeconds(15)}. The clear-freeze half of {@code igniteForTicks} is
	 * collected with the visit.
	 */
	public void lavaIgnite() {
		ServerPlayerState server = requireBound();
		server.remainingFireTicks = Math.max(server.remainingFireTicks, LAVA_TICKS);
	}

	/** {@code Entity.clearFire}: burning stops, the immune window is kept. */
	public void clearFire() {
		ServerPlayerState server = requireBound();
		server.remainingFireTicks = Math.min(0, server.remainingFireTicks);
	}

	/** {@code LivingEntity.calculateFallDamage}: the floored fall power times the block's multiplier. */
	static int calculateFallDamage(final double fallDistance, final float multiplier) {
		return Mth.floor((fallDistance + FALL_POWER_EPSILON - SAFE_FALL_DISTANCE) * (double) multiplier * 1.0);
	}

	/** {@code Player.actuallyHurt} for the admitted bare player, after the cooldown gate. */
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
			throw UnimplementedMechanicException.deferred(RefusalCause.UNMODELED_SESSION_END,
			    ()
			        -> "the server kills the player with " + cause
			        + " damage; death and respawn are outside the admitted domain");
		}
		return afterAbsorption;
	}

	private ServerPlayerState requireBound() {
		if (this.server == null) {
			throw new IllegalStateException("no server transaction is bound");
		}
		return this.server;
	}
}
