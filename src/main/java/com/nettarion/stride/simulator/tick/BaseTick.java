package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.server.Survival;
import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.world.WorldView;
import java.util.HashSet;
import java.util.Set;

/**
 * What the player is immersed in at tick start: {@code Entity.baseTick} and
 * {@code LivingEntity.baseTick}, the first phase of {@link ClientTick}.
 *
 * <p>Vanilla saves the prior eye-water result, refreshes the fluid trackers
 * over the box deflated by a thousandth and applies their current, updates
 * the swimming latch from the sprint flag and the feet block, and halves the
 * fall distance in lava. The powder-snow latch rolls over here too, before
 * the block effects can set it again.
 *
 * <p>On the server's copy the survival branches of both base ticks are
 * live, at the pre-movement position and pose with the fluid facts this
 * tick's refresh produced: the burning tick between the fluid refresh and
 * the lava fall halving, then {@code checkBelowWorld}, then
 * {@code LivingEntity.baseTick}'s suffocation and drowning. Each hit goes
 * through {@link com.nettarion.stride.simulator.server.TickAuthority#survival()}.
 *
 * <p>Reads the box, the eye latch, the sprint and flight flags, and the
 * previous powder-snow bit. Writes the fields in {@link #WRITES} and
 * {@link Scratch#wasUnderWaterAtTickStart}. Runs on both copies of the player.
 */
public final class BaseTick {
	/** Every {@link ServerPlayerState} field this phase may write. */
	public static final Set<String> WRITES;

	static {
		Set<String> writes = new HashSet<>(Survival.HURT_WRITES);
		writes.addAll(Set.of("entityDataDirty", "wasInPowderSnow", "isInPowderSnow", "waterHeight", "lavaHeight",
		    "eyeInWater", "fallDistance", "deltaMovementX", "deltaMovementY", "deltaMovementZ", "swimming",
		    "remainingFireTicks", "airSupply", "sharedFlagOnFire"));
		WRITES = Set.copyOf(writes);
	}

	/** {@code LivingEntity.shouldTakeDrowningDamage}: the air supply that drowns. */
	private static final int DROWNING_AIR = -20;
	/** {@code Entity.checkBelowWorld}: this far under the world's floor. */
	private static final int BELOW_WORLD = 64;

	private BaseTick() {}

	/** {@code Entity.baseTick}: the fluid ordering and the previous-eye swimming latch. */
	public static void run(final PlayerState state, final WorldView world, final Scratch scratch) {
		state.wasInPowderSnow = state.isInPowderSnow;
		state.isInPowderSnow = false;
		boolean wasEyeInWater = state.eyeInWater;
		scratch.wasUnderWaterAtTickStart = wasEyeInWater;
		EntityFluidInteraction.update(state, world, scratch);
		updateSwimming(state, wasEyeInWater, world, scratch);
		if (scratch.authority.isServer()) {
			burn(scratch.authority.serverState(), scratch.authority.survival());
		}
		if (state.lavaHeight > 0.0) {
			state.fallDistance *= 0.5;
		}
		if (scratch.authority.isServer()) {
			ServerPlayerState server = scratch.authority.serverState();
			checkBelowWorld(server, world, scratch.authority.survival());
			server.setSharedFlag(0, server.remainingFireTicks > 0);
			livingEntityBaseTick(server, world, scratch.authority.survival());
		}
	}

	/** Player.updateSwimming's abilities gate followed by Entity.updateSwimming. */
	private static void updateSwimming(
	    final PlayerState state, final boolean wasUnderWater, final WorldView world, final Scratch scratch) {
		if (state.flying) {
			state.setSwimming(false);
		} else if (state.swimming) {
			state.setSwimming(state.sprinting && state.waterHeight > 0.0);
		} else {
			// Entering asks isUnderWater, then the fluid of the feet *block*.
			// The call is virtual: the server's copy runs Entity.isUnderWater,
			// the eye latch sampled before this tick's tracker refresh together
			// with the refreshed in-water answer, while LocalPlayer overrides it
			// with the latch alone. So a sprinting player whose eyes were under
			// at the previous refresh but whose box now clears the surface inside
			// a water cell, such as one a bubble column lifted into the top of
			// its column, starts swimming on the client and not on the server.
			boolean underWater =
			    scratch.authority.isServer() ? wasUnderWater && state.waterHeight > 0.0 : wasUnderWater;
			state.setSwimming(state.sprinting && underWater
			    && EntityFluidInteraction.kindAt(
			           world, Mth.floor(state.x), Mth.floor(state.y), Mth.floor(state.z), scratch)
			        == FluidSample.Kind.WATER);
		}
	}

	/**
	 * {@code Entity.baseTick} on a server level: a burning player is hurt
	 * every twentieth remaining tick unless in lava, and the count runs down.
	 */
	private static void burn(final ServerPlayerState server, final Survival survival) {
		if (server.remainingFireTicks > 0) {
			if (server.remainingFireTicks % 20 == 0 && !(server.lavaHeight > 0.0)) {
				survival.hurtServer(HurtCause.ON_FIRE, 1.0F);
			}
			server.remainingFireTicks--;
		}
	}

	/** {@code Entity.checkBelowWorld}: {@code onBelowWorld}'s hit past the floor. */
	private static void checkBelowWorld(
	    final ServerPlayerState server, final WorldView world, final Survival survival) {
		if (server.y < world.minY() - BELOW_WORLD) {
			survival.hurtServer(HurtCause.FELL_OUT_OF_WORLD, 4.0F);
		}
	}

	/**
	 * The survival branches of {@code LivingEntity.baseTick}: suffocating in
	 * a wall, then drowning or the air refill, in that order.
	 *
	 * @throws PendingServerWriteException when the air of a player who may
	 *         fly would count down, since whether the ability came with
	 *         invulnerability is not a captured fact
	 */
	private static void livingEntityBaseTick(
	    final ServerPlayerState server, final WorldView world, final Survival survival) {
		// LivingEntity.isInWall: a box of eight tenths of the width and a
		// millionth of height at the eye, against suffocating collision.
		double halfWidth = server.pose.width * 0.8F / 2.0;
		double eyeY = server.y + server.pose.eyeHeight;
		if (world.suffocatingShapeMeets(server.x - halfWidth, eyeY - 0.5E-6, server.z - halfWidth, server.x + halfWidth,
		        eyeY + 0.5E-6, server.z + halfWidth)) {
			survival.hurtServer(HurtCause.IN_WALL, 1.0F);
		}
		boolean eyesInWater = server.eyeInWater
		    && world.bubbleColumnModeAt(Mth.floor(server.x), Mth.floor(eyeY), Mth.floor(server.z))
		        == WorldView.BubbleColumnMode.NONE;
		if (eyesInWater) {
			if (server.mayfly) {
				throw new PendingServerWriteException(RefusalCause.PENDING_ABILITY,
				    "the server would count down the"
				        + " air of a player who may fly; whether the ability came with"
				        + " invulnerability is not a captured fact");
			}
			server.airSupply--;
			if (server.airSupply <= DROWNING_AIR) {
				server.airSupply = 0;
				survival.hurtServer(HurtCause.DROWN, 2.0F);
			}
		} else if (server.airSupply < ServerPlayerState.DEFAULT_MAXIMUM_AIR) {
			server.airSupply = Math.min(server.airSupply + 4, ServerPlayerState.DEFAULT_MAXIMUM_AIR);
		}
	}
}
