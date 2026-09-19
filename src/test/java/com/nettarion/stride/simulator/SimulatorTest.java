package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SimulatorTest {
	private static final PlayerInput SPRINT =
	    new PlayerInput(true, false, false, false, false, false, true, 0.0F, 0.0F);
	private static final PlayerInput CROUCH =
	    new PlayerInput(false, false, false, false, false, true, false, 0.0F, 0.0F);
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);

	@Test
	void fixedRunMatchesIndividualStepsIncludingDamageAndPendingBoundaries() {
		List<PlayerInput> actions = new ArrayList<>(Collections.nCopies(6, SPRINT));
		actions.addAll(Collections.nCopies(30, IDLE));
		assertRunMatchesSteps(towerStart(), towerServerWithoutRegeneration(), null, actions, towerWorld());
		Simulator simulator = new Simulator();
		SimulationState boundary = simulator.start(towerStart(), towerServerWithoutRegeneration());
		for (int tick = 0; tick < actions.size(); tick++) {
			boundary = simulator.advance(boundary, actions.get(tick), towerWorld()).state();
			if (boundary.hasPendingEffect()) {
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
		SimulationState state = simulator.start(towerStart(), towerServerWithoutRegeneration());
		for (int tick = 0; tick < actions.size(); tick++) {
			state = simulator.advance(state, actions.get(tick), towerWorld()).state();
			if (state.hasPendingEffect()) {
				List<PlayerInput> prefix = actions.subList(0, tick + 1);
				assertThrows(PendingServerWriteException.class,
				    () -> simulator.run(towerStart(), towerServerWithoutRegeneration(), prefix, towerWorld()));
				return;
			}
		}
		throw new AssertionError("fixture must produce pending hurt");
	}

	private static void assertRunMatchesSteps(final PlayerState client, final ServerPlayerState server,
	    final HurtCause pending, final List<PlayerInput> actions, final SnapshotView world) {
		long clientBefore = StateDigest.state(client);
		long serverBefore = StateDigest.server(server);
		Simulator simulator = new Simulator();
		SimulationState state = new SimulationState(client, Publisher.atBoundary(client), server, 0, pending);
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
		Simulator.Run run = simulator.run(client, server, Optional.ofNullable(pending), actions, world);
		assertEquals(StateDigest.state(state.clientState()), StateDigest.state(run.clientState()));
		assertEquals(StateDigest.server(state.serverState()), StateDigest.server(run.serverState()));
		assertEquals(confirmations, run.confirmations());
		assertEquals(writes.size(), run.timeline().events().size());
		for (int index = 0; index < writes.size(); index++) {
			ServerWrite expected = writes.get(index);
			ServerWrite actual = run.timeline().events().get(index);
			assertEquals(expected.actionIndex(), actual.actionIndex());
			if (expected instanceof HurtMotionWrite hurt) {
				HurtMotion motion = ((HurtMotionWrite) actual).event();
				assertEquals(hurt.event().cause(), motion.cause());
				assertEquals(hurt.event().causeTick(), motion.causeTick());
				assertEquals(hurt.event().writeAfterTick(), motion.writeAfterTick());
				assertEquals(
				    Double.doubleToRawLongBits(hurt.event().writeX()), Double.doubleToRawLongBits(motion.writeX()));
				assertEquals(
				    Double.doubleToRawLongBits(hurt.event().writeY()), Double.doubleToRawLongBits(motion.writeY()));
				assertEquals(
				    Double.doubleToRawLongBits(hurt.event().writeZ()), Double.doubleToRawLongBits(motion.writeZ()));
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

	@Test
	void advancingPreservesItsInputAcrossOrdinaryAndPendingHurtBoundaries() {
		Simulator simulator = new Simulator();
		assertTrue(assertAdvancingPreservesItsInput(simulator, towerWorld(),
		    simulator.start(towerStart(), towerServerWithoutRegeneration()), 36, tick -> tick < 6 ? SPRINT : IDLE));
	}

	private static boolean assertAdvancingPreservesItsInput(final Simulator simulator, final SnapshotView world,
	    final SimulationState start, final int ticks, final java.util.function.IntFunction<PlayerInput> actions) {
		SimulationState boundary = start;
		boolean sawPending = false;
		for (int tick = 0; tick < ticks; tick++) {
			PlayerInput action = actions.apply(tick);
			long clientBefore = StateDigest.state(boundary.clientState());
			long serverBefore = StateDigest.server(boundary.serverState());
			Publisher publisherBefore = boundary.publisher();
			sawPending |= boundary.hasPendingEffect();
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
		Simulator.Step local = simulator.advance(simulator.start(initial, initial), IDLE, flatWorld());
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

		Simulator.Run replay = new Simulator().run(groundedState(), groundedState(), actions, flatWorld());

		assertFalse(replay.timeline().isEmpty());
		assertTrue(replay.timeline().events().stream().allMatch(EntityDataWrite.class ::isInstance));
		assertTrue(replay.confirmations().stream().anyMatch(Simulator.SprintConfirmation.class ::isInstance));
		assertTrue(replay.confirmations().stream().anyMatch(Simulator.PoseConfirmation.class ::isInstance));
	}

	@Test
	void matchingOldMetadataCannotChangeTheCanonicalSuccessor() {
		Simulator kernel = new Simulator();
		WriteLedger ledger = new WriteLedger();
		SimulationState state = kernel.start(groundedState(), groundedState());
		Simulator.Step started = kernel.advance(state, SPRINT, flatWorld());
		started.confirmations().forEach(ledger::expect);
		Simulator.Step stopped = kernel.advance(started.state(), IDLE, flatWorld());
		stopped.confirmations().forEach(ledger::expect);
		Simulator.Step restarted = kernel.advance(stopped.state(), SPRINT, flatWorld());
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
		Simulator kernel = new Simulator();

		Simulator.Run replay = kernel.run(towerStart(), towerServerWithoutRegeneration(), actions, towerWorld());

		assertEquals(1, replay.timeline().hurtMotion().size());
		Simulator.VelocityConfirmation velocity = replay.confirmations()
		                                              .stream()
		                                              .filter(Simulator.VelocityConfirmation.class ::isInstance)
		                                              .map(Simulator.VelocityConfirmation.class ::cast)
		                                              .findFirst()
		                                              .orElseThrow();
		WriteLedger ledger = new WriteLedger();
		ledger.expect(velocity);
		assertEquals(WriteLedger.Match.CONFIRMED, ledger.observeVelocity(velocity.x(), velocity.y(), velocity.z()));
		assertEquals(WriteLedger.Match.DUPLICATE, ledger.observeVelocity(velocity.x(), velocity.y(), velocity.z()));
		assertTrue(Double.isFinite(replay.clientState().deltaMovementY));
	}

	@Test
	void unmatchedAuthorityIsExternal() {
		WriteLedger ledger = new WriteLedger();

		assertEquals(WriteLedger.Match.UNATTRIBUTED, ledger.observeVelocity(1.0, 2.0, 3.0));
		WriteLedger.EntityDataResult result =
		    ledger.observeEntityData(Optional.of(true), Optional.of(true), Optional.of(PlayerState.Pose.SWIMMING));
		assertEquals(WriteLedger.Match.UNATTRIBUTED, result.sprint());
		assertEquals(WriteLedger.Match.UNATTRIBUTED, result.swimming());
		assertEquals(WriteLedger.Match.UNATTRIBUTED, result.pose());
	}

	@Test
	void adjacentVelocityBucketIsNotThePredictedPacket() {
		WriteLedger ledger = new WriteLedger();
		double expectedX = 0.031274;
		double expectedY = -0.0784000015258789;
		double expectedZ = -0.031274;
		ledger.expect(new Simulator.VelocityConfirmation(0, expectedX, expectedY, expectedZ));
		double adjacentX = expectedX + 2.0 / 32766.0;

		assertEquals(WriteLedger.Match.UNATTRIBUTED, ledger.observeVelocity(adjacentX, expectedY, expectedZ));
		assertEquals(WriteLedger.Match.UNATTRIBUTED, ledger.observeVelocity(adjacentX, expectedY, expectedZ));
	}

	@Test
	void differentDecodedWireBinsRemainUnattributed() {
		WriteLedger ledger = new WriteLedger();
		// Reduced from the rolling-world landing at x=265.639308. The
		// integrated server's hidden velocity had advanced far enough to decode
		// two LP-vector bins below the predicted value. These are distinct decoded
		// packets; causality does not make their movement effects identical.
		ledger.expect(
		    new Simulator.VelocityConfirmation(0, 0.004455838369041176, -0.0783739241897089, -7.324665812122877E-4));

		assertEquals(WriteLedger.Match.UNATTRIBUTED,
		    ledger.observeVelocity(0.002380441677348299, -0.0783739241897089, -7.324665812122877E-4));
		assertEquals(WriteLedger.Match.UNATTRIBUTED,
		    ledger.observeVelocity(0.00433376060550561, -0.0783739241897089, -7.324665812122877E-4));
	}

	@Test
	void hiddenCollisionResidualCannotBeConfirmedByTolerance() {
		WriteLedger ledger = new WriteLedger();
		// Reduced from random rolling seed 1473185492903643773. The
		// landing packet retained 20 more Z bins than the hidden-server replay
		// beside the block edge, while its vertical landing value was identical.
		ledger.expect(
		    new Simulator.VelocityConfirmation(0, 0.03519547116474486, -0.0783739241897089, -0.007374248489272376));

		assertEquals(WriteLedger.Match.UNATTRIBUTED,
		    ledger.observeVelocity(0.03521943477995482, -0.0783739241897089, -0.00860648232924377));
	}

	@Test
	void lateDuplicateDoesNotConsumeTheNextVelocityConfirmation() {
		WriteLedger ledger = new WriteLedger();
		Simulator.VelocityConfirmation first =
		    new Simulator.VelocityConfirmation(0, 0.031274, -0.0784000015258789, -0.031274);
		Simulator.VelocityConfirmation second =
		    new Simulator.VelocityConfirmation(1, 0.011274, -0.0784000015258789, -0.011274);
		ledger.expect(first);
		ledger.expect(second);

		assertEquals(WriteLedger.Match.CONFIRMED, ledger.observeVelocity(first.x(), first.y(), first.z()));
		assertEquals(WriteLedger.Match.DUPLICATE, ledger.observeVelocity(first.x(), first.y(), first.z()));
		assertEquals(WriteLedger.Match.CONFIRMED, ledger.observeVelocity(second.x(), second.y(), second.z()));
	}

	@Test
	void causalFallPacketCannotConfirmAnArbitraryServerPayload() {
		WriteLedger ledger = new WriteLedger();
		ledger.expect(new Simulator.VelocityConfirmation(0, 0.25, -0.0784, 0.0));

		assertEquals(WriteLedger.Match.UNATTRIBUTED, ledger.confirmVelocityCausally(0.04346, -0.078374, 0.000061));
		assertEquals(WriteLedger.Match.UNATTRIBUTED, ledger.observeVelocity(0.04346, -0.078374, 0.000061));
	}

	@Test
	void entityDataAndVelocityConfirmOnIndependentStreams() {
		WriteLedger ledger = new WriteLedger();
		ledger.expect(new Simulator.SprintConfirmation(0, false));
		ledger.expect(new Simulator.VelocityConfirmation(0, 0.031274, -0.0784000015258789, -0.031274));

		WriteLedger.EntityDataResult entity =
		    ledger.observeEntityData(Optional.of(false), Optional.empty(), Optional.empty());
		WriteLedger.Match velocity = ledger.observeVelocity(0.031274, -0.0784000015258789, -0.031274);

		assertEquals(WriteLedger.Match.CONFIRMED, entity.sprint());
		assertEquals(WriteLedger.Match.CONFIRMED, velocity);
		assertEquals(WriteLedger.Match.DUPLICATE,
		    ledger.observeEntityData(Optional.of(false), Optional.empty(), Optional.empty()).sprint());
		assertEquals(WriteLedger.Match.DUPLICATE, ledger.observeVelocity(0.031274, -0.0784000015258789, -0.031274));
	}

	@Test
	void frozenMetadataChangesOnlyWhenPublishedAndDelivered() {
		PlayerState client = groundedState();
		client.ticksFrozen = 23;
		client.frostSpeedTicks = 7;
		PlayerState server = groundedState();
		server.ticksFrozen = 5;
		server.frostSpeedTicks = 5;
		Simulator kernel = new Simulator();

		Simulator.Step step = kernel.advance(kernel.start(client, server), IDLE, flatWorld());

		assertEquals(new Simulator.FreezeWrite(23, 7), step.freezeBeforeAction());
		assertEquals(23, step.state().clientState().ticksFrozen);
		assertEquals(7, step.state().clientState().frostSpeedTicks);
		Simulator.Step delivered = kernel.advance(step.state(), IDLE, flatWorld());
		assertEquals(3, delivered.state().clientState().ticksFrozen);
		assertEquals(3, delivered.state().clientState().frostSpeedTicks);
		assertEquals(3, step.state().serverState().ticksFrozen);
		assertEquals(3, step.state().serverState().frostSpeedTicks);
		Simulator.FreezeConfirmation confirmation = delivered.confirmations()
		                                                .stream()
		                                                .filter(Simulator.FreezeConfirmation.class ::isInstance)
		                                                .map(Simulator.FreezeConfirmation.class ::cast)
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
		// next, the phase the live captures measure. Delivery at that boundary
		// is pinned separately by aPendingBoundaryPublicationIsDeliveredAfterItsFirstAction.
		assertEquals(write.event().causeTick() + 1, write.actionIndex());
		assertEquals(write.event().causeTick() + 1, write.event().writeAfterTick());
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

		Simulator.Run replay = new Simulator().run(client, server, actions, flatWorld());

		// Out of powder snow the authoritative count sheds two per action, and
		// the client movement path sees the projection installed at the boundary
		// rather than its own zero.
		assertEquals(1, replay.serverState().ticksFrozen);
		assertEquals(
		    3, replay.clientState().ticksFrozen, "the authority kernel projects server freeze onto the client");
		assertEquals(replay.serverState().ticksFrozen, replay.serverState().frostSpeedTicks,
		    "the frost speed modifier is rebuilt from the frozen count");
	}

	@Test
	void aPendingBoundaryPublicationIsDeliveredAfterItsFirstAction() {
		List<PlayerInput> actions = new ArrayList<>(Collections.nCopies(6, SPRINT));
		actions.addAll(Collections.nCopies(30, IDLE));
		Simulator kernel = new Simulator();
		SimulationState boundary = kernel.start(towerStart(), towerServerWithoutRegeneration());
		int consumed = -1;
		for (int tick = 0; tick < actions.size(); tick++) {
			boundary = kernel.advance(boundary, actions.get(tick), towerWorld()).state();
			if (boundary.hasPendingEffect()) {
				consumed = tick + 1;
				break;
			}
		}
		assertTrue(consumed > 0);

		Simulator.Run pending = kernel.run(boundary.clientState(), boundary.serverState(), boundary.pendingHurt(),
		    actions.subList(consumed, actions.size()), towerWorld());

		assertEquals(1, pending.timeline().hurtMotion().size());
		assertEquals(0, pending.timeline().hurtMotion().getFirst().actionIndex());
	}

	@Test
	void theObservedScheduleCarriesTheVelocityAfterTheNextPacket() {
		List<PlayerInput> actions = new ArrayList<>(Collections.nCopies(6, SPRINT));
		actions.addAll(Collections.nCopies(30, IDLE));

		Simulator.Run scheduled =
		    new Simulator().run(towerStart(), towerServerWithoutRegeneration(), actions, towerWorld());
		Simulator.Run observed =
		    Simulator.observed().run(towerStart(), towerServerWithoutRegeneration(), actions, towerWorld());

		HurtMotionWrite constructed = scheduled.timeline().hurtMotion().getFirst();
		HurtMotionWrite free = observed.timeline().hurtMotion().getFirst();
		assertEquals(
		    constructed.actionIndex(), free.actionIndex(), "both deliver at the boundary after the landing action");
		assertEquals(constructed.event().causeTick(), free.event().causeTick());
		assertTrue(free.event().writeY() < 0.0 && constructed.event().writeY() < 0.0);
	}

	/** Hurt publication fixtures explicitly disable the independent healing mechanic. */
	private static ServerPlayerState towerServerWithoutRegeneration() {
		ServerPlayerState server = ServerPlayerState.atBoundary(towerStart());
		server.naturalRegeneration = false;
		return server;
	}

	private static long rawBits(final double value) {
		return Double.doubleToRawLongBits(value);
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

	private static final PlayerInput GLIDE = PlayerInput.idle(-90.0F, 0.0F);

	@Test
	void aWallImpactPendsLikeALandingAndTheNextInputDeliversItsWrite() {
		Simulator kernel = new Simulator();
		PlayerState start = glideStart();
		PlayerInput glide = GLIDE;
		SnapshotView world = wallWorld();

		Simulator.Step impact = kernel.advance(kernel.start(start, start), glide, world);
		assertEquals(Optional.of(HurtCause.FLY_INTO_WALL), impact.state().pendingHurt(),
		    "the server's glide travel hit the wall in this input's connection tick");
		assertEquals(1, impact.damage().size(), "the hit is on the stream at its input");
		assertTrue(impact.hasDamage());
		assertTrue(impact.writes().stream().anyMatch(HealthWrite.class ::isInstance));
		DamageWrite hit = impact.damage().getFirst();
		assertEquals(0, hit.actionIndex());
		assertEquals(HurtCause.FLY_INTO_WALL, hit.event().cause());
		assertTrue(hit.event().full());
		assertEquals(20.0F - hit.event().healthDamage(), impact.state().serverState().health);
		assertEquals(20, impact.state().serverState().invulnerableTime);
		assertTrue(impact.state().clientState().horizontalCollision);

		Simulator.Step delivery = kernel.advance(impact.state(), glide, world);
		assertTrue(delivery.state().pendingHurt().isEmpty());
		assertEquals(1, delivery.hurtMotion().size());
		HurtMotionWrite write = delivery.hurtMotion().getFirst();
		assertEquals(HurtCause.FLY_INTO_WALL, write.event().cause());
		assertEquals(0, write.event().causeTick());
		assertEquals(1, write.actionIndex());
		assertTrue(delivery.confirmations().stream().anyMatch(Simulator.VelocityConfirmation.class ::isInstance),
		    "the hurt-marked publication has a decoded wire value to confirm");
	}

	/** Stone floor at y=-1 and a stone wall filling x=0 from y=3 to y=6. */
	private static SnapshotView wallWorld() {
		WorldSnapshot.BlockEntry air = new WorldSnapshot.BlockEntry(0, "air", 0.6F, 1.0F, 1.0F, List.of());
		WorldSnapshot.BlockEntry stone = new WorldSnapshot.BlockEntry(
		    1, "stone", 0.6F, 1.0F, 1.0F, List.of(new WorldSnapshot.ShapeBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)));
		WorldSnapshot.Builder builder =
		    WorldSnapshot.builder(WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING, -6, -3, -6, 12, 12, 12)
		        .palette(air, stone);
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

	private static PlayerState towerStart() {
		PlayerState state = groundedState();
		state.placeAt(3.0, 7.0, 3.0);
		state.mainSupportingBlockPosX = 2;
		state.mainSupportingBlockPosY = 6;
		state.mainSupportingBlockPosZ = 2;
		return state;
	}

	private static SnapshotView flatWorld() {
		return world(false);
	}

	private static SnapshotView towerWorld() {
		return world(true);
	}

	private static SnapshotView world(final boolean tower) {
		int size = 32;
		int height = 12;
		int[] cells = new int[size * height * size];
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++)
				cells[z * size + x] = 1;
		}
		if (tower) {
			for (int y = 1; y <= 6; y++) {
				for (int z = 2; z <= 3; z++) {
					for (int x = 2; x <= 3; x++) {
						cells[(y * size + z) * size + x] = 1;
					}
				}
			}
		}
		WorldSnapshot.BlockEntry air = new WorldSnapshot.BlockEntry(0, "air", 0.6F, 1.0F, 1.0F, List.of());
		WorldSnapshot.BlockEntry stone = new WorldSnapshot.BlockEntry(
		    1, "stone", 0.6F, 1.0F, 1.0F, List.of(new WorldSnapshot.ShapeBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)));
		return SnapshotView.compile(new WorldSnapshot(
		    0, 0, 0, size, height, size, WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING, List.of(air, stone), cells));
	}
}
