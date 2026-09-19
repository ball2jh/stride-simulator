package com.nettarion.stride.simulator.server;

import static com.nettarion.stride.simulator.server.ServerTestWorlds.IDLE;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.airborneAt;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.emptyWorld;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.floorWorld;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.packet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.Publisher;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.world.SnapshotView;

import org.junit.jupiter.api.Test;

/** The floating latch an accepted packet computes and the connection tick's counter and kick. */
final class FloatingLatchTest {
	@Test
	void floatingNeedsAirAroundAndTheCounterAdvancesEveryConnectionTick() {
		SnapshotView world = emptyWorld();
		PlayerState before = airborneAt(0.5, 10.0, 0.5);
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		PlayerState after = before.copy();
		after.placeAt(0.5, 10.0 - 0.03125, 0.5);

		ServerTick tick = new ServerTick();
		ServerTick.Accepted accepted = assertInstanceOf(
		    ServerTick.Accepted.class, tick.transact(after, packet(before, after), server, IDLE, world));
		assertTrue(accepted.clientIsFloating(), "-1/32 requested is inclusive");

		PlayerState falling = before.copy();
		falling.placeAt(0.5, Math.nextDown(10.0 - 0.03125), 0.5);
		ServerPlayerState fallingServer = ServerPlayerState.atBoundary(before);
		assertFalse(assertInstanceOf(
		    ServerTick.Accepted.class, tick.transact(falling, packet(before, falling), fallingServer, IDLE, world))
		        .clientIsFloating());

		// The connection tick of the server tick that accepted the packet is
		// the first floating tick. Standing still: the publisher is silent
		// except for the reminder's forced Pos every twentieth tick, which is
		// accepted and floating again.
		assertEquals(1, server.aboveGroundTickCount);
		Publisher publisher = Publisher.atBoundary(after);
		for (int connectionTick = 2; connectionTick <= 80; connectionTick++) {
			tick.transact(after, publisher.publish(after), server, IDLE, world);
			assertEquals(connectionTick, server.aboveGroundTickCount);
		}
		// The eighty-first floating connection tick is the kick.
		PendingServerWriteException kick = assertThrows(PendingServerWriteException.class,
		    () -> tick.transact(after, publisher.publish(after), server, IDLE, world));
		assertEquals(RefusalCause.UNMODELED_SESSION_END, kick.cause());
		assertTrue(server.clientIsFloating);
	}

	@Test
	void anUndecidableFloatingLatchIsHeldFalseAndOnlyTheKickIsRefused() {
		// The player's box stays inside the region (x from -3.98) but the air
		// box inflated by 0.0625 reaches x=-4.0425, one cell past its edge, so
		// floating cannot be decided; the latch stays false, the counter bounds
		// it from above, and only the eighty-first tick refuses, as undetermined
		// rather than as a kick.
		SnapshotView world = emptyWorld();
		PlayerState before = airborneAt(-3.68, 10.0, 0.5);
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		PlayerState after = before.copy();
		after.placeAt(-3.68, 10.0 - 0.03125, 0.5);

		ServerTick tick = new ServerTick();
		ServerTick.Accepted accepted = assertInstanceOf(
		    ServerTick.Accepted.class, tick.transact(after, packet(before, after), server, IDLE, world));
		assertFalse(accepted.clientIsFloating());
		assertTrue(accepted.floatingUnknown());
		assertTrue(server.floatingUnknown);

		assertEquals(1, server.aboveGroundTickCount);
		Publisher publisher = Publisher.atBoundary(after);
		for (int connectionTick = 2; connectionTick <= 80; connectionTick++) {
			tick.transact(after, publisher.publish(after), server, IDLE, world);
			assertEquals(connectionTick, server.aboveGroundTickCount);
		}
		PendingServerWriteException refusal = assertThrows(PendingServerWriteException.class,
		    () -> tick.transact(after, publisher.publish(after), server, IDLE, world));
		assertEquals(RefusalCause.UNMODELED_SESSION_END, refusal.cause());
		assertFalse(server.clientIsFloating, "the latch was never decided true");
		assertTrue(server.floatingUnknown, "so the refusal is the undetermined kick, not the kick");

		// A known non-air cell decides NOT_AIR whatever else is unknown.
		// Feet 0.3 above the floor: the box extended 0.55 down reaches the stone.
		PlayerState nearStone = airborneAt(3.9, 0.3, 0.5);
		ServerPlayerState nearStoneServer = ServerPlayerState.atBoundary(nearStone);
		PlayerState nearStoneAfter = nearStone.copy();
		nearStoneAfter.placeAt(3.9, 0.3 - 0.03125, 0.5);
		ServerTick.Accepted decided = assertInstanceOf(ServerTick.Accepted.class,
		    tick.transact(nearStoneAfter, packet(nearStone, nearStoneAfter), nearStoneServer, IDLE, floorWorld()));
		assertFalse(decided.clientIsFloating());
		assertFalse(decided.floatingUnknown(), "the floor under the box is known not to be air");
	}
}
