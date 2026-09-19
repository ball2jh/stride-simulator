package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.PlayerState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StateVectorTest {
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
}
