package com.nettarion.stride.simulator.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.MovementPacket;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.Publisher;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;

import org.junit.jupiter.api.Test;

/** The listener's non-movement packets: the end-of-tick marker, input, sprint command and teleport acknowledgement. */
final class ServerCommandTest {
	@Test
	void clientTickEndRetainsAcceptedMovementAndClearsOmittedMovement() {
		ServerPlayerState server = new ServerPlayerState();
		server.lastKnownClientMovementX = 0.25;
		server.lastKnownClientMovementY = -0.125;
		server.lastKnownClientMovementZ = 0.5;
		ServerMovementListener.handleClientTickEnd(server, true);
		assertEquals(0.25, server.lastKnownClientMovementX);
		assertEquals(-0.125, server.lastKnownClientMovementY);
		assertEquals(0.5, server.lastKnownClientMovementZ);
		ServerMovementListener.handleClientTickEnd(server, false);
		assertEquals(0.0, server.lastKnownClientMovementX);
		assertEquals(0.0, server.lastKnownClientMovementY);
		assertEquals(0.0, server.lastKnownClientMovementZ);
	}

	@Test
	void emptyMovementStorageDoesNotRetainPriorSegmentIdentity() {
		ServerPlayerState server = new ServerPlayerState();
		server.movementThisTickPresent = true;
		server.movementAxisDependent = true;
		server.movementFromX = 1;
		server.movementToY = 2;
		server.movementRequestedZ = 0.125;
		server.clearMovementThisTick();
		assertTrue(ServerPlayerState.rawEquals(new ServerPlayerState(), server));
	}

	@Test
	void inputAndSprintCommandsArriveWithoutAMovementPacket() {
		ServerPlayerState server = new ServerPlayerState();
		server.placeAt(0.5, 0.0, 0.5);
		server.onGround = true;
		ServerTick tick = new ServerTick();
		SnapshotView world = world();
		for (boolean pressed : new boolean[] {true, false}) {
			PlayerState client = server.copy();
			client.setSprinting(pressed);
			PlayerInput action = pressed ? PlayerInput.of(0.0F, 0.0F, PlayerInput.Key.SNEAK, PlayerInput.Key.SPRINT)
			                             : PlayerInput.idle(0.0F, 0.0F);
			Publisher publisher = Publisher.atBoundary(client);
			assertEquals(MovementPacket.NONE, publisher.publish(client));
			assertInstanceOf(
			    ServerTick.Unpublished.class, tick.transact(client, MovementPacket.NONE, server, action, world));
			assertEquals(pressed, server.shiftKeyDown);
			assertEquals(pressed, server.sprinting);
			// The control packet arrives after this tick's server movement.
			// Its shift flag affects the following connection tick's pose.
			tick.transact(client, MovementPacket.NONE, server, action, world);
			assertEquals(pressed ? PlayerState.Pose.CROUCHING : PlayerState.Pose.STANDING, server.pose);
		}
	}

	@Test
	void correctionAcknowledgementUsesItsIdentifierAndRetainedPosition() {
		PlayerState start = new PlayerState();
		start.placeAt(0.5, 0, 0.5);
		start.onGround = true;
		ServerPlayerState server = ServerPlayerState.atBoundary(start);
		PlayerState target = start.copy();
		target.placeAt(20.5, 0, 0.5);
		ServerTick tick = new ServerTick();
		assertInstanceOf(ServerTick.Corrected.class,
		    tick.transact(target, MovementPacket.POS, server, PlayerInput.idle(0, 0), world()));
		int id = server.awaitingTeleport;
		tick.handleAcceptTeleportPacket(server, id + 1);
		assertTrue(server.correctionPending, "another id is ignored");
		server.placeAt(2, 3, 4);
		tick.handleAcceptTeleportPacket(server, id);
		assertFalse(server.correctionPending);
		assertEquals(0.5, server.x);
		assertEquals(0, server.y);
		assertEquals(0.5, server.z);
		UnimplementedMechanicException disconnect =
		    assertThrows(UnimplementedMechanicException.class, () -> tick.handleAcceptTeleportPacket(server, id));
		assertEquals(RefusalCause.UNMODELED_SESSION_END, disconnect.cause());
	}

	private static SnapshotView world() {
		WorldSnapshot.Builder builder = WorldSnapshot.builder(OutsidePolicy.REFUSING, -4, -2, -4, 32, 16, 8)
		                                    .palette(BlockEntry.builder(0, "minecraft:air").build(),
		                                        BlockEntry.builder(1, "minecraft:stone").fullCube().build());
		for (int x = -4; x < 28; x++) {
			for (int z = -4; z < 4; z++) {
				builder.set(x, -1, z, 1);
			}
		}
		return SnapshotView.compile(builder.build());
	}
}
