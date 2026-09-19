package com.nettarion.stride.simulator;

/**
 * Deterministic raw-bit digest of semantic kernel state: the client copy
 * through {@link #state}, the server copy with its survival and listener
 * facts through {@link #server}, the composed boundary through
 * {@link #boundary}, and a chain over a run through {@link #chain}.
 */
public final class StateDigest {
	private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;

	private StateDigest() {}

	/** Hashes the semantic client fields in their pinned raw-bit order; a hash match is not proof of equality. */
	public static long state(final PlayerState state) {
		return StateFields.digest(FNV_OFFSET_BASIS, state);
	}

	/**
	 * The server copy: every client field, then the survival and listener
	 * facts only the server holds. Equal digests are a fast comparison signal,
	 * not a substitute for {@link ServerPlayerState#rawEquals}.
	 */
	public static long server(final ServerPlayerState state) {
		return StateFields.digestOwn(StateFields.digest(FNV_OFFSET_BASIS, state), state);
	}

	/**
	 * The composed boundary: the client copy, its publisher, the server copy,
	 * the completed-action count and the pending hit. Distinct boundaries can
	 * share this finite digest; use {@link SimulationState#rawEquals} to resolve
	 * hash matches. The planner's search uses a coarser kinematic identity.
	 */
	public static long boundary(final SimulationState state) {
		return boundary(state.client, state.publisher, state.server, state.completedActions, state.pendingHurt);
	}

	/** {@link #boundary(SimulationState)} over the loose parts of a boundary a caller owns. */
	static long boundary(final PlayerState client, final Publisher publisher, final ServerPlayerState server,
	    final int completedActions, final HurtCause pendingHurt) {
		long hash = StateFields.digest(FNV_OFFSET_BASIS, client);
		hash = StateFields.digest(hash, publisher);
		hash = StateFields.digestOwn(StateFields.digest(hash, server), server);
		hash = StateFields.bits(hash, completedActions);
		return StateFields.bits(hash, pendingHurt == null ? -1 : pendingHurt.ordinal());
	}

	/**
	 * Fold one more digest into a running chain. A chain over a run's
	 * per-tick digests identifies the whole trajectory, so a transient
	 * disagreement that later reconverges still changes it.
	 */
	public static long chain(final long chain, final long digest) {
		return StateFields.bits(chain == 0L ? FNV_OFFSET_BASIS : chain, digest);
	}

	/** Fold the UTF-16 units of a label into a chain, for refusals and events. */
	public static long chain(final long chain, final String label) {
		long hash = chain == 0L ? FNV_OFFSET_BASIS : chain;
		for (int i = 0; i < label.length(); i++) {
			hash = StateFields.bits(hash, label.charAt(i));
		}
		return hash;
	}
}
