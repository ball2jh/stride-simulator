package com.nettarion.stride.simulator.world;

import com.nettarion.stride.simulator.block.BlockBehaviour;

/** Test-world shorthand whose implementations intentionally model a complete slice. */
public interface CompleteWorldView extends WorldView {
	@Override
	default boolean movementFactsComplete() {
		return true;
	}

	/** A test world holds no block body unless it says which. */
	@Override
	default BlockBehaviour behaviourAt(final int x, final int y, final int z) {
		return BlockBehaviour.INERT;
	}
}
