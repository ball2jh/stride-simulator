package com.nettarion.stride.simulator;

import java.io.Serializable;
import java.util.Objects;

/**
 * A movement correction the server sent instead of accepting a packet: what the client asked for, what the server's
 * own collision made of it, and where the server sent the client back to.
 *
 * <p>Positions are in blocks and rotations in degrees. For {@link CorrectionReason#MOVED_TOO_QUICKLY} and
 * {@link CorrectionReason#TELEPORT_RETRY} the server never moved, so {@link #resolved()} repeats the pre-packet
 * position.
 *
 * @param packet the kind of movement packet that was corrected
 * @param reason why it was corrected
 * @param target the position the packet asked for
 * @param resolved the position the server's collision resolved the packet to, and the residual it measured
 * @param teleport the position and rotation the server sent the client back to
 */
public record MovementCorrection(MovementPacket packet, CorrectionReason reason, Target target, Resolved resolved,
    Teleport teleport) implements Serializable {
	/** Validates that no component is null. */
	public MovementCorrection {
		Objects.requireNonNull(packet, "packet");
		Objects.requireNonNull(reason, "reason");
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(resolved, "resolved");
		Objects.requireNonNull(teleport, "teleport");
	}

	/**
	 * The position the movement packet asked for, in blocks.
	 *
	 * @param x the requested x
	 * @param y the requested y
	 * @param z the requested z
	 */
	public record Target(double x, double y, double z) implements Serializable {}

	/**
	 * Where the server's own collision put the requested move, in blocks, and the squared residual the moved-wrongly
	 * test measured between it and the target, in blocks squared.
	 *
	 * @param x the resolved x
	 * @param y the resolved y
	 * @param z the resolved z
	 * @param residualSquared the squared distance from the resolved position to the target
	 */
	public record Resolved(double x, double y, double z, double residualSquared) implements Serializable {}

	/**
	 * The absolute destination the correction packet carries: the pre-packet position with the packet's rotation, in
	 * blocks and degrees.
	 *
	 * @param x the sent x
	 * @param y the sent y
	 * @param z the sent z
	 * @param yRot the sent yaw
	 * @param xRot the sent pitch
	 */
	public record Teleport(double x, double y, double z, float yRot, float xRot) implements Serializable {}
}
