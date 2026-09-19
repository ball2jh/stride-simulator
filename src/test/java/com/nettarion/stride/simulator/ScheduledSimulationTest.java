package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.server.ServerTick;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * An explicit schedule delivers each packet where it is told to, forks own their queues, and the
 * composed order reproduces {@link Simulator} bit for bit.
 */
final class ScheduledSimulationTest {
	private static final PlayerInput SPRINT = PlayerInput.of(0, 0, PlayerInput.Key.FORWARD, PlayerInput.Key.SPRINT);

	private static final PlayerInput WALK = PlayerInput.of(0, 0, PlayerInput.Key.FORWARD);

	@Test
	void delayedHealthChangesSprintOnlyAtDelivery() {
		PlayerState client = state();
		ServerPlayerState server = ServerPlayerState.atBoundary(client);
		server.foodLevel = 6;
		ScheduledSimulation simulation = new ScheduledSimulation(new SimulationState(client, server, 0), world());
		simulation.tickConnection();
		simulation.tickClient(SPRINT);
		simulation.tickClient(SPRINT);
		assertTrue(simulation.clientState().sprinting);
		assertEquals(20, simulation.clientState().foodLevel);
		ScheduledSimulation fork = simulation.fork();
		assertTrue(simulation.deliverClientbound());
		assertEquals(6, simulation.clientState().foodLevel);
		assertEquals(20, fork.clientState().foodLevel);
		assertEquals(1, simulation.writes().getLast().actionIndex());
		simulation.tickClient(SPRINT);
		assertFalse(simulation.clientState().sprinting);
	}

	@Test
	void delayedCorrectionsProduceAcknowledgementAndPositionResponseInOrder() {
		ScheduledSimulation simulation =
		    new ScheduledSimulation(new SimulationState(state(), ServerPlayerState.atBoundary(state()), 0), world());
		simulation.tickConnection();
		for (int i = 0; i < 60; i++) {
			simulation.tickClient(SPRINT);
		}
		while (simulation.deliverServerbound()) {}
		assertTrue(simulation.serverState().correctionPending);
		while (simulation.deliverClientbound()) {}
		assertEquals(2, simulation.pendingServerboundPackets());
		assertInstanceOf(PlayerPositionWrite.class, simulation.writes().getLast());
		assertTrue(simulation.deliverServerbound());
		assertFalse(simulation.serverState().correctionPending);
		assertEquals(1, simulation.pendingServerboundPackets());
		assertTrue(simulation.deliverServerbound());
		assertEquals(simulation.clientState().z, simulation.serverState().z);
	}

	@Test
	void absoluteCorrectionsNormalizeZeroAndClampPitchAsPositionMoveRotationDoes() {
		PlayerState player = state();
		new PlayerPositionWrite(0, StateDigest.state(player), 7, -0.0, -0.0, -0.0, -0.0F, 120)
		    .applyAfterAction(0, player);
		assertEquals(0L, Double.doubleToRawLongBits(player.x));
		assertEquals(0, Float.floatToRawIntBits(player.yRot));
		assertEquals(90, player.xRot);
		ServerPlayerState server = ServerPlayerState.atBoundary(state());
		PlayerState packet = state();
		packet.xRot = 120;
		new ServerTick().handleMovePlayer(packet, MovementPacket.ROT, server, world());
		assertEquals(90, server.xRot);
	}

	@Test
	void explicitFixedScheduleMatchesExistingComposition() {
		SnapshotView world = world();
		Simulator simulator = new Simulator();
		SimulationState fixed = new SimulationState(state(), ServerPlayerState.atBoundary(state()), 0);
		ScheduledSimulation scheduled = new ScheduledSimulation(fixed, world);
		ActionSchedule schedule = ActionSchedule.composed();
		for (int i = 0; i < 35; i++) {
			PlayerInput input = new PlayerInput(i < 20, false, false, false, i == 5, i > 25, i < 15, 0, 0);
			// The composed step's schedule: the server tick that drained the
			// last packets finishes (sample, level tick, connection tick), the
			// client ticks and the next drain runs, and the sample reaches the
			// client before its next tick, as the bundled captures record.
			schedule.advance(scheduled, input);
			fixed = simulator.advance(fixed, input, world).state();
			assertTrue(PlayerState.rawEquals(fixed.clientState(), scheduled.clientState()), "client tick " + i);
			assertTrue(ServerPlayerState.rawEquals(fixed.serverState(), scheduled.serverState()), "server tick " + i);
			assertTrue(Publisher.rawEquals(fixed.publisher(), scheduled.publisher()), "publisher tick " + i);
			assertEquals(0, scheduled.pendingServerboundPackets());
			assertEquals(0, scheduled.pendingClientboundPackets());
		}
	}

