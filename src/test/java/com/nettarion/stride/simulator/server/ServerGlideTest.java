package com.nettarion.stride.simulator.server;

import static com.nettarion.stride.simulator.server.ServerTestWorlds.IDLE;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.airborneAt;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.assertRaw;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.emptyWorld;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.floorWorld;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.gliding;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.packet;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.wallWorld;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.SharedFlag;
import com.nettarion.stride.simulator.EntityDataWrite;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.MovementPacket;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.ServerPlayerState;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The server's side of a glide: the start command, its own glide travel, and what it refuses. */
final class ServerGlideTest {
	@Test
	void startFallFlyingOnAGlidingCopyEndsTheGlide() {
		// Player.tryToStartFallFlying refuses while a glide is under way, so
		// handlePlayerCommand's START_FALL_FLYING falls through to
		// stopFallFlying's pulse, which is a dirty flags entry.
		ServerPlayerState gliding = ServerPlayerState.atBoundary(gliding(0.5, 10.0, 0.5, 1.0));
		gliding.entityDataDirty = 0;
		new ServerTick().handleStartFallFlying(gliding);
		assertFalse(gliding.fallFlying);
		assertEquals(EntityDataWrite.FLAGS, gliding.entityDataDirty & EntityDataWrite.FLAGS);

		ServerPlayerState airborne = ServerPlayerState.atBoundary(airborneAt(0.5, 10.0, 0.5));
		airborne.gliderUsable = true;
		new ServerTick().handleStartFallFlying(airborne);
		assertTrue(airborne.fallFlying, "a copy that may glide starts");
	}

	@Test
	void theServersGlideTravelIntoAWallHurtsAtTheConnectionTick() {
		ServerPlayerState server = ServerPlayerState.atBoundary(gliding(-1.0, 0.5, 0.5, 1.0));
		PlayerState client = server.copy();

		ServerTick.Transaction transaction =
		    new ServerTick().transact(client, MovementPacket.NONE, server, IDLE, wallWorld());

		assertInstanceOf(ServerTick.Unpublished.class, transaction);
		assertEquals(Optional.of(HurtCause.FLY_INTO_WALL), transaction.hurt(),
		    "handleFallFlyingCollisions: (1.0 - 0.0) * 10 - 3 > 0 on a horizontal collision");
		assertTrue(server.horizontalCollision);
		assertEquals(
		    0.0, Math.abs(server.deltaMovementX), "the wall stopped the server's copy, at a zero of either sign");
		assertRaw(-1.0, server.x, "the connection tick restores the anchor");
		assertTrue(server.fallFlying, "the hit does not end the glide");
	}

	@Test
	void aGlideThatShedsTooLittleSpeedDoesNotHurt() {
		// Only 0.2 of speed is lost against a wall reached at a crawl.
		ServerPlayerState server = ServerPlayerState.atBoundary(gliding(-0.35, 0.5, 0.5, 0.25));
		PlayerState client = server.copy();

		ServerTick.Transaction transaction =
		    new ServerTick().transact(client, MovementPacket.NONE, server, IDLE, wallWorld());

		assertTrue(server.horizontalCollision);
		assertEquals(Optional.empty(), transaction.hurt(), "(0.25 - 0.0) * 10 - 3 is not positive");
	}

	@Test
	void anAttachedRocketRefusesTheFirstInputInWhichItWouldWrite() {
		ServerPlayerState server = ServerPlayerState.atBoundary(gliding(-3.0, 3.0, 0.5, 0.5));
		PlayerState client = server.copy();
		server.attachedRockets = 1;

		PendingServerWriteException refusal = assertThrows(PendingServerWriteException.class,
		    () -> new ServerTick().transact(client, MovementPacket.NONE, server, IDLE, emptyWorld()));
		assertEquals(RefusalCause.UNMODELED_SCHEDULE, refusal.cause());

		PlayerState walking = airborneAt(0.5, 0.0, 0.5);
		walking.onGround = true;
		ServerPlayerState walkingServer = ServerPlayerState.atBoundary(walking);
		walkingServer.attachedRockets = 1;
		assertEquals(Optional.empty(),
		    new ServerTick().transact(walking, packet(walking, walking), walkingServer, IDLE, floorWorld()).hurt(),
		    "a rocket attached to a player who is not gliding writes nothing");

		// A connection tick on its own has no client copy to judge, so any
		// attached rocket refuses there.
		assertEquals(RefusalCause.UNMODELED_SCHEDULE,
		    assertThrows(
		        PendingServerWriteException.class, () -> new ServerTick().tickConnection(walkingServer, floorWorld()))
		        .cause());
	}

