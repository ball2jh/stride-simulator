package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.geometry.CollisionQuerySpan;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.tick.Move;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.FlatFloorView;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Executable counterexamples for the finite-only coordinate precondition. */
final class CoordinateDomainTest {
	@Test
	void rawCollapsedBoxAndNegativeFloorWitnessesAreRejectedAtAdmission() {
		PlayerState atTwoToThe53 = new PlayerState();
		atTwoToThe53.placeAt(0x1.0p53, 64.0, 0.5);

		assertThrows(UnimplementedMechanicException.class, atTwoToThe53::requireValidForTransition);
		assertEquals(0L, Double.doubleToRawLongBits(atTwoToThe53.boundingBoxMaxX - atTwoToThe53.boundingBoxMinX));

		double belowIntMinimum = Math.nextDown((double) Integer.MIN_VALUE);
		PlayerState belowIntegerCursor = new PlayerState();
		belowIntegerCursor.placeAt(belowIntMinimum, 64.0, 0.5);

		assertThrows(UnimplementedMechanicException.class, belowIntegerCursor::requireValidForTransition);
		assertEquals(0xc1e0000000000001L, Double.doubleToRawLongBits(belowIntMinimum));
		assertEquals(
		    Integer.MIN_VALUE, (int) Math.floor(belowIntMinimum), "vanilla Mth.floor casts Math.floor's result");
		assertEquals(Integer.MAX_VALUE, Mth.floor(belowIntMinimum),
		    "Stride subtracts after a saturating cast and wraps INT_MIN");
	}

	@Test
	void otherFiniteExtremeWitnessIsRejectedAtAdmission() {
		PlayerState state = new PlayerState();
		state.placeAt(Double.MAX_VALUE, 64.0, 0.5);

		assertThrows(UnimplementedMechanicException.class, state::requireValidForTransition);
		assertEquals(0L, Double.doubleToRawLongBits(state.boundingBoxMaxX - state.boundingBoxMinX));
	}

	@Test
	void collisionCursorCardinalityHasAnExactAdjacentCubeBoundary() {
		assertDoesNotThrow(() -> CollisionQuerySpan.requireSupported(0.0, 0.0, 0.0, 1_286.0, 1_286.0, 1_286.0));
		assertThrows(UnimplementedMechanicException.class,
		    () -> CollisionQuerySpan.requireSupported(0.0, 0.0, 0.0, 1_287.0, 1_287.0, 1_287.0));
	}

	@Test
	void packetEdgeBroadphaseHasAdjacentRawIntegerWrapThresholds() {
		PlayerState negative = new PlayerState();
		negative.placeAt(-30_000_000.0, 64.0, 0.5);
		double negativeExact = Double.longBitsToDouble(0xc1df8d8f1feccccdL);
		double negativeOutside = Math.nextDown(negativeExact);

		double exactNegativeQuery = negative.boundingBoxMinX + negativeExact - 1.0E-7;
		double outsideNegativeQuery = negative.boundingBoxMinX + negativeOutside - 1.0E-7;
		assertEquals(0xc1e0000000000000L, Double.doubleToRawLongBits(exactNegativeQuery));
		assertEquals(Integer.MIN_VALUE, Mth.floor(exactNegativeQuery));
		assertEquals(0xc1e0000000000001L, Double.doubleToRawLongBits(outsideNegativeQuery));
		assertEquals(Integer.MAX_VALUE, Mth.floor(outsideNegativeQuery));

		PlayerState positive = new PlayerState();
		positive.placeAt(30_000_000.0, 64.0, 0.5);
		double positiveExact = Double.longBitsToDouble(0x41df8d8f1faccccdL);
		double positiveInside = Math.nextDown(positiveExact);

		double exactPositiveQuery = positive.boundingBoxMaxX + positiveExact + 1.0E-7;
		double insidePositiveQuery = positive.boundingBoxMaxX + positiveInside + 1.0E-7;
		assertEquals(0x41dfffffffc00000L, Double.doubleToRawLongBits(exactPositiveQuery));
		assertEquals(Integer.MIN_VALUE, Mth.floor(exactPositiveQuery) + 1,
		    "the vanilla/Stride collision cursor outer ring wraps at INT_MAX");
		assertEquals(0x41dfffffffbfffffL, Double.doubleToRawLongBits(insidePositiveQuery));
		assertEquals(Integer.MAX_VALUE, Mth.floor(insidePositiveQuery) + 1);

		UnimplementedMechanicException negativeRefusal = assertThrows(UnimplementedMechanicException.class,
		    ()
		        -> Move.resolve(
		            negative, negativeExact, 0.0, 0.0, false, FlatFloorView.ordinary(0, -64), new Scratch()));
		UnimplementedMechanicException positiveRefusal = assertThrows(UnimplementedMechanicException.class,
		    ()
		        -> Move.resolve(
		            positive, positiveExact, 0.0, 0.0, false, FlatFloorView.ordinary(0, -64), new Scratch()));
		assertEquals("collision query exceeds the exact integer-cursor domain", negativeRefusal.getMessage());
		assertEquals(negativeRefusal.getMessage(), positiveRefusal.getMessage());
	}

	@Test
	void broadphaseRefusesFiniteDisplacementBeforeItCanCreateAnEscapedSuccessor() {
		PlayerState state = new PlayerState();
		state.placeAt(30_000_000.0, 64.0, 0.5);

		assertThrows(UnimplementedMechanicException.class,
		    ()
		        -> Move.resolve(
		            state, Double.MAX_VALUE, 0.0, 0.0, false, FlatFloorView.ordinary(0, -64), new Scratch()));

		assertEquals(Double.doubleToRawLongBits(30_000_000.0), Double.doubleToRawLongBits(state.x));
		assertDoesNotThrow(state::requireValidForTransition);
	}

	@Test
	void retainedSuccessorRefusesTheFirstWholeBlockOutsideTheCenterSlice() {
		PlayerState state = new PlayerState();
		state.placeAt(30_000_000.0, 64.0, 0.5);

		UnimplementedMechanicException refusal = assertThrows(UnimplementedMechanicException.class,
		    () -> Move.resolve(state, 1.0, 0.0, 0.0, false, FlatFloorView.ordinary(0, -64), new Scratch()));

		assertEquals(Double.doubleToRawLongBits(30_000_000.0), Double.doubleToRawLongBits(state.x));
		assertTrue(refusal.getMessage().startsWith("movement center lies outside the exact kernel coordinate domain"));
	}
}
