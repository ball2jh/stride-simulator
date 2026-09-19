package com.nettarion.stride.simulator;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;

/**
 * Matches packets a caller observed on its connection to the writes the simulator predicted, by value and order
 * rather than by arrival time.
 *
 * <p>The caller queues each {@link Simulator.Confirmation} of a step with {@link #expect}, then attributes each
 * observed packet with an {@code observe} method and reads the {@link Match}. Each field has its own ordered stream
 * because one entity-data packet can coalesce values caused at different actions; a match may skip intermediate
 * values the server coalesced, and a repeated last value is a {@link Match#DUPLICATE}. At most 64 pending values are
 * retained per field; an older prediction is dropped and its packet is {@link Match#UNATTRIBUTED}.
 *
 * <p>The ledger classifies causality only: an unattributed value says a write was not predicted, and an attributed
 * one says a predicted write arrived. It never says when a write is applied, and it never licenses suppressing one.
 * Position corrections are outside it. Impulses have their own stream: an add matches on its full doubles, a
 * replacement in decoded packet space, and an impulse nobody predicted is unattributed, which is what makes it a
 * correction. Not thread-safe, like every other object in this library.
 */
public final class WriteLedger {
	private static final int RETAINED_CONFIRMATIONS = 64;

	private final ValueStream<Boolean> sprint = new ValueStream<>();

	private final ValueStream<Boolean> swimming = new ValueStream<>();

	private final ValueStream<PlayerState.Pose> pose = new ValueStream<>();

	private final ValueStream<Integer> frozenTicks = new ValueStream<>();

	private final ValueStream<VelocityValue> velocity = new ValueStream<>(WriteLedger::sameVelocityPacketValue);

	private final ValueStream<ImpulseValue> impulses = new ValueStream<>(WriteLedger::sameImpulseValue);

	private final ValueStream<HurtCause> damage = new ValueStream<>();

	/** An empty ledger with no expectations and no observations. */
	public WriteLedger() {}

	/** Queue one predicted write for a later observation to match. */
	public void expect(final Simulator.Confirmation confirmation) {
		Objects.requireNonNull(confirmation, "confirmation");
		switch (confirmation) {
			case Simulator.SprintConfirmation value -> this.sprint.expect(value.sprinting());
			case Simulator.SwimmingConfirmation value -> this.swimming.expect(value.swimming());
			case Simulator.PoseConfirmation value -> this.pose.expect(value.pose());
			case Simulator.VelocityConfirmation value ->
				this.velocity.expect(new VelocityValue(value.x(), value.y(), value.z()));
			case Simulator.FreezeConfirmation value -> this.frozenTicks.expect(value.ticksFrozen());
			case Simulator.ImpulseConfirmation value ->
				this.impulses.expect(new ImpulseValue(value.operation(), value.x(), value.y(), value.z()));
			case Simulator.DamageConfirmation value -> this.damage.expect(value.cause());
		}
	}

	/** Attribute one observed damage-event packet on the player by its cause. */
	public Match observeDamage(final HurtCause cause) {
		Objects.requireNonNull(cause, "cause");
		return this.damage.observe(cause);
	}

	/** Attribute one observed external velocity operation on the player. */
	public Match observeImpulse(
	    final ImpulseWrite.Operation operation, final double x, final double y, final double z) {
		Objects.requireNonNull(operation, "operation");
		return this.impulses.observe(new ImpulseValue(operation, x, y, z));
	}

	/**
	 * Attribute one observed entity-data packet that carried no frozen tick count: each present value is matched
	 * against its own stream and an absent one is {@link Match#ABSENT}.
	 */
	public EntityDataResult observeEntityData(final Optional<Boolean> sprinting, final Optional<Boolean> swimmingValue,
	    final Optional<PlayerState.Pose> poseValue) {
		return observeEntityData(sprinting, swimmingValue, poseValue, Optional.empty());
	}

	/**
	 * Attribute one observed entity-data packet: each present value is matched against its own stream and an absent
	 * one is {@link Match#ABSENT}.
	 */
	public EntityDataResult observeEntityData(final Optional<Boolean> sprinting, final Optional<Boolean> swimmingValue,
	    final Optional<PlayerState.Pose> poseValue, final Optional<Integer> frozenTicksValue) {
		Objects.requireNonNull(sprinting, "sprinting");
		Objects.requireNonNull(swimmingValue, "swimming");
		Objects.requireNonNull(poseValue, "pose");
		Objects.requireNonNull(frozenTicksValue, "frozenTicks");
		return new EntityDataResult(sprinting.map(this.sprint::observe).orElse(Match.ABSENT),
		    swimmingValue.map(this.swimming::observe).orElse(Match.ABSENT),
		    poseValue.map(this.pose::observe).orElse(Match.ABSENT),
		    frozenTicksValue.map(this.frozenTicks::observe).orElse(Match.ABSENT));
	}

