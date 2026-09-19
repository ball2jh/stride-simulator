package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** An action canonicalizes its rotation and its key factories agree with the packed wire byte. */
final class PlayerInputTest {
	@Test
	void canonicalizesSignedZeroViewRotationsAtTheControlBoundary() {
		PlayerInput action = PlayerInput.idle(-0.0F, -0.0F);

		assertEquals(Float.floatToRawIntBits(0.0F), Float.floatToRawIntBits(action.yRot()));
		assertEquals(Float.floatToRawIntBits(0.0F), Float.floatToRawIntBits(action.xRot()));
	}

	@Test
	void keyFactoriesHoldAndReleaseExactlyTheNamedKeys() {
		PlayerInput sprintJump = PlayerInput.of(30.0F, -5.0F, PlayerInput.Key.FORWARD, PlayerInput.Key.SPRINT);
		assertTrue(sprintJump.forward());
		assertTrue(sprintJump.sprint());
		assertFalse(sprintJump.jump());
		assertEquals(30.0F, sprintJump.yRot());
		assertEquals(-5.0F, sprintJump.xRot());
		assertEquals(PlayerInput.FLAG_FORWARD | PlayerInput.FLAG_SPRINT, sprintJump.packedInput());

		PlayerInput withJump = sprintJump.with(PlayerInput.Key.JUMP);
		assertTrue(withJump.jump());
		assertTrue(withJump.forward(), "adding a key keeps the others held");
		assertEquals(sprintJump, withJump.without(PlayerInput.Key.JUMP), "releasing it restores the action");

		PlayerInput released = withJump.without(PlayerInput.Key.FORWARD, PlayerInput.Key.SPRINT, PlayerInput.Key.JUMP);
		assertEquals(PlayerInput.idle(30.0F, -5.0F), released);
		assertEquals(sprintJump, PlayerInput.ofPacked(sprintJump.packedInput(), 30.0F, -5.0F));
		assertEquals(PlayerInput.idle(0.0F, 0.0F), sprintJump.without(PlayerInput.Key.values()).looking(0.0F, 0.0F));
		for (PlayerInput.Key key : PlayerInput.Key.values()) {
			assertEquals(key.flag(), PlayerInput.idle(0.0F, 0.0F).with(key).packedInput(), key.name());
		}
	}

	@Test
	void rejectsNonFiniteRotationAndPitchOutsideTheClientsClamp() {
		assertThrows(IllegalArgumentException.class, () -> PlayerInput.idle(Float.NaN, 0.0F));
		assertThrows(IllegalArgumentException.class, () -> PlayerInput.idle(0.0F, 90.5F));
		assertThrows(IllegalArgumentException.class, () -> PlayerInput.idle(0.0F, -90.5F));
	}
}
