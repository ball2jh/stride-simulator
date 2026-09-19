package com.nettarion.stride.simulator;

/**
 * Why the simulator refused a transition: one stable name per boundary of the
 * admitted slice, so a consumer can count and compare refusals without
 * parsing the sentence that explains one.
 *
 * <p>Every refusal the simulator throws carries exactly one cause, reached
 * through {@link Refuses#cause()}. A cause names the boundary, not the site:
 * every query that leaves the captured region shares {@link #OUTSIDE_REGION}
 * whichever fact asked. The message beside it stays the human explanation
 * with the coordinates and values of the one call, and its text is a stable
 * part of the recorded evidence; a cause is added when a boundary is, never
 * renamed, so a histogram keyed on it reads the same across revisions.
 *
 * <p>The groups follow the refusal types. The simulator does not model a
 * mechanic ({@code UNMODELLED_*}); the state or world offered is outside the
 * admitted slice ({@code INADMISSIBLE_*}); the world cannot answer
 * ({@code OUTSIDE_REGION}, {@code UNDECLARED_*}); the server outcome is not
 * determined by the composed state ({@code PENDING_*}); the server corrects
 * the client ({@code CORRECTED_*}); or a predicted write met a state it was
 * not derived from ({@link #STALE_WRITE}).
 */
public enum Refusal {
	/** A block body, fluid or listener effect the slice does not model. */
	UNMODELLED_BLOCK,
	/** A world write the slice does not make: cauldron levels, melted snow, block updates. */
	UNMODELLED_WORLD_WRITE,
	/** Death, respawn, disconnection, or another session-ending transition. */
	UNMODELLED_SESSION_END,
	/** A scheduled actor or packet the composition does not model. */
	UNMODELLED_SCHEDULE,
	/** An external displacement not reducible to a velocity write. */
	UNMODELLED_IMPULSE,

	/** A player field holds a value the admitted slice does not carry. */
	INADMISSIBLE_STATE,
	/** A movement attribute or modifier outside the supported inputs. */
	INADMISSIBLE_ATTRIBUTE,
	/** The centre or a collision query lies outside the exact coordinate domain. */
	INADMISSIBLE_COORDINATE,
	/** A world view that has not declared complete movement facts. */
	INADMISSIBLE_WORLD,
	/** Block facts that do not identify one admitted behaviour, or contradict the catalog. */
	INADMISSIBLE_BLOCK_FACTS,

	/** A query reached space the capture does not declare. */
	OUTSIDE_REGION,
	/** A captured cell whose answer depends on block state the capture did not record. */
	UNDECLARED_BLOCK_STATE,
	/** A fact this view cannot resolve: suffocation, fluids, support, rain, a fall-reset clip. */
	UNDECLARED_WORLD_FACT,
	/** A query reached geometry the server may change after contact. */
	UNDECLARED_SERVER_MUTABLE,

	/** A hit, food or air outcome that depends on the uncaptured invulnerable ability. */
	PENDING_ABILITY,
	/** A server outcome that depends on the server's own randomness or tick parity. */
	PENDING_SERVER_RANDOM,
	/** A run ended, or a correction awaits acknowledgement, while a write is still pending. */
	PENDING_WRITE,
	/** A survival fact the boundary did not supply: difficulty, authority, the food phase. */
	PENDING_SURVIVAL_FACT,

	/** The listener corrects the reported movement instead of accepting it. */
	CORRECTED_MOVEMENT,

	/** A predicted write applied to a state other than the one it was derived from. */
	STALE_WRITE,

	/** A refusal relayed from outside the simulator by a thrower that cannot name its boundary. */
	UNCLASSIFIED;
}
