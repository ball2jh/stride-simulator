package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.Refusal;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.server.Survival;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * A block with a body the slice does not model: every hook that would run
 * it refuses, on contact and never at compile time, so a world holding
 * such a block still steps until the player reaches it. This is the
 * second law made structural: a palette entry whose behaviour is missing
 * refuses rather than passing as inert.
 *
 * <p>The refusal is deferred to {@link ServerTick} through the contact log
 * on the server's copy, as the hits are, so that it lands at the same point
 * of the composed step as the other server outcomes; the client's copy marks
 * the visit and applies nothing, which is what a client does with a body
 * only the server runs. A landing refuses in the landing itself.
 */
final class UnmodelledBlock extends BlockBehaviour {
	static final UnmodelledBlock INSTANCE = new UnmodelledBlock();

	private UnmodelledBlock() {}

	@Override
	int families() {
		return CONTACT | LANDING;
	}

	@Override
	boolean hasContact() {
		return true;
	}

	@Override
	void entityInside(final PlayerState state, final int x, final int y, final int z, final boolean isPrecise,
	    final WorldView world, final Scratch scratch) {
		if (!scratch.authority.isServer()) return;
		throw UnimplementedMechanicException.deferred(Refusal.UNMODELLED_BLOCK,
		    ()
		        -> "the server's copy touched a block at " + x + "," + y + "," + z
		        + " whose contact body the slice does not model");
	}

	@Override
	void stepOn(final PlayerState state, final int x, final int y, final int z, final Scratch scratch) {
		if (scratch.authority.isServer()) {
			throw UnimplementedMechanicException.deferred(Refusal.UNMODELLED_BLOCK,
			    ()
			        -> "the server's copy stood on a block at " + x + "," + y + "," + z
			        + " whose stepOn body the slice does not model");
		}
	}

	@Override
	public void fallOn(final double fallDistance, final int x, final int y, final int z, final Survival survival) {
		throw UnimplementedMechanicException.deferred(Refusal.UNMODELLED_BLOCK,
		    ()
		        -> "the server landed the player on a block at " + x + "," + y + "," + z
		        + " whose fallOn body the slice does not model");
	}

	@Override
	public String toString() {
		return "unmodelled";
	}
}
