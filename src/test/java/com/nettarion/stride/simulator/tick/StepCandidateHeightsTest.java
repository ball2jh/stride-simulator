package com.nettarion.stride.simulator.tick;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.geometry.ShapeCollision;

import org.junit.jupiter.api.Test;

/**
 * Raw-bit probes of the step-candidate heights Stride collects before a step trial.
 *
 * <p>Each rule here is read off {@code Entity.collectCandidateStepUpHeights}:
 * every collider Y coordinate becomes {@code (float) (coord - groundedBox.minY)},
 * one double subtraction narrowed once; the height equal to the ordinary
 * resolved Y is skipped by exact float equality; negative heights and heights
 * above {@code maxUpStep} are dropped, with the bound itself kept; survivors are
 * raw-bit deduplicated and sorted ascending.
 */
final class StepCandidateHeightsTest {
	private static final float MAX_UP_STEP = 0.6F;
	private static final float NO_SKIP = -1.0F;

	@Test
	void eachHeightIsSubtractedInDoubleAndNarrowedOnce() {
		// 64.1 is not a float, so narrowing the operands first lands on a
		// different float from narrowing the double difference.
		double groundMinY = 64.1;
		double colliderY = groundMinY + 0.3;
		float once = (float) (colliderY - groundMinY);
		float twice = (float) colliderY - (float) groundMinY;
		assertNotEquals(Float.floatToRawIntBits(once), Float.floatToRawIntBits(twice),
		    "the fixture must separate the two narrowing orders or it checks nothing");

		float[] candidates = collect(groundMinY, NO_SKIP, box(colliderY, colliderY + 0.5));

		assertEquals(1, candidates.length, "the top of the box lies above maxUpStep");
		assertEquals(Float.floatToRawIntBits(once), Float.floatToRawIntBits(candidates[0]));
	}

	@Test
	void heightsWithEqualRawBitsCollapseToOneCandidate() {
		float[] candidates = collect(0.0, NO_SKIP, box(0.0, 0.5), box(0.5, 0.5 + 0.05), box(0.25, 0.5));

		assertArrayEquals(new float[] {0.0F, 0.25F, 0.5F, 0.55F}, candidates);
	}

	@Test
	void theOrdinaryHeightIsSkippedByExactFloatEqualityAfterNarrowing() {
		float skip = 0.25F;
		double narrowsToSkip = 0.25 + 1.0E-9;
		assertEquals(Float.floatToRawIntBits(skip), Float.floatToRawIntBits((float) (narrowsToSkip - 0.0)),
		    "the fixture height must narrow onto the skipped float");
		double oneFloatAbove = Math.nextUp(skip);

		float[] candidates = collect(0.0, skip, box(-1.0, 0.25), box(-1.0, narrowsToSkip), box(-1.0, oneFloatAbove));

		assertArrayEquals(new float[] {Math.nextUp(skip)}, candidates,
		    "both heights that narrow to the ordinary float are skipped;"
		        + " the next float up survives");
	}

	@Test
	void candidatesAreBoundedByMaxUpStepInclusiveAndNeverNegative() {
		float[] candidates = collect(
		    0.0, NO_SKIP, box(-1.0, -1.0E-9), box(-1.0, 0.0), box(-1.0, 0.6), box(-1.0, Math.nextUp(MAX_UP_STEP)));

		assertArrayEquals(new float[] {0.0F, MAX_UP_STEP}, candidates,
		    "a tiny negative height is dropped, zero is kept, the bound is kept,"
		        + " one float above the bound is dropped");
	}

	@Test
	void candidatesAreSortedAscendingRegardlessOfColliderOrder() {
		float[] candidates = collect(0.0, NO_SKIP, box(-1.0, 0.5), box(-1.0, 0.125), box(-1.0, 0.375));

		assertArrayEquals(new float[] {0.125F, 0.375F, 0.5F}, candidates);
	}

	private static float[] collect(final double groundMinY, final float skip, final double[]... boxes) {
		CollisionBuffer shapes = new CollisionBuffer();
		for (double[] box : boxes) {
			shapes.add(0.0, box[0], 0.0, 1.0, box[1], 1.0);
		}
		Scratch scratch = new Scratch();
		ShapeCollision.collectCandidateStepUpHeights(groundMinY, shapes, MAX_UP_STEP, skip, scratch);
		float[] candidates = new float[scratch.stepCandidateCount()];
		for (int i = 0; i < candidates.length; i++) {
			candidates[i] = scratch.stepCandidate(i);
		}
		return candidates;
	}

	private static double[] box(final double minY, final double maxY) {
		return new double[] {minY, maxY};
	}
}
