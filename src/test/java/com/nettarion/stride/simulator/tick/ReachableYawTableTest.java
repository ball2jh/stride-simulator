package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.geometry.Mth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Exhaustive source-expression evidence for finite float yaw/input support. */
final class ReachableYawTableTest {
	private static final int TABLE_SIZE = 65_536;
	private static final int TABLE_MASK = TABLE_SIZE - 1;
	private static final int COS_OFFSET = TABLE_SIZE / 4;
	private static final double SIN_SCALE = 10430.378350470453;
	private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

	private static final float ORDINARY_CARDINAL = Float.intBitsToFloat(0x3f7a_e148);
	private static final float ORDINARY_DIAGONAL = Float.intBitsToFloat(0x3f35_04f2);
	private static final float DEFAULT_SPRINT_GROUND_SPEED = Float.intBitsToFloat(0x3e05_1eb9);
	private static final float LAST_CARDINAL_SPRINT_JUMP_SPEED = Float.intBitsToFloat(0x4030_6abd);
	private static final float FIRST_DIAGONAL_SPRINT_JUMP_SPEED = Float.intBitsToFloat(0x4030_6abe);

	private static final Input[] INPUTS = {new Input("forward", Kind.CARDINAL, 0.0F, ORDINARY_CARDINAL),
	    new Input("backward", Kind.CARDINAL, 0.0F, -ORDINARY_CARDINAL),
	    new Input("left", Kind.CARDINAL, ORDINARY_CARDINAL, 0.0F),
	    new Input("right", Kind.CARDINAL, -ORDINARY_CARDINAL, 0.0F),
	    new Input("forward-left", Kind.DIAGONAL, ORDINARY_DIAGONAL, ORDINARY_DIAGONAL),
	    new Input("forward-right", Kind.DIAGONAL, -ORDINARY_DIAGONAL, ORDINARY_DIAGONAL),
	    new Input("backward-left", Kind.DIAGONAL, ORDINARY_DIAGONAL, -ORDINARY_DIAGONAL),
	    new Input("backward-right", Kind.DIAGONAL, -ORDINARY_DIAGONAL, -ORDINARY_DIAGONAL),
	    new Input("idle", Kind.IDLE, 0.0F, 0.0F)};

	@Test
	void everyTableBucketHasFiniteDegreePreimagesInBothQuarterOffsetWrapFamilies() {
		for (int index = 0; index < TABLE_SIZE; index++) {
			// q in [index,index+1) proves the base positive preimage. Adding one
			// table period proves positive masking rather than merely the first turn.
			assertYawPair(yawPreimage(index + 0.5), index, index + COS_OFFSET);
			assertYawPair(yawPreimage(index + TABLE_SIZE + 0.5), index, index + COS_OFFSET);

			// A negative q whose truncated long is congruent to index, with both q
			// and q+16384 negative, proves the ordinary negative wrap family.
			long negativeTruncation = (long) index - 2L * TABLE_SIZE;
			assertYawPair(yawPreimage(negativeTruncation - 0.5), index, index + COS_OFFSET);
		}
	}

	@Test
	void everyNegativeStraddlingBucketHasAFiniteDegreePreimage() {
		// For -16384 < q < 0, Java truncates q and q+16384 on opposite sides
		// of zero. The cosine index is therefore +16383, not +16384. These are
		// all 16,384 possible truncations in that interval, not sampled angles.
		for (int magnitude = 0; magnitude < COS_OFFSET; magnitude++) {
			int sinIndex = -magnitude & TABLE_MASK;
			assertYawPair(yawPreimage(-magnitude - 0.5), sinIndex, sinIndex + COS_OFFSET - 1);
		}
	}

	@Test
	void cosineAdditionCannotRoundAcrossAnExtraSmallMagnitudeBucket() {
		// Every action-produced radian is a float. For each integer boundary in
		// |q| <= 16384, the rounded inverse and its adjacent floats bracket the
		// closest possible float-radian products. Testing those preimages proves
		// that q+16384 cannot round across an additional integer. Outside this
		// interval, 16384 is an exact multiple of every binary64 ULP below long
		// saturation, so the addition itself is exact.
		for (int boundary = 1; boundary <= COS_OFFSET; boundary++) {
			float nearest = (float) (boundary / SIN_SCALE);
			assertCosOffsetFamily(Math.nextDown(nearest));
			assertCosOffsetFamily(nearest);
			assertCosOffsetFamily(Math.nextUp(nearest));
			assertCosOffsetFamily(-Math.nextDown(nearest));
			assertCosOffsetFamily(-nearest);
			assertCosOffsetFamily(-Math.nextUp(nearest));
		}
	}

