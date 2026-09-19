package com.nettarion.stride.simulator;

import java.util.Objects;

/**
 * Who wrote the player's velocity from outside the ordinary tick: the actor's kind and, when it is an entity, its
 * network id.
 *
 * <p>Identity is what lets two writes with equal payloads stay two events and lets a refusal name what it could not
 * schedule. An explosion is not an entity; its packet carries no id.
 *
 * @param kind the actor's kind
 * @param entityId the actor's network entity id, or {@link #NO_ENTITY} for an explosion
 */
public record ExternalActor(Kind kind, int entityId) {
	/** The id of an actor that is not an entity. */
	public static final int NO_ENTITY = -1;

	/** The actors the admitted domain can name; every other writer is unmodeled. */
	public enum Kind {
		/** {@code ServerExplosion}: the owner's {@code ClientboundExplodePacket} adds its vector. */
		EXPLOSION,
		/**
		 * {@code FireworkRocketEntity} attached to the player: each actor tick, on both sides, replaces a gliding
		 * player's velocity.
		 */
		FIREWORK_ROCKET
	}

	/** Validates that an explosion has no entity id and an entity actor has a non-negative one. */
	public ExternalActor {
		Objects.requireNonNull(kind, "kind");
		if (kind == Kind.EXPLOSION ? entityId != NO_ENTITY : entityId < 0) {
			throw new IllegalArgumentException(kind + " actor has entity id " + entityId);
		}
	}

	/** The one explosion actor: explosions carry no entity id. */
	public static ExternalActor explosion() {
		return new ExternalActor(Kind.EXPLOSION, NO_ENTITY);
	}

	/** A firework rocket by its observed network entity id. */
	public static ExternalActor fireworkRocket(final int entityId) {
		return new ExternalActor(Kind.FIREWORK_ROCKET, entityId);
	}
}
