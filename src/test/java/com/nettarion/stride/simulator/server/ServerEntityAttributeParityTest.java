package com.nettarion.stride.simulator.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nettarion.stride.simulator.EntityDataWrite;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.ServerPlayerState;

import org.junit.jupiter.api.Test;

/**
 * {@code ServerEntity.sendChanges} sends attributes only inside the block guarded by the tracker's update interval
 * or dirty entity data, and a player's interval is two ticks: a movement-speed attribute that changed with nothing
 * else dirty is sent on the tracker's even ticks only, a parity the state does not carry.
 */
final class ServerEntityAttributeParityTest {
	@Test
	void anAttributeChangeWithNoDirtyEntityDataRefusesOnTheTrackersParity() {
		ServerPlayerState server = sampled();
		// Fully frozen and unchanged, but the block underfoot became air, so
		// tryAddFrost skipped the modifier removeFrost took away.
		server.frostSpeedTicks = 0;
		server.movementSpeedAttributeDirty = true;

		PendingServerWriteException refusal =
		    assertThrows(PendingServerWriteException.class, () -> new ServerTick().collectPublications(server));
		assertEquals(RefusalCause.PENDING_SERVER_RANDOM, refusal.cause());
	}

	@Test
	void aRepeatedModifierAndAnAttributeRidingDirtyDataArePublished() {
		// tryAddFrost re-adds an equal modifier every tick: dirty in the
		// attribute map, unobservable to the client, sent whenever the tracker
		// sends anything.
		ServerPlayerState repeated = sampled();
		repeated.movementSpeedAttributeDirty = true;
		ServerTick tick = new ServerTick();
		tick.collectPublications(repeated);
		EntityDataWrite write = tick.entityDataPublication(0, new PlayerState());
		assertNotNull(write);
		assertEquals(EntityDataWrite.MOVEMENT_SPEED, write.dirty());
		assertEquals(140, write.frostSpeedTicks());

		// Thawing changes the frozen count and the modifier together, and the
		// dirty count carries the attribute in the same sendDirtyEntityData.
		ServerPlayerState thawing = sampled();
		thawing.setTicksFrozen(138);
		thawing.frostSpeedTicks = 138;
		thawing.movementSpeedAttributeDirty = true;
		tick.collectPublications(thawing);
		write = tick.entityDataPublication(0, new PlayerState());
		assertNotNull(write);
		assertEquals(EntityDataWrite.FROZEN | EntityDataWrite.MOVEMENT_SPEED, write.dirty());
		assertEquals(138, write.frostSpeedTicks());
	}

	private static ServerPlayerState sampled() {
		PlayerState client = new PlayerState();
		client.placeAt(0.5, 0.0, 0.5);
		client.onGround = true;
		client.ticksFrozen = 140;
		client.frostSpeedTicks = 140;
		ServerPlayerState server = ServerPlayerState.atBoundary(client);
		server.entityDataDirty = 0;
		return server;
	}
}
