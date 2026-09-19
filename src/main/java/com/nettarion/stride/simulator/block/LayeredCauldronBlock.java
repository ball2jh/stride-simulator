package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldView;

import java.util.List;

/**
 * A layered water or powder-snow cauldron: {@code LayeredCauldronBlock.entityInside}, which lowers the level
 * under a burning player.
 *
 * <p>One instance per catalog entry, carrying the inside shape the {@code BlockStateCatalog} extracted and the
 * palette index of the successor state one level lower. On the server's copy a visit queues the lowering to run
 * before {@code EXTINGUISH}; the world write refuses unless the caller owns the world. The client's copy applies
 * nothing.
 *
 * <p>Public only until {@code BlockStateCatalog} constructs it through {@link BlockBehavior#layeredCauldron};
 * consumers never instantiate it.
 */
final class LayeredCauldronBlock extends BlockBehavior {
	private final int successor;

	private final double[] insideShape;

	/** The cauldron whose lowered state is palette index {@code successor} and whose inside shape is {@code boxes}. */
	LayeredCauldronBlock(final int successor, final List<ShapeBox> boxes) {
		this.successor = successor;
		this.insideShape = flattenShape(boxes);
	}

	@Override
	int families() {
		return CONTACT;
	}

	@Override
	boolean hasContact() {
		return true;
	}

	@Override
	boolean entityInsideShapeReached(
	    final PlayerState state, final int x, final int y, final int z, final Scratch scratch) {
		return scratch.insideTraversal.collidedWithShapeMovingFrom(state, x, y, z, this.insideShape);
	}

	@Override
	void entityInside(final PlayerState state, final int x, final int y, final int z, final boolean isPrecise,
	    final WorldView world, final Scratch scratch) {
		if (!scratch.authority.isServer()) {
			return;
		}
		if (!(world instanceof SnapshotView snapshot)) {
			throw new UnimplementedMechanicException(
			    RefusalCause.UNMODELED_WORLD_WRITE, "lowering a cauldron needs a snapshot the caller owns");
		}
		scratch.insideEffects.collectCauldron(snapshot, x, y, z, this.successor);
	}

	@Override
	public String toString() {
		return "layered cauldron";
	}
}