	/** Attribute one observed entity-motion packet by its decoded vector, bit for bit. */
	public Match observeVelocity(final double x, final double y, final double z) {
		return this.velocity.observe(new VelocityValue(x, y, z));
	}

	/** Former name of {@link #observeVelocity}, retained for a caller in the root package. */
	public Match confirmVelocityCausally(final double x, final double y, final double z) {
		return observeVelocity(x, y, z);
	}

	/** How one observed value relates to the predictions. */
	public enum Match {
		/** The observation carried no value for this field. */
		ABSENT,
		/** A pending predicted value matched and was consumed. */
		CONFIRMED,
		/** The value repeats the last matched one and consumed nothing. */
		DUPLICATE,
		/** No retained prediction attributes the value. */
		UNATTRIBUTED;

		/** Whether the value was predicted: {@link #CONFIRMED} or {@link #DUPLICATE}. */
		public boolean predicted() {
			return this == CONFIRMED || this == DUPLICATE;
		}

		/** Whether the observation carried a value for this field. */
		public boolean present() {
			return this != ABSENT;
		}
	}

	/**
	 * The match of each field of one entity-data packet.
	 *
	 * @param sprint the sprint flag's match
	 * @param swimming the swimming flag's match
	 * @param pose the pose's match
	 * @param frozenTicks the frozen tick count's match
	 */
	public record EntityDataResult(Match sprint, Match swimming, Match pose, Match frozenTicks) {
		/** Validates that no match is null. */
		public EntityDataResult {
			Objects.requireNonNull(sprint, "sprint");
			Objects.requireNonNull(swimming, "swimming");
			Objects.requireNonNull(pose, "pose");
			Objects.requireNonNull(frozenTicks, "frozenTicks");
		}
	}

	private record VelocityValue(double x, double y, double z) {}

	private record ImpulseValue(ImpulseWrite.Operation operation, double x, double y, double z) {}

	/**
	 * An add is the explode packet's own doubles, so it matches bit for bit; a replacement travels as a motion
	 * packet and matches like one.
	 */
	private static boolean sameImpulseValue(final ImpulseValue expected, final ImpulseValue observed) {
		if (expected.operation != observed.operation) {
			return false;
		}
		return switch (expected.operation) {
			case ADD, REPLACE ->
				sameBits(expected.x, observed.x) && sameBits(expected.y, observed.y)
				    && sameBits(expected.z, observed.z);
			case MOVE -> false;
		};
	}

	/** Exact equality of the decoded motion payload; nearby packets are different writes. */
	private static boolean sameVelocityPacketValue(final VelocityValue expected, final VelocityValue observed) {
		return sameBits(expected.x, observed.x) && sameBits(expected.y, observed.y) && sameBits(expected.z, observed.z);
	}

	private static boolean sameBits(final double first, final double second) {
		return Double.doubleToRawLongBits(first) == Double.doubleToRawLongBits(second);
	}

	@FunctionalInterface
	private interface ValueMatcher<T> {
		boolean matches(T expected, T observed);
	}

	private static final class ValueStream<T> {
		private final Deque<T> pending = new ArrayDeque<>();

		private final ValueMatcher<T> matcher;

		private T lastMatched;

		private ValueStream() {
			this(Objects::equals);
		}

		private ValueStream(final ValueMatcher<T> matcher) {
			this.matcher = matcher;
		}

		private void expect(final T value) {
			this.pending.addLast(value);
			while (this.pending.size() > RETAINED_CONFIRMATIONS) {
				this.pending.removeFirst();
			}
		}

		private Match observe(final T value) {
			for (T expected : this.pending) {
				if (!this.matcher.matches(expected, value)) {
					continue;
				}
				while (!this.pending.isEmpty()) {
					T consumed = this.pending.removeFirst();
					if (consumed == expected) {
						break;
					}
				}
				this.lastMatched = expected;
				return Match.CONFIRMED;
			}
			if (this.lastMatched != null && this.matcher.matches(this.lastMatched, value)) {
				return Match.DUPLICATE;
			}
			return Match.UNATTRIBUTED;
		}
	}
}
