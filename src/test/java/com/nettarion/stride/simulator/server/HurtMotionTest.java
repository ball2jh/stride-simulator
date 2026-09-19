package com.nettarion.stride.simulator.server;

import com.nettarion.stride.simulator.HurtMotion;
import com.nettarion.stride.simulator.HurtMotionWrite;
import com.nettarion.stride.simulator.ServerWriteTimeline;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.Simulator;
import com.nettarion.stride.simulator.StaleServerWriteException;
import com.nettarion.stride.simulator.tick.ClientTick;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.BlockEntry;

final class HurtMotionTest {
	@Test
	void independentlyPredictedTimelinesCompareByCausalValue() {
		PlayerState state = new PlayerState();
		HurtMotion first = HurtMotion.decoded(HurtCause.FALL, 3, state, .2, -.1, 0);
		HurtMotion second = HurtMotion.decoded(HurtCause.FALL, 3, state.copy(), .2, -.1, 0);
		assertEquals(first, second);
		assertEquals(first.hashCode(), second.hashCode());
		assertEquals(ServerWriteTimeline.of(List.of(new HurtMotionWrite(4, first))),
		    ServerWriteTimeline.of(List.of(new HurtMotionWrite(4, second))));
		state.x = 1;
		assertNotEquals(first, HurtMotion.decoded(HurtCause.FALL, 3, state, .2, -.1, 0),
		    "identical payloads cannot erase a different causal-state guard");
		assertNotEquals(first, HurtMotion.decoded(HurtCause.FALL, 4, new PlayerState(), .2, -.1, 0));
		assertNotEquals(first, HurtMotion.decoded(HurtCause.FALL, 3, new PlayerState(), .3, -.1, 0));
	}

	@Test
	void appliesThePacketQuantizedVelocityAfterTheDeclaredTick() {
		PlayerState state = new PlayerState();
		HurtMotion event = HurtMotion.decoded(HurtCause.FALL, 138, state, 0.031274, -0.0784000015258789, -0.031274);

		event.applyAfterTick(139, state);

		assertEquals(-0.0783739241897089, state.deltaMovementY,
		    "the predicted write must include the lossy 26.2 packet round trip");
		assertEquals(-state.deltaMovementX, state.deltaMovementZ);
	}

	@Test
	void refusesToApplyAHitToAStateOtherThanTheOneThatPredictedIt() {
		PlayerState expected = new PlayerState();
		HurtMotion event = HurtMotion.decoded(HurtCause.FALL, 6, expected, 0.0, -0.0784000015258789, 0.0);
		PlayerState stale = expected.copy();
		stale.x = 1.0;

		assertThrows(StaleServerWriteException.class, () -> event.applyAfterTick(7, stale));
		assertThrows(IllegalArgumentException.class, () -> event.applyAfterTick(8, expected));
	}

	@Test
	void predictsTheFirstDamagingLandingFromAFinishedPlan() {
		WorldSnapshot snapshot = dropWorld();
		PlayerState start = new PlayerState();
		start.placeAt(0.5, 6.0, 0.5);
		List<PlayerInput> plan = Collections.nCopies(40, PlayerInput.idle(0.0F, 0.0F));

		HurtMotion event = first(start, plan, snapshot).orElseThrow();
		PlayerState boundary = replay(start, plan, snapshot, event.writeAfterAction() + 1);

		// The landing packet's mark is published by the next step's tracker
		// sample and applied before the tick after next, as the live captures
		// measure, so the write completes after tick 14.
		assertEquals(13, event.causeAction());
		assertEquals(14, event.writeAfterAction());
		double boundaryX = boundary.deltaMovementX;
		double boundaryY = boundary.deltaMovementY;
		double boundaryZ = boundary.deltaMovementZ;
		event.applyAfterTick(event.writeAfterAction(), boundary);
		assertEquals(boundaryX, boundary.deltaMovementX);
		assertEquals(event.writeY(), boundary.deltaMovementY);
		assertNotEquals(boundaryY, boundary.deltaMovementY, "decoded motion quantizes the server vector");
		assertEquals(boundaryZ, boundary.deltaMovementZ);
	}

