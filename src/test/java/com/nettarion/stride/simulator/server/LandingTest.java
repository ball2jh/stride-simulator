package com.nettarion.stride.simulator.server;

import static com.nettarion.stride.simulator.server.ServerTestWorlds.EXTRA;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.HALF_WIDTH;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.IDLE;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.STONE;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.airborneAt;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.assertRaw;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.floor;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.floorWorld;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.packet;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.region;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.view;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.wallWorld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.MovementPacket;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.FluidEntry;
import com.nettarion.stride.simulator.world.FluidKind;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.Suffocation;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * The accepted packet's landing: {@code Entity.checkFallDamage} through the landed block's {@code fallOn}, and the
 * wind-charge impulse context the landing and the contact bodies settle.
 */
final class LandingTest {
	@Test
	void aLandingHurtsFromAFullBlockPastTheSafeDistanceNotBefore() {
		// LivingEntity.calculateFallDamage: floor(fall + 1e-6 - 3) > 0, on the
		// fall the packet's own descent completes, in creative never.
		assertEquals(
		    Optional.empty(), landingHurt(3.4, 0.5, false), "3.9 blocks is under a full block past the safe distance");
		assertEquals(
		    Optional.of(HurtCause.FALL), landingHurt(3.5, 0.5, false), "4.0 blocks is the first damaging fall");
		assertEquals(Optional.empty(), landingHurt(3.5, 0.5, true),
		    "Player.causeFallDamage returns before hurting a player who may fly");
	}

	@Test
	void aDisabledFallDamageGameRuleDropsTheLandingHitBeforeTheCooldown() {
		// Player.isInvulnerableTo answers the fall game rule before any other gate.
		assertEquals(Optional.empty(), landingHurt(3.5, 0.5, false, false), "the fall game rule drops the hit");
		assertEquals(Optional.of(HurtCause.FALL), landingHurt(3.5, 0.5, false, true));
	}

	@Test
	void aDescentEndingInWaterRefreshesTheTrackerBeforeTheLandingIsJudged() {
		// LivingEntity.checkFallDamage refreshes the fluid tracker when the copy
		// was dry, so a fall that ends on the floor of a pool resets first.
		PlayerState before = airborneAt(0.5, 8.0, 0.5);
		before.fallDistance = 4.0;
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		PlayerState after = before.copy();
		after.placeAt(0.5, 0.0, 0.5);
		after.onGround = true;

		ServerTick.Transaction transaction =
		    new ServerTick().transact(after, packet(before, after), server, IDLE, poolWorld());

		assertInstanceOf(ServerTick.Accepted.class, transaction);
		assertTrue(server.waterHeight > 0.0, "the landed copy stands in the pool");
		assertEquals(Optional.empty(), transaction.hurt(), "water resets the fall before the ground test");
		assertRaw(0.0, server.fallDistance, "");
		assertEquals(20.0F, server.health);
	}

	@Test
	void aCorrectionSearchesSupportWithThePacketsGroundBitNotTheServers() {
		// The teleport path passes packet.isOnGround() to checkSupportingBlock
		// without installing it; the server's own ground flag stays true here.
		PlayerState before = airborneAt(-HALF_WIDTH, 0.0, 0.5);
		before.onGround = true;
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		server.mainSupportingBlockPosPresent = true;
		server.mainSupportingBlockPosY = -1;
		PlayerState after = before.copy();
		after.placeAt(1.0 + HALF_WIDTH, 0.0, 0.5);
		after.onGround = false;

		ServerTick.Transaction corrected =
		    new ServerTick().transact(after, packet(before, after), server, IDLE, wallWorld());

		assertInstanceOf(ServerTick.Corrected.class, corrected);
		assertTrue(server.onGround, "the server's own flag is untouched");
		assertFalse(server.mainSupportingBlockPosPresent, "the packet's airborne bit clears the support");
		assertFalse(server.onGroundNoBlocks);
	}

