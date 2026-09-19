package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.world.SnapshotView;
import java.util.Optional;

/** One server block replacement, delivered later to the matching branch's client world. */
public record BlockUpdateWrite(int actionIndex, int x, int y, int z, int paletteIndex) implements ServerWrite {
	public BlockUpdateWrite {
		if (actionIndex < 0 || paletteIndex < 0) throw new IllegalArgumentException("invalid block update");
	}
	/** A player-only replay cannot apply a block packet and must provide a world-aware runner. */
	@Override
	public void applyAfterAction(final int action, final PlayerState player) {
		if (action == this.actionIndex)
			throw new UnimplementedMechanicException(
			    Refusal.UNMODELLED_WORLD_WRITE, "block update needs client-world delivery");
	}
	/** Deliver to the client view compiled from the same palette as the server view. */
	public void applyTo(final SnapshotView world) {
		world.replaceCell(this.x, this.y, this.z, this.paletteIndex);
	}
	@Override
	public Optional<BlockUpdateWrite> afterConsuming(final int actions) {
		if (actions < 0) throw new IllegalArgumentException("negative consumed actions");
		return this.actionIndex < actions
		    ? Optional.empty()
		    : Optional.of(new BlockUpdateWrite(this.actionIndex - actions, this.x, this.y, this.z, this.paletteIndex));
	}
	@Override
	public BlockUpdateWrite delayedBy(final int actions) {
		if (actions < 0) throw new IllegalArgumentException("negative delay");
		return new BlockUpdateWrite(
		    Math.addExact(this.actionIndex, actions), this.x, this.y, this.z, this.paletteIndex);
	}
}
