package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.server.Survival;

/**
 * Farmland: {@code FarmlandBlock.fallOn}, the ordinary hit after a trample the simulator cannot make.
 *
 * <p>Vanilla tramples the farmland to dirt when {@code random.nextFloat() < fallDistance - 0.5} and the player's
 * body is bigger than 0.512 cubic blocks. A certain trample refuses as a world write the simulator does not make,
 * an uncertain one as a server-random outcome, and a fall of half a block or less lands ordinarily. The swimming
 * and gliding boxes are too small to trample and always land ordinarily.
 */
final class FarmlandBlock extends BlockBehavior {
	static final FarmlandBlock INSTANCE = new FarmlandBlock();

	/** The body volume above which vanilla tramples, in cubic blocks as the float product it compares. */
	private static final float TRAMPLE_MIN_BODY_VOLUME = 0.512F;

	/** The largest value {@code RandomSource.nextFloat()} returns: a chance above it is certain. */
	private static final double LARGEST_NEXT_FLOAT = Math.nextDown(1.0F);

	private FarmlandBlock() {}

	@Override
	int families() {
		return LANDING;
	}

	@Override
	public void fallOn(final double fallDistance, final int x, final int y, final int z, final Survival survival) {
		double trampleChance = fallDistance - 0.5;
		if (trampleChance > 0.0 && survival.bodyVolume() > TRAMPLE_MIN_BODY_VOLUME) {
			if (trampleChance > LARGEST_NEXT_FLOAT) {
				throw new PendingServerWriteException(RefusalCause.UNMODELED_WORLD_WRITE,
				    "landing on farmland from " + fallDistance
				        + " blocks tramples it to dirt: a world write the simulator does not make");
			}
			throw new PendingServerWriteException(RefusalCause.PENDING_SERVER_RANDOM,
			    "landing on farmland from " + fallDistance + " blocks tramples it with a server-random chance");
		}
		survival.causeFallDamage(fallDistance, 1.0F, HurtCause.FALL);
	}

	@Override
	public String toString() {
		return "farmland";
	}
}
