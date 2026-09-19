package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.block.InsideBlockEffectCollector;
import com.nettarion.stride.simulator.block.InsideBlockTraversal;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.geometry.RetainedSpan;
import com.nettarion.stride.simulator.server.TickAuthority;
import com.nettarion.stride.simulator.world.SupportCell;
import com.nettarion.stride.simulator.world.WorldView;
import java.util.Arrays;
import java.util.Objects;

/**
 * Caller-owned reusable buffers, intermediate results, and query caches for one stepping thread.
 *
 * <p>Every field is implementation state owned by the tick: the phases, the block effects, the
 * server listener and the geometry package write and read them between the start and the end of
 * one tick or transaction, and nothing here is meaningful to a consumer between ticks. A scratch is
 * not thread-safe; keep one per thread. Its caches ({@link #span}) are not semantic state and
 * invalidate with the world and state they read.
 *
 * <p>The scratch retains a fixed {@link TickAuthority} and an {@link InsideBlockEffectCollector};
 * those owners decide authority and collected-effect execution. {@link InsideBlockTraversal} owns
 * its retained traversal workspace. Storage here contains no damage or effect rules.
 */
public final class Scratch {
	/** The authority this scratch ticks under, fixed for its lifetime. */
	public final TickAuthority authority;

	/** The block bodies collected during this tick's block-effect traversal. */
	public final InsideBlockEffectCollector insideEffects;

	/** The reusable cell walk the block-effect traversal runs in. */
	public final InsideBlockTraversal insideTraversal = new InsideBlockTraversal();

	/** Reusable output for support queries; the retained support identity lives in the state. */
	public final SupportCell support = new SupportCell();

	/**
	 * The world's own answer to a collision query, when the query could not be answered from the
	 * retained collision span. {@code CollisionCollector.collect} returns whichever buffer holds the
	 * answer; callers read the returned one.
	 */
	public final CollisionBuffer collisions = new CollisionBuffer();

	/** Reusable output for one fluid cell sample. */
	public final FluidSample fluidCell = new FluidSample();

	/** The retained collision span this scratch answers queries from. See {@link RetainedSpan}. */
	public final RetainedSpan span = new RetainedSpan();

	/** Set by {@link AiStep} when this tick's jump edge started gliding; the root package reads it. */
	public boolean startFallFlying;

	/** {@code Entity.collide}'s resolved X displacement, in blocks; written by {@code ShapeCollision}. */
	public double movedX;

	/** {@code Entity.collide}'s resolved Y displacement, in blocks; written by {@code ShapeCollision}. */
	public double movedY;

	/** {@code Entity.collide}'s resolved Z displacement, in blocks; written by {@code ShapeCollision}. */
	public double movedZ;

	/**
	 * {@code Entity.oldPosition()} X: the position {@code ClientLevel.tickNonPassenger} saved before
	 * the tick, which the block-effect traversal moves from when {@code move()} recorded no segment.
	 */
	public double oldX;

	/** {@code Entity.oldPosition()} Y; see {@link #oldX}. */
	public double oldY;

	/** {@code Entity.oldPosition()} Z; see {@link #oldX}. */
	public double oldZ;

	/*
	 * The one Entity.Movement this tick's move() recorded. Entity.checkInsideBlocks
	 * splits it per axis in the order of the requested delta, so the resolved
	 * endpoints alone are not enough to reproduce the traversal.
	 */

	/** Whether this tick's {@code move()} recorded a movement segment. */
	public boolean hasMovementSegment;

	/** The recorded segment's start X, in blocks. */
	public double segmentFromX;

	/** The recorded segment's start Y, in blocks. */
	public double segmentFromY;

	/** The recorded segment's start Z, in blocks. */
	public double segmentFromZ;

	/** The X the segment's mover requested before collision, which orders the per-axis traversal. */
	public double segmentRequestedX;

	/** The Z the segment's mover requested before collision, which orders the per-axis traversal. */
	public double segmentRequestedZ;

	/**
	 * {@code Entity.wasEyeInWater}: the eye latch as {@code baseTick} samples it before refreshing
	 * the fluid tracker, so the previous tick's answer.
	 */
	boolean wasUnderWaterAtTickStart;

	private float[] stepCandidates = new float[8];

	/**
	 * {@link #stepCandidates} as raw bits, so the duplicate scan compares ints. Vanilla's
	 * {@code FloatArraySet} compares {@code Float.floatToIntBits}, and the conversion in the scan
	 * itself was measurable; holding it alongside the value keeps the same comparison without
	 * recomputing it per candidate. Valid only between {@link #clearStepCandidates} and
	 * {@link #sortStepCandidates}, which is the only window the scan runs in.
	 */
	private int[] stepCandidateBits = new int[8];

	private int stepCandidateCount;

	/** A scratch for client movement. */
	public Scratch() {
		this(TickAuthority.client());
	}

	/** A scratch for the given authority, fixed for its lifetime. */
	public Scratch(final TickAuthority authority) {
		this.authority = Objects.requireNonNull(authority, "authority");
		this.insideEffects = new InsideBlockEffectCollector(authority);
	}

	/**
	 * Carries this scratch's retained span across a publication, so it answers again in a view that
	 * kept the sections it read; see {@link RetainedSpan#carry}. A consumer that steps one scratch
	 * through successive views calls this with the view it is leaving.
	 */
	public void carry(final WorldView world) {
		this.span.carry(world);
	}

	/** Empties the step-candidate set before {@code Entity.collectCandidateStepUpHeights}. */
	public void clearStepCandidates() {
		this.stepCandidateCount = 0;
	}

	/** Adds a candidate step height, in blocks, unless one with the same float bits is present. */
	public void addStepCandidate(final float candidate) {
		int bits = Float.floatToIntBits(candidate);
		for (int i = 0; i < this.stepCandidateCount; i++) {
			if (this.stepCandidateBits[i] == bits) {
				return;
			}
		}
		if (this.stepCandidateCount == this.stepCandidates.length) {
			this.stepCandidates = Arrays.copyOf(this.stepCandidates, this.stepCandidates.length * 2);
			this.stepCandidateBits = Arrays.copyOf(this.stepCandidateBits, this.stepCandidateBits.length * 2);
		}
		this.stepCandidateBits[this.stepCandidateCount] = bits;
		this.stepCandidates[this.stepCandidateCount++] = candidate;
	}

	/** Sorts the candidates ascending, as vanilla's {@code FloatArraySet} is sorted before use. */
	public void sortStepCandidates() {
		Arrays.sort(this.stepCandidates, 0, this.stepCandidateCount);
	}

	int stepCandidateCount() {
		return this.stepCandidateCount;
	}

	float stepCandidate(final int index) {
		return this.stepCandidates[index];
	}
}
