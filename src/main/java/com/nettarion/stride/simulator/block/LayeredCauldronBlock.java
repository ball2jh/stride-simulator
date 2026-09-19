package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.Refusal;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;
import java.util.List;

/** A source-verified layered cauldron, with its filled shape and lower-water-level successor. */
public final class LayeredCauldronBlock extends BlockBehaviour {
	private final int successor;
	private final double[] insideShape;
	public LayeredCauldronBlock(final int successor, final List<WorldSnapshot.ShapeBox> boxes) {
		this.successor = successor;
		this.insideShape = compileShape(boxes);
	}
	public static double[] compileShape(final List<WorldSnapshot.ShapeBox> boxes) {
		double[] shape = new double[boxes.size() * 6];
		for (int i = 0; i < boxes.size(); i++) {
			var box = boxes.get(i);
			int at = i * 6;
			shape[at] = box.minX();
			shape[at + 1] = box.minY();
			shape[at + 2] = box.minZ();
			shape[at + 3] = box.maxX();
			shape[at + 4] = box.maxY();
			shape[at + 5] = box.maxZ();
		}
		return shape;
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
	void entityInside(final PlayerState state, final int x, final int y, final int z, final boolean precise,
	    final WorldView world, final Scratch scratch) {
		if (scratch.authority.isServer()) {
			if (!(world instanceof SnapshotView snapshot))
				throw new UnimplementedMechanicException(
				    Refusal.UNMODELLED_WORLD_WRITE, "cauldron needs a branch-owned snapshot");
			scratch.insideEffects.collectCauldron(snapshot, x, y, z, this.successor);
		}
	}
}
