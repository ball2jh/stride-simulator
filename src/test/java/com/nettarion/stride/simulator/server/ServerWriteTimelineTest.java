package com.nettarion.stride.simulator.server;

import com.nettarion.stride.simulator.HurtMotion;
import com.nettarion.stride.simulator.HurtMotionWrite;
import com.nettarion.stride.simulator.ServerWriteTimeline;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.AABB;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ServerWriteTimelineTest {
	@Test
	void appliesAWriteOnlyAtItsCausalBoundary() {
		PlayerState start = state();
		HurtMotionWrite write = write(2, start, -0.5);
		ServerWriteTimeline timeline = ServerWriteTimeline.of(List.of(write));
		PlayerState actual = start.copy();

		timeline.applyAfterAction(1, actual);
		assertEquals(start.deltaMovementY, actual.deltaMovementY);
		timeline.applyAfterAction(2, actual);

		assertEquals(write.event().writeY(), actual.deltaMovementY);
		assertTrue(timeline.velocityWriteAfterAction(2));
		assertFalse(timeline.velocityWriteAfterAction(1));
	}

	@Test
	void rebasingDropsConsumedWritesAndShiftsTheRemainder() {
		PlayerState start = state();
		ServerWriteTimeline timeline = ServerWriteTimeline.of(List.of(write(1, start, -0.4), write(4, start, -0.6)));

		ServerWriteTimeline rebased = timeline.afterConsuming(2);

		assertEquals(1, rebased.events().size());
		assertEquals(2, rebased.events().getFirst().actionIndex());
	}

	@Test
	void composesLooseEventsIntoDeliveryBoundaryOrder() {
		PlayerState start = state();
		HurtMotionWrite later = write(4, start, -0.6);
		HurtMotionWrite earlier = write(1, start, -0.4);

		ServerWriteTimeline timeline = ServerWriteTimeline.of(List.of(later, earlier));

		assertEquals(List.of(earlier, later), timeline.events());
	}

	@Test
	void refusesTwoMovementWritesAtOneBoundary() {
		PlayerState start = state();

		assertThrows(IllegalArgumentException.class,
		    () -> ServerWriteTimeline.of(List.of(write(1, start, -0.5), write(1, start, -0.6))));
	}

	private static HurtMotionWrite write(final int actionIndex, final PlayerState state, final double velocityY) {
		return new HurtMotionWrite(actionIndex, HurtMotion.decoded(HurtCause.FALL, 0, state, 0.0, velocityY, 0.0));
	}

	private static PlayerState state() {
		PlayerState state = new PlayerState();
		state.placeAt(2.5, 1.0, 2.5);
		state.setBox(
		    AABB.around(state.x, state.y, state.z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));
		state.onGround = true;
		state.mainSupportingBlockPosPresent = true;
		state.mainSupportingBlockPosX = 2;
		state.mainSupportingBlockPosY = 0;
		state.mainSupportingBlockPosZ = 2;
		return state;
	}
}
