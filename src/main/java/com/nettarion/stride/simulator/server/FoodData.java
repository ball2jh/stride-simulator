package com.nettarion.stride.simulator.server;

import com.nettarion.stride.simulator.Refusal;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.ServerPlayerState;
import java.util.Set;

/** FoodData's exhaustion and natural regeneration for an admitted non-peaceful player. */
public final class FoodData {
	/** The retained fields FoodData.tick can write before a publication refuses. */
	public static final Set<String> WRITES = Set.of("exhaustionLevel", "saturationLevel", "tickTimer", "health",
	    "foodLevel", "absorption", "invulnerableTime", "lastHurt");

	private FoodData() {}

	/** The constructed food state must identify the non-peaceful difficulty. */
	static void requireSupported(final ServerPlayerState state) {
		if (state.difficulty == null)
			throw new PendingServerWriteException(Refusal.PENDING_SURVIVAL_FACT, "unknown food difficulty");
	}

	/** FoodData.tick, after Player.tick and before the connection restores position. */
	public static void tick(final ServerPlayerState state) {
		tick(state, null);
	}

	public static void tick(final ServerPlayerState state, final Survival survival) {
		requireSupported(state);
		if (state.exhaustionLevel > 4.0F) {
			state.exhaustionLevel -= 4.0F;
			if (state.saturationLevel > 0.0F) {
				state.saturationLevel = Math.max(state.saturationLevel - 1.0F, 0.0F);
			} else {
				state.foodLevel = Math.max(state.foodLevel - 1, 0);
			}
		}
		boolean hurt = state.health > 0.0F && state.health < state.maximumHealth;
		if (state.naturalRegeneration && state.saturationLevel > 0.0F && hurt && state.foodLevel >= 20) {
			if (++state.tickTimer >= 10) {
				float saturationSpent = Math.min(state.saturationLevel, 6.0F);
				state.health = Math.min(state.health + saturationSpent / 6.0F, state.maximumHealth);
				addExhaustion(state, saturationSpent);
				state.tickTimer = 0;
			}
		} else if (state.naturalRegeneration && state.foodLevel >= 18 && hurt) {
			if (++state.tickTimer >= 80) {
				state.health = Math.min(state.health + 1.0F, state.maximumHealth);
				addExhaustion(state, 6.0F);
				state.tickTimer = 0;
			}
		} else if (state.foodLevel <= 0) {
			if (++state.tickTimer >= 80) {
				if (state.health > 10.0F || state.difficulty == ServerPlayerState.Difficulty.HARD
				    || state.health > 1.0F && state.difficulty == ServerPlayerState.Difficulty.NORMAL) {
					if (survival == null)
						throw new PendingServerWriteException(
						    Refusal.PENDING_SURVIVAL_FACT, "starvation needs ServerTick authority");
					survival.hurtServer(HurtCause.STARVE, 1.0F);
				}
				state.tickTimer = 0;
			}
		} else {
			state.tickTimer = 0;
		}
	}

	/** FoodData.addExhaustion keeps float addition and the upper clamp in source order. */
	public static void addExhaustion(final ServerPlayerState state, final float amount) {
		state.exhaustionLevel = Math.min(state.exhaustionLevel + amount, 40.0F);
	}
}