	@Test
	void adjacentFiniteYawBoundaryExcludesMixedSaturationAndCanonicalizesSignedZero() {
		float firstSaturatedYaw = Float.intBitsToFloat(0x5b34_0000);
		float priorYaw = Math.nextDown(firstSaturatedYaw);
		double priorScaled = scaled(priorYaw);
		double saturated = scaled(firstSaturatedYaw);

		// Positive float multiplication is monotone. The adjacent predecessor is
		// still below the cast boundary even after the cosine offset, while the
		// next float saturates both casts. Hence no admitted positive float yaw can
		// select a one-saturated/one-unsaturated pair.
		assertTrue(priorScaled + COS_OFFSET < (double) Long.MAX_VALUE);
		assertTrue(saturated >= (double) Long.MAX_VALUE);
		assertEquals(Long.MAX_VALUE, (long) saturated);
		assertEquals(Long.MAX_VALUE, (long) (saturated + COS_OFFSET));
		assertYawPair(firstSaturatedYaw, TABLE_MASK, TABLE_MASK);

		// Negation is exact for finite floats. At the negative boundary both casts
		// saturate to Long.MIN_VALUE; its adjacent smaller-magnitude float leaves
		// both casts nonsaturated.
		assertTrue(-priorScaled > (double) Long.MIN_VALUE);
		assertEquals(Long.MIN_VALUE, (long) -saturated);
		assertEquals(Long.MIN_VALUE, (long) (-saturated + COS_OFFSET));
		assertYawPair(-firstSaturatedYaw, 0, 0);

		PlayerInput action = PlayerInput.idle(-0.0F, 0.0F);
		assertEquals(Float.floatToRawIntBits(0.0F), Float.floatToRawIntBits(action.yRot()));
		assertYawPair(action.yRot(), 0, COS_OFFSET);
		assertEquals(0, Mth.sinIndex(-0.0));
		assertEquals(COS_OFFSET, Mth.cosIndex(-0.0));
	}

	@Test
	void exhaustiveReachablePairSetOwnsTheTrigNormMaximum() {
		NormMaximum maximum = new NormMaximum();
		NormMaximum quarter = new NormMaximum();
		NormMaximum negative = new NormMaximum();
		for (int index = 0; index < TABLE_SIZE; index++) {
			quarter.consider(index, index + COS_OFFSET & TABLE_MASK);
		}
		for (int magnitude = 0; magnitude < COS_OFFSET; magnitude++) {
			int index = -magnitude & TABLE_MASK;
			negative.consider(index, index + COS_OFFSET - 1 & TABLE_MASK);
		}
		int pairCount = visitEveryReachablePair(maximum::consider);

		assertEquals(TABLE_SIZE + COS_OFFSET + 2, pairCount);
		assertEquals(0x1.000000ac9f324p0, quarter.norm);
		assertEquals(8, quarter.equalMaxima);
		assertEquals(0x1.00000013bd3cep0, negative.norm);
		assertEquals(2, negative.equalMaxima);
		assertEquals(0x1.1c58323296917p-13, Math.hypot(tableValue(TABLE_MASK), tableValue(TABLE_MASK)));
		assertEquals(0.0, Math.hypot(tableValue(0), tableValue(0)));
		assertEquals(0x1.000000ac9f324p0, maximum.norm);
		assertEquals(8, maximum.equalMaxima);
		assertEquals(8023, maximum.sinIndex);
		assertEquals(24407, maximum.cosIndex);

		float witnessYaw = yawPreimage(maximum.sinIndex + 0.5);
		assertYawPair(witnessYaw, maximum.sinIndex, maximum.cosIndex);
		assertEquals(0x1.64201cp-1F, Mth.sin(witnessYaw * DEG_TO_RAD));
		assertEquals(0x1.6fdb5ep-1F, Mth.cos(witnessYaw * DEG_TO_RAD));
	}

