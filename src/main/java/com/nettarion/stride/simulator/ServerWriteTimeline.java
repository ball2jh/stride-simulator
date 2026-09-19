package com.nettarion.stride.simulator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The server writes one action list causes, ordered by the completed action each is applied after.
 *
 * <p>Damage, entity-data, health, position, block and velocity writes share the one stream. Writes at the same action
 * index keep their insertion order. One action index carries at most one velocity write ({@link HurtMotionWrite} or
 * {@link ImpulseWrite}); the other kinds do not take that slot. Immutable.
 */
public final class ServerWriteTimeline {
	/** The timeline of an action list that causes no server write. */
	public static final ServerWriteTimeline EMPTY = new ServerWriteTimeline(List.of());

	private static final Comparator<ServerWrite> DELIVERY_ORDER = Comparator.comparingInt(ServerWrite::actionIndex);

	private final List<ServerWrite> writes;

	/** Wraps an already ordered list; {@link #of} is the sorting entry point. */
	private ServerWriteTimeline(final List<ServerWrite> writes) {
		this.writes = List.copyOf(writes);
		validate(this.writes);
	}

	/**
	 * Order loose writes by action index into one timeline.
	 *
	 * @throws IllegalArgumentException when a write is null, has a negative action index, or two velocity writes
	 *     share an action index
	 */
	public static ServerWriteTimeline of(final List<? extends ServerWrite> writes) {
		Objects.requireNonNull(writes, "writes");
		if (writes.isEmpty()) {
			return EMPTY;
		}
		List<ServerWrite> ordered = new ArrayList<>(writes);
		ordered.sort(DELIVERY_ORDER);
		return new ServerWriteTimeline(ordered);
	}

	/** Every write in delivery order. */
	public List<ServerWrite> writes() {
		return this.writes;
	}

	/** The hurt-marked velocity writes, in delivery order. */
	public List<HurtMotionWrite> hurtMotion() {
		List<HurtMotionWrite> found = new ArrayList<>();
		for (ServerWrite write : this.writes) {
			if (write instanceof HurtMotionWrite motion) {
				found.add(motion);
			}
		}
		return List.copyOf(found);
	}

	/** The hits the server deals, in delivery order. */
	public List<DamageWrite> damage() {
		List<DamageWrite> found = new ArrayList<>();
		for (ServerWrite write : this.writes) {
			if (write instanceof DamageWrite hit) {
				found.add(hit);
			}
		}
		return List.copyOf(found);
	}

	/** Whether this timeline contains no write. */
	public boolean isEmpty() {
		return this.writes.isEmpty();
	}

	/** Whether a velocity write is applied after this completed action. */
	public boolean velocityWriteAfterAction(final int actionIndex) {
		for (ServerWrite write : this.writes) {
			if (write.actionIndex() == actionIndex && movesVelocity(write)) {
				return true;
			}
		}
		return false;
	}

	/** Apply every write whose action index is {@code completedAction}, in delivery order. */
	public void applyAfterAction(final int completedAction, final PlayerState state) {
		for (ServerWrite write : this.writes) {
			write.applyAfterAction(completedAction, state);
		}
	}

	/**
	 * This timeline relative to the actions remaining after the first {@code actions} completed, without the writes
	 * delivered inside them. Shifting every index by the same amount keeps the order, so nothing is re-sorted.
	 */
	public ServerWriteTimeline afterConsuming(final int actions) {
		if (actions == 0) {
			return this;
		}
		List<ServerWrite> remaining = new ArrayList<>(this.writes.size());
		for (ServerWrite write : this.writes) {
			Optional<ServerWrite> shifted = write.afterConsuming(actions);
			if (shifted.isPresent()) {
				remaining.add(shifted.get());
			}
		}
		return remaining.isEmpty() ? EMPTY : new ServerWriteTimeline(remaining);
	}

	private static boolean movesVelocity(final ServerWrite write) {
		return write instanceof HurtMotionWrite || write instanceof ImpulseWrite;
	}

	private static void validate(final List<ServerWrite> writes) {
		ServerWrite previous = null;
		int velocityAction = -1;
		for (ServerWrite write : writes) {
			Objects.requireNonNull(write, "timeline contains null write");
			if (write.actionIndex() < 0) {
				throw new IllegalArgumentException("timeline write delivery is out of range");
			}
			if (previous != null && previous.actionIndex() > write.actionIndex()) {
				throw new IllegalArgumentException("timeline writes are outside delivery order");
			}
			if (movesVelocity(write)) {
				if (velocityAction == write.actionIndex()) {
					throw new IllegalArgumentException("one action index cannot deliver two velocity writes");
				}
				velocityAction = write.actionIndex();
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
