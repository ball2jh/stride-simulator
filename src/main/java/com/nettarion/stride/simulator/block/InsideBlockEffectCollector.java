package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.Refusal;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.server.TickAuthority;
import com.nettarion.stride.simulator.world.SnapshotView;
import java.util.ArrayList;
import java.util.List;

import java.util.Arrays;

/**
 * Collects and applies the step-grouped effects of one inside-block traversal.
 *
 * <p>Each effect is deduplicated within its step; the attached fire hits retain
 * their order and multiplicity. Server effects queue at step boundaries and apply after traversal through the transaction's
 * {@link com.nettarion.stride.simulator.server.Survival}; freeze effects are accumulated for the traversal tail.
 * This preserves the admitted slice's specialization of the pinned
 * {@code InsideBlockEffectApplier.StepBasedCollector} and
 * {@code InsideBlockEffectType} order. Ambiguous grouping refuses, for every
 * collected type alike: a cell the traversal cannot see would have flushed the
 * step in vanilla, and two effects the flush would have separated must not
 * merge into one de-duplicated step here.
 *
 * <p>Retained by one stepping thread. {@link #begin()} resets all pending
 * effects before a traversal, including after a refused transition.
 */
public final class InsideBlockEffectCollector {
	private final TickAuthority authority;
	/** InsideBlockEffectApplier.StepBasedCollector.lastStep. */
	private int lastStep = -1;
	private boolean freezePendingInStep;
	private boolean clearFreezePendingInStep;
	private int freezeSteps;
	private boolean clearFreezeSeen;
	private int freezeStepsAfterLastClear;
	private boolean collectedStepAmbiguous;
	private boolean insideSweepAborted;
	private boolean startedInsideBlockSweep;

	/*
	 * The server-only collected effects of the inside-block step in progress,
	 * flushed with the step in InsideBlockEffectType order: FIRE_IGNITE and
	 * the in-fire hits that run after it, LAVA_IGNITE and the lava hits that
	 * run after it, then EXTINGUISH. Vanilla collects them as closures on
	 * StepBasedCollector; here each is the fact the flush needs.
	 */

	private boolean fireIgnitePendingInStep;
	private boolean lavaIgnitePendingInStep;
	private boolean extinguishPendingInStep;
	private float[] stepFireHurts = new float[4];
	private int stepFireHurtCount;
	private int stepLavaHurtCount;
	private record ServerEffects(boolean fire, boolean lava, boolean extinguish, float[] fireHurts, int lavaHurts,
	    List<Runnable> beforeExtinguish) {}
	private static final ServerEffects EXTINGUISH = new ServerEffects(false, false, true, new float[0], 0, List.of());
	private final ArrayList<ServerEffects> finalServerEffects = new ArrayList<>();
	private final ArrayList<Runnable> beforeExtinguish = new ArrayList<>();

	/** LayeredCauldronBlock.runBefore(EXTINGUISH), using the state captured at the visit. */
	void collectCauldron(final SnapshotView world, final int x, final int y, final int z, final int successor) {
		this.beforeExtinguish.add(() -> {
			if (this.authority.serverState().remainingFireTicks > 0)
				this.authority.lowerCauldron(world, x, y, z, successor);
		});
		collectExtinguish();
	}

	/**
	 * {@code PowderSnowBlock.entityInside} on the server after FREEZE:
	 * {@code runBefore(EXTINGUISH)} destroys the block under a burning player
	 * who may interact with it, then {@code apply(EXTINGUISH)}. The melt is a
	 * world write the slice does not make, so a burning server copy refuses
	 * at the visit rather than passing as merely extinguished.
	 */
	void collectPowderSnowContact() {
		this.beforeExtinguish.add(() -> {
			if (this.authority.serverState().remainingFireTicks > 0) {
				throw UnimplementedMechanicException.deferred(Refusal.UNMODELLED_WORLD_WRITE,
				    ()
				        -> "a burning player melts the powder"
				        + " snow it is in: a world write the slice does not make");
			}
		});
		collectExtinguish();
	}

	public InsideBlockEffectCollector(final TickAuthority authority) {
		this.authority = java.util.Objects.requireNonNull(authority, "authority");
	}

