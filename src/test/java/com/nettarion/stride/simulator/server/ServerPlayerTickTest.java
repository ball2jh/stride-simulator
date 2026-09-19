package com.nettarion.stride.simulator.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.StateDigest;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.tick.AiStep;
import com.nettarion.stride.simulator.tick.PlayerTick;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.FluidEntry;
import com.nettarion.stride.simulator.world.FluidKind;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;

import org.junit.jupiter.api.Test;

/** The server's own player tick: what it shares with the client tick, what it skips, and what it charges. */
final class ServerPlayerTickTest {
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
		PendingServerWriteException sprinting = assertThrows(PendingServerWriteException.class,
		    () -> ServerPlayerTick.checkMovementStatistics(flier, 0.1, 0, 0, world(false)));
		assertEquals(RefusalCause.PENDING_ABILITY, sprinting.cause());
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
		PlayerInput held = PlayerInput.of(
		    0.0F, 0.0F, PlayerInput.Key.FORWARD, PlayerInput.Key.JUMP, PlayerInput.Key.SNEAK, PlayerInput.Key.SPRINT);
		AiStep.run(server, held, null, scratch(server));
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
		ServerDamage damage = new ServerDamage();
		damage.begin(server);
		damage.hurtServer(HurtCause.CACTUS, 1);
		assertEquals(0.0F, server.exhaustionLevel, "absorbed hits cost no food");
		damage.hurtServer(HurtCause.CACTUS, 1);
		assertEquals(0.0F, server.exhaustionLevel, "cooldown-blocked hits cost no food");
		damage.hurtServer(HurtCause.LAVA, 4);
		assertEquals(0.1F, server.exhaustionLevel, "a partial health hit costs the source amount");
		PlayerTick.causeFoodExhaustion(server, 100);
		assertEquals(40.0F, server.exhaustionLevel);
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
		            BlockEntry.builder(1, "minecraft:stone").boxes(ShapeBox.FULL_CUBE).build())
		        .fluidPalette(
		            FluidEntry.EMPTY, new FluidEntry(1, "minecraft:water", FluidKind.WATER, 1, 0, 0, 0, true));
		for (int x = -8; x < 8; x++) {
			for (int z = -8; z < 8; z++) {
				builder.set(x, -1, z, 1);
				if (water) {
					for (int y = 0; y < 4; y++) {
						builder.setFluid(x, y, z, 1);
					}
				}
			}
		}
		return SnapshotView.compile(builder.build());
	}
}
