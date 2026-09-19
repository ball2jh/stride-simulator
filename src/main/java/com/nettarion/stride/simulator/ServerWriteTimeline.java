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
 * stream. Equal-boundary events retain insertion order. One boundary can deliver
 * at most one velocity operation; metadata does not consume that slot.
 */
public final class ServerWriteTimeline {
	/** The empty timeline of a plan with no predicted authority writes. */
	public static final ServerWriteTimeline EMPTY = new ServerWriteTimeline(List.of());

	private static final Comparator<ServerWrite> DELIVERY_ORDER = Comparator.comparingInt(ServerWrite::actionIndex);

	private final List<ServerWrite> events;

	private ServerWriteTimeline(final List<ServerWrite> events) {
		this.events = List.copyOf(events);
		validate(this.events);
	}

	/** Compose loose events into one timeline ordered by delivery boundary. */
	public static ServerWriteTimeline of(final List<? extends ServerWrite> events) {
		Objects.requireNonNull(events, "events");
		if (events.isEmpty()) return EMPTY;
		List<ServerWrite> ordered = new ArrayList<>(events);
		ordered.sort(DELIVERY_ORDER);
		return new ServerWriteTimeline(ordered);
	}

	/** Every event in delivery order. */
	public List<ServerWrite> events() {
		return this.events;
	}

	/** The hurt-marked velocity publications, in delivery order. */
	public List<HurtMotionWrite> hurtMotion() {
		return this.events.stream()
		    .filter(HurtMotionWrite.class ::isInstance)
		    .map(HurtMotionWrite.class ::cast)
		    .toList();
	}

	/** The hits the server deals, in delivery order. */
	public List<DamageWrite> damage() {
		return this.events.stream().filter(DamageWrite.class ::isInstance).map(DamageWrite.class ::cast).toList();
	}

	/** Returns whether this timeline contains no server writes. */
	public boolean isEmpty() {
		return this.events.isEmpty();
	}

	/** The hurt-marked publication delivered after this completed action, if any. */
	public Optional<HurtMotionWrite> hurtMotionAfterAction(final int actionIndex) {
		for (ServerWrite event : this.events) {
			if (event.actionIndex() == actionIndex && event instanceof HurtMotionWrite hurt) {
				return Optional.of(hurt);
			}
		}
		return Optional.empty();
	}

	/** Whether a velocity write is delivered after this completed action. */
	public boolean velocityWriteAfterAction(final int actionIndex) {
		return this.events.stream().anyMatch(event -> event.actionIndex() == actionIndex && movesVelocity(event));
	}

	private static boolean movesVelocity(final ServerWrite event) {
		return event instanceof HurtMotionWrite || event instanceof ImpulseWrite;
	}

	/** Apply every write delivered at this boundary in delivery order. */
	public void applyAfterAction(final int completedAction, final PlayerState state) {
		for (ServerWrite event : this.events) {
			event.applyAfterAction(completedAction, state);
		}
	}

	/** Return this timeline relative to a suffix, dropping delivered events. */
	public ServerWriteTimeline afterConsuming(final int actions) {
		if (actions == 0) return this;
		return of(this.events.stream()
		        .map(event -> event.afterConsuming(actions))
		        .flatMap(Optional::stream)
		        .map(ServerWrite.class ::cast)
		        .toList());
	}

	/** Retain only events delivered strictly inside the first {@code ticks}. */
	public ServerWriteTimeline before(final int ticks) {
		return of(this.events.stream().filter(event -> event.actionIndex() < ticks).toList());
	}

	private static void validate(final List<ServerWrite> events) {
		ServerWrite previous = null;
		int velocityBoundary = -1;
		for (ServerWrite event : events) {
			Objects.requireNonNull(event, "timeline contains null event");
			if (event.actionIndex() < 0) {
				throw new IllegalArgumentException("timeline event delivery is out of range");
			}
			if (previous != null && previous.actionIndex() > event.actionIndex()) {
				throw new IllegalArgumentException("timeline events are outside delivery order");
			}
			if (movesVelocity(event)) {
				if (velocityBoundary == event.actionIndex()) {
					throw new IllegalArgumentException("one boundary cannot deliver two velocity writes");
				}
				velocityBoundary = event.actionIndex();
			}
			previous = event;
		}
	}

	@Override
	public boolean equals(final Object other) {
		return other instanceof ServerWriteTimeline timeline && this.events.equals(timeline.events);
	}

	@Override
	public int hashCode() {
		return this.events.hashCode();
	}

	@Override
	public String toString() {
		return "ServerWriteTimeline" + this.events;
	}
}
