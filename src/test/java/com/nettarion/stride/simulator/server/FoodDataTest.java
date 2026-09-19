package com.nettarion.stride.simulator.server;

import static com.nettarion.stride.simulator.server.ServerTestWorlds.IDLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.HealthWrite;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.MovementPacket;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.SimulationState;
import com.nettarion.stride.simulator.Simulator;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.tick.ClientTick;
import com.nettarion.stride.simulator.tick.PlayerTick;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

/** Vanilla's {@code FoodData.tick}: exhaustion, regeneration, starvation by difficulty, and the health packet. */
final class FoodDataTest {
	@Test
	void injuredDefaultServerHealsOnTheTenthFoodTickEvenWithoutPackets() {
		ServerPlayerState server = standingServer();
		server.health = 19.0F;
		ServerTick tick = new ServerTick();
		SnapshotView world = world();
		for (int i = 0; i < 9; i++) {
			tick.transact(server.copy(), MovementPacket.NONE, server, IDLE, world);
		}
		assertEquals(9, server.tickTimer);
		tick.transact(server.copy(), MovementPacket.NONE, server, IDLE, world);
		assertEquals(19.0F + 5.0F / 6.0F, server.health);
		assertEquals(5.0F, server.exhaustionLevel);
		assertEquals(0, server.tickTimer);
	}

	@Test
	void unsaturatedRegenerationUsesEightyTicksAndFullHealthResetsTheTimer() {
		ServerPlayerState server = new ServerPlayerState();
		server.saturationLevel = 0;
		server.foodLevel = 18;
		server.health = 19;
		for (int i = 0; i < 79; i++) {
			FoodData.tick(server);
		}
		FoodData.tick(server);
		assertEquals(20, server.health);
		assertEquals(6, server.exhaustionLevel);
		server.tickTimer = 79;
		server.health = 20;
		FoodData.tick(server);
		assertEquals(0, server.tickTimer);
	}

	@Test
	void exhaustionConsumesOneUnitOnlyAboveFour() {
		ServerPlayerState server = new ServerPlayerState();
		server.exhaustionLevel = 4.0F;
		FoodData.tick(server);
		assertEquals(5.0F, server.saturationLevel);
		PlayerTick.causeFoodExhaustion(server, 0.1F);
		float remainder = (4.0F + 0.1F) - 4.0F;
		FoodData.tick(server);
		assertEquals(remainder, server.exhaustionLevel);
		assertEquals(4.0F, server.saturationLevel);
		server.exhaustionLevel = 12;
		FoodData.tick(server);
		assertEquals(8.0F, server.exhaustionLevel, "one charge per tick, not a while loop");
		assertEquals(3.0F, server.saturationLevel);
		server.exhaustionLevel = 5;
		server.saturationLevel = 1;
		FoodData.tick(server);
		assertEquals(0, server.saturationLevel);
		server.exhaustionLevel = 5;
		server.saturationLevel = 0;
		FoodData.tick(server);
		assertEquals(19, server.foodLevel);
		server.exhaustionLevel = 0;
		server.foodLevel = 6;
		FoodData.tick(server);
		assertEquals(6, server.foodLevel);
	}

	@Test
	void lowFoodDoesNotSuppressTheConnectionTick() {
		ServerPlayerState server = new ServerPlayerState();
		server.foodLevel = 6;
		new ServerTick().transact(server.copy(), MovementPacket.NONE, server, IDLE, world());
		assertEquals(0, server.tickTimer);
	}

	@Test
	void disabledRegenerationClearsItsTimer() {
		ServerPlayerState server = new ServerPlayerState();
		server.foodLevel = 18;
		server.health = 19;
		server.tickTimer = 42;
		server.naturalRegeneration = false;
		FoodData.tick(server);
		assertEquals(0, server.tickTimer);
		assertEquals(19, server.health);
	}

	@Test
	void starvationStopsAtTenOnEasyAtOneOnNormalAndNeverOnHard() {
		// FoodData.tick: health > 10 || hard || (health > 1 && normal).
		assertEquals(10.0F, starved(ServerPlayerState.Difficulty.EASY, 10.0F), "easy stops at ten");
		assertEquals(10.0F, starved(ServerPlayerState.Difficulty.EASY, 11.0F), "easy hurts above ten");
		assertEquals(1.0F, starved(ServerPlayerState.Difficulty.NORMAL, 1.0F), "normal stops at one");
		assertEquals(1.0F, starved(ServerPlayerState.Difficulty.NORMAL, 2.0F), "normal hurts above one");
		assertEquals(1.0F, starved(ServerPlayerState.Difficulty.HARD, 2.0F), "hard hurts above one");
		UnimplementedMechanicException death = assertThrows(UnimplementedMechanicException.class,
		    () -> starved(ServerPlayerState.Difficulty.HARD, 1.0F), "hard starves to death, which refuses");
		assertEquals(RefusalCause.UNMODELED_SESSION_END, death.cause());
	}

