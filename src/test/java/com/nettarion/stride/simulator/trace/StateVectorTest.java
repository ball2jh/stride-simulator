package com.nettarion.stride.simulator.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.PlayerState;

import org.junit.jupiter.api.Test;

final class StateVectorTest {
	@Test
	void retainedSupportingBlockRoundTripsThroughPortableState() {
		PlayerState source = new PlayerState();
		source.mainSupportingBlockPosPresent = true;
		source.mainSupportingBlockPosX = -31;
		source.mainSupportingBlockPosY = 204;
		source.mainSupportingBlockPosZ = 17;
		source.onGroundNoBlocks = true;

		PlayerState restored = StateVector.of(source).toPlayerState();

		assertTrue(restored.mainSupportingBlockPosPresent);
		assertEquals(-31, restored.mainSupportingBlockPosX);
		assertEquals(204, restored.mainSupportingBlockPosY);
		assertEquals(17, restored.mainSupportingBlockPosZ);
		assertTrue(restored.onGroundNoBlocks);
	}

	@Test
	void transitionRestoreChecksCorrelatedGeometry() {
		PlayerState valid = new PlayerState();
		valid.placeAt(0.5, 64.0, -2.5);

		PlayerState restored = StateVector.of(valid).toPlayerStateForTransition();
		assertEquals(
		    Double.doubleToRawLongBits(valid.boundingBoxMaxY), Double.doubleToRawLongBits(restored.boundingBoxMaxY));

		PlayerState malformed = valid.copy();
		malformed.boundingBoxMinZ = Math.nextDown(malformed.boundingBoxMinZ);
		assertThrows(IllegalStateException.class, () -> StateVector.of(malformed).toPlayerStateForTransition());
	}

	@Test
	void poseIsRestoredFromItsVanillaIdNotItsLabel() {
		// The label is a diagnostic aid outside the equality relation; the id is the fact.
		StateVector mislabeled = TraceFixtures.zeroStateBuilder()
		                             .setEnum(StateField.POSE, PlayerState.Pose.CROUCHING.id, "STANDING")
		                             .build();

		assertEquals(PlayerState.Pose.CROUCHING, mislabeled.toPlayerState().pose);
		assertEquals(PlayerState.Pose.CROUCHING.id, mislabeled.getEnumId(StateField.POSE));
	}

	@Test
	void anUnknownPoseIdRefuses() {
		StateVector unknown = TraceFixtures.zeroStateBuilder().setEnum(StateField.POSE, 2, "SLEEPING").build();

		assertThrows(IllegalArgumentException.class, unknown::toPlayerState);
	}

	@Test
	void everyPoseRoundTripsThroughItsId() {
		for (PlayerState.Pose pose : PlayerState.Pose.values()) {
			PlayerState state = new PlayerState();
			state.pose = pose;
			assertEquals(pose, StateVector.of(state).toPlayerState().pose, pose.name());
		}
	}
}
