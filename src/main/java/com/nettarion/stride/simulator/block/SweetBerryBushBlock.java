package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * A sweet berry bush: {@code SweetBerryBushBlock.entityInside}, the bush's stuck vector on both sides and, on the
 * server, a hit from a bush past age zero.
 *
 * <p>The hit is one point when the last known client movement is horizontal by at least the threshold, dealt at
 * the visit through the server's {@code ServerDamage}. A bush whose age the capture did not record still holds the
 * client, since the stuck vector is age-independent, and refuses on the server's visit.
 */
final class SweetBerryBushBlock extends BlockBehavior {
	static final SweetBerryBushBlock INSTANCE = new SweetBerryBushBlock(Age.GROWN);

	static final SweetBerryBushBlock YOUNG = new SweetBerryBushBlock(Age.YOUNG);

	static final SweetBerryBushBlock UNRECORDED_AGE = new SweetBerryBushBlock(Age.UNRECORDED);

	/** {@code SweetBerryBushBlock.entityInside}'s known-movement threshold, the float widened. */
	private static final double MOVEMENT_THRESHOLD = (double) 0.003F;

	/** What the capture recorded of the bush's {@code AGE}: vanilla hurts when {@code age > 0}, not only when ripe. */
	private enum Age {
		/** Age zero: holds but never hurts. */
		YOUNG,
		/** Age above zero: holds and hurts. */
		GROWN,
		/** The capture predates the age fact. */
		UNRECORDED
	}

	private final Age age;

	private SweetBerryBushBlock(final Age age) {
		this.age = age;
	}

	@Override
	int families() {
		return INSIDE | (this.age == Age.YOUNG ? 0 : CONTACT);
	}

	@Override
	boolean hasEntityInside() {
		return true;
	}

	@Override
	boolean hasContact() {
		return this.age != Age.YOUNG;
	}

	/**
	 * {@code SweetBerryBushBlock.entityInside}: the shared {@link #makeStuckInBlock} with the bush's vector, then
	 * the server's hit.
	 *
	 * @implNote {@code new Vec3(0.8F, 0.75, 0.8F)} widens a float into X and Z, so two of the three are not exact
	 *     decimal doubles; writing 0.8 here disagrees on the first stuck tick.
	 */
	@Override
	void entityInside(final PlayerState state, final int x, final int y, final int z, final boolean isPrecise,
	    final WorldView world, final Scratch scratch) {
		makeStuckInBlock(state, (double) 0.8F, 0.75, (double) 0.8F, scratch);
		if (!scratch.authority.isServer()) {
			return;
		}
		if (this.age == Age.UNRECORDED) {
			throw UnimplementedMechanicException.deferred(RefusalCause.UNDECLARED_BLOCK_STATE,
			    ()
			        -> "the server's copy touched a sweet berry bush at " + x + "," + y + "," + z
			        + " whose age the capture did not record");
		}
		if (this.age == Age.GROWN) {
			ServerPlayerState server = scratch.authority.serverState();
			double kx = server.lastKnownClientMovementX;
			double kz = server.lastKnownClientMovementZ;
			if (kx * kx + kz * kz > 0.0 && (Math.abs(kx) >= MOVEMENT_THRESHOLD || Math.abs(kz) >= MOVEMENT_THRESHOLD)) {
				scratch.authority.damage().hurtServer(HurtCause.SWEET_BERRY_BUSH, 1.0F);
			}
		}
	}

	@Override
	public String toString() {
		return this.age == Age.UNRECORDED ? "sweet berry bush of unrecorded age" : "sweet berry bush";
	}
}
