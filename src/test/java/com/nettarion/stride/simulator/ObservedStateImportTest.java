package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

/**
 * A server copy derived from what a client can read is validated once, and an observed entity-data
 * packet applies exactly as the simulator's own predicted write does.
 */
final class ObservedStateImportTest {
	private static PlayerState grounded() {
		PlayerState state = new PlayerState();
		state.placeAt(0.5, 64.0, 0.5);
		state.onGround = true;
		return state;
	}

	private static Publisher publisherAt(final PlayerState movement) {
		Publisher publisher = new Publisher();
		publisher.xLast = movement.x;
		publisher.yLast = movement.y;
		publisher.zLast = movement.z;
		publisher.yRotLast = movement.yRot;
		publisher.xRotLast = movement.xRot;
		publisher.lastOnGround = movement.onGround;
		return publisher;
	}

	private static ServerPlayerState.Observed observed(final float health, final int food) {
		return new ServerPlayerState.Observed(
		    health, 20.0F, 0.0F, 300, food, 5.0F, 0, false, 0, 0, ServerPlayerState.Difficulty.NORMAL, true);
	}

	@Test
	void aDerivedServerCopyCarriesThePublishedFactsAndTheClientsBaselines() {
		PlayerState movement = grounded();
		Publisher publisher = publisherAt(movement);
		publisher.lastOnGround = false;
		ServerPlayerState server = ServerPlayerState.derivedFrom(movement, publisher, observed(13.5F, 17));
		assertEquals(13.5F, server.health);
		assertEquals(13.5F, server.lastSentHealth);
		assertEquals(17, server.foodLevel);
		assertEquals(17, server.lastSentFood);
		assertFalse(server.onGround, "the server's contact flag is the publisher's last published one");
		assertEquals(movement.x, server.x);
		assertEquals(server.x, server.lastGoodX);
		assertTrue(server.singleplayerOwner);
		assertTrue(server.allowFlight);
		assertEquals(0, server.sprintWindowTicks);
		assertEquals(ServerPlayerState.Difficulty.NORMAL, server.difficulty);
	}

	@Test
	void aDerivedServerCopyOutsideTheAdmittedDomainIsRejected() {
		PlayerState movement = grounded();
		Publisher publisher = publisherAt(movement);
		assertThrows(IllegalStateException.class,
		    ()
		        -> ServerPlayerState.derivedFrom(movement, publisher, observed(20.0F, 25)),
		    "food above the vanilla range refuses");
		assertThrows(IllegalStateException.class,
		    ()
		        -> ServerPlayerState.derivedFrom(movement, publisher, observed(25.0F, 20)),
		    "health above maximum refuses");
	}

	@Test
	void anObservedPublicationAppliesAsThePredictedOneDoes() {
		PlayerState observed = grounded();
		int flags = SharedFlag.SPRINTING_MASK | SharedFlag.SWIMMING_MASK;
		observed.applyObservedEntityData(OptionalInt.of(flags), Optional.of("SWIMMING"), OptionalInt.of(40));
		PlayerState predicted = grounded();
		new EntityDataWrite(0, StateDigest.state(predicted),
		    EntityDataWrite.FLAGS | EntityDataWrite.POSE | EntityDataWrite.FROZEN, flags, PlayerState.Pose.SWIMMING, 40,
		    0, false)
		    .applyUnchecked(predicted);
		assertTrue(PlayerState.rawEquals(observed, predicted));
	}

	@Test
	void anObservedVelocityAppliesAsAPredictedHurtMotionDoesAndRefusesTheNonFinite() {
		PlayerState state = grounded();
		state.applyObservedVelocity(0.25, -0.5, 0.125);
		assertEquals(0.25, state.deltaMovementX);
		assertEquals(-0.5, state.deltaMovementY);
		assertEquals(0.125, state.deltaMovementZ);
		assertThrows(IllegalStateException.class, () -> state.applyObservedVelocity(Double.NaN, 0.0, 0.0));
	}

	@Test
	void anUnmodeledPoseAndAnAbsentFieldLeaveTheCopyAsItWas() {
		PlayerState state = grounded();
		PlayerState before = state.copy();
		state.applyObservedEntityData(OptionalInt.empty(), Optional.of("SLEEPING"), OptionalInt.empty());
		assertTrue(PlayerState.rawEquals(before, state));
	}
}
