package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.server.Survival;
import com.nettarion.stride.simulator.world.WorldView;
import java.util.HashSet;
import java.util.Set;

/**
 * The freezing tail of {@code LivingEntity.aiStep}, which only the server
 * runs, the phase after {@link com.nettarion.stride.simulator.block.BlockEffects}: thaw by two outside powder
 * snow, rebuild the frost modifier, and hurt every fortieth entity tick
 * while fully frozen.
 *
 * <p>Reads the powder-snow latch the block effects set, the freeze flag,
 * the frozen count, and the entity tick count. Writes the fields in
 * {@link #WRITES}. Nothing on the client's copy, whose frozen count the
 * composed step installs from the server's at the next input.
 */
public final class Freezing {
	/** Every {@link ServerPlayerState} field this phase may write. */
	public static final Set<String> WRITES;

	static {
		Set<String> writes = new HashSet<>(Survival.HURT_WRITES);
		writes.addAll(Set.of("entityDataDirty", "ticksFrozen", "frostSpeedTicks", "movementSpeedAttributeDirty"));
		WRITES = Set.copyOf(writes);
	}

	private static final int FREEZE_DAMAGE_INTERVAL = 40;

	private Freezing() {}

	/**
	 * The {@code freezing} section of {@code LivingEntity.aiStep} on a server
	 * level.
	 *
	 * @throws PendingServerWriteException when the fully frozen player's hit
	 *         depends on an entity tick count the state does not carry
	 */
	public static void run(final PlayerState state, final WorldView world, final Scratch scratch) {
		if (!scratch.authority.isServer()) {
			return;
		}
		if (!state.isInPowderSnow || !state.canFreeze) {
			state.setTicksFrozen(Math.max(0, state.ticksFrozen - 2));
		}
		scratch.authority.serverState().movementSpeedAttributeDirty |= state.frostSpeedTicks != 0;
		removeFrost(state);
		tryAddFrost(state, world, scratch);
		scratch.authority.serverState().movementSpeedAttributeDirty |= state.frostSpeedTicks != 0;
		if (state.ticksFrozen >= ServerPlayerState.DEFAULT_TICKS_REQUIRED_TO_FREEZE && state.canFreeze) {
			ServerPlayerState server = scratch.authority.serverState();
			if (server.tickCount == ServerPlayerState.UNKNOWN_TICK_COUNT) {
				throw new PendingServerWriteException(RefusalCause.PENDING_SERVER_RANDOM,
				    "the fully frozen player is hurt every"
				        + " fortieth entity tick, and the entity tick count is not a captured fact");
			}
			if (server.tickCount % FREEZE_DAMAGE_INTERVAL == 0L) {
				scratch.authority.survival().hurtServer(HurtCause.FREEZE, 1.0F);
			}
		}
	}

	/** LivingEntity.removeFrost removes the modifier regardless of the block below. */
	private static void removeFrost(final PlayerState state) {
		state.frostSpeedTicks = 0;
	}

	/** LivingEntity.tryAddFrost requires a non-air getBlockStateOnLegacy. */
	private static void tryAddFrost(final PlayerState state, final WorldView world, final Scratch scratch) {
		SupportingBlock.getOnPosLegacy(state, world, scratch);
		int x = scratch.support.x, y = scratch.support.y, z = scratch.support.z;
		WorldView.Air air = world.airIn(x, y, z, x, y, z);
		if (air == WorldView.Air.UNKNOWN && state.ticksFrozen > 0) {
			throw new UnimplementedMechanicException(
			    RefusalCause.UNDECLARED_BLOCK_STATE, "frost modifier needs the identity of the block underfoot");
		}
		if (air == WorldView.Air.NOT_AIR && state.ticksFrozen > 0) {
			state.frostSpeedTicks = state.ticksFrozen;
		}
	}
}
