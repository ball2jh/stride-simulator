package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.server.ServerDamage;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * A block whose hook vanilla runs but the simulator does not model: that hook refuses on contact.
 *
 * <p>The refusal is on contact and never at compile time, so a world holding such a block still steps until the
 * player reaches it, and a palette entry whose hook is missing refuses rather than passing as inert. The hooks a
 * capture marks unmodeled are server-only, so the client's copy marks the visit and applies nothing, while the
 * server's copy throws {@link RefusalCause#UNMODELED_BLOCK} from the visit or the step; a landing refuses in
 * {@link #fallOn} itself.
 *
 * <p>One instance per combination of refused hook families, so an entry whose contact is unmodeled but whose
 * landing was captured as ordinary lands ordinarily and refuses only on a visit or a step.
 */
final class UnmodeledBlock extends BlockBehavior {
	private static final UnmodeledBlock CONTACT_ONLY = new UnmodeledBlock(CONTACT);

	private static final UnmodeledBlock LANDING_ONLY = new UnmodeledBlock(LANDING);

	private static final UnmodeledBlock CONTACT_AND_LANDING = new UnmodeledBlock(CONTACT | LANDING);

	private final int families;

	private UnmodeledBlock(final int families) {
		this.families = families;
	}

	/** The sentinel refusing exactly {@code families}, a non-empty subset of {@code CONTACT | LANDING}. */
	static UnmodeledBlock of(final int families) {
		return switch (families) {
			case CONTACT -> CONTACT_ONLY;
			case LANDING -> LANDING_ONLY;
			case CONTACT | LANDING -> CONTACT_AND_LANDING;
			default -> throw new IllegalArgumentException("no unmodeled sentinel for families " + families);
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
		throw UnimplementedMechanicException.deferred(RefusalCause.UNMODELED_BLOCK,
		    ()
		        -> "the server's copy touched a block at " + x + "," + y + "," + z
		        + " whose contact body the simulator does not model");
	}

	@Override
	void stepOn(final PlayerState state, final int x, final int y, final int z, final Scratch scratch) {
		if (!scratch.authority.isServer() || (this.families & CONTACT) == 0) {
			return;
		}
		throw UnimplementedMechanicException.deferred(RefusalCause.UNMODELED_BLOCK,
		    ()
		        -> "the server's copy stood on a block at " + x + "," + y + "," + z
		        + " whose stepOn body the simulator does not model");
	}

	@Override
	public void fallOn(final double fallDistance, final int x, final int y, final int z, final ServerDamage damage) {
		if ((this.families & LANDING) == 0) {
			super.fallOn(fallDistance, x, y, z, damage);
			return;
		}
		throw UnimplementedMechanicException.deferred(RefusalCause.UNMODELED_BLOCK,
		    ()
		        -> "the server landed the player on a block at " + x + "," + y + "," + z
		        + " whose fallOn body the simulator does not model");
	}

	@Override
	public String toString() {
		return switch (this.families) {
			case CONTACT -> "unmodeled contact";
			case LANDING -> "unmodeled landing";
			default -> "unmodeled contact and landing";
		};
	}
}
