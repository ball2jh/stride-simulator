package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.geometry.Mth;

/**
 * The server's correction packet: an absolute position and rotation with zero velocity, bound to the client state
 * it is delivered to.
 *
 * <p>Positions are in blocks and rotations in degrees. Delivery is {@code ClientboundPlayerPositionPacket}'s handler
 * on the client's copy: the position is installed as sent, the velocity zeroed, and the pitch clamped to
 * [-90, 90] as {@code Entity.setXRot} does.
 *
 * @param actionIndex the completed action after which the write is applied
 * @param beforeDigest the digest of the client state the write is bound to
 * @param teleportId the id the client acknowledges with {@code ServerboundAcceptTeleportationPacket}
 * @param x the sent x, in blocks
 * @param y the sent y, in blocks
 * @param z the sent z, in blocks
 * @param yRot the sent yaw, in degrees
 * @param xRot the sent pitch, in degrees, before clamping
 */
public record PlayerPositionWrite(int actionIndex, long beforeDigest, int teleportId, double x, double y, double z,
    float yRot, float xRot) implements ServerWrite {
	/** Validates the action index and that every coordinate and angle is finite. */
	public PlayerPositionWrite {
		if (actionIndex < 0 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
		    || !Float.isFinite(yRot) || !Float.isFinite(xRot)) {
			throw new IllegalArgumentException("invalid correction");
		}
	}

	@Override
	public void applyAfterAction(final int completedAction, final PlayerState state) {
		if (completedAction != this.actionIndex) {
			return;
		}
		long actual = StateDigest.state(state);
		if (actual != this.beforeDigest) {
			throw new StaleServerWriteException(this.beforeDigest, actual);
		}
		// 0.0 + v turns a -0.0 read off the wire into +0.0, which is what
		// vanilla's packet handler stores: the sum is exact for every other
		// value and only the sign of zero changes.
		state.placeAt(0.0 + this.x, 0.0 + this.y, 0.0 + this.z);
		state.deltaMovementX = 0.0;
		state.deltaMovementY = 0.0;
		state.deltaMovementZ = 0.0;
		state.yRot = 0.0F + this.yRot;
		state.xRot = Mth.clamp(0.0F + this.xRot, -90.0F, 90.0F);
	}

	@Override
	public PlayerPositionWrite withActionIndex(final int index) {
		return new PlayerPositionWrite(
		    index, this.beforeDigest, this.teleportId, this.x, this.y, this.z, this.yRot, this.xRot);
	}
}
