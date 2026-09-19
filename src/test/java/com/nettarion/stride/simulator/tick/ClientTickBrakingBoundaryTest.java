package com.nettarion.stride.simulator.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.world.FlatFloorView;

import org.junit.jupiter.api.Test;

/** The two discontinuities in dry braking, at their exact double boundaries. */
final class ClientTickBrakingBoundaryTest {
	private static final double FLOOR_VELOCITY = -0.0784000015258789;
	private static final double POSITION_COMMIT_SQUARED = 1.0E-7;
	private static final double HORIZONTAL_DEAD_ZONE_SQUARED = 9.0E-6;
	private static final double SPRINT_FORWARD_IMPULSE = 0x1.04ea4c367a1p-3;
	private static final PlayerInput SPRINT_FORWARD =
	    new PlayerInput(true, false, false, false, false, false, true, 0.0F, 0.0F);

	@Test
	void horizontalDeadZoneHasAOneUlpBoundaryAtPointZeroZeroThree() {
		double threshold = 0.003;
		double[] entries = {Math.nextDown(threshold), threshold, Math.nextUp(threshold)};
		long[] expectedZ = {0x3fe0000000000000L, 0x3fe0189374bc6a7fL, 0x3fe0189374bc6a7fL};
		long[] expectedRetainedZ = {0x0000000000000000L, 0x3f5ad6454fdf3b65L, 0x3f5ad6454fdf3b66L};

		assertTrue(entries[0] * entries[0] < HORIZONTAL_DEAD_ZONE_SQUARED);
		assertFalse(entries[1] * entries[1] < HORIZONTAL_DEAD_ZONE_SQUARED,
		    "vanilla's comparison is strict at the first surviving double");
		for (int row = 0; row < entries.length; row++) {
			PlayerState state = stableGrounded();
			state.deltaMovementZ = entries[row];

			new ClientTick().tick(state, PlayerInput.idle(0.0F, 0.0F), FlatFloorView.ordinary(0, -64));

			assertEquals(expectedZ[row], Double.doubleToRawLongBits(state.z));
			assertEquals(expectedRetainedZ[row], Double.doubleToRawLongBits(state.deltaMovementZ));
		}
	}

	@Test
	void floorClipMakesTheMovementSegmentThresholdStrict() {
		double threshold = Math.sqrt(POSITION_COMMIT_SQUARED);
		double[] requested = {Math.nextDown(threshold), threshold, Math.nextUp(threshold)};
		boolean[] commits = {false, true, true};

		assertFalse(requested[0] * requested[0] > POSITION_COMMIT_SQUARED);
		assertTrue(requested[1] * requested[1] > POSITION_COMMIT_SQUARED,
		    "rounded sqrt(1e-7) already squares to the strict greater side");
		for (int row = 0; row < requested.length; row++) {
			PlayerState state = stableGrounded();
			state.deltaMovementZ = requested[row];

			Move.resolve(
			    state, 0.0, FLOOR_VELOCITY, requested[row], false, FlatFloorView.ordinary(0, -64), new Scratch());

			assertEquals(commits[row], state.z != 0.5, "movement-segment admission row " + row);
			assertEquals(Double.doubleToRawLongBits(requested[row]), Double.doubleToRawLongBits(state.deltaMovementZ),
			    "a dropped segment does not erase the retained horizontal velocity");
		}
	}

	@Test
	void adjacentBrakingEntriesSplitPositionButRetainNonzeroVelocity() {
		double lowerEntry = Math.sqrt(POSITION_COMMIT_SQUARED) - SPRINT_FORWARD_IMPULSE;
		double upperEntry = Math.nextUp(lowerEntry);
		double lowerRequest = lowerEntry + SPRINT_FORWARD_IMPULSE;
		double upperRequest = upperEntry + SPRINT_FORWARD_IMPULSE;
		assertFalse(lowerRequest * lowerRequest > POSITION_COMMIT_SQUARED);
		assertTrue(upperRequest * upperRequest > POSITION_COMMIT_SQUARED);

		PlayerState dropped = brakingEntry(lowerEntry);
		PlayerState committed = brakingEntry(upperEntry);
		ClientTick simulator = new ClientTick();
		simulator.tick(dropped, SPRINT_FORWARD, FlatFloorView.ordinary(0, -64));
		simulator.tick(committed, SPRINT_FORWARD, FlatFloorView.ordinary(0, -64));

		assertEquals(0x3fe0000000000000L, Double.doubleToRawLongBits(dropped.z));
		assertEquals(0x3fe002972d7d385cL, Double.doubleToRawLongBits(committed.z));
		assertEquals(0x3f26a1855f97acc8L, Double.doubleToRawLongBits(dropped.deltaMovementZ));
		assertEquals(0x3f26a1855f97aef8L, Double.doubleToRawLongBits(committed.deltaMovementZ));
		assertTrue(dropped.deltaMovementZ > 0.0, "the dropped position segment is not a stopped-velocity state");
		assertTrue(dropped.onGround && committed.onGround);
	}

	private static PlayerState brakingEntry(final double deltaMovementZ) {
		PlayerState state = stableGrounded();
		state.deltaMovementZ = deltaMovementZ;
		state.setSprinting(true);
		return state;
	}

	private static PlayerState stableGrounded() {
		PlayerState state = new PlayerState();
		state.placeAt(0.5, 0.0, 0.5);
		state.onGround = true;
		state.mainSupportingBlockPosPresent = true;
		state.mainSupportingBlockPosX = 0;
		state.mainSupportingBlockPosY = -1;
		state.mainSupportingBlockPosZ = 0;
		state.deltaMovementY = FLOOR_VELOCITY;
		return state;
	}
}
