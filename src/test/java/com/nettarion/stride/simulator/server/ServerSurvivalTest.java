package com.nettarion.stride.simulator.server;

import com.nettarion.stride.simulator.DamageEvent;
import com.nettarion.stride.simulator.DamageWrite;
import com.nettarion.stride.simulator.ServerWriteTimeline;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.MovementPacket;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.SimulationState;
import com.nettarion.stride.simulator.Simulator;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.block.InsideBlockEffectCollector;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The server's survival phases in the composed step, rule by rule from
 * {@code LivingEntity.hurtServer}, {@code Player.actuallyHurt}, and the
 * block bodies that call them: every hit is on the stream at its input, the
 * cooldown decides what a later hit does, and a body the slice does not
 * model refuses inside the tick.
 */
final class ServerSurvivalTest {
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);
	private static final int AIR = 0;
	private static final int STONE = 1;
	private static final int CACTUS = 2;
	private static final int MAGMA = 3;
	private static final int HAY = 4;
	private static final int STALAGMITE = 5;
	private static final int WITHER_ROSE = 6;
	private static final int OLD_CAMPFIRE = 7;
	private static final int POWDER_SNOW = 8;
	private static final int WATER = 1;

	@Test
	void aCactusVisitHurtsOncePerCooldownAndMarksTheFirstHit() {
		SnapshotView world = world(builder -> builder.set(1, 0, 0, CACTUS));
		// Standing on the floor with the box's east face inside the cactus cell.
		PlayerState start = standing(0.75, 0.0, 0.5);
		Simulator simulator = new Simulator();
		ServerPlayerState server = ServerPlayerState.atBoundary(start);
		// Isolate repeated contact damage from the default regeneration timer.
		server.naturalRegeneration = false;
		SimulationState state = simulator.start(start, server);

		Simulator.Step first = simulator.advance(state, IDLE, world);
		assertEquals(1, first.damage().size(), first.writes().toString());
		DamageEvent hit = first.damage().getFirst().event();
		assertEquals(HurtCause.CACTUS, hit.cause());
		assertEquals(1.0F, hit.healthDamage());
		assertTrue(hit.full());
		assertEquals(19.0F, first.state().serverState().health);
		assertEquals(20, first.state().serverState().invulnerableTime);
		assertEquals(Optional.of(HurtCause.CACTUS), first.state().pendingHurt(),
		    "a cactus hit is outside no_impact, so it marks and republishes velocity");
		assertTrue(first.confirmations().stream().anyMatch(confirmation
		    -> confirmation instanceof Simulator.DamageConfirmation damage && damage.cause() == HurtCause.CACTUS));

		// The next input delivers the hurt motion; the cactus still touches but
		// the cooldown refuses an equal hit, so no second event and no mark.
		Simulator.Step second = simulator.advance(first.state(), IDLE, world);
		assertEquals(1, second.hurtMotion().size());
		assertEquals(HurtCause.CACTUS, second.hurtMotion().getFirst().event().cause());
		assertTrue(second.state().pendingHurt().isEmpty());
		assertEquals(19.0F, second.state().serverState().health);
		assertEquals(19, second.state().serverState().invulnerableTime);

		// The level tick counts the cooldown down before each hit, so the
		// tenth input after a full hit is the next full hit: one point every
		// ten ticks, as a cactus is known to deal.
		state = second.state();
		int fullHits = 0;
		for (int input = 2; input < 30; input++) {
			Simulator.Step step = simulator.advance(state, IDLE, world);
			for (DamageWrite write : step.damage()) {
				if (write.event().full()) {
					fullHits++;
					assertEquals(input, write.actionIndex());
					assertEquals(10 * fullHits, input, "a full hit lands when the cooldown reaches ten");
				}
			}
			state = step.state();
		}
		assertEquals(2, fullHits);
		assertEquals(17.0F, state.serverState().health);
	}

	@Test
	void aHotFloorHurtsUnlessTheLastInputPacketSaidShift() {
		SnapshotView world = world(builder -> builder.set(0, -1, 0, MAGMA));
		PlayerState start = standing(0.5, 0.0, 0.5);
		start.mainSupportingBlockPosY = -1;
		Simulator simulator = new Simulator();

		Simulator.Step burned = simulator.advance(simulator.start(start, start), IDLE, world);
		assertEquals(HurtCause.HOT_FLOOR, burned.damage().getFirst().event().cause());
		assertEquals(19.0F, burned.state().serverState().health);

		// The shift bit the transaction installs is the input packet's; the
		// stepOn body reads it at the following connection tick.
		PlayerInput shift = new PlayerInput(false, false, false, false, false, true, false, 0.0F, 0.0F);
		ServerPlayerState careful = ServerPlayerState.atBoundary(start);
		careful.shiftKeyDown = true;
		Simulator.Step spared = simulator.advance(new SimulationState(start, careful, 0), shift, world);
		assertTrue(spared.damage().isEmpty(), spared.writes().toString());
		assertEquals(20.0F, spared.state().serverState().health);
	}

	@Test
	void theLandedBlockDecidesTheFallHit() {
		// A drop of four blocks: fall power one on stone, nothing on hay (the
		// floor of one fifth), and a stalagmite at twice the power of the fall
		// plus two and a half: floor((4 + 2.5 - 3) * 2).
		assertEquals(1, landingDamage(STONE, HurtCause.FALL));
		assertEquals(0, landingDamage(HAY, null));
		assertEquals(7, landingDamage(STALAGMITE, HurtCause.STALAGMITE));
	}

	@Test
	void anUnmodelledContactBodyRefusesInsideTheTick() {
		SnapshotView world = world(builder -> builder.set(1, 0, 0, WITHER_ROSE));
		PlayerState start = standing(0.75, 0.0, 0.5);
		Simulator simulator = new Simulator();
		UnimplementedMechanicException refusal = assertThrows(
		    UnimplementedMechanicException.class, () -> simulator.advance(simulator.start(start, start), IDLE, world));
		assertTrue(refusal.getMessage().contains("contact body"), refusal.getMessage());
	}

	@Test
	void aLegacyPaletteLeavesStateDependentBodiesUnknownAndTheyRefuse() {
		assertEquals(WorldView.Contact.UNKNOWN, WorldSnapshot.BlockEntry.legacyContact("minecraft:campfire"));
		assertEquals(WorldView.Contact.CACTUS, WorldSnapshot.BlockEntry.legacyContact("minecraft:cactus"));
		assertEquals(WorldView.Landing.UNKNOWN, WorldSnapshot.BlockEntry.legacyLanding("minecraft:pointed_dripstone"));
		assertEquals(WorldView.Landing.BED, WorldSnapshot.BlockEntry.legacyLanding("minecraft:red_bed"));
		SnapshotView world = world(builder -> builder.set(1, 0, 0, OLD_CAMPFIRE));
		PlayerState start = standing(0.75, 0.0, 0.5);
		Simulator simulator = new Simulator();
		assertThrows(
		    UnimplementedMechanicException.class, () -> simulator.advance(simulator.start(start, start), IDLE, world));
	}

	@Test
	void drowningCountsDownTheAirAndHurtsWithoutMarking() {
		// Water two deep over the floor: the eye is under the surface after this
		// tick's fluid refresh, which is what the drowning branch reads.
		SnapshotView pool = world(builder -> {
			for (int x = -8; x < 8; x++) {
				for (int z = -8; z < 8; z++) {
					builder.setFluid(x, 0, z, WATER);
					builder.setFluid(x, 1, z, WATER);
				}
			}
		});
		ServerPlayerState server = ServerPlayerState.atBoundary(standing(0.5, 0.0, 0.5));
		server.airSupply = -19;
		ServerTick.Transaction drowned =
		    new ServerTick().transact(server.copy(), MovementPacket.NONE, server, IDLE, pool, false);
		assertEquals(1, drowned.damage().size(), drowned.damage().toString());
		DamageEvent hit = drowned.damage().getFirst();
		assertEquals(HurtCause.DROWN, hit.cause());
		assertEquals(2.0F, hit.healthDamage());
		assertTrue(hit.full());
		assertEquals(Optional.empty(), drowned.hurt(), "drowning is in no_impact");
		assertEquals(0, server.airSupply);

		ServerPlayerState surfaced = ServerPlayerState.atBoundary(standing(0.5, 0.0, 0.5));
		surfaced.airSupply = 100;
		new ServerTick().transact(surfaced.copy(), MovementPacket.NONE, surfaced, IDLE, world(builder -> {}), false);
		assertEquals(104, surfaced.airSupply, "air refills by four a tick out of water");
	}

	@Test
	void absorptionIsSpentBeforeHealthAndALargerHitInsideTheCooldownIsPartial() {
		SnapshotView world = world(builder -> builder.set(1, 0, 0, CACTUS));
		ServerPlayerState server = ServerPlayerState.atBoundary(standing(0.75, 0.0, 0.5));
		server.absorption = 0.5F;
		server.invulnerableTime = 15;
		server.lastHurt = 0.25F;
		ServerTick.Transaction transaction =
		    new ServerTick().transact(server.copy(), MovementPacket.NONE, server, IDLE, world, false);
		DamageEvent hit = transaction.damage().getFirst();
		assertFalse(hit.full(), "the cooldown was above ten, so the hit is the difference");
		assertEquals(1.0F, hit.attempted());
		assertEquals(0.5F, hit.absorbed());
		assertEquals(0.25F, hit.healthDamage());
		assertEquals(19.75F, server.health);
		assertEquals(0.0F, server.absorption);
		assertEquals(1.0F, server.lastHurt);
		assertEquals(14, server.invulnerableTime, "a partial hit does not restart the cooldown");
		assertEquals(Optional.empty(), transaction.hurt());
	}

	@Test
	void fireIgnitionWalksTheImmuneWindowAndRefusesTheRandomIncrement() {
		ServerPlayerState server = ServerPlayerState.atBoundary(standing(0.5, 0.0, 0.5));
		assertEquals(-20, server.remainingFireTicks, "the dry steady state");
		Survival survival = new Survival();
		survival.begin(server);

		survival.fireIgnite();
		assertEquals(-19, server.remainingFireTicks, "one tick through the immune window");
		server.remainingFireTicks = -1;
		survival.fireIgnite();
		assertEquals(160, server.remainingFireTicks, "reaching zero ignites for eight seconds");
		server.remainingFireTicks = 100;
		survival.fireIgnite();
		assertEquals(160, server.remainingFireTicks, "either random increment lands below the eight seconds");

		server.remainingFireTicks = 159;
		PendingServerWriteException refusal = assertThrows(PendingServerWriteException.class, survival::fireIgnite);
		assertTrue(refusal.getMessage().contains("random"), refusal.getMessage());

		// The step's flush runs the ignition, then the in-fire hit after it,
		// in InsideBlockEffectType order.
		ServerPlayerState burned = ServerPlayerState.atBoundary(standing(0.5, 0.0, 0.5));
		burned.remainingFireTicks = -1;
		TickAuthority authority = TickAuthority.server();
		authority.begin(burned);
		InsideBlockEffectCollector effects = new InsideBlockEffectCollector(authority);
		effects.begin();
		effects.collectFireContact(1.0F);
		effects.applyAndClear(burned);
		assertEquals(160, burned.remainingFireTicks);
		assertEquals(
		    List.of(HurtCause.IN_FIRE), authority.survival().dealt().stream().map(DamageEvent::cause).toList());
		assertEquals(19.0F, burned.health);
	}

	@Test
	void aFullyFrozenPlayerThawsOutsidePowderSnowAndRefusesTheHitAtAnUnknownTickCount() {
		SnapshotView dry = world(builder -> {});
		ServerPlayerState thawing = ServerPlayerState.atBoundary(standing(0.5, 0.0, 0.5));
		thawing.ticksFrozen = 140;
		ServerTick.Transaction thawed =
		    new ServerTick().transact(thawing.copy(), MovementPacket.NONE, thawing, IDLE, dry, false);
		assertTrue(thawed.damage().isEmpty(), "the thaw precedes the fully-frozen check");
		assertEquals(138, thawing.ticksFrozen, "thawing by two outside powder snow");
		assertEquals(138, thawing.frostSpeedTicks);

		// Sunk in powder snow the count holds at the cap, and the fortieth
		// entity tick hurts: known, or refused when the count is not.
		SnapshotView snow = world(builder -> builder.set(0, 0, 0, POWDER_SNOW));
		PlayerState sunk = new PlayerState();
		sunk.placeAt(0.5, 0.0, 0.5);
		ServerPlayerState unknown = ServerPlayerState.atBoundary(sunk);
		unknown.ticksFrozen = 140;
		PendingServerWriteException refusal = assertThrows(PendingServerWriteException.class,
		    () -> new ServerTick().transact(unknown.copy(), MovementPacket.NONE, unknown, IDLE, snow, false));
		assertTrue(refusal.getMessage().contains("tick count"), refusal.getMessage());

		ServerPlayerState known = ServerPlayerState.atBoundary(sunk);
		known.ticksFrozen = 140;
		known.tickCount = 39L;
		ServerTick.Transaction frozen =
		    new ServerTick().transact(known.copy(), MovementPacket.NONE, known, IDLE, snow, false);
		assertEquals(HurtCause.FREEZE, frozen.damage().getFirst().cause());
		assertEquals(40L, known.tickCount);
		assertEquals(140, known.ticksFrozen);
	}

	@Test
	void powderSnowFreezesExtinguishesAndRefusesToMeltUnderABurningPlayer() {
		// PowderSnowBlock.entityInside on the server: FREEZE, then the melt a
		// burning player causes before EXTINGUISH clears the fire. The freeze
		// counts up through the composed tick; the extinguish keeps a
		// non-burning player's immune window; the melt is a world write the
		// slice does not make and refuses at the visit.
		SnapshotView snow = world(builder -> builder.set(0, 0, 0, POWDER_SNOW));
		PlayerState sunk = new PlayerState();
		sunk.placeAt(0.5, 0.0, 0.5);
		ServerPlayerState cold = ServerPlayerState.atBoundary(sunk);
		cold.tickCount = 1L;
		cold.remainingFireTicks = -5;
		ServerTick.Transaction freezing =
		    new ServerTick().transact(cold.copy(), MovementPacket.NONE, cold, IDLE, snow, false);
		assertTrue(freezing.damage().isEmpty());
		assertEquals(1, cold.ticksFrozen, "one FREEZE per visited step");
		assertEquals(-ServerPlayerState.FIRE_IMMUNE_TICKS, cold.remainingFireTicks,
		    "not burning after the effects: Entity.baseTick restores the immune window");

		ServerPlayerState burning = ServerPlayerState.atBoundary(sunk);
		burning.tickCount = 1L;
		burning.remainingFireTicks = 60;
		UnimplementedMechanicException refusal = assertThrows(UnimplementedMechanicException.class,
		    () -> new ServerTick().transact(burning.copy(), MovementPacket.NONE, burning, IDLE, snow, false));
		assertTrue(refusal.getMessage().contains("melts the powder snow"), refusal.getMessage());
	}

	@Test
	void spendingTheWholeAbsorptionLeavesExactlyZero() {
		// setAbsorptionAmount clamps at zero: in float, 1 - 0.1 rounds to 0.9
		// and 1 - 0.9 to 0.100000024, a hair more than the amount held.
		SnapshotView world = world(builder -> builder.set(1, 0, 0, CACTUS));
		ServerPlayerState server = ServerPlayerState.atBoundary(standing(0.75, 0.0, 0.5));
		server.absorption = 0.1F;
		ServerTick tick = new ServerTick();
		DamageEvent hit =
		    tick.transact(server.copy(), MovementPacket.NONE, server, IDLE, world, false).damage().getFirst();
		assertEquals(1.0F - 0.9F, hit.absorbed());
		assertEquals(Float.floatToRawIntBits(0.0F), Float.floatToRawIntBits(server.absorption));
		assertEquals(20.0F - 0.9F, server.health);
		tick.transact(server.copy(), MovementPacket.NONE, server, IDLE, world, false);
	}

	@Test
	void fallingOutOfTheWorldHurtsAPlayerWhoMayFly() {
		// bypasses_invulnerability holds fell_out_of_world, so Player.hurtServer
		// deals it whatever abilities.invulnerable says; its zero exhaustion
		// leaves the food level as it was either way.
		ServerPlayerState flier = ServerPlayerState.atBoundary(standing(0.5, 0.0, 0.5));
		flier.mayfly = true;
		flier.exhaustionLevel = 0.5F;
		Survival survival = new Survival();
		survival.begin(flier);
		survival.hurtServer(HurtCause.FELL_OUT_OF_WORLD, 4.0F);
		assertEquals(16.0F, flier.health);
		assertEquals(0.5F, flier.exhaustionLevel);
		assertEquals(Optional.of(HurtCause.FELL_OUT_OF_WORLD), survival.marked());

		// A negative-zero level would come out positive without the ability
		// and stay negative with it, so that charge still refuses.
		ServerPlayerState signedZero = ServerPlayerState.atBoundary(standing(0.5, 0.0, 0.5));
		signedZero.mayfly = true;
		signedZero.exhaustionLevel = -0.0F;
		survival.begin(signedZero);
		assertThrows(PendingServerWriteException.class, () -> survival.hurtServer(HurtCause.FELL_OUT_OF_WORLD, 4.0F));
	}

	@Test
	void aKillingHitAndAHitOnAPlayerWhoMayFlyRefuse() {
		SnapshotView world = world(builder -> builder.set(1, 0, 0, CACTUS));
		ServerPlayerState dying = ServerPlayerState.atBoundary(standing(0.75, 0.0, 0.5));
		dying.health = 1.0F;
		assertThrows(UnimplementedMechanicException.class,
		    () -> new ServerTick().transact(dying.copy(), MovementPacket.NONE, dying, IDLE, world, false));
		ServerPlayerState flier = ServerPlayerState.atBoundary(standing(0.75, 0.0, 0.5));
		flier.mayfly = true;
		assertThrows(PendingServerWriteException.class,
		    () -> new ServerTick().transact(flier.copy(), MovementPacket.NONE, flier, IDLE, world, false));
	}

	@Test
	void theTimelineCarriesSeveralHitsAtOneBoundaryButOneVelocityWrite() {
		DamageEvent cactus = new DamageEvent(HurtCause.CACTUS, 1.0F, 0.0F, 1.0F, 19.0F, true);
		DamageEvent lava = new DamageEvent(HurtCause.LAVA, 4.0F, 0.0F, 3.0F, 16.0F, false);
		ServerWriteTimeline timeline =
		    ServerWriteTimeline.of(List.of(new DamageWrite(3, cactus), new DamageWrite(3, lava)));
		assertEquals(2, timeline.damage().size());
		assertFalse(timeline.velocityWriteAfterAction(3), "a hit moves no velocity");
		PlayerState state = standing(0.5, 0.0, 0.5);
		timeline.applyAfterAction(3, state);
		assertEquals(0.0, state.deltaMovementX, "applying a hit is the identity on the client");
		assertEquals(1, timeline.afterConsuming(2).damage().getFirst().actionIndex());
	}

	/** The composed step's fall hit on the given landing block, from a four-block drop. */
	private static int landingDamage(final int floorEntry, final HurtCause expected) {
		SnapshotView world = world(builder -> {
			for (int x = -4; x < 4; x++) {
				for (int z = -4; z < 4; z++) {
					builder.set(x, -1, z, floorEntry);
				}
			}
		});
		PlayerState start = new PlayerState();
		start.placeAt(0.5, 4.0, 0.5);
		Simulator simulator = new Simulator();
		SimulationState state = simulator.start(start, start);
		for (int input = 0; input < 40; input++) {
			Simulator.Step step = simulator.advance(state, IDLE, world);
			state = step.state();
			if (!step.damage().isEmpty()) {
				DamageEvent hit = step.damage().getFirst().event();
				assertEquals(expected, hit.cause());
				assertTrue(state.clientState().onGround);
				return (int) hit.healthDamage();
			}
			if (state.clientState().onGround) {
				assertEquals(null, expected, "the landing dealt no hit");
				return 0;
			}
		}
		throw new AssertionError("the drop did not land");
	}

	private static PlayerState standing(final double x, final double y, final double z) {
		PlayerState state = new PlayerState();
		state.placeAt(x, y, z);
		state.onGround = true;
		state.mainSupportingBlockPosPresent = true;
		state.mainSupportingBlockPosX = Mth.floor(x);
		state.mainSupportingBlockPosY = Mth.floor(y) - 1;
		state.mainSupportingBlockPosZ = Mth.floor(z);
		return state;
	}

	private interface Cells {
		void fill(WorldSnapshot.Builder builder);
	}

	/** A stone floor under y=0 from -8 to 8, plus the caller's cells. */
	private static SnapshotView world(final Cells cells) {
		WorldSnapshot.BlockEntry air = WorldSnapshot.BlockEntry.builder(0, "minecraft:air").build();
		WorldSnapshot.BlockEntry stone = cube(1, "minecraft:stone").build();
		WorldSnapshot.BlockEntry cactus = cube(2, "minecraft:cactus").contact(WorldView.Contact.CACTUS).build();
		WorldSnapshot.BlockEntry magma = cube(3, "minecraft:magma_block").contact(WorldView.Contact.HOT_FLOOR).build();
		WorldSnapshot.BlockEntry hay = cube(4, "minecraft:hay_block").landing(WorldView.Landing.HAY).build();
		WorldSnapshot.BlockEntry stalagmite =
		    cube(5, "minecraft:pointed_dripstone").landing(WorldView.Landing.STALAGMITE).build();
		WorldSnapshot.BlockEntry witherRose =
		    WorldSnapshot.BlockEntry.builder(6, "minecraft:wither_rose").contact(WorldView.Contact.UNMODELLED).build();
		WorldSnapshot.BlockEntry oldCampfire = new WorldSnapshot.BlockEntry(7, "minecraft:campfire", 0.6F, 1.0F, 1.0F,
		    List.of(new WorldSnapshot.ShapeBox(0.0, 0.0, 0.0, 1.0, 7.0 / 16.0, 1.0)));
		WorldSnapshot.BlockEntry powderSnow = WorldSnapshot.BlockEntry.builder(8, "minecraft:powder_snow")
		                                          .collisionBehavior(WorldView.CollisionBehavior.POWDER_SNOW_NO_BOOTS)
		                                          .landing(WorldView.Landing.POWDER_SNOW)
		                                          .build();
		WorldSnapshot.Builder builder =
		    WorldSnapshot.builder(WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING, -8, -3, -8, 16, 12, 16)
		        .palette(air, stone, cactus, magma, hay, stalagmite, witherRose, oldCampfire, powderSnow)
		        .fluidPalette(WorldSnapshot.FluidEntry.EMPTY,
		            new WorldSnapshot.FluidEntry(
		                1, "minecraft:water", WorldSnapshot.FluidKind.WATER, 1.0, 0.0, 0.0, 0.0, true));
		for (int x = -8; x < 8; x++) {
			for (int z = -8; z < 8; z++) {
				builder.set(x, -1, z, STONE);
			}
		}
		cells.fill(builder);
		return SnapshotView.compile(builder.build());
	}

	private static WorldSnapshot.BlockEntry.Builder cube(final int id, final String name) {
		return WorldSnapshot.BlockEntry.builder(id, name).boxes(WorldSnapshot.ShapeBox.FULL_CUBE);
	}
}
