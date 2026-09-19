package com.nettarion.stride.simulator.server;

import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.tick.AiStep;
import com.nettarion.stride.simulator.tick.PlayerTick;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.WorldView;

/** ServerPlayer's admitted player tick, jump override and movement statistics. */
public final class ServerPlayerTick {
	private ServerPlayerTick() {}

	/** ServerPlayer.doTick's admitted Player.tick body followed by food bookkeeping. */
	static void doTick(final ServerPlayerState state, final WorldView world, final Scratch scratch) {
		PlayerTick.serverTick(state, world, scratch);
		FoodData.tick(state, scratch.authority.survival());
	}

	/** ServerPlayer.jumpFromGround calls the shared impulse before charging exhaustion. */
	public static void jumpFromGround(final ServerPlayerState state, final WorldView world) {
		AiStep.jumpFromGround(state, world);
		PlayerTick.causeFoodExhaustion(state, state.sprinting ? 0.2F : 0.05F);
	}

	/** ServerPlayer.checkMovementStatistics on an accepted movement packet. */
	static void checkMovementStatistics(
	    final ServerPlayerState state, final double dx, final double dy, final double dz, final WorldView world) {
		if (dx == 0.0 && dy == 0.0 && dz == 0.0) return;
		if (state.swimming || state.eyeInWater) {
			int distance = Math.round((float) Math.sqrt(dx * dx + dy * dy + dz * dz) * 100.0F);
			if (distance > 0) PlayerTick.causeFoodExhaustion(state, 0.01F * distance * 0.01F);
		} else if (state.waterHeight > 0.0) {
			int distance = Math.round((float) Math.sqrt(dx * dx + dz * dz) * 100.0F);
			if (distance > 0) PlayerTick.causeFoodExhaustion(state, 0.01F * distance * 0.01F);
		} else if (!PlayerTick.onClimbable(state, world) && state.onGround) {
			int distance = Math.round((float) Math.sqrt(dx * dx + dz * dz) * 100.0F);
			if (distance > 0) PlayerTick.causeFoodExhaustion(state, (state.sprinting ? 0.1F : 0.0F) * distance * 0.01F);
		}
	}
}
