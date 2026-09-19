package com.nettarion.stride.simulator.world;

import com.nettarion.stride.simulator.block.BlockBehavior;

/** Test-world shorthand whose implementations intentionally model a complete slice. */
public interface CompleteWorldView extends WorldView {
	@Override
	default boolean movementFactsComplete() {
		return true;
	}

	/** A test world holds no block body unless it says which. */
	@Override
	default BlockBehavior behaviorAt(final int x, final int y, final int z) {
		return BlockBehavior.INERT;
	}
}