	@Test
	void exactFiniteInputAndYawEnumerationOwnsDefaultImpulseMaxima() {
		SupportMaximum ordinary = maximumImpulse(DEFAULT_SPRINT_GROUND_SPEED, false);
		assertEquals(0x1.0a3d70ee8f9fep-3, ordinary.norm);
		assertEquals(Kind.DIAGONAL, ordinary.input.kind);
		assertEquals(8023, ordinary.sinIndex);
		assertEquals(24407, ordinary.cosIndex);

		SupportMaximum sprintJump = maximumImpulse(DEFAULT_SPRINT_GROUND_SPEED, true);
		assertEquals(0x1.4f41f3ca1a9fcp-2, sprintJump.norm);
		assertEquals("forward", sprintJump.input.name);
		assertEquals(8023, sprintJump.sinIndex);
		assertEquals(24407, sprintJump.cosIndex);

		assertEquals(0x1.04ea4ce669bbfp-3, maximumImpulse(DEFAULT_SPRINT_GROUND_SPEED, false, input("forward")).norm);
		assertEquals(
		    0x1.0a3d70ee8f9fep-3, maximumImpulse(DEFAULT_SPRINT_GROUND_SPEED, false, input("forward-left")).norm);
		assertEquals(0x1.4f41f3ca1a9fcp-2, maximumImpulse(DEFAULT_SPRINT_GROUND_SPEED, true, input("forward")).norm);
		assertEquals(
		    0x1.39666fe950202p-2, maximumImpulse(DEFAULT_SPRINT_GROUND_SPEED, true, input("forward-left")).norm);
		assertEquals(0x1.e5a48eda3e9d1p-3, maximumImpulse(DEFAULT_SPRINT_GROUND_SPEED, true, input("left")).norm);
		assertEquals(0x1.99999aadcb83ap-3, maximumImpulse(DEFAULT_SPRINT_GROUND_SPEED, true, input("idle")).norm);
		assertEquals(
		    0x1.2292eeaecbe18p-3, maximumImpulse(DEFAULT_SPRINT_GROUND_SPEED, true, input("backward-left")).norm);
		assertEquals(0x1.295e9b8ec38f6p-4, maximumImpulse(DEFAULT_SPRINT_GROUND_SPEED, true, input("backward")).norm);
	}

	@Test
	void diagonalOvertakesSprintJumpCardinalAtTheAdjacentEffectiveSpeedFloats() {
		assertEquals(LAST_CARDINAL_SPRINT_JUMP_SPEED, Math.nextDown(FIRST_DIAGONAL_SPRINT_JUMP_SPEED));

		SupportMaximum below = maximumImpulse(LAST_CARDINAL_SPRINT_JUMP_SPEED, true);
		SupportMaximum above = maximumImpulse(FIRST_DIAGONAL_SPRINT_JUMP_SPEED, true);
		assertEquals(Kind.CARDINAL, below.input.kind);
		assertEquals("forward", below.input.name);
		assertEquals(0x1.7360929449bd5p1, below.norm);
		assertEquals(Kind.DIAGONAL, above.input.kind);
		assertTrue(above.input.name.startsWith("forward-"));
		assertEquals(0x1.736094934fe2cp1, above.norm);

		double cardinal = ORDINARY_CARDINAL;
		double diagonal = ORDINARY_DIAGONAL;
		double realCrossover = 0.4 * (cardinal - diagonal) / (2.0 * diagonal * diagonal - cardinal * cardinal);
		assertEquals(0x1.60d57a1387a45p1, realCrossover);
		assertTrue(LAST_CARDINAL_SPRINT_JUMP_SPEED < realCrossover);
		assertTrue(FIRST_DIAGONAL_SPRINT_JUMP_SPEED > realCrossover);

		SupportMaximum attributeMaximum = maximumImpulse(1024.0F, true);
		assertEquals(Kind.DIAGONAL, attributeMaximum.input.kind);
		assertEquals(0x1.00090c2e375ep10, attributeMaximum.norm);
		assertEquals(8023, attributeMaximum.sinIndex);
		assertEquals(24407, attributeMaximum.cosIndex);
	}

	private static float yawPreimage(final double targetScaledRadians) {
		float yaw = (float) (targetScaledRadians / (SIN_SCALE * (double) DEG_TO_RAD));
		assertTrue(Float.isFinite(yaw));
		return yaw;
	}

