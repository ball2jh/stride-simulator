package com.nettarion.stride.simulator;

import java.util.Objects;

/**
 * Who wrote the player's velocity from outside the ordinary tick: the actor's
 * kind and, when it is an entity, its network id.
 *
 * <p>Identity is what lets two writes with equal payloads stay two events and
 * lets a refusal name what it could not schedule. An explosion is not an
 * entity; its packet carries no id.
 */
public record ExternalActor(Kind kind, int entityId) {
	/** The id of an actor that is not an entity. */
	public static final int NO_ENTITY = -1;

	/** The actors this slice can name; every other writer is unmodeled. */
	public enum Kind {
		/** {@code ServerExplosion}: the owner's {@code ClientboundExplodePacket} adds its vector. */
		EXPLOSION,
		/**
		 * {@code FireworkRocketEntity} attached to the player: each actor tick,
		 * on both sides, replaces a gliding player's velocity.
		 */
		FIREWORK_ROCKET
	}

	public ExternalActor {
		Objects.requireNonNull(kind, "kind");
		if (kind == Kind.EXPLOSION ? entityId != NO_ENTITY : entityId < 0) {
			throw new IllegalArgumentException(kind + " actor has entity id " + entityId);
		}
	}

	/** Returns the synthetic identity used for an explicitly scheduled explosion impulse. */
	public static ExternalActor explosion() {
		return new ExternalActor(Kind.EXPLOSION, NO_ENTITY);
	}

	/** Returns a firework-rocket actor identity for the supplied observed entity identifier. */
	public static ExternalActor fireworkRocket(final int entityId) {
		return new ExternalActor(Kind.FIREWORK_ROCKET, entityId);
	}
}