	@Test
	void packetPayloadAndQueuesSurviveIndependentForks() {
		ScheduledSimulation scheduled =
		    new ScheduledSimulation(new SimulationState(state(), ServerPlayerState.atBoundary(state()), 0), world());
		scheduled.tickClient(WALK);
		double firstZ = scheduled.clientState().z;
		ScheduledSimulation branch = scheduled.fork();
		scheduled.tickClient(WALK);
		assertTrue(scheduled.deliverServerbound()); // input
		assertTrue(scheduled.deliverServerbound()); // first movement, not the later mutable client
		assertEquals(firstZ, scheduled.serverState().z);
		assertEquals(0.5, branch.serverState().z);
		while (branch.deliverServerbound()) {}
		assertEquals(firstZ, branch.serverState().z);
		assertEquals(3, scheduled.pendingServerboundPackets());
	}

	@Test
	void theInteractionDeclarationIsCarriedByForksAndTheTransportState() {
		SimulationState start = new SimulationState(state(), ServerPlayerState.atBoundary(state()), 0);
		ScheduledSimulation undeclared = new ScheduledSimulation(start, world());
		assertEquals(Interaction.UNDECLARED, undeclared.transportState().interaction());
		ScheduledSimulation allowed = new ScheduledSimulation(start, world(), Interaction.ALLOWED);
		assertEquals(Interaction.ALLOWED, allowed.fork().transportState().interaction());
		ScheduledSimulation denied = new ScheduledSimulation(start, world(), Interaction.DENIED);
		assertEquals(Interaction.DENIED, denied.transportState().interaction());
		assertFalse(ScheduledSimulation.sameState(allowed, denied), "the declaration is part of the branch");
	}

	@Test
	void batchedSpeedBudgetResetsAfterFiveAndUsesFirstGood() {
		ServerPlayerState server = ServerPlayerState.atBoundary(state());
		ServerTick tick = new ServerTick();
		tick.tickConnection(server, world());
		for (double x : new double[] {9.5, 13.5, 17.5, 19.5, 21.5}) {
			PlayerState packet = state();
			packet.placeAt(x, 0, 0.5);
			assertInstanceOf(
			    ServerTick.Accepted.class, tick.handleMovePlayer(packet, MovementPacket.POS, server, world()));
		}
		PlayerState packet = state();
		packet.placeAt(22.5, 0, 0.5);
		assertEquals(CorrectionReason.MOVED_TOO_QUICKLY,
		    assertInstanceOf(
		        ServerTick.Corrected.class, tick.handleMovePlayer(packet, MovementPacket.POS, server, world()))
		        .reason());
		assertEquals(6, server.receivedMovePacketCount);
		assertEquals(0.5, server.firstGoodX);
		assertEquals(21.5, server.lastGoodX);
	}