	@Test
	void aSlimeLandingSettlesTheImpulseContextUnlessSneaking() {
		// SlimeBlock.fallOn calls causeFallDamage at multiplier zero unless the
		// player suppresses the bounce; the call never hits but a landing above
		// the impact height resets the wind-charge context outright, where the
		// accepted packet's own reset still defers to the grace period.
		ServerPlayerState landed = landOnSlime(false);
		assertFalse(landed.currentImpulseImpactPosPresent, "the context is settled by the landing");
		assertEquals(20.0F, landed.health);
		ServerPlayerState sneaking = landOnSlime(true);
		assertTrue(sneaking.currentImpulseImpactPosPresent, "sneaking skips the slime's fall handling");
	}

	@Test
	void aCobwebVisitSettlesTheImpulseContextEvenWhileFlying() {
		// Player.makeStuckInBlock resets the context after its flying gate, so a
		// flying player keeps its speed but still loses the context.
		for (boolean flying : new boolean[] {false, true}) {
			PlayerState before = airborneAt(0.5, 0.0, 0.5);
			before.mayfly = flying;
			before.flying = flying;
			ServerPlayerState server = ServerPlayerState.atBoundary(before);
			server.setIgnoreFallDamageFromCurrentImpulse(true, 0.5, 6.0, 0.5);
			server.currentImpulseContextResetGraceTime = 0;
			assertTrue(server.currentImpulseImpactPosPresent);
			new ServerTick().transact(before.copy(), MovementPacket.NONE, server, IDLE, cobwebWorld());
			assertFalse(server.currentImpulseImpactPosPresent, "flying " + flying);
			assertEquals(flying, server.stuckSpeedMultiplierX == 0.0, "the vector stays behind the flying gate");
		}
	}

	@Test
	void aPowderSnowVisitSettlesTheImpulseContextEvenWhileFlying() {
		// PowderSnowBlock.entityInside reaches the same Player.makeStuckInBlock
		// as the cobweb once the feet cell is powder snow: the vector and the
		// fall reset stay behind the flying gate, the impulse-context reset does
		// not. A bootless player in powder snow never lands, so no fall handling
		// would otherwise settle the context.
		for (boolean flying : new boolean[] {false, true}) {
			PlayerState before = airborneAt(0.5, 0.0, 0.5);
			before.mayfly = flying;
			before.flying = flying;
			before.fallDistance = 1.0;
			ServerPlayerState server = ServerPlayerState.atBoundary(before);
			server.setIgnoreFallDamageFromCurrentImpulse(true, 0.5, 6.0, 0.5);
			server.currentImpulseContextResetGraceTime = 0;
			server.tickCount = 1L;
			assertTrue(server.currentImpulseImpactPosPresent);
			new ServerTick().transact(before.copy(), MovementPacket.NONE, server, IDLE, powderSnowWorld());
			assertFalse(server.currentImpulseImpactPosPresent, "flying " + flying);
			assertEquals(flying, server.stuckSpeedMultiplierX == 0.0, "the vector stays behind the flying gate");
			assertEquals(1, server.ticksFrozen, "FREEZE is outside the gate");
		}
	}

	@Test
	void impulseGraceExpiresWithoutErasingFallProtectionUntilReset() {
		PlayerState client = airborneAt(0.5, 5.0, 0.5);
		ServerPlayerState server = ServerPlayerState.atBoundary(client);
		server.setIgnoreFallDamageFromCurrentImpulse(true, 0.5, 7, 0.5);
		server.currentImpulseContextResetGraceTime = 1;
		new ServerTick().transact(server.copy(), MovementPacket.NONE, server, IDLE, floorWorld());
		assertEquals(0, server.currentImpulseContextResetGraceTime);
		assertTrue(server.currentImpulseImpactPosPresent);
		ServerDamage damage = new ServerDamage();
		damage.begin(server);
		damage.causeFallDamage(20, 1, HurtCause.FALL);
		assertEquals(20, server.health, "the declared impact height caps the damaging fall");
		assertFalse(server.currentImpulseImpactPosPresent);
	}

