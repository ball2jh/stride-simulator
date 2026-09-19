package com.nettarion.stride.simulator.server;

import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.MovementPacket;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.Publisher;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.StateDigest;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.tick.AiStep;
import com.nettarion.stride.simulator.tick.PlayerTick;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.FluidKind;
import com.nettarion.stride.simulator.world.FluidEntry;

/** Regression cases for server control authority and the first food-driven publication. */
class ServerControlAndFoodTest {
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);

	@Test
	void clientTickEndRetainsAcceptedMovementAndClearsOmittedMovement() {
		var server = new ServerPlayerState();
		server.lastKnownClientMovementX = .25;
		server.lastKnownClientMovementY = -.125;
		server.lastKnownClientMovementZ = .5;
		ServerMovementListener.handleClientTickEnd(server, true);
		assertEquals(.25, server.lastKnownClientMovementX);
		assertEquals(-.125, server.lastKnownClientMovementY);
		assertEquals(.5, server.lastKnownClientMovementZ);
		ServerMovementListener.handleClientTickEnd(server, false);
		assertEquals(0.0, server.lastKnownClientMovementX);
		assertEquals(0.0, server.lastKnownClientMovementY);
		assertEquals(0.0, server.lastKnownClientMovementZ);
	}

	@Test
	void emptyMovementStorageDoesNotRetainPriorSegmentIdentity() {
		var server = new ServerPlayerState();
		server.movementThisTickPresent = true;
		server.movementAxisDependent = true;
		server.movementFromX = 1;
		server.movementToY = 2;
		server.movementRequestedZ = .125;
		server.clearMovementThisTick();
		assertTrue(ServerPlayerState.rawEquals(new ServerPlayerState(), server));
	}

	@Test
	void inputFreeServerTickMatchesConstructedInputAndKeepsItsOwnRotationBits() {
		for (float rotation : new float[] {0.0F, -0.0F, 73.25F, -180.0F, 720.0F}) {
			for (boolean water : new boolean[] {false, true}) {
				ServerPlayerState actual = new ServerPlayerState();
				actual.placeAt(0.5, 1.0, 0.5);
				actual.yRot = rotation;
				// The listener installs every pitch clamped, so the server's copy never holds one outside [-90, 90].
				actual.xRot = Mth.clamp(-rotation, -90.0F, 90.0F);
				actual.shiftKeyDown = true;
				actual.setSprinting(true);
				actual.deltaMovementX = 0.1;
				ServerPlayerState expected = actual.copy();
				SnapshotView view = world(water);
				PlayerTick.tick(expected, PlayerInput.idle(expected.yRot, expected.xRot), view, scratch(expected));
				PlayerTick.serverTick(actual, view, scratch(actual));
				// doTick installs no rotation, so the copy keeps its own bits, signed
				// zero included; the action boundary canonicalizes -0.0 but the tick's
				// reads cannot tell the zeros apart, so every other field agrees.
				assertEquals(Float.floatToRawIntBits(rotation), Float.floatToRawIntBits(actual.yRot));
				assertEquals(
				    Float.floatToRawIntBits(Mth.clamp(-rotation, -90.0F, 90.0F)), Float.floatToRawIntBits(actual.xRot));
				expected.yRot = actual.yRot;
				expected.xRot = actual.xRot;
				assertEquals(StateDigest.server(expected), StateDigest.server(actual));
			}
		}
	}

	@Test
	void invalidServerStateRefusesBeforeCanonicalizingRotation() {
		ServerPlayerState server = new ServerPlayerState();
		server.yRot = -0.0F;
		server.xRot = -0.0F;
		server.autoJumpEnabled = true;
		assertThrows(
		    UnimplementedMechanicException.class, () -> PlayerTick.serverTick(server, world(false), scratch(server)));
		assertEquals(Float.floatToRawIntBits(-0.0F), Float.floatToRawIntBits(server.yRot));
		assertEquals(Float.floatToRawIntBits(-0.0F), Float.floatToRawIntBits(server.xRot));
	}

	@Test
	void inputAndSprintCommandsArriveWithoutAMovementPacket() {
		ServerPlayerState server = new ServerPlayerState();
		server.placeAt(0.5, 0.0, 0.5);
		server.onGround = true;
		ServerTick tick = new ServerTick();
		SnapshotView world = world(false);
		for (boolean pressed : new boolean[] {true, false}) {
			PlayerState client = server.copy();
			client.setSprinting(pressed);
			PlayerInput action = new PlayerInput(false, false, false, false, false, pressed, pressed, 0, 0);
			Publisher publisher = Publisher.atBoundary(client);
			assertEquals(MovementPacket.NONE, publisher.publish(client));
			assertInstanceOf(
			    ServerTick.Unpublished.class, tick.transact(client, MovementPacket.NONE, server, action, world, false));
			assertEquals(pressed, server.shiftKeyDown);
			assertEquals(pressed, server.sprinting);
			// The control packet arrives after this tick's server movement.
			// Its shift flag affects the following connection tick's pose.
			tick.transact(client, MovementPacket.NONE, server, action, world, false);
			assertEquals(pressed ? PlayerState.Pose.CROUCHING : PlayerState.Pose.STANDING, server.pose);
		}
	}

	@Test
	void aZeroChargeOnAPlayerWhoMayFlyRefusesOnlyWhenTheAbilityWouldShow() {
		// Player.causeFoodExhaustion adds nothing for a walking step unless the
		// level is negative zero, which the addition alone would sign-flip.
		ServerPlayerState flier = new ServerPlayerState();
		flier.mayfly = true;
		flier.onGround = true;
		flier.exhaustionLevel = 0.3F;
		ServerPlayerTick.checkMovementStatistics(flier, 0.1, 0, 0, world(false));
		assertEquals(0.3F, flier.exhaustionLevel);
		flier.setSprinting(true);
		assertThrows(PendingServerWriteException.class,
		    () -> ServerPlayerTick.checkMovementStatistics(flier, 0.1, 0, 0, world(false)));
		flier.setSprinting(false);
		flier.exhaustionLevel = -0.0F;
		assertThrows(PendingServerWriteException.class,
		    () -> ServerPlayerTick.checkMovementStatistics(flier, 0.1, 0, 0, world(false)));
	}

	@Test
	void walkingExhaustionPreservesVanillasZeroAddition() {
		ServerPlayerState server = new ServerPlayerState();
		server.onGround = true;
		server.exhaustionLevel = -0.0F;
		ServerPlayerTick.checkMovementStatistics(server, 0.1, 0, 0, world(false));
		assertEquals(Float.floatToRawIntBits(0.0F), Float.floatToRawIntBits(server.exhaustionLevel));
	}

	@Test
	void sprintSwimmingUsesServerSprintDragWithoutAKeyboardDecision() {
		ServerPlayerState server = new ServerPlayerState();
		server.placeAt(0.5, 1.0, 0.5);
		server.setSprinting(true);
		server.swimming = true;
		server.eyeInWater = true;
		server.waterHeight = 0.6;
		server.deltaMovementX = 0.1;
		PlayerTick.serverTick(server, world(true), scratch(server));
		assertTrue(server.sprinting);
		assertEquals(
		    Double.doubleToRawLongBits(0.1 * (double) 0.9F), Double.doubleToRawLongBits(server.deltaMovementX));
	}

	@Test
	void serverDoesNotQueryLocalSuffocationEscapeOrInstallKeyboardInput() {
		ServerPlayerState server = new ServerPlayerState();
		server.placeAt(0.5, 1.0, 0.5);
		server.setSprinting(true);
		server.sprintTriggerTime = 3;
		server.xxa = 0.25F;
		server.zza = -0.5F;
		server.deltaMovementX = 0.01;
		// Any world query would fail: only LocalPlayer's prefix needs one here.
		AiStep.run(server, new PlayerInput(true, false, false, false, true, true, true, 0, 0), null, scratch(server));
		assertTrue(server.sprinting);
		assertEquals(3, server.sprintTriggerTime);
		assertEquals(0.25F * 0.98F, server.xxa);
		assertEquals(-0.5F * 0.98F, server.zza);
		assertEquals(0.01, server.deltaMovementX);
		assertFalse(server.jumping);
		assertEquals(0, server.inputKeyPresses);
	}

	@Test
	void constructedServerDoesNotInheritClientKeyboardAcceleration() {
		PlayerState client = new PlayerState();
		client.xxa = 0.98F;
		client.zza = 0.98F;
		client.jumping = true;
		client.crouching = true;
		client.jumpTriggerTime = 6;
		client.noJumpDelay = 10;
		ServerPlayerState server = ServerPlayerState.atBoundary(client);
		assertEquals(0.0F, server.xxa);
		assertEquals(0.0F, server.zza);
		assertFalse(server.jumping);
		assertFalse(server.crouching);
		assertEquals(0, server.jumpTriggerTime);
		assertEquals(0, server.noJumpDelay);
	}

	@Test
	void injuredDefaultServerHealsOnTheTenthFoodTickEvenWithoutPackets() {
		ServerPlayerState server = new ServerPlayerState();
		server.placeAt(0.5, 0.0, 0.5);
		server.onGround = true;
		server.health = 19.0F;
		ServerTick tick = new ServerTick();
		SnapshotView world = world(false);
		for (int i = 0; i < 9; i++) {
			tick.transact(server.copy(), MovementPacket.NONE, server, IDLE, world, false);
		}
		assertEquals(9, server.tickTimer);
		tick.transact(server.copy(), MovementPacket.NONE, server, IDLE, world, false);
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
		for (int i = 0; i < 79; i++)
			FoodData.tick(server);
		FoodData.tick(server);
		assertEquals(20, server.health);
		assertEquals(6, server.exhaustionLevel);
		server.tickTimer = 79;
		server.health = 20;
		FoodData.tick(server);
		assertEquals(0, server.tickTimer);
	}

	@Test
	void exhaustionConsumesOneUnitOnlyAboveFourAndPermitsSupportedFoodPublications() {
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
		SnapshotView world = world(false);
		new ServerTick().transact(server.copy(), MovementPacket.NONE, server, IDLE, world, true);
		assertEquals(0, server.tickTimer);
	}

	@Test
	void jumpMovementAndDamageExhaustionUseTheirDistinctVanillaAmounts() {
		ServerPlayerState server = new ServerPlayerState();
		ServerPlayerTick.jumpFromGround(server, world(false));
		assertEquals(0.05F, server.exhaustionLevel);
		server.setSprinting(true);
		ServerPlayerTick.jumpFromGround(server, world(false));
		assertEquals(0.05F + 0.2F, server.exhaustionLevel);
		server.exhaustionLevel = 0;
		server.onGround = true;
		ServerPlayerTick.checkMovementStatistics(server, 0.126, 0, 0, world(false));
		assertEquals(0.1F * 13 * 0.01F, server.exhaustionLevel);
		server.exhaustionLevel = 0;
		server.swimming = true;
		ServerPlayerTick.checkMovementStatistics(server, 0, 0.126, 0, world(true));
		assertEquals(0.01F * 13 * 0.01F, server.exhaustionLevel);
		server.exhaustionLevel = 0;
		server.absorption = 1;
		Survival survival = new Survival();
		survival.begin(server);
		survival.hurtServer(HurtCause.CACTUS, 1);
		assertEquals(0.0F, server.exhaustionLevel, "absorbed hits cost no food");
		survival.hurtServer(HurtCause.CACTUS, 1);
		assertEquals(0.0F, server.exhaustionLevel, "cooldown-rejected hits cost no food");
		survival.hurtServer(HurtCause.LAVA, 4);
		assertEquals(0.1F, server.exhaustionLevel, "a partial health hit costs the source amount");
		PlayerTick.causeFoodExhaustion(server, 100);
		assertEquals(40.0F, server.exhaustionLevel);
	}

	@Test
	void foodFactsSurviveCopyAndParticipateInEqualityDigestAndValidation() {
		ServerPlayerState server = new ServerPlayerState();
		server.foodLevel = 18;
		server.saturationLevel = 2;
		server.exhaustionLevel = 3;
		server.tickTimer = 42;
		server.naturalRegeneration = false;
		ServerPlayerState copy = server.copy();
		assertTrue(ServerPlayerState.rawEquals(server, copy));
		assertEquals(StateDigest.server(server), StateDigest.server(copy));
		copy.tickTimer++;
		assertFalse(ServerPlayerState.rawEquals(server, copy));
		assertNotEquals(StateDigest.server(server), StateDigest.server(copy));
		copy.exhaustionLevel = Float.NaN;
		assertThrows(IllegalStateException.class, copy::requireValid);
		FoodData.tick(server);
		assertEquals(0, server.tickTimer, "disabled regeneration clears its timer");
	}

	private static Scratch scratch(final ServerPlayerState server) {
		TickAuthority authority = TickAuthority.server();
		authority.begin(server);
		return new Scratch(authority);
	}

	private static SnapshotView world(final boolean water) {
		WorldSnapshot.Builder builder =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, -8, -3, -8, 16, 12, 16)
		        .palette(BlockEntry.builder(0, "minecraft:air").build(),
		            BlockEntry.builder(1, "minecraft:stone")
		                .boxes(ShapeBox.FULL_CUBE)
		                .build())
		        .fluidPalette(FluidEntry.EMPTY,
		            new FluidEntry(
		                1, "minecraft:water", FluidKind.WATER, 1, 0, 0, 0, true));
		for (int x = -8; x < 8; x++) {
			for (int z = -8; z < 8; z++) {
				builder.set(x, -1, z, 1);
				if (water)
					for (int y = 0; y < 4; y++)
						builder.setFluid(x, y, z, 1);
			}
		}
		return SnapshotView.compile(builder.build());
	}
}