	@Test
	void aStarvingTickWithoutABoundTransactionRefuses() {
		ServerPlayerState server = new ServerPlayerState();
		server.foodLevel = 0;
		server.tickTimer = 79;
		PendingServerWriteException refusal =
		    assertThrows(PendingServerWriteException.class, () -> FoodData.tick(server));
		assertEquals(RefusalCause.PENDING_SURVIVAL_FACT, refusal.cause());
	}

	@Test
	void anUndeclaredDifficultyRefusesAtAdmission() {
		ServerPlayerState server = new ServerPlayerState();
		server.difficulty = null;
		PendingServerWriteException refusal =
		    assertThrows(PendingServerWriteException.class, () -> FoodData.requireSupported(server));
		assertEquals(RefusalCause.PENDING_SURVIVAL_FACT, refusal.cause());
	}

	@Test
	void foodCrossesTheSprintThresholdAtDeliveryAndStarvationHurtsThroughTheTransaction() {
		PlayerState client = standing();
		client.foodLevel = 7;
		ServerPlayerState server = ServerPlayerState.atBoundary(client);
		server.exhaustionLevel = 5;
		server.saturationLevel = 0;
		Simulator simulator = new Simulator();
		PlayerInput sprint = PlayerInput.of(0.0F, 0.0F, PlayerInput.Key.FORWARD, PlayerInput.Key.SPRINT);
		Simulator.Step step = simulator.advance(new SimulationState(client, server, 0), sprint, world());
		assertEquals(6, step.state().clientState().foodLevel);
		assertEquals(6, step.state().serverState().foodLevel);
		long healthWrites = step.writes().stream().filter(write -> write instanceof HealthWrite).count();
		assertEquals(1, healthWrites);
		PlayerState hungry = step.state().clientState();
		new ClientTick().tick(hungry, sprint, world());
		assertFalse(hungry.sprinting);

		server = ServerPlayerState.atBoundary(standing());
		server.foodLevel = 0;
		server.tickTimer = 79;
		server.health = 2;
		step = simulator.advance(new SimulationState(standing(), server, 0), IDLE, world());
		assertEquals(1, step.state().serverState().health);
		assertEquals(HurtCause.STARVE, step.damage().getFirst().event().cause());
		assertEquals(0, step.state().clientState().foodLevel);
	}

	@Test
	void foodFactsSurviveCopyAndParticipateInValidation() {
		ServerPlayerState server = new ServerPlayerState();
		server.foodLevel = 18;
		server.saturationLevel = 2;
		server.exhaustionLevel = 3;
		server.tickTimer = 42;
		server.naturalRegeneration = false;
		ServerPlayerState copy = server.copy();
		assertTrue(ServerPlayerState.rawEquals(server, copy));
		copy.tickTimer++;
		assertFalse(ServerPlayerState.rawEquals(server, copy));
		copy.exhaustionLevel = Float.NaN;
		assertThrows(IllegalStateException.class, copy::requireValid);
	}

	/** The health after one starving food tick at {@code health}, on {@code difficulty}. */
	private static float starved(final ServerPlayerState.Difficulty difficulty, final float health) {
		ServerPlayerState server = new ServerPlayerState();
		server.difficulty = difficulty;
		server.foodLevel = 0;
		server.health = health;
		server.tickTimer = 79;
		Survival survival = new Survival();
		survival.begin(server);
		FoodData.tick(server, survival);
		assertEquals(0, server.tickTimer, "the starvation timer wraps whether or not it hurt");
		return server.health;
	}

	private static PlayerState standing() {
		PlayerState state = new PlayerState();
		state.placeAt(0.5, 0, 0.5);
		state.onGround = true;
		state.mainSupportingBlockPosPresent = true;
		state.mainSupportingBlockPosY = -1;
		return state;
	}

	private static ServerPlayerState standingServer() {
		ServerPlayerState server = new ServerPlayerState();
		server.placeAt(0.5, 0.0, 0.5);
		server.onGround = true;
		return server;
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
