package com.nettarion.stride.simulator;

import java.io.Serializable;
import java.util.Objects;

/**
 * A movement correction the server issued: the packet form and reason, the target the client
 * reported, the position the server's collision resolved to, the squared residual it measured,
 * and the position and rotation it sent the client back to. Positions are in blocks; rotations in
 * degrees.
 */
public record MovementCorrection(MovementPacket packet, CorrectionReason reason, double targetX, double targetY,
    double targetZ, double resolvedX, double resolvedY, double resolvedZ, double residualSquared, double correctionX,
    double correctionY, double correctionZ, float correctionYRot, float correctionXRot) implements Serializable {
	public MovementCorrection {
		Objects.requireNonNull(packet, "packet");
		Objects.requireNonNull(reason, "reason");
	}
}
