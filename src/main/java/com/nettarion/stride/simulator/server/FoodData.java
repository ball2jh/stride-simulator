package com.nettarion.stride.simulator.server;

import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;

import java.util.Set;

/**
 * Vanilla's {@code FoodData.tick} for a damage player on a non-peaceful difficulty: exhaustion spends saturation
 * then food, natural regeneration heals, and starvation hurts.
 *
 * <p>Runs inside the connection tick after {@code Player.tick}. Food is in food points, exhaustion and saturation in
 * food points as floats, health in health points. Peaceful difficulty, which heals and never starves, is outside the
 * admitted domain and refuses at admission.
 */
public final class FoodData {
	/**
	 * Every {@link ServerPlayerState} field the food tick may write. Public so the declared write sets of the tick
	 * phases can be checked against the fields they touch ({@code PhaseWriteSetTest}).
	 */
	public static final Set<String> WRITES = Set.of("exhaustionLevel", "saturationLevel", "tickTimer", "health",
	    "foodLevel", "absorption", "invulnerableTime", "lastHurt");

	/** Ticks of exhaustion above which one point of saturation or food is spent. */
	private static final float EXHAUSTION_PER_POINT = 4.0F;

	/** {@code FoodData.MAX_EXHAUSTION}: the level saturates here. */
	private static final float MAX_EXHAUSTION = 40.0F;

	/** Saturated regeneration heals every tenth tick. */
	private static final int SATURATED_REGENERATION_TICKS = 10;

	/** Unsaturated regeneration and starvation act every eightieth tick. */
	private static final int SLOW_FOOD_TICKS = 80;

	/** The most saturation one saturated regeneration spends, in food points. */
	private static final float MAX_SATURATION_SPENT = 6.0F;

	private FoodData() {}

	/**
	 * Refuse a copy whose difficulty is undeclared or outside the three starving difficulties.
	 *
	 * @throws PendingServerWriteException when {@link ServerPlayerState#difficulty} is {@code null}
	 * @throws UnimplementedMechanicException for any difficulty but easy, normal and hard
	 */
	static void requireSupported(final ServerPlayerState state) {
		if (state.difficulty == null) {
			throw new PendingServerWriteException(RefusalCause.PENDING_SURVIVAL_FACT, "level difficulty is undeclared");
		}
		// Peaceful heals and never starves; when the enum grows it, this
		// refuses it without a further edit here.
		switch (state.difficulty) {
			case EASY, NORMAL, HARD -> {
				return;
			}
			default ->
				throw new UnimplementedMechanicException(RefusalCause.INADMISSIBLE_STATE,
				    state.difficulty + " difficulty is outside the admitted domain: it heals and never starves");
		}
	}

	/**
	 * One food tick with nowhere to deal starvation: refuses when the tick would starve. For tests of the food
	 * arithmetic alone ({@code PhaseWriteSetTest} in the root package uses it); the connection tick uses
	 * {@link #tick(ServerPlayerState, ServerDamage)}.
	 */
	public static void tick(final ServerPlayerState state) {
		tick(state, null);
	}

	/**
	 * {@code FoodData.tick} on the server's copy, dealing a starvation hit through {@code damage}.
	 *
	 * @throws PendingServerWriteException when the difficulty is undeclared, or when the tick starves and
	 *     {@code damage} is {@code null}
	 */
	public static void tick(final ServerPlayerState state, final ServerDamage damage) {
		requireSupported(state);
		if (state.exhaustionLevel > EXHAUSTION_PER_POINT) {
			state.exhaustionLevel -= EXHAUSTION_PER_POINT;
			if (state.saturationLevel > 0.0F) {
				state.saturationLevel = Math.max(state.saturationLevel - 1.0F, 0.0F);
			} else {
				state.foodLevel = Math.max(state.foodLevel - 1, 0);
			}
		}
		boolean hurt = state.health > 0.0F && state.health < state.maximumHealth;
		if (state.naturalRegeneration && state.saturationLevel > 0.0F && hurt && state.foodLevel >= 20) {
			if (++state.tickTimer >= SATURATED_REGENERATION_TICKS) {
				float saturationSpent = Math.min(state.saturationLevel, MAX_SATURATION_SPENT);
				state.health = Math.min(state.health + saturationSpent / 6.0F, state.maximumHealth);
				addExhaustion(state, saturationSpent);
				state.tickTimer = 0;
			}
		} else if (state.naturalRegeneration && state.foodLevel >= 18 && hurt) {
			if (++state.tickTimer >= SLOW_FOOD_TICKS) {
				state.health = Math.min(state.health + 1.0F, state.maximumHealth);
				addExhaustion(state, 6.0F);
				state.tickTimer = 0;
			}
		} else if (state.foodLevel <= 0) {
			if (++state.tickTimer >= SLOW_FOOD_TICKS) {
				// Starvation stops at 10 health on easy, at 1 on normal, and never on hard.
				if (state.health > 10.0F || state.difficulty == ServerPlayerState.Difficulty.HARD
				    || state.health > 1.0F && state.difficulty == ServerPlayerState.Difficulty.NORMAL) {
					if (damage == null) {
						throw new PendingServerWriteException(
						    RefusalCause.PENDING_SURVIVAL_FACT, "starvation needs a bound server transaction");
					}
					damage.hurtServer(HurtCause.STARVE, 1.0F);
				}
				state.tickTimer = 0;
			}
		} else {
			state.tickTimer = 0;
		}
	}

	/** {@code FoodData.addExhaustion}: float addition, then the clamp at {@code MAX_EXHAUSTION}, in that order. */
	public static void addExhaustion(final ServerPlayerState state, final float amount) {
		state.exhaustionLevel = Math.min(state.exhaustionLevel + amount, MAX_EXHAUSTION);
	}
}
