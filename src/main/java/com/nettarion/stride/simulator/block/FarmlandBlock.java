package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.Refusal;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.server.Survival;

/**
 * Farmland: {@code FarmlandBlock.fallOn}, the ordinary hit after a trample
 * that the server decides at random for a fall past half a block by a body
 * of more than 0.512 cubic blocks, which refuses as undetermined; the
 * swimming and gliding boxes are too small to trample and land ordinarily.
 */
final class FarmlandBlock extends BlockBehaviour {
	static final FarmlandBlock INSTANCE = new FarmlandBlock();

	private FarmlandBlock() {}

	@Override
	int families() {
		return LANDING;
	}

	@Override
	public void fallOn(final double fallDistance, final int x, final int y, final int z, final Survival survival) {
		if (fallDistance - 0.5 > 0.0 && survival.bodyVolume() > 0.512F) {
			throw new PendingServerWriteException(Refusal.PENDING_SERVER_RANDOM,
			    "landing on farmland from " + fallDistance + " blocks tramples it with a server-random chance");
		}
		survival.causeFallDamage(fallDistance, 1.0F, HurtCause.FALL);
	}

	@Override
	public String toString() {
		return "farmland";
	}
}
