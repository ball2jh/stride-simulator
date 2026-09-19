package com.nettarion.stride.simulator.world;

/**
 * {@code BlockState.isSuffocating}, which {@code LocalPlayer.aiStep} reads
 * four times a tick through {@code moveTowardsClosestSpace}.
 *
 * <p>Carried per state rather than derived, because it cannot be derived: in
 * vanilla it is a {@code BlockBehaviour.StatePredicate} supplied through the
 * block's {@code Properties}, and stone, glass and oak leaves share one
 * collision shape and one full-cube answer while disagreeing about this.
 *
 * <p>{@link #UNKNOWN} is what a snapshot written before this fact existed
 * says, and it is not a synonym for {@link #NO}: a consumer that reaches a
 * cell whose suffocation could matter must fail closed rather than assume.
 */
public enum Suffocation {
	/** The capture did not record the predicate; a query that reaches the cell refuses. */
	UNKNOWN,
	/** The state does not suffocate. */
	NO,
	/** The state suffocates. */
	YES
}
