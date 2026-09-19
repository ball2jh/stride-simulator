package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.world.SnapshotView;

/**
 * One block the server replaced, delivered to the client's world rather than to the player.
 *
 * <p>The only admitted cause is a burning player lowering a cauldron. The write names the cell and the palette
 * index of the successor state; the client's view must be compiled from the same palette as the server's.
 *
 * @param actionIndex the completed action after which the write is applied
 * @param x the cell's x, in blocks
 * @param y the cell's y, in blocks
 * @param z the cell's z, in blocks
 * @param paletteIndex the successor state's index in the shared palette
 */
public record BlockUpdateWrite(int actionIndex, int x, int y, int z, int paletteIndex) implements ServerWrite {
	/** Validates the action index and the palette index. */
	public BlockUpdateWrite {
		if (actionIndex < 0 || paletteIndex < 0) {
			throw new IllegalArgumentException("invalid block update");
		}
	}

	/**
	 * Refuses: a player-only delivery cannot apply a block change. A caller that owns the client's world delivers
	 * with {@link #applyTo} instead.
	 *
	 * @throws UnimplementedMechanicException when {@code completedAction} is this write's action index
	 */
	@Override
	public void applyAfterAction(final int completedAction, final PlayerState state) {
		if (completedAction == this.actionIndex) {
			throw new UnimplementedMechanicException(
			    RefusalCause.UNMODELED_WORLD_WRITE, "block update needs client-world delivery");
		}
	}

	/** Replace the cell in the client's view, which must share the server view's palette. */
	public void applyTo(final SnapshotView world) {
		world.replaceCell(this.x, this.y, this.z, this.paletteIndex);
	}

	@Override
	public BlockUpdateWrite withActionIndex(final int index) {
		return new BlockUpdateWrite(index, this.x, this.y, this.z, this.paletteIndex);
	}
}
