package com.nettarion.stride.simulator.server;

import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.tick.AiStep;
import com.nettarion.stride.simulator.tick.PlayerTick;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.WorldView;

/** The {@code ServerPlayer} overrides the admitted domain reaches: its tick, jump, and movement statistics. */
public final class ServerPlayerTick {
	/** {@code ServerPlayer.jumpFromGround}: the exhaustion a sprinting jump costs, in food points. */
	private static final float SPRINT_JUMP_EXHAUSTION = 0.2F;

	/** {@code ServerPlayer.jumpFromGround}: the exhaustion a walking jump costs, in food points. */
	private static final float JUMP_EXHAUSTION = 0.05F;

	private ServerPlayerTick() {}

	/** {@code ServerPlayer.doTick}: the admitted {@code Player.tick} body, then the food tick. */
	static void doTick(final ServerPlayerState state, final WorldView world, final Scratch scratch) {
		PlayerTick.serverTick(state, world, scratch);
		FoodData.tick(state, scratch.authority.damage());
	}

	/** {@code ServerPlayer.jumpFromGround}: the shared impulse, then the jump's exhaustion. */
	public static void jumpFromGround(final ServerPlayerState state, final WorldView world) {
		AiStep.jumpFromGround(state, world);
		PlayerTick.causeFoodExhaustion(state, state.sprinting ? SPRINT_JUMP_EXHAUSTION : JUMP_EXHAUSTION);
	}

	/** {@code ServerPlayer.checkMovementStatistics} on an accepted movement packet's displacement, in blocks. */
	static void checkMovementStatistics(
	    final ServerPlayerState state, final double dx, final double dy, final double dz, final WorldView world) {
		if (dx == 0.0 && dy == 0.0 && dz == 0.0) {
			return;
		}
		if (state.swimming || state.eyeInWater) {
			int distance = Math.round((float) Math.sqrt(dx * dx + dy * dy + dz * dz) * 100.0F);
			if (distance > 0) {
				PlayerTick.causeFoodExhaustion(state, 0.01F * distance * 0.01F);
			}
		} else if (state.waterHeight > 0.0) {
			int distance = Math.round((float) Math.sqrt(dx * dx + dz * dz) * 100.0F);
			if (distance > 0) {
				PlayerTick.causeFoodExhaustion(state, 0.01F * distance * 0.01F);
			}
		} else if (!PlayerTick.onClimbable(state, world) && state.onGround) {
			int distance = Math.round((float) Math.sqrt(dx * dx + dz * dz) * 100.0F);
			if (distance > 0) {
				// Vanilla charges walking through the same call with a zero
				// rate: the product is 0.0F, and causeFoodExhaustion still
				// runs its addition and ability gate on it, which a signed
				// zero level can observe.
				PlayerTick.causeFoodExhaustion(state, (state.sprinting ? 0.1F : 0.0F) * distance * 0.01F);
			}
		}
	}
}
