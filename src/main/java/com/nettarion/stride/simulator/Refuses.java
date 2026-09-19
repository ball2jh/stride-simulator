package com.nettarion.stride.simulator;

/**
 * What every refusal the simulator throws has in common: one {@link Refusal}
 * cause a consumer can count on, beside the message that explains the call.
 *
 * <p>A refusal is a signal, not a fault. It is thrown on the search's hot
 * path whenever a branch leaves the admitted slice, so by default none of
 * the four types records a stack trace; the cause, the message and the
 * type are the diagnosis. Set {@code -Dstride.refusal.trace=true} to record
 * traces while hunting where one is thrown from.
 */
public interface Refuses {
	/** Whether refusals record a stack trace; read once. */
	boolean TRACE = Boolean.getBoolean("stride.refusal.trace");

	/** The boundary this refusal names. */
	Refusal cause();
}
