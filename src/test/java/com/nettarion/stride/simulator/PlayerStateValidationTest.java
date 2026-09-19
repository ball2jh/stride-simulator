package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nettarion.stride.simulator.world.FlatFloorView;
import org.junit.jupiter.api.Test;

/** {@code requireValidForTransition} admits exactly the finite, consistent states inside the coordinate domain. */
final class PlayerStateValidationTest {
	@Test
	void acceptsAPlacedFiniteState() {
		PlayerState state = new PlayerState();
		state.placeAt(1.25, 64.0, -3.5);

		assertDoesNotThrow(state::requireValidForTransition);
	}

	@Test
	void packetEnvelopeEndpointPreservesTheFloatDerivedStandingWidth() {
		PlayerState state = new PlayerState();
		state.placeAt(30_000_000.0, 20_000_000.0, -30_000_000.0);

		assertDoesNotThrow(state::requireValidForTransition);
		assertEquals(0x417c9c37fb333330L, Double.doubleToRawLongBits(state.boundingBoxMinX));
		assertEquals(0x417c9c3804ccccd0L, Double.doubleToRawLongBits(state.boundingBoxMaxX));
		assertEquals(0x3fe3333340000000L, Double.doubleToRawLongBits(state.boundingBoxMaxX - state.boundingBoxMinX));
		assertEquals(0x3fd3333340000000L, Double.doubleToRawLongBits((double) state.pose.width / 2.0F));
	}

	@Test
	void admitsEveryPacketCenterEndpointAndRejectsTheAdjacentDoubles() {
		double[][] admitted = {{-30_000_000.0, 0.0, 0.0}, {30_000_000.0, 0.0, 0.0}, {0.0, -20_000_000.0, 0.0},
		    {0.0, 20_000_000.0, 0.0}, {0.0, 0.0, -30_000_000.0}, {0.0, 0.0, 30_000_000.0}};
		for (double[] center : admitted) {
			PlayerState state = new PlayerState();
			state.placeAt(center[0], center[1], center[2]);
			assertDoesNotThrow(state::requireValidForTransition);
		}

		double[][] rejected = {{Math.nextDown(-30_000_000.0), 0.0, 0.0}, {Math.nextUp(30_000_000.0), 0.0, 0.0},
		    {0.0, Math.nextDown(-20_000_000.0), 0.0}, {0.0, Math.nextUp(20_000_000.0), 0.0},
		    {0.0, 0.0, Math.nextDown(-30_000_000.0)}, {0.0, 0.0, Math.nextUp(30_000_000.0)}};
		for (double[] center : rejected) {
			PlayerState state = new PlayerState();
			state.placeAt(center[0], center[1], center[2]);
			assertThrows(UnimplementedMechanicException.class, state::requireValidForTransition);
		}
	}

	@Test
	void rejectsPositionBoxAndFiniteValueDisagreement() {
		PlayerState state = new PlayerState();
		state.placeAt(0.5, 1.0, 0.5);
		state.boundingBoxMaxX = Math.nextUp(state.boundingBoxMaxX);

		IllegalStateException mismatch = assertThrows(IllegalStateException.class, state::requireValidForTransition);
		assertEquals("boundingBoxMaxX does not match position and pose", mismatch.getMessage());

		state.placeAt(0.5, 1.0, 0.5);
		state.deltaMovementZ = Double.NaN;
		assertThrows(IllegalStateException.class, state::requireValidForTransition);
	}

	@Test
	void locationCopyMovesCorrelatedFieldsAndClearsTheTargetsPoseFitCache() {
		PlayerState source = new PlayerState();
		source.placeAt(-7.25, 18.0, 4.5);
		PlayerState target = new PlayerState();
		target.placeAt(0.5, 1.0, 0.5);
		FlatFloorView world = FlatFloorView.ordinary(0, -64);
		target.cachePoseFit(world, PlayerState.Pose.STANDING);

		target.copyLocationFrom(source);

		assertEquals(Double.doubleToRawLongBits(source.x), Double.doubleToRawLongBits(target.x));
		assertEquals(
		    Double.doubleToRawLongBits(source.boundingBoxMaxZ), Double.doubleToRawLongBits(target.boundingBoxMaxZ));
		assertFalse(target.hasCachedPoseFit(world, PlayerState.Pose.STANDING));
		assertThrows(NullPointerException.class, () -> target.copyLocationFrom(null));
		assertDoesNotThrow(target::requireValidForTransition);
	}
}
