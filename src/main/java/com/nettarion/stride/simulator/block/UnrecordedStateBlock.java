package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.server.ServerDamage;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * A block whose hook depends on block state the capture did not record: that hook refuses on contact.
 *
 * <p>A capture made before the fact existed cannot say whether a campfire is lit, a berry bush grown, or a
 * dripstone an upward tip. The class is known, so what it cannot do is known too: none of them has a
 * {@code stepOn}, so the step is inert; a visit or a landing refuses with
 * {@link RefusalCause#UNDECLARED_BLOCK_STATE}, as {@link UnmodeledBlock}'s do, because the capture cannot say
 * which hook runs. Recapture with the current facts to admit the block.
 *
 * <p>One instance per combination of refused hook families, so an entry whose contact is unrecorded but whose
 * landing was captured as ordinary lands ordinarily.
 */
final class UnrecordedStateBlock extends BlockBehavior {
	private static final UnrecordedStateBlock CONTACT_ONLY = new UnrecordedStateBlock(CONTACT);

	private static final UnrecordedStateBlock LANDING_ONLY = new UnrecordedStateBlock(LANDING);

	private static final UnrecordedStateBlock CONTACT_AND_LANDING = new UnrecordedStateBlock(CONTACT | LANDING);

	private final int families;

	private UnrecordedStateBlock(final int families) {
		this.families = families;
	}

	/** The sentinel refusing exactly {@code families}, a non-empty subset of {@code CONTACT | LANDING}. */
	static UnrecordedStateBlock of(final int families) {
		return switch (families) {
			case CONTACT -> CONTACT_ONLY;
			case LANDING -> LANDING_ONLY;
			case CONTACT | LANDING -> CONTACT_AND_LANDING;
			default -> throw new IllegalArgumentException("no unrecorded-state sentinel for families " + families);
		};
	}

	@Override
	int families() {
		return this.families;
	}

	@Override
	boolean hasContact() {
		return (this.families & CONTACT) != 0;
	}

	@Override
	void entityInside(final PlayerState state, final int x, final int y, final int z, final boolean isPrecise,
	    final WorldView world, final Scratch scratch) {
		if (!scratch.authority.isServer() || (this.families & CONTACT) == 0) {
			return;
		}
		throw UnimplementedMechanicException.deferred(RefusalCause.UNDECLARED_BLOCK_STATE,
		    ()
		        -> "the server's copy touched a block at " + x + "," + y + "," + z
		        + " whose contact body depends on block state the capture did not record");
	}

	@Override
	public void fallOn(final double fallDistance, final int x, final int y, final int z, final ServerDamage damage) {
		if ((this.families & LANDING) == 0) {
			super.fallOn(fallDistance, x, y, z, damage);
			return;
		}
		throw UnimplementedMechanicException.deferred(RefusalCause.UNDECLARED_BLOCK_STATE,
		    ()
		        -> "the server landed the player on a block at " + x + "," + y + "," + z
		        + " whose fallOn body depends on block state the capture did not record");
	}

	@Override
	public String toString() {
		return switch (this.families) {
			case CONTACT -> "unrecorded contact state";
			case LANDING -> "unrecorded landing state";
			default -> "unrecorded contact and landing state";
		};
	}
}
