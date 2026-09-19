package com.nettarion.stride.simulator.server;

import com.nettarion.stride.simulator.HealthWrite;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.MovementPacket;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.SimulationState;
import com.nettarion.stride.simulator.Simulator;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.tick.ClientTick;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.BlockEntry;

class AuthorityPublicationTest {
	@Test
	void foodCrossesSprintThresholdAtDeliveryAndStarvationUsesAuthority() {
		PlayerState client = state();
		client.foodLevel = 7;
		ServerPlayerState server = ServerPlayerState.atBoundary(client);
		server.exhaustionLevel = 5;
		server.saturationLevel = 0;
		Simulator simulator = new Simulator();
		PlayerInput sprint = new PlayerInput(true, false, false, false, false, false, true, 0, 0);
		Simulator.Step step = simulator.advance(new SimulationState(client, server, 0), sprint, world());
		assertEquals(6, step.state().clientState().foodLevel);
		assertEquals(6, step.state().serverState().foodLevel);
		assertEquals(1, step.writes().stream().filter(HealthWrite.class ::isInstance).count());
		PlayerState hungry = step.state().clientState();
		new ClientTick().tick(hungry, sprint, world());
		assertFalse(hungry.sprinting);

		server = ServerPlayerState.atBoundary(state());
		server.foodLevel = 0;
		server.tickTimer = 79;
		server.health = 2;
		step = simulator.advance(new SimulationState(state(), server, 0), PlayerInput.idle(0, 0), world());
		assertEquals(1, step.state().serverState().health);
		assertEquals(HurtCause.STARVE, step.damage().getFirst().event().cause());
		assertEquals(0, step.state().clientState().foodLevel);
	}

	@Test
	void correctionAcknowledgementUsesItsIdentifierAndRetainedPosition() {
		ServerPlayerState server = ServerPlayerState.atBoundary(state());
		PlayerState target = state();
		target.placeAt(20.5, 0, 0.5);
		ServerTick tick = new ServerTick();
		assertInstanceOf(ServerTick.Corrected.class,
		    tick.transact(target, MovementPacket.POS, server, PlayerInput.idle(0, 0), world(), false));
		int id = server.awaitingTeleport;
		tick.handleAcceptTeleportPacket(server, id + 1);
		assertTrue(server.correctionPending);
		server.placeAt(2, 3, 4);
		tick.handleAcceptTeleportPacket(server, id);
		assertFalse(server.correctionPending);
		assertEquals(0.5, server.x);
		assertEquals(0, server.y);
		assertEquals(0.5, server.z);
		assertThrows(UnimplementedMechanicException.class, () -> tick.handleAcceptTeleportPacket(server, id));
	}

	@Test
	void impulseGraceExpiresWithoutErasingFallProtectionUntilReset() {
		ServerPlayerState server = ServerPlayerState.atBoundary(state());
		server.placeAt(0.5, 5, 0.5);
		server.onGround = false;
		server.setIgnoreFallDamageFromCurrentImpulse(true, 0.5, 7, 0.5);
		server.currentImpulseContextResetGraceTime = 1;
		new ServerTick().transact(server.copy(), MovementPacket.NONE, server, PlayerInput.idle(0, 0), world(), false);
		assertEquals(0, server.currentImpulseContextResetGraceTime);
		assertTrue(server.currentImpulseImpactPosPresent);
		Survival survival = new Survival();
		survival.begin(server);
		survival.causeFallDamage(20, 1, HurtCause.FALL);
		assertEquals(20, server.health, "the declared impact height caps the damaging fall");
		assertFalse(server.currentImpulseImpactPosPresent);
	}

	private static PlayerState state() {
		PlayerState state = new PlayerState();
		state.placeAt(0.5, 0, 0.5);
		state.onGround = true;
		state.mainSupportingBlockPosPresent = true;
		state.mainSupportingBlockPosY = -1;
		return state;
	}

	private static SnapshotView world() {
		var builder = WorldSnapshot.builder(OutsidePolicy.REFUSING, -4, -2, -4, 32, 16, 8)
		                  .palette(BlockEntry.builder(0, "minecraft:air").build(),
		                      BlockEntry.builder(1, "minecraft:stone").fullCube().build());
		for (int x = -4; x < 28; x++)
			for (int z = -4; z < 4; z++)
				builder.set(x, -1, z, 1);
		return SnapshotView.compile(builder.build());
	}
}
