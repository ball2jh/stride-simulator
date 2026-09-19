package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.Refusal;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.server.Survival;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * A block whose body depends on block state a capture made before the fact
 * existed did not record: a lit or unlit campfire, a grown or young berry
 * bush, a dripstone tip. Its class is known, so what it cannot do is known
 * too: none of them has a {@code stepOn}, so the step is inert; a visit or
 * a landing refuses, as {@link UnmodelledBlock}'s do, because the capture
 * cannot say which body runs.
 */
final class UnknownStateBlock extends BlockBehaviour {
	static final UnknownStateBlock INSTANCE = new UnknownStateBlock();

	private UnknownStateBlock() {}

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
		throw UnimplementedMechanicException.deferred(Refusal.UNDECLARED_BLOCK_STATE,
		    ()
		        -> "the server's copy touched a block at " + x + "," + y + "," + z
		        + " whose contact body depends on block state the capture did not record");
	}

	@Override
	public void fallOn(final double fallDistance, final int x, final int y, final int z, final Survival survival) {
		throw UnimplementedMechanicException.deferred(Refusal.UNDECLARED_BLOCK_STATE,
		    ()
		        -> "the server landed the player on a block at " + x + "," + y + "," + z
		        + " whose fallOn body depends on block state the capture did not record");
	}

	@Override
	public String toString() {
		return "unknown state";
	}
}