	@Test
	void pendingTeleportAppliesRotationAndRetriesOnlyOnPacketAfterTwentyTicks() {
		ServerPlayerState server = ServerPlayerState.atBoundary(state());
		ServerTick tick = new ServerTick();
		tick.tickConnection(server, world());
		PlayerState packet = state();
		packet.placeAt(25, 0, 0.5);
		tick.handleMovePlayer(packet, MovementPacket.POS, server, world());
		int originalId = server.awaitingTeleport;
		packet.yRot = 73;
		for (int i = 0; i < 20; i++) {
			tick.tickConnection(server, world());
		}
		assertInstanceOf(
		    ServerTick.AwaitingTeleport.class, tick.handleMovePlayer(packet, MovementPacket.POS_ROT, server, world()));
		assertEquals(73, server.yRot);
		assertEquals(originalId, server.awaitingTeleport);
		tick.tickConnection(server, world());
		assertEquals(originalId, server.awaitingTeleport, "no autonomous retry");
		assertEquals(CorrectionReason.TELEPORT_RETRY,
		    assertInstanceOf(
		        ServerTick.Corrected.class, tick.handleMovePlayer(packet, MovementPacket.POS, server, world()))
		        .reason());
		tick.handleAcceptTeleportPacket(server, originalId);
		assertTrue(server.correctionPending);
		tick.handleAcceptTeleportPacket(server, server.awaitingTeleport);
		assertFalse(server.correctionPending);
	}

	@Test
	void movementTailIsCopiedDigestedAndCappedWithVanillaMerge() {
		ServerPlayerState server = ServerPlayerState.atBoundary(state());
		for (int i = 0; i < 100; i++) {
			server.addMovementThisTick(i, 0, 0, i + 1, 0, 0, 0, 0);
		}
		ServerPlayerState fork = server.copy();
		assertTrue(ServerPlayerState.rawEquals(server, fork));
		long digest = StateDigest.server(server);
		server.addMovementThisTick(100, 0, 0, 101, 0, 0, 0, 0);
		assertEquals(ServerPlayerState.MAXIMUM_MOVEMENTS_THIS_TICK - 1, server.additionalMovementCount());
		assertFalse(server.movementAxisDependent);
		assertEquals(0, server.movementFromX);
		assertEquals(2, server.movementToX);
		assertNotEquals(digest, StateDigest.server(server));
		assertFalse(ServerPlayerState.rawEquals(server, fork));
		server.clearMovementThisTick();
		assertEquals(ServerPlayerState.MAXIMUM_MOVEMENTS_THIS_TICK - 1, fork.additionalMovementCount());
	}

	static PlayerState state() {
		PlayerState state = new PlayerState();
		state.placeAt(0.5, 0, 0.5);
		state.onGround = true;
		state.mainSupportingBlockPosPresent = true;
		state.mainSupportingBlockPosY = -1;
		return state;
	}

	static SnapshotView world() {
		WorldSnapshot.Builder builder = WorldSnapshot.builder(OutsidePolicy.REFUSING, -4, -2, -4, 36, 16, 36)
		                                    .palette(BlockEntry.builder(0, "minecraft:air").build(),
		                                        BlockEntry.builder(1, "minecraft:stone").fullCube().build());
		for (int x = -4; x < 32; x++) {
			for (int z = -4; z < 32; z++) {
				builder.set(x, -1, z, 1);
			}
		}
		return SnapshotView.compile(builder.build());
	}
	@Test
	void aPendingHitAtActionZeroIsPublishedAlikeByBothRunners() {
		// A branch may start right after a hit whose velocity write the first
		// action publishes: the cause action is -1 on both runners and the write
		// is delivered after action zero.
		PlayerState client = state();
		SimulationState start = new SimulationState(client, ServerPlayerState.atBoundary(client), 0, HurtCause.FALL);
		Simulator.Step fixed = new Simulator().advance(start, WALK, world());
		ScheduledSimulation scheduled = new ScheduledSimulation(start, world());
		ActionSchedule.composed().advance(scheduled, WALK);
		List<HurtMotionWrite> fixedWrites = fixed.hurtMotion();
		assertEquals(1, fixedWrites.size(), "the pending hit publishes one velocity write");
		assertEquals(-1, fixedWrites.getFirst().event().causeAction());
		assertEquals(0, fixedWrites.getFirst().actionIndex());
		assertTrue(PlayerState.rawEquals(fixed.state().clientState(), scheduled.clientState()), "client");
		assertTrue(ServerPlayerState.rawEquals(fixed.state().serverState(), scheduled.serverState()), "server");
		assertEquals(fixedWrites, scheduled.writes().stream().filter(w -> w instanceof HurtMotionWrite).toList());
	}
}
