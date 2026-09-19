package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/** The composed step: its run and step forms agree, its boundaries are isolated, and its writes land where measured. */
final class SimulatorTest {
	private static final PlayerInput SPRINT =
	    PlayerInput.of(0.0F, 0.0F, PlayerInput.Key.FORWARD, PlayerInput.Key.SPRINT);

	private static final PlayerInput CROUCH = PlayerInput.of(0.0F, 0.0F, PlayerInput.Key.SNEAK);

	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);

	private static final PlayerInput GLIDE = PlayerInput.idle(-90.0F, 0.0F);

	@Test
	void fixedRunMatchesIndividualStepsIncludingDamageAndPendingBoundaries() {
		List<PlayerInput> actions = new ArrayList<>(Collections.nCopies(6, SPRINT));
		actions.addAll(Collections.nCopies(30, IDLE));
		assertRunMatchesSteps(towerStart(), towerServerWithoutRegeneration(), null, actions, towerWorld());
		Simulator simulator = new Simulator();
		SimulationState boundary = new SimulationState(towerStart(), towerServerWithoutRegeneration(), 0);
		for (int tick = 0; tick < actions.size(); tick++) {
			boundary = simulator.advance(boundary, actions.get(tick), towerWorld()).state();
			if (boundary.hasPendingHurt()) {
				assertRunMatchesSteps(boundary.clientState(), boundary.serverState(),
				    boundary.pendingHurt().orElseThrow(), actions.subList(tick + 1, actions.size()), towerWorld());
				return;
			}
		}
		throw new AssertionError("fixture must produce pending hurt");
	}

	@Test
	void fixedRunMatchesMetadataAndFreezeTransitionsAndOwnsItsResults() {
		PlayerState client = groundedState();
		client.swimming = true;
		client.pose = PlayerState.Pose.SWIMMING;
		ServerPlayerState server = ServerPlayerState.atBoundary(client);
		server.ticksFrozen = 5;
		server.frostSpeedTicks = 5;
		assertRunMatchesSteps(client, server, null, List.of(IDLE, SPRINT, SPRINT, CROUCH, IDLE), flatWorld());
		assertRunMatchesSteps(client, server, null, List.of(), flatWorld());
	}

	@Test
	void fixedRunPreservesFreezeValidationAndPendingEndRefusal() {
		ServerPlayerState server = ServerPlayerState.atBoundary(groundedState());
		server.frostSpeedTicks = 141;
		assertThrows(IllegalArgumentException.class,
		    () -> new Simulator().run(groundedState(), server, List.of(IDLE), flatWorld()));
		List<PlayerInput> actions = new ArrayList<>(Collections.nCopies(6, SPRINT));
		actions.addAll(Collections.nCopies(30, IDLE));
		Simulator simulator = new Simulator();
		SimulationState state = new SimulationState(towerStart(), towerServerWithoutRegeneration(), 0);
		for (int tick = 0; tick < actions.size(); tick++) {
			state = simulator.advance(state, actions.get(tick), towerWorld()).state();
			if (state.hasPendingHurt()) {
				List<PlayerInput> prefix = actions.subList(0, tick + 1);
				assertThrows(PendingServerWriteException.class,
				    () -> simulator.run(towerStart(), towerServerWithoutRegeneration(), prefix, towerWorld()));
				return;
			}
		}
		throw new AssertionError("fixture must produce pending hurt");
	}

	@Test
	void advancingPreservesItsInputAcrossOrdinaryAndPendingHurtBoundaries() {
		Simulator simulator = new Simulator();
		assertTrue(assertAdvancingPreservesItsInput(simulator, towerWorld(),
		    new SimulationState(towerStart(), towerServerWithoutRegeneration(), 0), 36,
		    tick -> tick < 6 ? SPRINT : IDLE));
	}

	@Test
	void publishedSuccessorsRemainIsolatedAcrossAccessorsAndSimulatorReuse() {
		Simulator simulator = new Simulator();
		PlayerState client = groundedState();
		ServerPlayerState server = ServerPlayerState.atBoundary(client);
		Publisher publisher = Publisher.atBoundary(client);
		SimulationState start = new SimulationState(client, publisher, server, 0, null);
		PlayerState expectedClient = client.copy();
		ServerPlayerState expectedServer = server.copy();
		Publisher expectedPublisher = publisher.copy();
		client.x += 100;
		server.health = 1;
		publisher.xLast += 100;
		assertTrue(PlayerState.rawEquals(expectedClient, start.clientState()));
		assertTrue(ServerPlayerState.rawEquals(expectedServer, start.serverState()));
		assertTrue(Publisher.rawEquals(expectedPublisher, start.publisher()));

		SimulationState retained = simulator.advance(start, SPRINT, flatWorld()).state();
		expectedClient = retained.clientState();
		expectedServer = retained.serverState();
		expectedPublisher = retained.publisher();
		retained.clientState().x += 100;
		retained.serverState().health = 1;
		retained.publisher().xLast += 100;
		simulator.advance(retained, CROUCH, flatWorld());
		simulator.advance(start, IDLE, flatWorld());
		simulator.run(towerStart(), towerServerWithoutRegeneration(), Collections.nCopies(36, IDLE), towerWorld());
		assertTrue(PlayerState.rawEquals(expectedClient, retained.clientState()));
		assertTrue(ServerPlayerState.rawEquals(expectedServer, retained.serverState()));
		assertTrue(Publisher.rawEquals(expectedPublisher, retained.publisher()));
		assertEquals(1, retained.completedActions());
	}

	@Test
	void metadataConfirmationsNamePublicationRatherThanLocalChanges() {
		PlayerState initial = groundedState();
		initial.swimming = true;
		initial.pose = PlayerState.Pose.SWIMMING;
		Simulator simulator = new Simulator();
		Simulator.Step local = simulator.advance(
		    new SimulationState(initial, ServerPlayerState.atBoundary(initial), 0), IDLE, flatWorld());
		assertTrue(local.confirmations().isEmpty(), "initial synchronized values are not dirty");
		Simulator.Step publication = simulator.advance(local.state(), IDLE, flatWorld());
		assertTrue(publication.confirmations().contains(new Simulator.SwimmingConfirmation(1, false)));
		assertTrue(publication.confirmations().contains(new Simulator.PoseConfirmation(1, PlayerState.Pose.STANDING)));
	}

	@Test
	void sprintAndPosePublicationsAreOrderedWritesWithConfirmations() {
		List<PlayerInput> actions = new ArrayList<>();
		actions.addAll(Collections.nCopies(3, SPRINT));
		actions.add(IDLE);
		actions.add(SPRINT);
		actions.add(CROUCH);
		actions.add(IDLE);
		actions.add(IDLE);

		Simulator.Run replay =
		    new Simulator().run(groundedState(), ServerPlayerState.atBoundary(groundedState()), actions, flatWorld());

		assertFalse(replay.timeline().isEmpty());
		assertTrue(replay.timeline().writes().stream().allMatch(write -> write instanceof EntityDataWrite));
		assertTrue(replay.confirmations().stream().anyMatch(c -> c instanceof Simulator.SprintConfirmation));
		assertTrue(replay.confirmations().stream().anyMatch(c -> c instanceof Simulator.PoseConfirmation));
	}

	@Test
	void matchingOldMetadataCannotChangeTheCanonicalSuccessor() {
		Simulator simulator = new Simulator();
		WriteLedger ledger = new WriteLedger();
		SimulationState state = new SimulationState(groundedState(), ServerPlayerState.atBoundary(groundedState()), 0);
		Simulator.Step started = simulator.advance(state, SPRINT, flatWorld());
		started.confirmations().forEach(ledger::expect);
		Simulator.Step stopped = simulator.advance(started.state(), IDLE, flatWorld());
		stopped.confirmations().forEach(ledger::expect);
		Simulator.Step restarted = simulator.advance(stopped.state(), SPRINT, flatWorld());
		restarted.confirmations().forEach(ledger::expect);
		PlayerState canonical = restarted.state().clientState();

		WriteLedger.EntityDataResult oldStop =
		    ledger.observeEntityData(Optional.of(false), Optional.empty(), Optional.empty());

		assertTrue(oldStop.sprint().predicted());
		assertFalse(canonical.sprinting,
		    "the scheduled server stop overwrites the local restart; ledger observation is read-only");
	}

	@Test
	void fallVelocityIsAppliedOnceBeforeItsPacketIsObserved() {
		List<PlayerInput> actions = new ArrayList<>(Collections.nCopies(6, SPRINT));
		actions.addAll(Collections.nCopies(30, IDLE));
		Simulator simulator = new Simulator();

		Simulator.Run replay = simulator.run(towerStart(), towerServerWithoutRegeneration(), actions, towerWorld());

		assertEquals(1, replay.timeline().hurtMotion().size());
		Simulator.VelocityConfirmation velocity = replay.confirmations()
		                                              .stream()
		                                              .filter(c -> c instanceof Simulator.VelocityConfirmation)
		                                              .map(c -> (Simulator.VelocityConfirmation) c)
		                                              .findFirst()
		                                              .orElseThrow();
		WriteLedger ledger = new WriteLedger();
		ledger.expect(velocity);
		assertEquals(WriteLedger.Match.CONFIRMED, ledger.observeVelocity(velocity.x(), velocity.y(), velocity.z()));
		assertEquals(WriteLedger.Match.DUPLICATE, ledger.observeVelocity(velocity.x(), velocity.y(), velocity.z()));
		assertTrue(Double.isFinite(replay.clientState().deltaMovementY));
	}

	@Test
	void frozenMetadataChangesOnlyWhenPublishedAndDelivered() {
		PlayerState client = groundedState();
		client.ticksFrozen = 23;
		client.frostSpeedTicks = 7;
		PlayerState server = groundedState();
		server.ticksFrozen = 5;
		server.frostSpeedTicks = 5;
		Simulator simulator = new Simulator();

		Simulator.Step step =
		    simulator.advance(new SimulationState(client, ServerPlayerState.atBoundary(server), 0), IDLE, flatWorld());

		assertEquals(new Simulator.FreezeSnapshot(23, 7), step.freezeBeforeAction());
		assertEquals(23, step.state().clientState().ticksFrozen);
		assertEquals(7, step.state().clientState().frostSpeedTicks);
		Simulator.Step delivered = simulator.advance(step.state(), IDLE, flatWorld());
		assertEquals(3, delivered.state().clientState().ticksFrozen);
		assertEquals(3, delivered.state().clientState().frostSpeedTicks);
		assertEquals(3, step.state().serverState().ticksFrozen);
		assertEquals(3, step.state().serverState().frostSpeedTicks);
		Simulator.FreezeConfirmation confirmation = delivered.confirmations()
		                                                .stream()
		                                                .filter(c -> c instanceof Simulator.FreezeConfirmation)
		                                                .map(c -> (Simulator.FreezeConfirmation) c)
		                                                .findFirst()
		                                                .orElseThrow();
		WriteLedger ledger = new WriteLedger();
		ledger.expect(confirmation);
		assertEquals(WriteLedger.Match.CONFIRMED,
		    ledger.observeEntityData(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(3))
		        .frozenTicks());
	}

	@Test
	void aDamagingFallPublishesOneWriteOneActionAfterItsLanding() {
		List<PlayerInput> actions = new ArrayList<>(Collections.nCopies(6, SPRINT));
		actions.addAll(Collections.nCopies(30, IDLE));

		Simulator.Run replay =
		    new Simulator().run(towerStart(), towerServerWithoutRegeneration(), actions, towerWorld());

		assertEquals(1, replay.timeline().hurtMotion().size(), "one landing publishes one write");
		HurtMotionWrite write = replay.timeline().hurtMotion().getFirst();
		// The landing packet marks the copy; the next step's tracker sample
		// publishes the mark and the client applies it before the tick after
		// next, the phase the bundled captures record. Delivery at that boundary
		// is checked separately by aPendingBoundaryPublicationIsDeliveredAfterItsFirstAction.
		assertEquals(write.event().causeAction() + 1, write.actionIndex());
		assertEquals(write.event().causeAction() + 1, write.event().writeAfterAction());
		assertTrue(write.event().writeY() < 0.0, "a damaging landing publishes the hidden server's downward motion");
		assertEquals(rawBits(write.event().writeY()),
		    rawBits(replay.timeline().hurtMotion().getFirst().event().writeY()),
		    "the published vector is a stable fact of the replay");
	}

	@Test
	void theServerFreezeTailIsRebuiltAndProjectedOntoTheClient() {
		PlayerState client = groundedState();
		PlayerState server = groundedState();
		server.ticksFrozen = 5;
		server.frostSpeedTicks = 5;
		List<PlayerInput> actions = List.of(IDLE, IDLE);

		Simulator.Run replay = new Simulator().run(client, ServerPlayerState.atBoundary(server), actions, flatWorld());

		// Out of powder snow the authoritative count sheds two per action, and
		// the client movement path sees the projection installed at the boundary
		// rather than its own zero.
		assertEquals(1, replay.serverState().ticksFrozen);
		assertEquals(3, replay.clientState().ticksFrozen, "the server's freeze count is projected onto the client");
		assertEquals(replay.serverState().ticksFrozen, replay.serverState().frostSpeedTicks,
		    "the frost speed modifier is rebuilt from the frozen count");
	}

	@Test
	void aPendingBoundaryPublicationIsDeliveredAfterItsFirstAction() {
		List<PlayerInput> actions = new ArrayList<>(Collections.nCopies(6, SPRINT));
		actions.addAll(Collections.nCopies(30, IDLE));
		Simulator simulator = new Simulator();
		SimulationState boundary = new SimulationState(towerStart(), towerServerWithoutRegeneration(), 0);
		int consumed = -1;
		for (int tick = 0; tick < actions.size(); tick++) {
			boundary = simulator.advance(boundary, actions.get(tick), towerWorld()).state();
			if (boundary.hasPendingHurt()) {
				consumed = tick + 1;
				break;
			}
		}
		assertTrue(consumed > 0);

		SimulationState restart = new SimulationState(
		    boundary.clientState(), boundary.serverState(), 0, boundary.pendingHurt().orElseThrow());
		Simulator.Run pending = simulator.run(restart, actions.subList(consumed, actions.size()), towerWorld());

		assertEquals(1, pending.timeline().hurtMotion().size());
		assertEquals(0, pending.timeline().hurtMotion().getFirst().actionIndex());
	}

	@Test
	void theObservedScheduleCarriesTheVelocityAfterTheNextPacket() {
		List<PlayerInput> actions = new ArrayList<>(Collections.nCopies(6, SPRINT));
		actions.addAll(Collections.nCopies(30, IDLE));

		Simulator.Run scheduled =
		    new Simulator().run(towerStart(), towerServerWithoutRegeneration(), actions, towerWorld());
		Simulator.Run observed = Simulator.forObservedConnection().run(
		    towerStart(), towerServerWithoutRegeneration(), actions, towerWorld());

		HurtMotionWrite constructed = scheduled.timeline().hurtMotion().getFirst();
		HurtMotionWrite free = observed.timeline().hurtMotion().getFirst();
		assertEquals(
		    constructed.actionIndex(), free.actionIndex(), "both deliver at the boundary after the landing action");
		assertEquals(constructed.event().causeAction(), free.event().causeAction());
		assertTrue(free.event().writeY() < 0.0 && constructed.event().writeY() < 0.0);
	}

	@Test
	void aWallImpactPendsLikeALandingAndTheNextInputDeliversItsWrite() {
		Simulator simulator = new Simulator();
		PlayerState start = glideStart();
		SnapshotView world = wallWorld();

		Simulator.Step impact =
		    simulator.advance(new SimulationState(start, ServerPlayerState.atBoundary(start), 0), GLIDE, world);
		assertEquals(Optional.of(HurtCause.FLY_INTO_WALL), impact.state().pendingHurt(),
		    "the server's glide travel hit the wall in this input's connection tick");
		assertEquals(1, impact.damage().size(), "the hit is on the stream at its input");
		assertTrue(impact.hasDamage());
		assertTrue(impact.writes().stream().anyMatch(write -> write instanceof HealthWrite));
		DamageWrite hit = impact.damage().getFirst();
		assertEquals(0, hit.actionIndex());
		assertEquals(HurtCause.FLY_INTO_WALL, hit.event().cause());
		assertTrue(hit.event().full());
		assertEquals(20.0F - hit.event().healthDamage(), impact.state().serverState().health);
		assertEquals(20, impact.state().serverState().invulnerableTime);
		assertTrue(impact.state().clientState().horizontalCollision);

		Simulator.Step delivery = simulator.advance(impact.state(), GLIDE, world);
		assertTrue(delivery.state().pendingHurt().isEmpty());
		assertEquals(1, delivery.hurtMotion().size());
		HurtMotionWrite write = delivery.hurtMotion().getFirst();
		assertEquals(HurtCause.FLY_INTO_WALL, write.event().cause());
		assertEquals(0, write.event().causeAction());
		assertEquals(1, write.actionIndex());
		assertTrue(delivery.confirmations().stream().anyMatch(c -> c instanceof Simulator.VelocityConfirmation),
		    "the hurt velocity write has a decoded wire value to confirm");
	}

	private static void assertRunMatchesSteps(final PlayerState client, final ServerPlayerState server,
	    final HurtCause pending, final List<PlayerInput> actions, final SnapshotView world) {
		long clientBefore = StateDigest.state(client);
		long serverBefore = StateDigest.server(server);
		Simulator simulator = new Simulator();
		SimulationState start = new SimulationState(client, Publisher.atBoundary(client), server, 0, pending);
		SimulationState state = start;
		List<Simulator.Confirmation> confirmations = new ArrayList<>();
		List<ServerWrite> writes = new ArrayList<>();
		for (PlayerInput action : actions) {
			Simulator.Step step = simulator.advance(state, action, world);
			assertEquals(!step.damage().isEmpty(), step.hasDamage(),
			    "allocation-free safety check must agree on damage, metadata, and freeze transitions");
			state = step.state();
			confirmations.addAll(step.confirmations());
			writes.addAll(step.writes());
		}
		Simulator.Run run = simulator.run(start, actions, world);
		assertEquals(StateDigest.state(state.clientState()), StateDigest.state(run.clientState()));
		assertEquals(StateDigest.server(state.serverState()), StateDigest.server(run.serverState()));
		assertEquals(confirmations, run.confirmations());
		assertEquals(writes.size(), run.timeline().writes().size());
		for (int index = 0; index < writes.size(); index++) {
			ServerWrite expected = writes.get(index);
			ServerWrite actual = run.timeline().writes().get(index);
			assertEquals(expected.actionIndex(), actual.actionIndex());
			if (expected instanceof HurtMotionWrite hurt) {
				HurtMotion motion = ((HurtMotionWrite) actual).event();
				assertEquals(hurt.event().cause(), motion.cause());
				assertEquals(hurt.event().causeAction(), motion.causeAction());
				assertEquals(hurt.event().writeAfterAction(), motion.writeAfterAction());
				assertEquals(rawBits(hurt.event().writeX()), rawBits(motion.writeX()));
				assertEquals(rawBits(hurt.event().writeY()), rawBits(motion.writeY()));
				assertEquals(rawBits(hurt.event().writeZ()), rawBits(motion.writeZ()));
			} else {
				assertEquals(expected, actual);
			}
		}
		assertEquals(clientBefore, StateDigest.state(client));
		assertEquals(serverBefore, StateDigest.server(server));
		long finalClient = StateDigest.state(run.clientState());
		long finalServer = StateDigest.server(run.serverState());
		run.clientState().x += 100;
		run.serverState().x += 100;
		assertEquals(finalClient, StateDigest.state(run.clientState()));
		assertEquals(finalServer, StateDigest.server(run.serverState()));
	}

	private static boolean assertAdvancingPreservesItsInput(final Simulator simulator, final SnapshotView world,
	    final SimulationState start, final int ticks, final IntFunction<PlayerInput> actions) {
		SimulationState boundary = start;
		boolean sawPending = false;
		for (int tick = 0; tick < ticks; tick++) {
			PlayerInput action = actions.apply(tick);
			long clientBefore = StateDigest.state(boundary.clientState());
			long serverBefore = StateDigest.server(boundary.serverState());
			Publisher publisherBefore = boundary.publisher();
			sawPending |= boundary.hasPendingHurt();
			Simulator.Step first = simulator.advance(boundary, action, world);
			Simulator.Step replay = simulator.advance(boundary, action, world);
			assertEquals(clientBefore, StateDigest.state(boundary.clientState()));
			assertEquals(serverBefore, StateDigest.server(boundary.serverState()));
			assertTrue(Publisher.rawEquals(publisherBefore, boundary.publisher()));
			assertEquals(
			    StateDigest.state(first.state().clientState()), StateDigest.state(replay.state().clientState()));
			assertEquals(
			    StateDigest.server(first.state().serverState()), StateDigest.server(replay.state().serverState()));
			assertTrue(Publisher.rawEquals(first.state().publisher(), replay.state().publisher()));
			assertEquals(first.state().pendingHurt(), replay.state().pendingHurt());
			assertEquals(first.confirmations(), replay.confirmations());
			boundary = first.state();
		}
		return sawPending;
	}

	private static long rawBits(final double value) {
		return Double.doubleToRawLongBits(value);
	}

	// ---- fixtures

	/** Hurt publication fixtures explicitly disable the independent healing mechanic. */
	private static ServerPlayerState towerServerWithoutRegeneration() {
		ServerPlayerState server = ServerPlayerState.atBoundary(towerStart());
		server.naturalRegeneration = false;
		return server;
	}

	private static PlayerState groundedState() {
		PlayerState state = new PlayerState();
		state.placeAt(2.5, 1.0, 2.5);
		state.setBox(
		    AABB.around(state.x, state.y, state.z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));
		state.onGround = true;
		state.mainSupportingBlockPosPresent = true;
		state.mainSupportingBlockPosX = 2;
		state.mainSupportingBlockPosY = 0;
		state.mainSupportingBlockPosZ = 2;
		return state;
	}

	private static PlayerState towerStart() {
		PlayerState state = groundedState();
		state.placeAt(3.0, 7.0, 3.0);
		state.mainSupportingBlockPosX = 2;
		state.mainSupportingBlockPosY = 6;
		state.mainSupportingBlockPosZ = 2;
		return state;
	}

	/** A glider one block from the wall, moving into it at a block per tick. */
	private static PlayerState glideStart() {
		PlayerState start = new PlayerState();
		start.pose = PlayerState.Pose.FALL_FLYING;
		start.placeAt(-1.0, 4.5, 0.5);
		start.fallFlying = true;
		start.gliderUsable = true;
		start.fallFlyTicks = 5;
		start.deltaMovementX = 1.0;
		start.yRot = -90.0F;
		return start;
	}

	private static BlockEntry air() {
		return BlockEntry.builder(0, "minecraft:air").build();
	}

	private static BlockEntry stone() {
		return BlockEntry.builder(1, "minecraft:stone").fullCube().build();
	}

	/** Stone floor at y=-1 and a stone wall filling x=0 from y=3 to y=6. */
	private static SnapshotView wallWorld() {
		WorldSnapshot.Builder builder =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, -6, -3, -6, 12, 12, 12).palette(air(), stone());
		for (int z = -6; z < 6; z++) {
			for (int x = -6; x < 6; x++) {
				builder.set(x, -1, z, 1);
			}
			for (int y = 3; y <= 6; y++) {
				builder.set(0, y, z, 1);
			}
		}
		return SnapshotView.compile(builder.build());
	}

	private static SnapshotView flatWorld() {
		return world(false);
	}

	private static SnapshotView towerWorld() {
		return world(true);
	}

	/** A 32 by 32 stone floor at y=0, with a two-by-two stone tower up to y=6 at x,z in [2, 3] when asked. */
	private static SnapshotView world(final boolean tower) {
		int size = 32;
		int height = 12;
		WorldSnapshot.Builder builder =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, 0, 0, 0, size, height, size).palette(air(), stone());
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				builder.set(x, 0, z, 1);
			}
		}
		if (tower) {
			for (int y = 1; y <= 6; y++) {
				for (int z = 2; z <= 3; z++) {
					for (int x = 2; x <= 3; x++) {
						builder.set(x, y, z, 1);
					}
				}
			}
		}
		return SnapshotView.compile(builder.build());
	}
}