	@Test
	void clearingTheImpulseFlagEndsTheFallCapAsVanillaDoes() {
		// Player.causeFallDamage caps the fall only while both the impact position
		// and ignoreFallDamageFromCurrentImpulse are set; clearing the flag leaves
		// vanilla's position inert, so the state drops it.
		PlayerState client = airborneAt(0.5, 5.0, 0.5);
		ServerPlayerState server = ServerPlayerState.atBoundary(client);
		server.setIgnoreFallDamageFromCurrentImpulse(true, 0.5, 7, 0.5);
		assertEquals(ServerPlayerState.IMPULSE_CONTEXT_RESET_GRACE_TICKS, server.currentImpulseContextResetGraceTime);
		server.setIgnoreFallDamageFromCurrentImpulse(false, 0, 0, 0);
		assertFalse(server.currentImpulseImpactPosPresent);
		assertEquals(0, server.currentImpulseContextResetGraceTime);
		ServerDamage damage = new ServerDamage();
		damage.begin(server);
		damage.causeFallDamage(20, 1, HurtCause.FALL);
		assertEquals(3.0F, server.health, "a twenty-block fall hurts in full once the flag is cleared");
	}

	@Test
	void farmlandRefusesATrampleOnlyForABodyLargeEnoughToTrampleIt() {
		// FarmlandBlock.fallOn tramples when random.nextFloat() < fallDistance - 0.5
		// and width^2 * height exceeds 0.512. A one-block drop is a coin toss the
		// server decides; a three-block drop tramples for certain and refuses as a
		// world write. A glider's 0.6-cube box is 0.216 and lands ordinarily.
		PendingServerWriteException random = assertThrows(PendingServerWriteException.class,
		    () -> landOnFarmland(false, 1.0, 0.0), "a standing body past half a block is a random trample");
		assertEquals(RefusalCause.PENDING_SERVER_RANDOM, random.cause());
		PendingServerWriteException certain = assertThrows(PendingServerWriteException.class,
		    () -> landOnFarmland(false, 2.0, 1.0), "a fall past a block and a half always tramples");
		assertEquals(RefusalCause.UNMODELED_WORLD_WRITE, certain.cause());
		ServerPlayerState glider = landOnFarmland(true, 2.0, 1.0);
		assertRaw(0.0, glider.fallDistance, "");
	}

	private static ServerPlayerState landOnFarmland(
	    final boolean gliding, final double startY, final double priorFallDistance) {
		PlayerState before = new PlayerState();
		if (gliding) {
			before.pose = PlayerState.Pose.FALL_FLYING;
			before.fallFlying = true;
			before.gliderUsable = true;
			before.fallFlyTicks = 5;
		}
		before.placeAt(0.5, startY, 0.5);
		before.fallDistance = priorFallDistance;
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		PlayerState after = before.copy();
		after.placeAt(0.5, 0.0, 0.5);
		after.onGround = true;
		// The landing is the movement handler's; the glider's own connection
		// tick afterwards would refuse the stopped glide, which is not the
		// trample question.
		ServerTick.Transaction transaction =
		    new ServerTick().handleMovePlayer(after, packet(before, after), server, farmlandWorld());
		assertInstanceOf(ServerTick.Accepted.class, transaction);
		return server;
	}

	private static ServerPlayerState landOnSlime(final boolean sneaking) {
		PlayerState before = airborneAt(0.5, 3.0, 0.5);
		before.fallDistance = 2.0;
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		server.setIgnoreFallDamageFromCurrentImpulse(true, 0.5, -2.0, 0.5);
		server.currentImpulseContextResetGraceTime = 5;
		// The input packet of the same tick installs the server's shift flag.
		PlayerInput action = sneaking ? PlayerInput.of(0.0F, 0.0F, PlayerInput.Key.SNEAK) : IDLE;
		PlayerState after = before.copy();
		after.placeAt(0.5, 0.0, 0.5);
		after.onGround = true;
		ServerTick.Transaction transaction =
		    new ServerTick().transact(after, packet(before, after), server, action, slimeWorld());
		assertEquals(sneaking, server.shiftKeyDown);
		assertInstanceOf(ServerTick.Accepted.class, transaction);
		assertEquals(Optional.empty(), transaction.hurt(), "a slime landing never hits");
		return server;
	}

