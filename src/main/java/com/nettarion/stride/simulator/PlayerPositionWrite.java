package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.geometry.Mth;
import java.util.Optional;

/** An absolute correction packet with zero velocity, bound to its actual client delivery state. */
public record PlayerPositionWrite(int actionIndex, long beforeDigest, int teleportId, double x, double y, double z,
    float yRot, float xRot) implements ServerWrite {
	public PlayerPositionWrite {
		if (actionIndex < 0 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
		    || !Float.isFinite(yRot) || !Float.isFinite(xRot))
			throw new IllegalArgumentException("invalid correction");
	}
	@Override
	public void applyAfterAction(final int action, final PlayerState player) {
		if (action != this.actionIndex) return;
		long actual = StateDigest.state(player);
		if (actual != this.beforeDigest) throw new StaleServerWriteException(this.beforeDigest, actual);
		player.placeAt(0.0 + this.x, 0.0 + this.y, 0.0 + this.z);
		player.deltaMovementX = player.deltaMovementY = player.deltaMovementZ = 0.0;
		player.yRot = 0.0F + this.yRot;
		player.xRot = Mth.clamp(0.0F + this.xRot, -90.0F, 90.0F);
	}
	@Override
	public Optional<PlayerPositionWrite> afterConsuming(final int actions) {
		if (actions < 0) throw new IllegalArgumentException("negative consumed actions");
		return this.actionIndex < actions ? Optional.empty() : Optional.of(at(this.actionIndex - actions));
	}
	@Override
	public PlayerPositionWrite delayedBy(final int actions) {
		if (actions < 0) throw new IllegalArgumentException("negative delay");
		return at(Math.addExact(this.actionIndex, actions));
	}
	private PlayerPositionWrite at(final int action) {
		return new PlayerPositionWrite(
		    action, this.beforeDigest, this.teleportId, this.x, this.y, this.z, this.yRot, this.xRot);
	}
}
