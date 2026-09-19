package com.nettarion.stride.simulator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The ordered server writes one plan will cause, each at the input that causes
 * it.
 *
 * <p>Damage, sampled metadata and attributes, and motion publications share this
 * stream. Equal-boundary writes retain insertion order. One boundary can deliver
 * at most one velocity operation; metadata does not consume that slot.
 */
public final class ServerWriteTimeline {
	/** The empty timeline of a plan with no predicted authority writes. */
	public static final ServerWriteTimeline EMPTY = new ServerWriteTimeline(List.of());

	private static final Comparator<ServerWrite> DELIVERY_ORDER = Comparator.comparingInt(ServerWrite::actionIndex);

	private final List<ServerWrite> writes;

	private ServerWriteTimeline(final List<ServerWrite> writes) {
		this.writes = List.copyOf(writes);
		validate(this.writes);
	}

	/** Compose loose writes into one timeline ordered by delivery boundary. */
	public static ServerWriteTimeline of(final List<? extends ServerWrite> writes) {
		Objects.requireNonNull(writes, "writes");
		if (writes.isEmpty()) return EMPTY;
		List<ServerWrite> ordered = new ArrayList<>(writes);
		ordered.sort(DELIVERY_ORDER);
		return new ServerWriteTimeline(ordered);
	}

	/** Every write in delivery order. */
	public List<ServerWrite> writes() {
		return this.writes;
	}

	/** The hurt-marked velocity publications, in delivery order. */
	public List<HurtMotionWrite> hurtMotion() {
		return this.writes.stream()
		    .filter(HurtMotionWrite.class ::isInstance)
		    .map(HurtMotionWrite.class ::cast)
		    .toList();
	}

	/** The hits the server deals, in delivery order. */
	public List<DamageWrite> damage() {
		return this.writes.stream().filter(DamageWrite.class ::isInstance).map(DamageWrite.class ::cast).toList();
	}

	/** Returns whether this timeline contains no server writes. */
	public boolean isEmpty() {
		return this.writes.isEmpty();
	}

	/** The hurt-marked publication delivered after this completed action, if any. */
	public Optional<HurtMotionWrite> hurtMotionAfterAction(final int actionIndex) {
		for (ServerWrite write : this.writes) {
			if (write.actionIndex() == actionIndex && write instanceof HurtMotionWrite hurt) {
				return Optional.of(hurt);
			}
		}
		return Optional.empty();
	}

	/** Whether a velocity write is delivered after this completed action. */
	public boolean velocityWriteAfterAction(final int actionIndex) {
		return this.writes.stream().anyMatch(write -> write.actionIndex() == actionIndex && movesVelocity(write));
	}

	private static boolean movesVelocity(final ServerWrite write) {
		return write instanceof HurtMotionWrite || write instanceof ImpulseWrite;
	}

	/** Apply every write delivered at this boundary in delivery order. */
	public void applyAfterAction(final int completedAction, final PlayerState state) {
		for (ServerWrite write : this.writes) {
			write.applyAfterAction(completedAction, state);
		}
	}

	/** Return this timeline relative to a suffix, dropping delivered writes. */
	public ServerWriteTimeline afterConsuming(final int actions) {
		if (actions == 0) return this;
		return of(this.writes.stream()
		        .map(write -> write.afterConsuming(actions))
		        .flatMap(Optional::stream)
		        .map(ServerWrite.class ::cast)
		        .toList());
	}

	/** Retain only writes delivered strictly inside the first {@code ticks}. */
	public ServerWriteTimeline before(final int ticks) {
		return of(this.writes.stream().filter(write -> write.actionIndex() < ticks).toList());
	}

	private static void validate(final List<ServerWrite> writes) {
		ServerWrite previous = null;
		int velocityBoundary = -1;
		for (ServerWrite write : writes) {
			Objects.requireNonNull(write, "timeline contains null write");
			if (write.actionIndex() < 0) {
				throw new IllegalArgumentException("timeline write delivery is out of range");
			}
			if (previous != null && previous.actionIndex() > write.actionIndex()) {
				throw new IllegalArgumentException("timeline writes are outside delivery order");
			}
			if (movesVelocity(write)) {
				if (velocityBoundary == write.actionIndex()) {
					throw new IllegalArgumentException("one boundary cannot deliver two velocity writes");
				}
				velocityBoundary = write.actionIndex();
			}
			previous = write;
		}
	}

	@Override
	public boolean equals(final Object other) {
		return other instanceof ServerWriteTimeline timeline && this.writes.equals(timeline.writes);
	}

	@Override
	public int hashCode() {
		return this.writes.hashCode();
	}

	@Override
	public String toString() {
		return "ServerWriteTimeline" + this.writes;
	}
}
