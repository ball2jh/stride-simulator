package com.nettarion.stride.simulator.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.HurtMotion;
import com.nettarion.stride.simulator.HurtMotionWrite;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.ServerWriteTimeline;
import com.nettarion.stride.simulator.Simulator;
import com.nettarion.stride.simulator.StaleServerWriteException;
import com.nettarion.stride.simulator.tick.ClientTick;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** A root-package type tested here because its writes come from this package's server tick. */
final class HurtMotionTest {
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);

	@Test
	void independentlyDecodedWritesCompareByValueAndBoundState() {
		PlayerState state = new PlayerState();
		HurtMotion first = HurtMotion.decoded(HurtCause.FALL, 3, 4, state, 0.2, -0.1, 0);
		HurtMotion second = HurtMotion.decoded(HurtCause.FALL, 3, 4, state.copy(), 0.2, -0.1, 0);
		assertEquals(first, second);
		assertEquals(first.hashCode(), second.hashCode());
		assertEquals(ServerWriteTimeline.of(List.of(new HurtMotionWrite(4, first))),
		    ServerWriteTimeline.of(List.of(new HurtMotionWrite(4, second))));
		state.x = 1;
		assertNotEquals(first, HurtMotion.decoded(HurtCause.FALL, 3, 4, state, 0.2, -0.1, 0),
		    "identical payloads cannot erase a different bound state");
		assertNotEquals(first, HurtMotion.decoded(HurtCause.FALL, 4, 5, new PlayerState(), 0.2, -0.1, 0));
		assertNotEquals(first, HurtMotion.decoded(HurtCause.FALL, 3, 4, new PlayerState(), 0.3, -0.1, 0));
		assertThrows(
		    IllegalArgumentException.class, () -> HurtMotion.decoded(HurtCause.FALL, 4, 3, state, 0.2, -0.1, 0));
	}

	@Test
	void appliesThePacketQuantizedVelocityAfterTheDeclaredAction() {
		PlayerState state = new PlayerState();
		HurtMotion event =
		    HurtMotion.decoded(HurtCause.FALL, 138, 139, state, 0.031274, -0.0784000015258789, -0.031274);

		event.applyAfterAction(139, state);

		assertEquals(-0.0783739241897089, state.deltaMovementY,
		    "the predicted write must include the lossy 26.2 packet round trip");
		assertEquals(-state.deltaMovementX, state.deltaMovementZ);
	}

	@Test
	void refusesToApplyAHitToAStateOtherThanTheOneItWasBoundTo() {
		PlayerState expected = new PlayerState();
		HurtMotion event = HurtMotion.decoded(HurtCause.FALL, 6, 7, expected, 0.0, -0.0784000015258789, 0.0);
		PlayerState stale = expected.copy();
		stale.x = 1.0;

		assertThrows(StaleServerWriteException.class, () -> event.applyAfterAction(7, stale));
		assertThrows(IllegalArgumentException.class, () -> event.applyAfterAction(8, expected));
	}

	@Test
	void predictsTheFirstDamagingLandingOfAnActionList() {
		WorldSnapshot snapshot = dropWorld();
		PlayerState start = new PlayerState();
		start.placeAt(0.5, 6.0, 0.5);
		List<PlayerInput> actions = Collections.nCopies(40, IDLE);

		HurtMotion event = first(start, actions, snapshot).orElseThrow();
		PlayerState client = replay(start, actions, snapshot, event.writeAfterAction() + 1);

		// The landing packet's mark is published by the next step's tracker
		// sample and applied before the tick after next, as the bundled
		// captures measure, so the write completes after action 14.
		assertEquals(13, event.causeAction());
		assertEquals(14, event.writeAfterAction());
		double clientX = client.deltaMovementX;
		double clientY = client.deltaMovementY;
		double clientZ = client.deltaMovementZ;
		event.applyAfterAction(event.writeAfterAction(), client);
		assertEquals(clientX, client.deltaMovementX);
		assertEquals(event.writeY(), client.deltaMovementY);
		assertNotEquals(clientY, client.deltaMovementY, "decoded motion quantizes the server vector");
		assertEquals(clientZ, client.deltaMovementZ);
	}

	@Test
	void theWriteReplacesAJumpAtTheActionItIsBoundTo() {
		WorldSnapshot snapshot = dropWorld();
		PlayerState start = new PlayerState();
		start.placeAt(0.5, 6.0, 0.5);
		start.deltaMovementX = 0.1;
		start.deltaMovementZ = -0.05;
		List<PlayerInput> actions = new ArrayList<>(Collections.nCopies(14, IDLE));
		actions.add(PlayerInput.of(0.0F, 0.0F, PlayerInput.Key.FORWARD, PlayerInput.Key.JUMP, PlayerInput.Key.SPRINT));

		HurtMotion event = first(start, actions, snapshot).orElseThrow();

		assertEquals(13, event.causeAction());
		assertEquals(14, event.writeAfterAction());
		PlayerState client = replay(start, actions, snapshot, event.writeAfterAction() + 1);
		assertTrue(client.deltaMovementY > 0.0);
		assertTrue(event.writeY() < 0.0);
		event.applyAfterAction(event.writeAfterAction(), client);
		assertEquals(event.writeX(), client.deltaMovementX);
		assertEquals(event.writeY(), client.deltaMovementY);
		assertEquals(event.writeZ(), client.deltaMovementZ);
	}

	@Test
	void refusesWhenTheActionListEndsBeforeTheWriteIsDelivered() {
		WorldSnapshot snapshot = dropWorld();
		PlayerState start = new PlayerState();
		start.placeAt(0.5, 6.0, 0.5);
		List<PlayerInput> endingAtLanding = Collections.nCopies(14, IDLE);

		assertThrows(PendingServerWriteException.class, () -> first(start, endingAtLanding, snapshot));
	}

	/** The first marking hit's write in the composed step's run of the actions, if any. */
	private static Optional<HurtMotion> first(
	    final PlayerState start, final List<PlayerInput> actions, final WorldSnapshot snapshot) {
		ServerPlayerState server = ServerPlayerState.atBoundary(start);
		// Regeneration is disabled so the health packet does not join the writes under test.
		server.naturalRegeneration = false;
		Simulator.Run run = new Simulator().run(start, server, actions, SnapshotView.compile(snapshot));
		return run.timeline().hurtMotion().stream().findFirst().map(HurtMotionWrite::event);
	}

	private static PlayerState replay(
	    final PlayerState start, final List<PlayerInput> actions, final WorldSnapshot snapshot, final int ticks) {
		PlayerState state = start.copy();
		ClientTick clientTick = new ClientTick();
		Scratch scratch = new Scratch();
		SnapshotView world = SnapshotView.compile(snapshot);
		for (int tick = 0; tick < ticks; tick++) {
			clientTick.tick(state, actions.get(tick), world, scratch);
		}
		return state;
	}

	/** A stone floor with its top at y=0 from -7 to 7 in a 16-cube region. */
	private static WorldSnapshot dropWorld() {
		WorldSnapshot.Builder world =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, -8, -3, -8, 16, 16, 16)
		        .palette(BlockEntry.builder(0, "minecraft:air").build(),
		            BlockEntry.builder(1, "minecraft:stone").boxes(ShapeBox.FULL_CUBE).build());
		for (int z = -7; z <= 7; z++) {
			for (int x = -7; x <= 7; x++) {
				world.set(x, -1, z, 1);
			}
		}
		return world.build();
	}
}