	@Test
	void serverPublicationReplacesAJumpAtTheDeclaredDeliveryBoundary() {
		WorldSnapshot snapshot = dropWorld();
		PlayerState start = new PlayerState();
		start.placeAt(0.5, 6.0, 0.5);
		start.deltaMovementX = 0.1;
		start.deltaMovementZ = -0.05;
		List<PlayerInput> plan = new java.util.ArrayList<>(Collections.nCopies(14, PlayerInput.idle(0.0F, 0.0F)));
		plan.add(new PlayerInput(true, false, false, false, true, false, true, 0.0F, 0.0F));

		HurtMotion event = first(start, plan, snapshot).orElseThrow();

		assertEquals(13, event.causeAction());
		assertEquals(14, event.writeAfterAction());
		PlayerState boundary = replay(start, plan, snapshot, event.writeAfterAction() + 1);
		assertTrue(boundary.deltaMovementY > 0.0);
		assertTrue(event.writeY() < 0.0);
		event.applyAfterTick(event.writeAfterAction(), boundary);
		assertEquals(event.writeX(), boundary.deltaMovementX);
		assertEquals(event.writeY(), boundary.deltaMovementY);
		assertEquals(event.writeZ(), boundary.deltaMovementZ);
	}

	@Test
	void requiresThePlanTickInWhichThePacketArrives() {
		WorldSnapshot snapshot = dropWorld();
		PlayerState start = new PlayerState();
		start.placeAt(0.5, 6.0, 0.5);
		List<PlayerInput> endingAtLanding = Collections.nCopies(14, PlayerInput.idle(0.0F, 0.0F));

		assertThrows(PendingServerWriteException.class, () -> first(start, endingAtLanding, snapshot));
	}

	/** The first marking hit's publication in the composed step's run of the plan, if any. */
	private static Optional<HurtMotion> first(
	    final PlayerState start, final List<PlayerInput> plan, final WorldSnapshot snapshot) {
		ServerPlayerState server = ServerPlayerState.atBoundary(start);
		// This fixture isolates hurt motion on a server with regeneration disabled.
		server.naturalRegeneration = false;
		Simulator.Run run = new Simulator().run(start, server, plan, SnapshotView.compile(snapshot));
		return run.timeline().hurtMotion().stream().findFirst().map(HurtMotionWrite::event);
	}

	private static PlayerState replay(
	    final PlayerState start, final List<PlayerInput> plan, final WorldSnapshot snapshot, final int ticks) {
		PlayerState state = start.copy();
		ClientTick kernel = new ClientTick();
		Scratch scratch = new Scratch();
		SnapshotView world = new SnapshotView(snapshot);
		for (int tick = 0; tick < ticks; tick++) {
			kernel.tick(state, plan.get(tick), world, scratch);
		}
		return state;
	}

	private static WorldSnapshot dropWorld() {
		int size = 16;
		int origin = -8;
		int originY = -3;
		int[] cells = new int[size * size * size];
		for (int z = -7; z <= 7; z++) {
			for (int x = -7; x <= 7; x++) {
				int localX = x - origin;
				int localY = -1 - originY;
				int localZ = z - origin;
				cells[(localY * size + localZ) * size + localX] = 1;
			}
		}
		BlockEntry air = new BlockEntry(0, "air", 0.6F, 1.0F, 1.0F, List.of());
		BlockEntry stone = new BlockEntry(
		    1, "stone", 0.6F, 1.0F, 1.0F, List.of(new ShapeBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)));
		return new WorldSnapshot(origin, originY, origin, size, size, size,
		    OutsidePolicy.REFUSING, List.of(air, stone), cells);
	}
}