	@Test
	void theServerStoppingAGlideClearsTheFlagForTheNextSampleAndTravelsInAir() {
		// LivingEntity.updateFallFlying on a copy that cannot glide clears the
		// shared flag and returns, so LivingEntity.travel selects the air in
		// the same tick; the dirty flag waits for the next tracker sample.
		ServerPlayerState server = ServerPlayerState.atBoundary(gliding(0.5, 0.0, 0.5, 0.5));
		server.onGround = true;
		PlayerState client = server.copy();

		assertEquals(Optional.empty(),
		    new ServerTick().transact(client, MovementPacket.NONE, server, IDLE, floorWorld()).hurt());
		assertFalse(server.fallFlying, "the server's copy stops gliding in its own tick");
		assertTrue((server.entityDataDirty & EntityDataWrite.FLAGS) != 0,
		    "the cleared flag is dirty for the next tracker sample");
		assertEquals(0, server.fallFlyTicks, "LivingEntity.tick resets the glide count");
		assertRaw(0.5 * (double) (0.6F * 0.91F), server.deltaMovementX,
		    "the same tick's travel is travelInAir on the ground");
	}

	@Test
	void theTwentiethGlideTickRefusesBecauseTheGliderWriteIsUncaptured() {
		ServerPlayerState server = ServerPlayerState.atBoundary(gliding(-3.0, 3.0, 0.5, 0.5));
		server.fallFlyTicks = 19;
		PlayerState client = server.copy();

		PendingServerWriteException refusal = assertThrows(PendingServerWriteException.class,
		    () -> new ServerTick().transact(client, MovementPacket.NONE, server, IDLE, emptyWorld()));
		assertEquals(RefusalCause.PENDING_ABILITY, refusal.cause());

		ServerPlayerState tenth = ServerPlayerState.atBoundary(gliding(-3.0, 3.0, 0.5, 0.5));
		tenth.fallFlyTicks = 9;
		assertEquals(Optional.empty(),
		    new ServerTick().transact(tenth.copy(), MovementPacket.NONE, tenth, IDLE, emptyWorld()).hurt(),
		    "the tenth tick emits only the glide event");
	}

	@Test
	void theServersCopyAdoptsAClientLaunchItCanContinueAndRefusesOneItCannot() {
		PlayerState before = airborneAt(0.5, 3.0, 0.5);
		before.gliderUsable = true;
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		PlayerState after = before.copy();
		after.placeAt(0.5, 2.9, 0.5);
		after.fallFlying = true;

		new ServerTick().transact(after, packet(before, after), server, IDLE, emptyWorld());
		assertTrue(server.fallFlying, "START_FALL_FLYING precedes the movement packet");

		// Player.tryToStartFallFlying fails without a glider, so the handler
		// runs stopFallFlying: the flag pulses on and off, which dirties it for
		// the tracker sample that echoes the stop to the client.
		ServerPlayerState bare = ServerPlayerState.atBoundary(before);
		bare.gliderUsable = false;
		new ServerTick().transact(after, packet(before, after), bare, IDLE, emptyWorld());
		assertFalse(bare.fallFlying, "the server's copy does not glide");
		assertTrue((bare.entityDataDirty & EntityDataWrite.FLAGS) != 0,
		    "stopFallFlying's pulse dirties the shared flags for the echo");

		// The same command on a copy that already glides is a stop as well.
		ServerPlayerState already = ServerPlayerState.atBoundary(before);
		already.setSharedFlag(SharedFlag.FALL_FLYING, true);
		already.entityDataDirty = 0;
		new ServerTick().handleStartFallFlying(already);
		assertFalse(already.fallFlying, "tryToStartFallFlying refuses an already gliding copy");
		assertTrue((already.entityDataDirty & EntityDataWrite.FLAGS) != 0);
	}
}
