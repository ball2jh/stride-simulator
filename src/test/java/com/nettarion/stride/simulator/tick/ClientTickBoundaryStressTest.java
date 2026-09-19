package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.StateDigest;
import com.nettarion.stride.simulator.world.FlatFloorView;
import com.nettarion.stride.simulator.world.WorldView;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Deterministic edge-biased stress around cell and section coordinates. */
class ClientTickBoundaryStressTest {
	private static final double[] BOUNDARIES = {Math.nextDown(-16.0), -16.0, Math.nextUp(-16.0), Math.nextDown(-1.0),
	    -1.0, Math.nextUp(-1.0), Math.nextDown(0.0), 0.0, Math.nextUp(0.0), Math.nextDown(1.0), 1.0, Math.nextUp(1.0),
	    Math.nextDown(16.0), 16.0, Math.nextUp(16.0)};

	@Test
	void cellAndSectionBoundaryStatesReplayAcrossCopyResume() {
		WorldView world = FlatFloorView.ordinary(0, -64);
		PlayerInput[] actions = actions();
		for (int i = 0; i < BOUNDARIES.length; i++) {
			double x = BOUNDARIES[i];
			double z = BOUNDARIES[BOUNDARIES.length - 1 - i];
			PlayerState direct = grounded(x, z);
			ClientTick directKernel = new ClientTick();
			Scratch directScratch = new Scratch();
			for (int tick = 0; tick < actions.length / 2; tick++) {
				directKernel.tick(direct, actions[tick], world, directScratch);
			}
			PlayerState resumed = direct.copy();
			ClientTick resumedKernel = new ClientTick();
			Scratch resumedScratch = new Scratch();
			for (int tick = actions.length / 2; tick < actions.length; tick++) {
				directKernel.tick(direct, actions[tick], world, directScratch);
				resumedKernel.tick(resumed, actions[tick], world, resumedScratch);
				assertEquals(StateDigest.state(direct), StateDigest.state(resumed),
				    "copy/resume diverged for boundary bin " + i + " at tick " + tick);
			}
		}
	}

	private static PlayerState grounded(final double x, final double z) {
		PlayerState state = new PlayerState();
		state.placeAt(x, 0.0, z);
		state.deltaMovementY = -0.0784000015258789;
		state.onGround = true;
		state.verticalCollision = true;
		state.verticalCollisionBelow = true;
		state.requireValidForTransition();
		return state;
	}

	private static PlayerInput[] actions() {
		PlayerInput[] actions = new PlayerInput[48];
		for (int tick = 0; tick < actions.length; tick++) {
			int bits = tick * 0x9E37_79B9;
			actions[tick] = new PlayerInput((bits & 1) == 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0,
			    (bits & 16) == 0, (bits & 32) != 0, (bits & 64) == 0, (bits >>> 8 & 255) * (360.0F / 256.0F), 0.0F);
		}
		return actions;
	}
}
