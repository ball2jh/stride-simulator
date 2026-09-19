package com.nettarion.stride.simulator;

/**
 * Why the simulator refused a transition: one stable name per limit of the admitted domain, so a
 * consumer can count and compare refusals without parsing the message that explains one.
 *
 * <p>Every refusal carries exactly one cause, reached through {@link RefusalException#cause()}. A
 * cause names the limit, not the site: every query that leaves the captured region shares
 * {@link #OUTSIDE_REGION} whichever fact asked. Only the cause is stable across releases; a cause is
 * added when a limit is, never renamed, so a histogram keyed on it reads the same across revisions.
 * The message beside it is a human explanation with the coordinates and values of the one call and
 * may change in any release; do not parse it.
 *
 * <p>Groups: {@code UNMODELED_*} (a mechanic this library lacks), {@code INADMISSIBLE_*} (state or
 * world outside the admitted domain), {@code OUTSIDE_REGION} and {@code UNDECLARED_*} (the world cannot
 * answer), {@code PENDING_*} (the server outcome is undetermined), {@code CORRECTED_*} (the server
 * corrects the client), {@link #STALE_WRITE} (a write met a state it was not derived from).
 */
public enum RefusalCause {
	/** A block body, fluid or listener effect the simulator does not model. */
	UNMODELED_BLOCK,
	/** A world write the simulator does not make: cauldron levels, melted snow, block updates. */
	UNMODELED_WORLD_WRITE,
	/** Death, respawn, disconnection, or another session-ending transition. */
	UNMODELED_SESSION_END,
	/** A scheduled actor or packet the composition does not model. */
	UNMODELED_SCHEDULE,
	/** An external displacement not reducible to a velocity write. */
	UNMODELED_IMPULSE,

	/** A player field holds a value outside the admitted domain. */
	INADMISSIBLE_STATE,
	/** A movement attribute or modifier outside the supported inputs. */
	INADMISSIBLE_ATTRIBUTE,
	/** The center or a collision query lies outside the exact coordinate domain. */
	INADMISSIBLE_COORDINATE,
	/** A world view that has not declared complete movement facts. */
	INADMISSIBLE_WORLD,
	/** Block facts that do not identify one admitted behavior, or contradict the catalog. */
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
	/** A damage fact the boundary did not supply: difficulty, authority, the food phase. */
	PENDING_SURVIVAL_FACT,

	/** The listener corrects the reported movement instead of accepting it. */
	CORRECTED_MOVEMENT,

	/** A predicted write applied to a state other than the one it was derived from. */
	STALE_WRITE;
}
