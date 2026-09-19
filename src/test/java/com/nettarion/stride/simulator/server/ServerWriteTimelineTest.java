package com.nettarion.stride.simulator.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.HurtMotion;
import com.nettarion.stride.simulator.HurtMotionWrite;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.ServerWriteTimeline;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A root-package type tested here because its writes come from this package's server tick. */
final class ServerWriteTimelineTest {
	@Test
	void appliesAWriteOnlyAtItsActionIndex() {
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

		assertEquals(1, rebased.writes().size());
		assertEquals(2, rebased.writes().getFirst().actionIndex());
		assertSame(timeline, timeline.afterConsuming(0));
		assertSame(ServerWriteTimeline.EMPTY, timeline.afterConsuming(5));
	}

	@Test
	void composesLooseWritesIntoActionOrder() {
		PlayerState start = state();
		HurtMotionWrite later = write(4, start, -0.6);
		HurtMotionWrite earlier = write(1, start, -0.4);

		ServerWriteTimeline timeline = ServerWriteTimeline.of(List.of(later, earlier));

		assertEquals(List.of(earlier, later), timeline.writes());
		assertEquals(List.of(earlier, later), timeline.hurtMotion());
		assertTrue(timeline.damage().isEmpty());
	}

	@Test
	void refusesTwoVelocityWritesAtOneActionIndex() {
		PlayerState start = state();

		assertThrows(IllegalArgumentException.class,
		    () -> ServerWriteTimeline.of(List.of(write(1, start, -0.5), write(1, start, -0.6))));
	}

	private static HurtMotionWrite write(final int actionIndex, final PlayerState state, final double velocityY) {
		return new HurtMotionWrite(
		    actionIndex, HurtMotion.decoded(HurtCause.FALL, 0, actionIndex, state, 0.0, velocityY, 0.0));
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