	/** Forget the previous traversal, including a traversal that refused midway. */
	public void begin() {
		this.lastStep = -1;
		this.beforeExtinguish.clear();
		this.finalServerEffects.clear();
		this.freezePendingInStep = false;
		this.clearFreezePendingInStep = false;
		this.freezeSteps = 0;
		this.clearFreezeSeen = false;
		this.freezeStepsAfterLastClear = 0;
		this.collectedStepAmbiguous = false;
		this.insideSweepAborted = false;
		this.startedInsideBlockSweep = false;
		this.fireIgnitePendingInStep = false;
		this.lavaIgnitePendingInStep = false;
		this.extinguishPendingInStep = false;
		this.stepFireHurtCount = 0;
		this.stepLavaHurtCount = 0;
	}

	/**
	 * {@code BaseFireBlock.entityInside} on the server: {@code apply(FIRE_IGNITE)}
	 * and the in-fire hit of this fire's damage after it.
	 */
	public void collectFireContact(final float fireDamage) {
		assertCollectedStepUnambiguous();
		this.fireIgnitePendingInStep = true;
		if (this.stepFireHurtCount == this.stepFireHurts.length) {
			this.stepFireHurts = Arrays.copyOf(this.stepFireHurts, this.stepFireHurts.length * 2);
		}
		this.stepFireHurts[this.stepFireHurtCount++] = fireDamage;
	}

	/**
	 * {@code LavaFluid} or {@code LavaCauldronBlock.entityInside} on the
	 * server: {@code apply(LAVA_IGNITE)} and {@code lavaHurt} after it.
	 */
	void collectLavaContact() {
		assertCollectedStepUnambiguous();
		this.lavaIgnitePendingInStep = true;
		this.stepLavaHurtCount++;
	}

	/**
	 * {@code StepBasedCollector.addEffect(FREEZE)}: one freeze increment for
	 * the step in progress, de-duplicated per step.
	 */
	void collectFreeze() {
		assertCollectedStepUnambiguous();
		this.freezePendingInStep = true;
	}

	/** {@code StepBasedCollector.addEffect(CLEAR_FREEZE)} for the step in progress. */
	void collectClearFreeze() {
		assertCollectedStepUnambiguous();
		this.clearFreezePendingInStep = true;
	}

	/**
	 * Whether any collected effect of the step in progress is still pending.
	 * Every type counts: a FIRE_IGNITE or EXTINGUISH merged into a step vanilla
	 * would have flushed changes what the flush applies, exactly as a FREEZE
	 * would.
	 */
	private boolean anyPendingInStep() {
		return this.freezePendingInStep || this.clearFreezePendingInStep || this.fireIgnitePendingInStep
		    || this.lavaIgnitePendingInStep || this.extinguishPendingInStep;
	}

	private void assertCollectedStepUnambiguous() {
		if (anyPendingInStep() && this.collectedStepAmbiguous) {
			throw new UnimplementedMechanicException(Refusal.UNDECLARED_BLOCK_STATE,
			    "inside-block collected-effect step grouping depends on an unclassified cell");
		}
	}

	/** {@code WaterFluid.entityInside} on the server: {@code apply(EXTINGUISH)}. */
	void collectExtinguish() {
		assertCollectedStepUnambiguous();
		this.extinguishPendingInStep = true;
	}

	/**
	 * The server-only part of {@code StepBasedCollector.flushStep}, applied
	 * through {@link TickAuthority#survival()} in {@code InsideBlockEffectType} order. The
	 * freeze effects, which precede these in that order, touch only the
	 * frozen count and are applied after the traversal.
	 */
	private void flushServerEffectsInStep() {
		if (this.fireIgnitePendingInStep || this.lavaIgnitePendingInStep || this.extinguishPendingInStep) {
			this.finalServerEffects.add(
			    !this.fireIgnitePendingInStep && !this.lavaIgnitePendingInStep && this.beforeExtinguish.isEmpty()
			        ? EXTINGUISH
			        : new ServerEffects(this.fireIgnitePendingInStep, this.lavaIgnitePendingInStep,
			              this.extinguishPendingInStep, Arrays.copyOf(this.stepFireHurts, this.stepFireHurtCount),
			              this.stepLavaHurtCount, List.copyOf(this.beforeExtinguish)));
		}
		this.beforeExtinguish.clear();
		this.fireIgnitePendingInStep = false;
		this.lavaIgnitePendingInStep = false;
		this.extinguishPendingInStep = false;
		this.stepFireHurtCount = 0;
		this.stepLavaHurtCount = 0;
	}

