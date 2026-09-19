package com.nettarion.stride.simulator.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.world.FlatFloorView;
import com.nettarion.stride.simulator.world.WorldView;

import org.junit.jupiter.api.Test;

/** Exact values, derived from vanilla's expressions, for 26.2 square movement input. */
final class ClientTickSquareInputTest {
	private static final int ORDINARY_CARDINAL = 0x3f7a_e148;
	private static final int ORDINARY_DIAGONAL_COMPONENT = 0x3f35_04f2;
	private static final int SLOW_COMPONENT = 0x3e96_872c;

	@Test
	void squareInputKeepsCardinalAndDiagonalMagnitudesDistinct() {
		PlayerState cardinal = grounded();
		PlayerState diagonal = grounded();
		PlayerState slowCardinal = groundedAfterShift();
		PlayerState slowDiagonal = groundedAfterShift();
		ClientTick simulator = new ClientTick();
		WorldView world = FlatFloorView.ordinary(0, -64);

		simulator.tick(cardinal, action(true, false, false), world);
		simulator.tick(diagonal, action(true, true, false), world);
		simulator.tick(slowCardinal, action(true, false, true), world);
		simulator.tick(slowDiagonal, action(true, true, true), world);

		assertFloatBits(0, cardinal.xxa);
		assertFloatBits(ORDINARY_CARDINAL, cardinal.zza);
		assertFloatBits(ORDINARY_DIAGONAL_COMPONENT, diagonal.xxa);
		assertFloatBits(ORDINARY_DIAGONAL_COMPONENT, diagonal.zza);
		assertFloatBits(0, slowCardinal.xxa);
		assertFloatBits(SLOW_COMPONENT, slowCardinal.zza);
		assertFloatBits(SLOW_COMPONENT, slowDiagonal.xxa);
		assertFloatBits(SLOW_COMPONENT, slowDiagonal.zza);
	}

	private static PlayerInput action(final boolean forward, final boolean left, final boolean shift) {
		return new PlayerInput(forward, false, left, false, false, shift, false, 0.0F, 0.0F);
	}

	private static PlayerState groundedAfterShift() {
		PlayerState state = grounded();
		state.inputKeyPresses = (byte) PlayerInput.FLAG_SNEAK;
		return state;
	}

	private static PlayerState grounded() {
		PlayerState state = new PlayerState();
		state.placeAt(0.5, 0.0, 0.5);
		state.onGround = true;
		state.mainSupportingBlockPosPresent = true;
		state.mainSupportingBlockPosX = 0;
		state.mainSupportingBlockPosY = -1;
		state.mainSupportingBlockPosZ = 0;
		return state;
	}

	private static void assertFloatBits(final int expected, final float actual) {
		assertEquals(expected, Float.floatToRawIntBits(actual));
	}
}
