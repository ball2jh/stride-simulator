package com.nettarion.stride.simulator.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.world.FlatFloorView;

import org.junit.jupiter.api.Test;

final class ClientTickInputStateTest {
	@Test
	void previousForwardVectorChangesTheSprintTriggerTransition() {
		ClientTick simulator = new ClientTick();
		PlayerInput forward = new PlayerInput(true, false, false, false, false, false, false, 0.0F, 0.0F);
		PlayerState firstPress = initialState();
		PlayerState heldFromPriorTick = initialState();
		heldFromPriorTick.inputKeyPresses = (byte) PlayerInput.FLAG_FORWARD;
		heldFromPriorTick.inputMoveVectorY = 1.0F;

		simulator.tick(firstPress, forward, FlatFloorView.ordinary(0, -64));
		simulator.tick(heldFromPriorTick, forward, FlatFloorView.ordinary(0, -64));

		assertEquals(7, firstPress.sprintTriggerTime, "a new forward press opens the double-tap sprint window");
		assertEquals(0, heldFromPriorTick.sprintTriggerTime, "held forward is not a new press");
		assertEquals(forward.packedInput(), heldFromPriorTick.inputKeyPresses);
		assertEquals(Float.floatToRawIntBits(1.0F), Float.floatToRawIntBits(heldFromPriorTick.inputMoveVectorY));
	}

	private static PlayerState initialState() {
		PlayerState state = new PlayerState();
		state.x = 0.5;
		state.y = 0.0;
		state.z = 0.5;
		state.setBox(
		    AABB.around(state.x, state.y, state.z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));
		state.onGround = true;
		return state;
	}
}