	private void applyServerEffects() {
		for (ServerEffects effects : this.finalServerEffects) {
			if (effects.fire()) this.authority.survival().fireIgnite();
			for (float hurt : effects.fireHurts())
				this.authority.survival().hurtServer(HurtCause.IN_FIRE, hurt);
			if (effects.lava()) this.authority.survival().lavaIgnite();
			for (int i = 0; i < effects.lavaHurts(); i++)
				this.authority.survival().hurtServer(HurtCause.LAVA, 4.0F);
			for (Runnable before : effects.beforeExtinguish())
				before.run();
			if (effects.extinguish()) this.authority.survival().clearFire();
		}
		this.finalServerEffects.clear();
	}

	boolean hasPendingEffects() {
		return this.freezePendingInStep || this.clearFreezePendingInStep || this.fireIgnitePendingInStep
		    || this.lavaIgnitePendingInStep || this.extinguishPendingInStep || !this.finalServerEffects.isEmpty();
	}

	/**
	 * Starts one sub-movement. Step indices restart at zero here while the
	 * collector's do not, so a collected effect still pending across this boundary
	 * is the one place an unclassified cell could have flushed the step and this
	 * traversal would not see it.
	 */
	void beginSweep() {
		if (this.startedInsideBlockSweep && anyPendingInStep() && (this.lastStep >= 2 || this.insideSweepAborted)) {
			this.collectedStepAmbiguous = true;
		}
		this.startedInsideBlockSweep = true;
	}

	/** InsideBlockEffectApplier.StepBasedCollector.flushStep for modelled effects. */
	private void flushStep() {
		if (this.freezePendingInStep) {
			this.freezeSteps++;
		}
		if (this.clearFreezePendingInStep) {
			// FREEZE precedes CLEAR_FREEZE within one vanilla collector step.
			this.clearFreezeSeen = true;
			this.freezeStepsAfterLastClear = 0;
		} else if (this.freezePendingInStep && this.clearFreezeSeen) {
			this.freezeStepsAfterLastClear++;
		}
		this.freezePendingInStep = false;
		this.clearFreezePendingInStep = false;
		this.collectedStepAmbiguous = false;
		flushServerEffectsInStep();
	}

	/** Record the traversal limit that makes later collected-step grouping ambiguous. */
	void abortSweep() {
		this.insideSweepAborted = true;
	}

	/** Flush the preceding group when a newly reached cell belongs to another step. */
	void advanceStep(final int step) {
		if (this.lastStep != step) {
			this.lastStep = step;
			flushStep();
		}
	}

	/**
	 * {@code StepBasedCollector.applyAndClear}: flush the final step, then apply
	 * the traversal's effects. The freeze projection runs whenever a FREEZE or
	 * CLEAR_FREEZE was collected, on either side and from any body, block or
	 * fluid; a traversal that collected neither writes nothing here.
	 */
	public void applyAndClear(final PlayerState state) {
		flushStep();
		applyFreeze(state);
		applyServerEffects();
	}

	/** The FREEZE and CLEAR_FREEZE effects {@code applyAndClear} runs after traversal. */
	private void applyFreeze(final PlayerState state) {
		if (this.freezeSteps == 0 && !this.clearFreezeSeen) {
			return;
		}
		if (this.freezeSteps > 0) {
			state.isInPowderSnow = true;
		}
		if (this.clearFreezeSeen) {
			state.setTicksFrozen(0);
		}
		if (state.canFreeze) {
			// A clear discards all earlier increments. Within a shared collector
			// step, FREEZE is ordered before CLEAR_FREEZE and is discarded too.
			int increments = this.clearFreezeSeen ? this.freezeStepsAfterLastClear : this.freezeSteps;
			for (int step = 0; step < increments; step++) {
				state.setTicksFrozen(Math.min(140, state.ticksFrozen + 1));
			}
		}
	}
}
