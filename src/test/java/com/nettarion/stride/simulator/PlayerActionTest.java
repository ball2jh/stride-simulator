package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class PlayerActionTest {
	@Test
	void canonicalizesSignedZeroViewRotationsAtTheControlBoundary() {
		PlayerInput action = PlayerInput.idle(-0.0F, -0.0F);

		assertEquals(Float.floatToRawIntBits(0.0F), Float.floatToRawIntBits(action.yRot()));
		assertEquals(Float.floatToRawIntBits(0.0F), Float.floatToRawIntBits(action.xRot()));
	}
}
