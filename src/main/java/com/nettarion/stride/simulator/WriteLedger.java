package com.nettarion.stride.simulator;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;

/**
 * Matches incoming packets to predicted server writes without using arrival
 * time: the one ledger the composed step's confirmations are expected on and
 * observed publications are attributed against.
 *
 * <p>Each field has its own ordered stream because one entity-data packet can
 * coalesce values caused at different actions. A match may skip intermediate
 * values that the server coalesced. A repeated last value is a duplicate.
 * The ledger is thread-safe and retains at most 64 pending values per field;
 * a value whose evidence has aged out is deliberately unattributed.
 *
 * <p>This class classifies causality only. In particular, an unattributed
 * sprint, swimming, or pose publication does not establish its delivery schedule.
 * The integration must apply packets under a known schedule or treat them as a
 * new boundary; a matching value alone never licenses suppressing a write. Position writes are outside this ledger.
 * External impulses have their own stream: an add matches on its full
 * doubles, a replacement in decoded packet space, and an impulse nobody
 * predicted is unattributed, which is what makes it a correction.
 */
public final class WriteLedger {
	/** An empty ledger with no expectations and no observations. */
	public WriteLedger() {}

	private static final int RETAINED_CONFIRMATIONS = 64;

	private final ValueStream<Boolean> sprint = new ValueStream<>();
	private final ValueStream<Boolean> swimming = new ValueStream<>();
	private final ValueStream<PlayerState.Pose> pose = new ValueStream<>();
	private final ValueStream<Integer> frozenTicks = new ValueStream<>();
	private final ValueStream<VelocityValue> velocity = new ValueStream<>(WriteLedger::sameVelocityPacketValue);
	private final ValueStream<ImpulseValue> impulses = new ValueStream<>(WriteLedger::sameImpulseValue);
	private final ValueStream<HurtCause> damage = new ValueStream<>();

	/** Queues one predicted semantic publication for subsequent reconciliation with observations. */
	public synchronized void expect(final Simulator.Confirmation confirmation) {
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

	/** Attribute one observed damage event on the player by its cause. */
	public synchronized Match observeDamage(final HurtCause cause) {
		Objects.requireNonNull(cause, "cause");
		return this.damage.observe(cause);
	}

	/** Attribute one observed external velocity operation on the player. */
	public synchronized Match observeImpulse(
	    final ImpulseWrite.Operation operation, final double x, final double y, final double z) {
		Objects.requireNonNull(operation, "operation");
		return this.impulses.observe(new ImpulseValue(operation, x, y, z));
	}

	public synchronized EntityDataResult observeEntityData(final Optional<Boolean> sprinting,
	    final Optional<Boolean> swimmingValue, final Optional<PlayerState.Pose> poseValue) {
		return observeEntityData(sprinting, swimmingValue, poseValue, Optional.empty());
	}

	public synchronized EntityDataResult observeEntityData(final Optional<Boolean> sprinting,
	    final Optional<Boolean> swimmingValue, final Optional<PlayerState.Pose> poseValue,
	    final Optional<Integer> frozenTicksValue) {
		Objects.requireNonNull(sprinting, "sprinting");
		Objects.requireNonNull(swimmingValue, "swimming");
		Objects.requireNonNull(poseValue, "pose");
		Objects.requireNonNull(frozenTicksValue, "frozenTicks");
		return new EntityDataResult(sprinting.map(this.sprint::observe).orElse(Match.ABSENT),
		    swimmingValue.map(this.swimming::observe).orElse(Match.ABSENT),
		    poseValue.map(this.pose::observe).orElse(Match.ABSENT),
		    frozenTicksValue.map(this.frozenTicks::observe).orElse(Match.ABSENT));
	}

	/** Matches an observed velocity payload against retained predictions and prior observations. */
	public synchronized Match observeVelocity(final double x, final double y, final double z) {
		return this.velocity.observe(new VelocityValue(x, y, z));
	}

	/** A known damage cause still requires the exact predicted motion payload. */
	public synchronized Match confirmVelocityCausally(final double x, final double y, final double z) {
		return this.velocity.observe(new VelocityValue(x, y, z));
	}

	/**
	 * A known damage cause whose payload is observed rather than predicted:
	 * the next velocity value is the scheduled write whatever it carries, so
	 * the earliest pending prediction is consumed by it.
	 */
	public synchronized Match confirmVelocityByCause() {
		return this.velocity.consumeEarliest();
	}

	/** Discards every pending prediction and remembered observation in the ledger. */
	public synchronized void reset() {
		this.sprint.reset();
		this.swimming.reset();
		this.pose.reset();
		this.frozenTicks.reset();
		this.velocity.reset();
		this.impulses.reset();
		this.damage.reset();
	}

	/** Causal disposition of one observed publication value. */
	public enum Match {
		/** No supported value for this semantic field was supplied. */
		ABSENT,
		/** A pending predicted value matched and was consumed. */
		CONFIRMED,
		/** The value repeats the last matched publication. */
		DUPLICATE,
		/** No retained prediction attributes the published value. */
		UNATTRIBUTED;

		public boolean predicted() {
			return this == CONFIRMED || this == DUPLICATE;
		}

		/** Whether the observation supplied a supported value for this field. */
		public boolean present() {
			return this != ABSENT;
		}
	}

	/** Independent causal dispositions for one entity-data packet. */
	public record EntityDataResult(Match sprint, Match swimming, Match pose, Match frozenTicks) {
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
	 * An add is the explode packet's own doubles, so it matches bit for bit; a
	 * replacement travels as a motion packet and matches like one.
	 */
	private static boolean sameImpulseValue(final ImpulseValue expected, final ImpulseValue observed) {
		if (expected.operation != observed.operation) {
			return false;
		}
		return switch (expected.operation) {
			case ADD ->
				sameBits(expected.x, observed.x) && sameBits(expected.y, observed.y)
				    && sameBits(expected.z, observed.z);
			case REPLACE ->
				sameVelocityPacketValue(expected.x, expected.y, expected.z, observed.x, observed.y, observed.z);
			case MOVE -> false;
		};
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
				if (!this.matcher.matches(expected, value)) continue;
				while (!this.pending.isEmpty()) {
					T consumed = this.pending.removeFirst();
					if (consumed == expected) break;
				}
				this.lastMatched = expected;
				return Match.CONFIRMED;
			}
			if (this.lastMatched != null && this.matcher.matches(this.lastMatched, value)) {
				return Match.DUPLICATE;
			}
			return Match.UNATTRIBUTED;
		}

		private Match consumeEarliest() {
			if (this.pending.isEmpty()) return Match.UNATTRIBUTED;
			this.lastMatched = this.pending.removeFirst();
			return Match.CONFIRMED;
		}

		private void reset() {
			this.pending.clear();
			this.lastMatched = null;
		}
	}

	/** Exact equality of the decoded motion payload; nearby packets are different writes. */
	public static boolean sameVelocityPacketValue(final double expectedX, final double expectedY,
	    final double expectedZ, final double observedX, final double observedY, final double observedZ) {
		return sameBits(expectedX, observedX) && sameBits(expectedY, observedY) && sameBits(expectedZ, observedZ);
	}

	private static boolean sameVelocityPacketValue(final VelocityValue expected, final VelocityValue observed) {
		return sameVelocityPacketValue(expected.x, expected.y, expected.z, observed.x, observed.y, observed.z);
	}

	private static boolean sameBits(final double first, final double second) {
		return Double.doubleToRawLongBits(first) == Double.doubleToRawLongBits(second);
	}
}
