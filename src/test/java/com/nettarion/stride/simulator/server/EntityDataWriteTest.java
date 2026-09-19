package com.nettarion.stride.simulator.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.SharedFlag;
import com.nettarion.stride.simulator.EntityDataWrite;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.HurtMotion;
import com.nettarion.stride.simulator.HurtMotionWrite;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.ServerWriteTimeline;
import com.nettarion.stride.simulator.StaleServerWriteException;
import com.nettarion.stride.simulator.StateDigest;
import com.nettarion.stride.simulator.trace.StateEventTrace;
import com.nettarion.stride.simulator.trace.StateField;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A root-package type tested here because its samples come from this package's entity tracker. */
final class EntityDataWriteTest {
	@Test
	void capturedAuthorityFieldsDoNotCoupleMetadataAndAttributes() {
		PlayerState state = new PlayerState();
		StateEventTrace.Phase phase = StateEventTrace.Phase.POST;
		StateEventTrace.apply(state, new StateEventTrace.StateEvent(0, phase, StateField.SPRINTING, 1));
		assertTrue(state.sprinting);
		assertFalse(state.sprintingAttribute);
		StateEventTrace.apply(state, new StateEventTrace.StateEvent(0, phase, StateField.SPRINTING_ATTRIBUTE, 1));
		assertTrue(state.sprintingAttribute);
	}

	@Test
	void sharedFlagMutationDoesNotInstallTheSprintAttribute() {
		ServerPlayerState state = new ServerPlayerState();
		state.setSharedFlag(SharedFlag.SPRINTING, true);
		assertTrue(state.sprinting);
		assertFalse(state.sprintingAttribute);
		assertFalse(state.movementSpeedAttributeDirty);
		state.setSprinting(true);
		assertTrue(state.sprintingAttribute);
		assertTrue(state.movementSpeedAttributeDirty);
	}

	@Test
	void changeThenChangeBackStillPublishesTheWholeSharedByte() {
		ServerPlayerState server = new ServerPlayerState();
		server.setSwimming(true);
		server.setSwimming(false);
		ServerEntity tracker = new ServerEntity();
		tracker.collectChanges(server);
		PlayerState client = new PlayerState();
		client.setSprinting(true);
		EntityDataWrite write = tracker.write(3, client);
		assertNotNull(write);
		assertEquals(EntityDataWrite.FLAGS, write.dirty());
		write.applyAfterAction(3, client);
		assertFalse(client.sprinting);
		tracker.collectChanges(server);
		assertNull(tracker.write(4, client));
	}

	@Test
	void dirtyEntriesSurviveForksAndDistinguishOtherwiseEqualServerStates() {
		ServerPlayerState clean = new ServerPlayerState();
		ServerPlayerState dirty = clean.copy();
		dirty.setTicksFrozen(1);
		dirty.setTicksFrozen(0);
		assertFalse(ServerPlayerState.rawEquals(clean, dirty));
		assertNotEquals(StateDigest.server(clean), StateDigest.server(dirty));
		assertTrue(ServerPlayerState.rawEquals(dirty, dirty.copy()));
		assertEquals(StateDigest.server(dirty), StateDigest.server(dirty.copy()));
	}

	@Test
	void replayRebaseAndStaleStateChecksPreserveTheActionIndex() {
		ServerPlayerState server = new ServerPlayerState();
		server.setPose(PlayerState.Pose.CROUCHING);
		server.setTicksFrozen(20);
		server.frostSpeedTicks = 20;
		ServerEntity tracker = new ServerEntity();
		tracker.collectChanges(server);
		PlayerState client = new PlayerState();
		client.placeAt(0.5, 1, 0.5);
		EntityDataWrite write = tracker.write(3, client);
		ServerWriteTimeline timeline = ServerWriteTimeline.of(List.of(write));
		assertFalse(timeline.velocityWriteAfterAction(3));
		PlayerState expected = client.copy();
		write.applyAfterAction(3, expected);
		timeline.afterConsuming(2).applyAfterAction(1, client);
		assertTrue(PlayerState.rawEquals(expected, client));
		assertEquals(20, client.ticksFrozen);
		assertEquals(20, client.frostSpeedTicks);
		assertEquals(PlayerState.Pose.CROUCHING, client.pose);
		assertThrows(StaleServerWriteException.class, () -> write.applyAfterAction(3, client));
	}

	@Test
	void metadataBetweenMotionWritesDoesNotHideDuplicateVelocityDelivery() {
		PlayerState client = new PlayerState();
		HurtMotionWrite motion = new HurtMotionWrite(2, HurtMotion.decoded(HurtCause.FALL, 1, 2, client, 0, -0.5, 0));
		EntityDataWrite data =
		    new EntityDataWrite(2, StateDigest.state(client), EntityDataWrite.FROZEN, 0, client.pose, 10, 0, false);
		assertThrows(IllegalArgumentException.class, () -> ServerWriteTimeline.of(List.of(motion, data, motion)));
	}
}
