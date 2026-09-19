package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.server.ServerDamage;
import com.nettarion.stride.simulator.server.TickAuthority;
import com.nettarion.stride.simulator.world.SnapshotView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Collects the effects one inside-block traversal queues and applies them after it, grouped by step.
 *
 * <p>Vanilla's {@code InsideBlockEffectApplier.StepBasedCollector} de-duplicates each effect type within a step
 * and flushes the step when the traversal reaches a cell of a later step. FREEZE and CLEAR_FREEZE are counted
 * here and applied to the frozen count at the end; FIRE_IGNITE, LAVA_IGNITE and EXTINGUISH, with the fire and
 * lava hits that follow each ignition in their visit order and multiplicity, are queued per step and applied
 * through {@link TickAuthority#damage()} in {@code InsideBlockEffectType} order. A cell this traversal cannot
 * see would have flushed a step in vanilla, so a grouping that depends on such a cell refuses instead of merging
 * two steps, for every collected type alike.
 *
 * <p>Not thread-safe: one instance belongs to the tick's {@code Scratch} and is obtained from it.
 * {@link #begin()} discards everything pending, including after a refused transition.
 */
public final class InsideBlockEffectCollector {
	/** {@code LavaFluid.entityInside}'s {@code lavaHurt}: four points per lava contact. */
	private static final float LAVA_DAMAGE = 4.0F;

	/** A step that collected only EXTINGUISH with nothing to run before it; shared, since it carries no state. */
	private static final StepServerEffects EXTINGUISH_ONLY =
	    new StepServerEffects(false, false, true, new float[0], 0, List.of());

	private final TickAuthority authority;

	/** {@code InsideBlockEffectApplier.StepBasedCollector.lastStep}. */
	private int lastStep = -1;

	/** The effect types collected in the step in progress; vanilla's collector de-duplicates them the same way. */
	private final EnumSet<InsideBlockEffectType> pendingInStep = EnumSet.noneOf(InsideBlockEffectType.class);

	/** How many flushed steps carried a FREEZE. */
	private int freezeSteps;

	private boolean clearFreezeSeen;

	/** How many flushed steps carried a FREEZE after the last one that carried a CLEAR_FREEZE. */
	private int freezeStepsAfterLastClear;

	private boolean collectedStepAmbiguous;

	private boolean insideSweepAborted;

	private boolean startedInsideBlockSweep;

	/** The in-fire hits of the step in progress, one per fire visit in visit order. */
	private float[] stepFireHurts = new float[4];

	private int stepFireHurtCount;

	/** The lava visits of the step in progress, each one {@code lavaHurt}. */
	private int stepLavaHurtCount;

	/** The {@code runBefore(EXTINGUISH)} closures of the step in progress. */
	private final ArrayList<Runnable> beforeExtinguish = new ArrayList<>();

	/** The flushed steps' server-only effects, in step order, applied by {@link #applyAndClear}. */
	private final ArrayList<StepServerEffects> finalServerEffects = new ArrayList<>();

	/** A collector applying its server-only effects through {@code authority}'s damage state. */
	public InsideBlockEffectCollector(final TickAuthority authority) {
		this.authority = Objects.requireNonNull(authority, "authority");
	}

	/** Forgets the previous traversal, including a traversal that refused midway. */
	public void begin() {
		this.lastStep = -1;
		this.beforeExtinguish.clear();
		this.finalServerEffects.clear();
		this.pendingInStep.clear();
		this.freezeSteps = 0;
		this.clearFreezeSeen = false;
		this.freezeStepsAfterLastClear = 0;
		this.collectedStepAmbiguous = false;
		this.insideSweepAborted = false;
		this.startedInsideBlockSweep = false;
		this.stepFireHurtCount = 0;
		this.stepLavaHurtCount = 0;
	}

	/**
	 * {@code StepBasedCollector.applyAndClear}: flushes the final step, then applies the traversal's effects.
	 *
	 * <p>The freeze projection runs whenever a FREEZE or CLEAR_FREEZE was collected, on either side and from any
	 * hook, block or fluid; a traversal that collected neither writes nothing here. The server-only effects then
	 * run step by step through the damage state, which may refuse.
	 */
	public void applyAndClear(final PlayerState state) {
		flushStep();
		applyFreeze(state);
		applyServerEffects();
	}

	/**
	 * {@code BaseFireBlock.entityInside} on the server: {@code apply(FIRE_IGNITE)} and the in-fire hit of this
	 * fire's damage after it.
	 *
	 * <p>Public only for {@code ServerSurvivalTest}; the tick reaches it through the fire block's behavior.
	 */
	public void collectFireContact(final float fireDamage) {
		assertCollectedStepUnambiguous();
		this.pendingInStep.add(InsideBlockEffectType.FIRE_IGNITE);
		if (this.stepFireHurtCount == this.stepFireHurts.length) {
			this.stepFireHurts = Arrays.copyOf(this.stepFireHurts, this.stepFireHurts.length * 2);
		}
		this.stepFireHurts[this.stepFireHurtCount++] = fireDamage;
	}

	/**
	 * {@code LavaFluid} or {@code LavaCauldronBlock.entityInside} on the server: {@code apply(LAVA_IGNITE)} and
	 * {@code lavaHurt} after it.
	 */
	void collectLavaContact() {
		assertCollectedStepUnambiguous();
		this.pendingInStep.add(InsideBlockEffectType.LAVA_IGNITE);
		this.stepLavaHurtCount++;
	}

	/** {@code StepBasedCollector.addEffect(FREEZE)}: one freeze increment for the step in progress. */
	void collectFreeze() {
		assertCollectedStepUnambiguous();
		this.pendingInStep.add(InsideBlockEffectType.FREEZE);
	}

	/** {@code StepBasedCollector.addEffect(CLEAR_FREEZE)} for the step in progress. */
	void collectClearFreeze() {
		assertCollectedStepUnambiguous();
		this.pendingInStep.add(InsideBlockEffectType.CLEAR_FREEZE);
	}

	/** {@code WaterFluid.entityInside} on the server: {@code apply(EXTINGUISH)}. */
	void collectExtinguish() {
		assertCollectedStepUnambiguous();
		this.pendingInStep.add(InsideBlockEffectType.EXTINGUISH);
	}

	/**
	 * {@code LayeredCauldronBlock.entityInside} on the server: {@code runBefore(EXTINGUISH)} lowers the cauldron
	 * under a burning player, using the cell captured at the visit, then {@code apply(EXTINGUISH)}.
	 */
	void collectCauldron(final SnapshotView world, final int x, final int y, final int z, final int successor) {
		assertCollectedStepUnambiguous();
		this.beforeExtinguish.add(() -> {
			if (this.authority.serverState().remainingFireTicks > 0) {
				this.authority.lowerCauldron(world, x, y, z, successor);
			}
		});
		this.pendingInStep.add(InsideBlockEffectType.EXTINGUISH);
	}

	/**
	 * {@code PowderSnowBlock.entityInside} on the server after FREEZE: {@code runBefore(EXTINGUISH)} destroys the
	 * block under a burning player who may interact with it, then {@code apply(EXTINGUISH)}. The melt is a world
	 * write the simulator does not make, so a burning server copy refuses at the flush rather than passing as
	 * merely extinguished.
	 */
	void collectPowderSnowContact() {
		assertCollectedStepUnambiguous();
		this.beforeExtinguish.add(() -> {
			if (this.authority.serverState().remainingFireTicks > 0) {
				throw UnimplementedMechanicException.deferred(RefusalCause.UNMODELED_WORLD_WRITE,
				    ()
				        -> "a burning player melts the powder"
				        + " snow it is in: a world write the simulator does not make");
			}
		});
		this.pendingInStep.add(InsideBlockEffectType.EXTINGUISH);
	}

	/**
	 * Starts one sub-movement. Step indices restart at zero here while the collector's do not, so a collected
	 * effect still pending across this boundary is the one place a cell this traversal cannot see could have
	 * flushed the step in vanilla. That needs the earlier step index to be at least 2, since a sub-movement's
	 * origin pass can only re-visit cells its predecessor already covered, or an aborted sweep.
	 */
	void beginSweep() {
		if (this.startedInsideBlockSweep && !this.pendingInStep.isEmpty()
		    && (this.lastStep >= 2 || this.insideSweepAborted)) {
			this.collectedStepAmbiguous = true;
		}
		this.startedInsideBlockSweep = true;
	}

	/** Records the traversal limit that makes later collected-step grouping ambiguous. */
	void abortSweep() {
		this.insideSweepAborted = true;
	}

	/** Flushes the preceding group when a newly reached cell belongs to another step. */
	void advanceStep(final int step) {
		if (this.lastStep != step) {
			this.lastStep = step;
			flushStep();
		}
	}

	/** Whether any effect is pending, in the step in progress or in a flushed step not yet applied. */
	boolean hasPendingEffects() {
		return !this.pendingInStep.isEmpty() || !this.finalServerEffects.isEmpty();
	}

	/**
	 * Refuses when the step in progress already holds an effect and a cell this traversal cannot see may have
	 * flushed it in vanilla. Every type counts: a FIRE_IGNITE or EXTINGUISH merged into a step vanilla would have
	 * flushed changes what the flush applies, exactly as a FREEZE would.
	 */
	private void assertCollectedStepUnambiguous() {
		if (!this.pendingInStep.isEmpty() && this.collectedStepAmbiguous) {
			throw new UnimplementedMechanicException(RefusalCause.UNDECLARED_BLOCK_STATE,
			    "inside-block collected-effect step grouping depends on an unclassified cell");
		}
	}

	/** {@code InsideBlockEffectApplier.StepBasedCollector.flushStep} for the modeled effects. */
	private void flushStep() {
		boolean freeze = this.pendingInStep.contains(InsideBlockEffectType.FREEZE);
		if (freeze) {
			this.freezeSteps++;
		}
		if (this.pendingInStep.contains(InsideBlockEffectType.CLEAR_FREEZE)) {
			// FREEZE precedes CLEAR_FREEZE within one vanilla collector step.
			this.clearFreezeSeen = true;
			this.freezeStepsAfterLastClear = 0;
		} else if (freeze && this.clearFreezeSeen) {
			this.freezeStepsAfterLastClear++;
		}
		this.collectedStepAmbiguous = false;
		flushServerEffectsInStep();
		this.pendingInStep.clear();
	}

	/**
	 * The server-only part of {@code StepBasedCollector.flushStep}: queues the step's ignitions, hits and
	 * extinguish for {@link #applyServerEffects}. The freeze effects, which precede these in vanilla's order,
	 * touch only the frozen count and are applied after the traversal.
	 */
	private void flushServerEffectsInStep() {
		boolean fireIgnite = this.pendingInStep.contains(InsideBlockEffectType.FIRE_IGNITE);
		boolean lavaIgnite = this.pendingInStep.contains(InsideBlockEffectType.LAVA_IGNITE);
		boolean extinguish = this.pendingInStep.contains(InsideBlockEffectType.EXTINGUISH);
		if (fireIgnite || lavaIgnite || extinguish) {
			this.finalServerEffects.add(!fireIgnite && !lavaIgnite && this.beforeExtinguish.isEmpty()
			        ? EXTINGUISH_ONLY
			        : new StepServerEffects(fireIgnite, lavaIgnite, extinguish,
			              Arrays.copyOf(this.stepFireHurts, this.stepFireHurtCount), this.stepLavaHurtCount,
			              List.copyOf(this.beforeExtinguish)));
		}
		this.beforeExtinguish.clear();
		this.stepFireHurtCount = 0;
		this.stepLavaHurtCount = 0;
	}

	/** Applies each flushed step's server-only effects in {@code InsideBlockEffectType} order. */
	private void applyServerEffects() {
		if (this.finalServerEffects.isEmpty()) {
			return;
		}
		ServerDamage damage = this.authority.damage();
		for (StepServerEffects effects : this.finalServerEffects) {
			if (effects.fireIgnite) {
				damage.fireIgnite();
			}
			for (float hurt : effects.fireHurts) {
				damage.hurtServer(HurtCause.IN_FIRE, hurt);
			}
			if (effects.lavaIgnite) {
				damage.lavaIgnite();
			}
			for (int i = 0; i < effects.lavaHurts; i++) {
				damage.hurtServer(HurtCause.LAVA, LAVA_DAMAGE);
			}
			for (Runnable before : effects.beforeExtinguish) {
				before.run();
			}
			if (effects.extinguish) {
				damage.clearFire();
			}
		}
		this.finalServerEffects.clear();
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
				state.setTicksFrozen(
				    Math.min(ServerPlayerState.DEFAULT_TICKS_REQUIRED_TO_FREEZE, state.ticksFrozen + 1));
			}
		}
	}

	/** One flushed step's server-only effects, in the order {@link #applyServerEffects} runs them. */
	private static final class StepServerEffects {
		private final boolean fireIgnite;

		private final boolean lavaIgnite;

		private final boolean extinguish;

		/** The in-fire hits in visit order; owned by this step and never shared. */
		private final float[] fireHurts;

		private final int lavaHurts;

		private final List<Runnable> beforeExtinguish;

		private StepServerEffects(final boolean fireIgnite, final boolean lavaIgnite, final boolean extinguish,
		    final float[] fireHurts, final int lavaHurts, final List<Runnable> beforeExtinguish) {
			this.fireIgnite = fireIgnite;
			this.lavaIgnite = lavaIgnite;
			this.extinguish = extinguish;
			this.fireHurts = fireHurts;
			this.lavaHurts = lavaHurts;
			this.beforeExtinguish = beforeExtinguish;
		}
	}
}