	private static double scaled(final float yaw) {
		float radians = yaw * DEG_TO_RAD;
		return (double) radians * SIN_SCALE;
	}

	private static void assertCosOffsetFamily(final float radians) {
		double q = (double) radians * SIN_SCALE;
		if (q >= 0.0 && q <= COS_OFFSET) {
			assertEquals((long) q + COS_OFFSET, (long) (q + COS_OFFSET));
		} else if (q > -COS_OFFSET && q < 0.0) {
			long expectedOffset = q == (long) q ? COS_OFFSET : COS_OFFSET - 1L;
			assertEquals((long) q + expectedOffset, (long) (q + COS_OFFSET));
		}
	}

	private static void assertYawPair(final float yaw, final int expectedSin, final int expectedCos) {
		assertTrue(Float.isFinite(yaw));
		float radians = yaw * DEG_TO_RAD;
		assertEquals(expectedSin & TABLE_MASK, Mth.sinIndex(radians), () -> "sin preimage failed for yaw " + yaw);
		assertEquals(expectedCos & TABLE_MASK, Mth.cosIndex(radians), () -> "cos preimage failed for yaw " + yaw);
	}

	private static int visitEveryReachablePair(final PairVisitor visitor) {
		int count = 0;
		for (int index = 0; index < TABLE_SIZE; index++) {
			visitor.visit(index, index + COS_OFFSET & TABLE_MASK);
			count++;
		}
		for (int magnitude = 0; magnitude < COS_OFFSET; magnitude++) {
			int index = -magnitude & TABLE_MASK;
			visitor.visit(index, index + COS_OFFSET - 1 & TABLE_MASK);
			count++;
		}
		visitor.visit(0, 0);
		visitor.visit(TABLE_MASK, TABLE_MASK);
		return count + 2;
	}

	private static SupportMaximum maximumImpulse(final float speed, final boolean sprintJump) {
		return maximumImpulse(speed, sprintJump, null);
	}

	private static SupportMaximum maximumImpulse(
	    final float speed, final boolean sprintJump, final Input selectedInput) {
		SupportMaximum[] maximum = {SupportMaximum.NONE};
		visitEveryReachablePair((sinIndex, cosIndex) -> {
			float sin = tableValue(sinIndex);
			float cos = tableValue(cosIndex);
			for (Input input : INPUTS) {
				if (selectedInput != null && input != selectedInput) {
					continue;
				}
				double movementX = (double) input.x * speed;
				double movementZ = (double) input.z * speed;
				double impulseX = sprintJump ? -sin * 0.2 : 0.0;
				double impulseZ = sprintJump ? cos * 0.2 : 0.0;
				impulseX += movementX * cos - movementZ * sin;
				impulseZ += movementZ * cos + movementX * sin;
				double norm = Math.hypot(impulseX, impulseZ);
				if (norm > maximum[0].norm) {
					maximum[0] = new SupportMaximum(norm, sinIndex, cosIndex, input);
				}
			}
		});
		return maximum[0];
	}

	private static Input input(final String name) {
		for (Input input : INPUTS) {
			if (input.name.equals(name)) {
				return input;
			}
		}
		throw new AssertionError("unknown input " + name);
	}

	private static float tableValue(final int index) {
		return (float) Math.sin(index / SIN_SCALE);
	}

	@FunctionalInterface
	private interface PairVisitor {
		void visit(int sinIndex, int cosIndex);
	}

	private enum Kind { CARDINAL, DIAGONAL, IDLE }

	private record Input(String name, Kind kind, float x, float z) {}

	private record SupportMaximum(double norm, int sinIndex, int cosIndex, Input input) {
		private static final SupportMaximum NONE = new SupportMaximum(-1.0, -1, -1, null);
	}

	private static final class NormMaximum {
		private double norm = -1.0;
		private int sinIndex;
		private int cosIndex;
		private int equalMaxima;

		private void consider(final int candidateSin, final int candidateCos) {
			double candidate = Math.hypot(tableValue(candidateSin), tableValue(candidateCos));
			if (candidate > this.norm) {
				this.norm = candidate;
				this.sinIndex = candidateSin;
				this.cosIndex = candidateCos;
				this.equalMaxima = 1;
			} else if (Double.doubleToRawLongBits(candidate) == Double.doubleToRawLongBits(this.norm)) {
				this.equalMaxima++;
			}
		}
	}
}
