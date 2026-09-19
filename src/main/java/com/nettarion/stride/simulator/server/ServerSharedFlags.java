package com.nettarion.stride.simulator.server;

import com.nettarion.stride.simulator.ServerPlayerState;

/**
 * The bits of vanilla's {@code Entity} shared-flags byte that the server's copy tracks, numbered as the
 * {@code Entity.setSharedFlag} call sites number them.
 *
 * <p>The same numbers appear in {@code trace.SharedFlagBits} for the capture format and as private masks in
 * {@code EntityDataWrite}; this holder is the server package's copy so that neither the trace format nor an exported
 * type is a dependency of the tick.
 */
final class ServerSharedFlags {
	/** Bit 0: {@code Entity.setSharedFlag(0, ...)}, the entity is on fire. */
	static final int ON_FIRE = 0;

	/** Bit 1: the shift key is held, which {@code Entity.isSuppressingBounce} and the pose read. */
	static final int SHIFT_KEY_DOWN = 1;

	/** Bit 3: the entity is sprinting. */
	static final int SPRINTING = 3;

	/** Bit 4: the entity is swimming. */
	static final int SWIMMING = 4;

	/** Bit 7: the entity is gliding ({@code fallFlying}). */
	static final int FALL_FLYING = 7;

	private ServerSharedFlags() {}

	/** The shared-flags byte of the server's copy, as the entity tracker samples it. */
	static int of(final ServerPlayerState server) {
		return (server.sharedFlagOnFire ? 1 << ON_FIRE : 0) | (server.shiftKeyDown ? 1 << SHIFT_KEY_DOWN : 0)
		    | (server.sprinting ? 1 << SPRINTING : 0) | (server.swimming ? 1 << SWIMMING : 0)
		    | (server.fallFlying ? 1 << FALL_FLYING : 0);
	}
}
