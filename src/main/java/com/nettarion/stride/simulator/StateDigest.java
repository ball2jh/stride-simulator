package com.nettarion.stride.simulator;

/**
 * Deterministic raw-bit digest of simulator state: the client copy through {@link #state}, the
 * server copy with its damage and listener facts through {@link #server}, the composed
 * boundary through {@link #boundary}, and a chain over a run through {@link #chain}.
 *
 * <p>Each digest folds the raw bits of every public field in the order of
 * {@code src/main/fields/state-fields.tsv}; equal digests are a fast comparison signal, not a
 * guarantee of equality.
 */
public final class StateDigest {
	private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;

	private StateDigest() {}

	/** Digests every client field in table order; a digest match does not guarantee equality. */
	public static long state(final PlayerState state) {
		return StateFields.digest(FNV_OFFSET_BASIS, state);
	}

	/**
	 * The server copy: every client field, then the damage and listener
	 * facts only the server holds. Equal digests are a fast comparison signal,
	 * not a substitute for {@link ServerPlayerState#rawEquals}.
	 */
	public static long server(final ServerPlayerState state) {
		return StateFields.digestOwn(StateFields.digest(FNV_OFFSET_BASIS, state), state);
	}

	/**
	 * The composed boundary: the client copy, its publisher, the server copy, the completed-action
	 * count and the pending hurt. Distinct boundaries can share this finite digest; use
	 * {@link SimulationState#rawEquals} to resolve hash matches.
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
