package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.tick.ClientTick;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.SnapshotView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The client tick alone and the composed step agree on the client copy, bit
 * for bit, until a delivered server write changes it.
 *
 * <p>This is the rule a consumer relies on when it drives {@link ClientTick}
 * for a kinematic question and certifies the answer with one composed run:
 * nothing in the step touches the client copy except the client tick and the
 * writes it delivers, so a tick at which the copies differ is a tick at which
 * a write was delivered. An entity-data echo that repeats what the client
 * already holds is a delivered write that changes nothing; a hurt-marked
 * velocity, a health packet, or a stale echo does change the copy, and from
 * there the client tick alone is an approximation. The rule is held here
 * rather than read off the code.
 */
class ClientOnlyAgreementTest {
	private static final PlayerInput SPRINT_JUMP =
	    new PlayerInput(true, false, false, false, true, false, true, 30.0F, 0.0F);
	private static final PlayerInput WALK =
	    new PlayerInput(true, false, false, false, false, false, false, -45.0F, 10.0F);
	private static final PlayerInput CROUCH =
	    new PlayerInput(false, false, true, false, false, true, false, 90.0F, -20.0F);

	@Test
	void theCopiesDivergeOnlyAtADeliveredWriteAndAgreeThroughAJumpAndALanding() {
		SnapshotView world = RolloutTest.Worlds.ledge();
		PlayerState start = RolloutTest.Worlds.groundedAt(0.5, 5.0, 0.5);
		List<PlayerInput> actions = new ArrayList<>();
		for (int tick = 0; tick < 64; tick++) {
			actions.add(tick < 6 ? SPRINT_JUMP : tick < 30 ? WALK : CROUCH);
		}
		Simulator simulator = new Simulator();
		SimulationState boundary = simulator.start(start, ServerPlayerState.atBoundary(start));
		ClientTick kernel = new ClientTick();
		Scratch scratch = new Scratch();
		PlayerState alone = start.copy();
		int firstDivergence = -1;
		boolean landed = false;
		for (int tick = 0; tick < actions.size() && firstDivergence < 0; tick++) {
			Simulator.Step step = simulator.advance(boundary, actions.get(tick), world);
			boundary = step.state();
			kernel.tick(alone, actions.get(tick), world, scratch);
			PlayerState composed = boundary.clientState();
			landed |= tick > 0 && composed.onGround && composed.y < 1.0;
			if (!PlayerState.rawEquals(composed, alone)) {
				firstDivergence = tick;
				assertFalse(
				    step.writes().isEmpty(), "tick " + tick + ": the copies differ, yet no write was delivered");
				assertTrue(step.writes().stream().anyMatch(HurtMotionWrite.class ::isInstance),
				    "the ledge fall's divergence is the hurt-marked velocity");
			}
		}
		assertTrue(landed, "the fixture must land from the ledge before the copies diverge");
		assertTrue(firstDivergence > 8, "the fixture must agree over the jump and the fall");
	}

	@Test
	void echoesThatRepeatWhatTheClientHoldsLeaveTheCopiesEqual() {
		SnapshotView world = RolloutTest.Worlds.ledge();
		PlayerState start = RolloutTest.Worlds.groundedAt(0.5, 5.0, -8.5);
		Simulator simulator = new Simulator();
		SimulationState boundary = simulator.start(start, ServerPlayerState.atBoundary(start));
		ClientTick kernel = new ClientTick();
		Scratch scratch = new Scratch();
		PlayerState alone = start.copy();
		int echoes = 0;
		for (int tick = 0; tick < 40; tick++) {
			PlayerInput action = tick % 10 < 5 ? WALK : CROUCH;
			Simulator.Step step = simulator.advance(boundary, action, world);
			boundary = step.state();
			for (ServerWrite write : step.writes()) {
				assertTrue(write instanceof EntityDataWrite, "the flat walk delivers only echoes: " + write);
				echoes++;
			}
			kernel.tick(alone, action, world, scratch);
			assertTrue(PlayerState.rawEquals(boundary.clientState(), alone), "tick " + tick);
		}
		assertTrue(echoes > 0, "the crouch cycle must echo the pose and flags");
		assertEquals(StateDigest.state(boundary.clientState()), StateDigest.state(alone));
	}
}
