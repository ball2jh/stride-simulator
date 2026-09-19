package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.server.ServerDamage;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * A block with a hook the simulator cannot run: the hook refuses on contact, naming why.
 *
 * <p>A hook family is <em>unmodeled</em> when vanilla runs it but this library does not, and <em>unrecorded</em>
 * when it depends on block state the capture did not record (whether a campfire is lit, a berry bush grown, a
 * dripstone an upward tip). The refusal is on contact and never at compile time, so a world holding such a block
 * still steps until the player reaches it. The refused hooks are server-only, so the client's copy marks the
 * visit and applies nothing while the server's copy throws {@link RefusalCause#UNMODELED_BLOCK} or
 * {@link RefusalCause#UNDECLARED_BLOCK_STATE} from the visit or the step; a landing refuses in {@link #fallOn}.
 *
 * <p>One instance per combination of refused families and causes, so an entry whose contact is unmodeled but
 * whose landing was captured as ordinary lands ordinarily, and an entry whose contact is unmodeled beside an
 * unrecorded landing refuses each hook with its own cause.
 */
final class RefusingBlock extends BlockBehavior {
	/** Indexed by {@link #slot}: two bits for the unmodeled families, two for the unrecorded ones. */
	private static final RefusingBlock[] INSTANCES = new RefusingBlock[16];

	static {
		int[] subsets = {0, CONTACT, LANDING, CONTACT | LANDING};
		for (int unmodeled : subsets) {
			for (int unrecorded : subsets) {
				if ((unmodeled | unrecorded) != 0 && (unmodeled & unrecorded) == 0) {
					INSTANCES[slot(unmodeled, unrecorded)] = new RefusingBlock(unmodeled, unrecorded);
				}
			}
		}
	}

	private final int unmodeled;

	private final int unrecorded;

	private RefusingBlock(final int unmodeled, final int unrecorded) {
		this.unmodeled = unmodeled;
		this.unrecorded = unrecorded;
	}

	/**
	 * The sentinel refusing the {@code unmodeled} families as unmodeled and the {@code unrecorded} families as
	 * unrecorded. The two are disjoint subsets of {@code CONTACT | LANDING} and at least one is non-empty.
	 */
	static RefusingBlock of(final int unmodeled, final int unrecorded) {
		if ((unmodeled | unrecorded) == 0 || (unmodeled & unrecorded) != 0
		    || ((unmodeled | unrecorded) & ~(CONTACT | LANDING)) != 0) {
			throw new IllegalArgumentException(
			    "no refusing sentinel for families " + unmodeled + " unmodeled and " + unrecorded + " unrecorded");
		}
		return INSTANCES[slot(unmodeled, unrecorded)];
	}

	private static int slot(final int unmodeled, final int unrecorded) {
		return bits(unmodeled) << 2 | bits(unrecorded);
	}

	private static int bits(final int families) {
		return ((families & CONTACT) != 0 ? 1 : 0) | ((families & LANDING) != 0 ? 2 : 0);
	}

	@Override
	int families() {
		return this.unmodeled | this.unrecorded;
	}

	@Override
	boolean hasContact() {
		return (families() & CONTACT) != 0;
	}

	@Override
	void entityInside(final PlayerState state, final int x, final int y, final int z, final boolean isPrecise,
	    final WorldView world, final Scratch scratch) {
		if (!scratch.authority.isServer() || (families() & CONTACT) == 0) {
			return;
		}
		if ((this.unmodeled & CONTACT) != 0) {
			throw UnimplementedMechanicException.deferred(RefusalCause.UNMODELED_BLOCK,
			    ()
			        -> "the server's copy touched a block at " + x + "," + y + "," + z
			        + " whose contact hook the simulator does not model");
		}
		throw UnimplementedMechanicException.deferred(RefusalCause.UNDECLARED_BLOCK_STATE,
		    ()
		        -> "the server's copy touched a block at " + x + "," + y + "," + z
		        + " whose contact hook depends on block state the capture did not record");
	}

	@Override
	void stepOn(final PlayerState state, final int x, final int y, final int z, final Scratch scratch) {
		// Only an unmodeled contact may own a stepOn: the unrecorded classes have none.
		if (!scratch.authority.isServer() || (this.unmodeled & CONTACT) == 0) {
			return;
		}
		throw UnimplementedMechanicException.deferred(RefusalCause.UNMODELED_BLOCK,
		    ()
		        -> "the server's copy stood on a block at " + x + "," + y + "," + z
		        + " whose stepOn hook the simulator does not model");
	}

	@Override
	public void fallOn(final double fallDistance, final int x, final int y, final int z, final ServerDamage damage) {
		if ((families() & LANDING) == 0) {
			super.fallOn(fallDistance, x, y, z, damage);
			return;
		}
		if ((this.unmodeled & LANDING) != 0) {
			throw UnimplementedMechanicException.deferred(RefusalCause.UNMODELED_BLOCK,
			    ()
			        -> "the server landed the player on a block at " + x + "," + y + "," + z
			        + " whose fallOn hook the simulator does not model");
		}
		throw UnimplementedMechanicException.deferred(RefusalCause.UNDECLARED_BLOCK_STATE,
		    ()
		        -> "the server landed the player on a block at " + x + "," + y + "," + z
		        + " whose fallOn hook depends on block state the capture did not record");
	}

	@Override
	public String toString() {
		StringBuilder text = new StringBuilder();
		if (this.unmodeled != 0) {
			text.append("unmodeled ").append(familyNames(this.unmodeled));
		}
		if (this.unrecorded != 0) {
			if (text.length() > 0) {
				text.append(" and ");
			}
			text.append("unrecorded ").append(familyNames(this.unrecorded)).append(" state");
		}
		return text.toString();
	}

	private static String familyNames(final int families) {
		return switch (families) {
			case CONTACT -> "contact";
			case LANDING -> "landing";
			default -> "contact and landing";
		};
	}
}
