package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.block.InsideBlockEffectCollector;
import com.nettarion.stride.simulator.block.InsideBlockTraversal;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.geometry.RetainedSpan;
import com.nettarion.stride.simulator.server.TickAuthority;
import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.world.SupportCell;
import com.nettarion.stride.simulator.world.WorldView;
import java.util.Arrays;

/**
 * Caller-owned reusable buffers, intermediate results, and query caches for one
 * stepping thread.
 *
 * <p>The workspace retains a fixed {@link TickAuthority} and an
 * {@link InsideBlockEffectCollector}; those owners decide authority and
 * collected-effect execution. {@link InsideBlockTraversal} owns its retained
 * traversal workspace. Storage here contains no damage or effect rules.
 */
public final class Scratch {
	public boolean startFallFlying;

	public final TickAuthority authority;
	public final InsideBlockEffectCollector insideEffects;
	public final InsideBlockTraversal insideTraversal = new InsideBlockTraversal();

	/** Reusable workspace for client movement. */
	public Scratch() {
		this(TickAuthority.client());
	}

	/** Reusable workspace for the given authority, fixed for its lifetime. */
	public Scratch(final TickAuthority authority) {
		this.authority = java.util.Objects.requireNonNull(authority, "authority");
		this.insideEffects = new InsideBlockEffectCollector(authority);
	}

	/** Reusable output for support queries; retained identity lives in state. */
	public final SupportCell support = new SupportCell();

	/**
	 * The world's own answer to a collision query, when the query could not be
	 * answered from the retained collision span. {@link com.nettarion.stride.simulator.geometry.CollisionCollector#collect}
	 * returns whichever buffer holds the answer; callers read the returned one.
	 */
	public final CollisionBuffer collisions = new CollisionBuffer();
	public final FluidSample fluidCell = new FluidSample();
	private float[] stepCandidates = new float[8];
	/**
	 * {@link #stepCandidates} as raw bits, so the duplicate scan compares ints.
	 * Vanilla's {@code FloatArraySet} compares {@code Float.floatToIntBits}, and
	 * the conversion in the scan itself was measurable; holding it alongside the
	 * value keeps the same comparison without recomputing it per candidate.
	 * Valid only between {@link #clearStepCandidates} and
	 * {@link #sortStepCandidates}, which is the only window the scan runs in.
	 */
	private int[] stepCandidateBits = new int[8];
	private int stepCandidateCount;
	public double movedX;
	public double movedY;
	public double movedZ;
	/**
	 * {@code Entity.wasEyeInWater}: the eye latch as {@code baseTick} samples it
	 * before refreshing the fluid tracker, so the previous tick's answer.
	 */
	boolean wasUnderWaterAtTickStart;
	/**
	 * {@code Entity.oldPosition()}: the position {@code ClientLevel.tickNonPassenger}
	 * saved before the tick, which the block-effect traversal moves from when
	 * {@code move()} recorded no segment.
	 */
	public double oldX;
	public double oldY;
	public double oldZ;

	/*
	 * The one Entity.Movement this tick's move() recorded. Entity.checkInsideBlocks
	 * splits it per axis in the order of the *requested* delta, so the resolved
	 * endpoints alone are not enough to reproduce the traversal.
	 */

	public boolean hasMovementSegment;
	public double segmentFromX;
	public double segmentFromY;
	public double segmentFromZ;
	public double segmentRequestedX;
	public double segmentRequestedZ;

	/** Hit point of the last successful {@code AABB.clip} against a unit cell. */
	public double clipHitX;
	public double clipHitY;
	public double clipHitZ;

	/** The retained collision span this workspace answers queries from. See {@link RetainedSpan}. */
	public final RetainedSpan span = new RetainedSpan();

	/**
	 * Carry this workspace's retained span across a publication, so it
	 * answers again in a view that kept the sections it read; see
	 * {@link RetainedSpan#carry}. A consumer that steps one workspace through
	 * successive corridors calls this with the view it is leaving.
	 */
	public void carry(final WorldView world) {
		this.span.carry(world);
	}

	public void clearStepCandidates() {
		this.stepCandidateCount = 0;
	}

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
