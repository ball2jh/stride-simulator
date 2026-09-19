package com.nettarion.stride.simulator;

/**
 * The movement packet form {@code LocalPlayer.sendPosition} selects for one
 * tick, in its priority order.
 *
 * <p>It is a fact of one transition: decided by the {@link Publisher} before
 * the tick and the {@link PlayerState} after it. The server's
 * {@link com.nettarion.stride.simulator.server.ServerTick#transact transaction} decodes it as the listener does,
 * with absent fields falling back to the server's own values.
 */
public enum MovementPacket {
	/** No movement packet is published. */
	NONE,
	/** Publishes contact flags without position or rotation. */
	STATUS_ONLY,
	/** Publishes yaw, pitch, and contact flags. */
	ROT,
	/** Publishes position and contact flags. */
	POS,
	/** Publishes position, rotation, and contact flags. */
	POS_ROT;

	/** Whether the packet carries coordinates. */
	public boolean hasPosition() {
		return this == POS || this == POS_ROT;
	}

	/** Whether the packet carries yaw and pitch. */
	public boolean hasRotation() {
		return this == ROT || this == POS_ROT;
	}
}
