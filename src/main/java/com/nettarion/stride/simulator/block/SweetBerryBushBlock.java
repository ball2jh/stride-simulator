package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * A sweet berry bush: {@code SweetBerryBushBlock.entityInside}, which holds a
 * player with the bush's stuck vector on both sides and, on the server, a
 * grown bush hurts one point when the known client movement is horizontal
 * by at least the threshold. The hit is decided by {@link ServerTick}, which
 * holds the survival facts and the known movement; the visit logs it. A bush
 * whose age the capture did not record still holds the client, since the
 * stuck vector is age-independent, and refuses on the server's visit.
 */
final class SweetBerryBushBlock extends BlockBehavior {
	static final SweetBerryBushBlock INSTANCE = new SweetBerryBushBlock(Age.GROWN);
	static final SweetBerryBushBlock YOUNG = new SweetBerryBushBlock(Age.YOUNG);
	static final SweetBerryBushBlock UNKNOWN_AGE = new SweetBerryBushBlock(Age.UNKNOWN);
	private enum Age { YOUNG, GROWN, UNKNOWN }
	private final Age age;
	private final boolean grown;

	/** {@code SweetBerryBushBlock.entityInside}'s known-movement threshold, the float widened. */
	private static final double MOVEMENT_THRESHOLD = (double) 0.003F;

	private SweetBerryBushBlock(final Age age) {
		this.age = age;
		this.grown = age == Age.GROWN;
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
	 * {@code new Vec3(0.8F, 0.75, 0.8F)} widens a float into X and Z, so two
	 * of the three are not exact decimal doubles; writing 0.8 here disagrees
	 * on the first stuck tick. See {@link WebBlock} for the shared gate.
	 */
	@Override
	void entityInside(final PlayerState state, final int x, final int y, final int z, final boolean isPrecise,
	    final WorldView world, final Scratch scratch) {
		makeStuckInBlock(state, (double) 0.8F, 0.75, (double) 0.8F, scratch);
		if (scratch.authority.isServer() && this.age == Age.UNKNOWN) {
			throw UnimplementedMechanicException.deferred(RefusalCause.UNDECLARED_BLOCK_STATE,
			    ()
			        -> "the server's copy touched a sweet berry bush at " + x + "," + y + "," + z
			        + " whose age the capture did not record");
		}
		if (scratch.authority.isServer() && this.grown) {
			ServerPlayerState server = scratch.authority.serverState();
			double kx = server.lastKnownClientMovementX;
			double kz = server.lastKnownClientMovementZ;
			if (kx * kx + kz * kz > 0.0 && (Math.abs(kx) >= MOVEMENT_THRESHOLD || Math.abs(kz) >= MOVEMENT_THRESHOLD)) {
				scratch.authority.survival().hurtServer(HurtCause.SWEET_BERRY_BUSH, 1.0F);
			}
		}
	}

	@Override
	public String toString() {
		return this.age == Age.UNKNOWN ? "sweet berry bush of unknown age" : "sweet berry bush";
	}
}