	private static Optional<HurtCause> landingHurt(
	    final double fallBefore, final double descent, final boolean mayfly) {
		return landingHurt(fallBefore, descent, mayfly, true);
	}

	private static Optional<HurtCause> landingHurt(
	    final double fallBefore, final double descent, final boolean mayfly, final boolean fallDamage) {
		PlayerState before = airborneAt(0.5, descent, 0.5);
		before.fallDistance = fallBefore;
		before.mayfly = mayfly;
		ServerPlayerState server = ServerPlayerState.atBoundary(before);
		server.fallDamage = fallDamage;
		PlayerState after = before.copy();
		after.placeAt(0.5, 0.0, 0.5);
		after.onGround = true;
		ServerTick.Transaction transaction =
		    new ServerTick().transact(after, packet(before, after), server, IDLE, floorWorld());
		assertInstanceOf(ServerTick.Accepted.class, transaction);
		assertRaw(0.0, server.fallDistance, "the packet's ground bit resets the fall");
		return transaction.hurt();
	}

	/** A farmland floor with its top at y=0 everywhere. */
	private static SnapshotView farmlandWorld() {
		BlockEntry farmland = BlockEntry.builder(EXTRA, "minecraft:farmland")
		                          .fullCube()
		                          .landing(WorldView.Landing.FARMLAND)
		                          .suffocation(Suffocation.NO)
		                          .build();
		return view(floor(region(farmland), EXTRA));
	}

	/** A slime floor with its top at y=0 everywhere. */
	private static SnapshotView slimeWorld() {
		BlockEntry slime = BlockEntry.builder(EXTRA, "minecraft:slime_block")
		                       .fullCube()
		                       .friction(0.8F)
		                       .bounceRestitution(1.0F)
		                       .landing(WorldView.Landing.SLIME)
		                       .stepOn(WorldView.StepOn.SLIME)
		                       .suffocation(Suffocation.NO)
		                       .build();
		return view(floor(region(slime), EXTRA));
	}

	/** The stone floor with a cobweb in the cell above the origin. */
	private static SnapshotView cobwebWorld() {
		BlockEntry cobweb = BlockEntry.builder(EXTRA, "minecraft:cobweb")
		                        .insideEffect(WorldView.InsideEffect.COBWEB)
		                        .contact(WorldView.Contact.NONE)
		                        .suffocation(Suffocation.NO)
		                        .build();
		return view(floor(region(cobweb), STONE).set(0, 0, 0, EXTRA));
	}

	/** The stone floor with powder snow in the cell above the origin. */
	static SnapshotView powderSnowWorld() {
		BlockEntry powderSnow = BlockEntry.builder(EXTRA, "minecraft:powder_snow")
		                            .collisionBehavior(WorldView.CollisionBehavior.POWDER_SNOW_NO_BOOTS)
		                            .landing(WorldView.Landing.POWDER_SNOW)
		                            .suffocation(Suffocation.NO)
		                            .build();
		return view(floor(region(powderSnow), STONE).set(0, 0, 0, EXTRA));
	}

	/** The stone floor under one block of water everywhere. */
	private static SnapshotView poolWorld() {
		WorldSnapshot.Builder world =
		    floor(region(), STONE)
		        .fluidPalette(
		            FluidEntry.EMPTY, new FluidEntry(1, "minecraft:water", FluidKind.WATER, 8.0 / 9.0, 0, 0, 0, true));
		for (int z = -4; z <= 4; z++) {
			for (int x = -4; x <= 4; x++) {
				world.setFluid(x, 0, z, 1);
			}
		}
		return view(world);
	}
}
