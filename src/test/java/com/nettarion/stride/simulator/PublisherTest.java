package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The retained publisher, rule by rule from {@code LocalPlayer.sendPosition}.
 *
 * <p>Every expectation is read off the pinned source: the strict squared
 * threshold {@code Mth.square(2.0E-4)} against the last <em>published</em>
 * position, the twentieth reminder, the packet priority, and which baselines
 * each form advances. {@code PublicationParityVanillaTest} runs the same
 * publisher on a real client for a whole plan.
 */
class PublisherTest {
	private static final double THRESHOLD = Math.sqrt(2.0E-4 * 2.0E-4);

	@Test
	void cumulativeSubThresholdMovesPublishAgainstTheLastSentPosition() {
		PlayerState state = at(0.0, 5.0, 0.0);
		Publisher publisher = Publisher.atBoundary(state);

		state.placeAt(0.00015, 5.0, 0.0);
		assertEquals(
		    MovementPacket.NONE, publisher.publish(state), "one adjacent delta of 0.00015 is below the threshold");
		assertEquals(1, publisher.positionReminder);
		assertRaw(0.0, publisher.xLast, "a silent call leaves the position baseline");

		state.placeAt(0.00030, 5.0, 0.0);
		assertEquals(MovementPacket.POS, publisher.publish(state),
		    "the second delta is compared with the last published position, so the sum publishes");
		assertEquals(0, publisher.positionReminder);
		assertRaw(0.00030, publisher.xLast, "");
	}

	@Test
	void thresholdIsStrictAtTheSquaredTwoTenThousandths() {
		assertEquals(2.0E-4 * 2.0E-4, THRESHOLD * THRESHOLD, "the probe must sit on the boundary");
		PlayerState exact = at(0.0, 5.0, 0.0);
		Publisher publisher = Publisher.atBoundary(exact);
		exact.placeAt(THRESHOLD, 5.0, 0.0);
		assertEquals(MovementPacket.NONE, publisher.publish(exact), "equal is not greater");

		PlayerState above = at(0.0, 5.0, 0.0);
		Publisher abovePublisher = Publisher.atBoundary(above);
		above.placeAt(Math.nextUp(THRESHOLD), 5.0, 0.0);
		assertEquals(MovementPacket.POS, abovePublisher.publish(above));
	}

	@Test
	void unchangedStateStaysSilentUntilTheTwentiethCall() {
		PlayerState state = at(1.25, 5.0, -0.75);
		state.yRot = 12.5F;
		state.xRot = -7.0F;
		Publisher publisher = Publisher.atBoundary(state);
		for (int call = 1; call < 20; call++) {
			assertEquals(MovementPacket.NONE, publisher.publish(state), "call " + call);
			assertEquals(call, publisher.positionReminder);
		}

		assertEquals(MovementPacket.POS, publisher.publish(state), "the reminder forces a position");
		assertEquals(0, publisher.positionReminder);
		assertRaw(1.25, publisher.xLast, "the forced position refreshes an unchanged baseline");
	}

	@Test
	void rotationOnlyPublishesRotAndLeavesThePositionBaselineAndReminder() {
		PlayerState state = at(2.0, 5.0, -1.0);
		state.yRot = 10.0F;
		state.xRot = 20.0F;
		Publisher publisher = Publisher.atBoundary(state);

		state.yRot = 37.5F;
		state.xRot = -11.25F;
		assertEquals(MovementPacket.ROT, publisher.publish(state));
		assertEquals(37.5F, publisher.yRotLast);
		assertEquals(-11.25F, publisher.xRotLast);
		assertRaw(2.0, publisher.xLast, "rotation does not touch the position baseline");
		assertEquals(1, publisher.positionReminder, "nor the reminder");
	}

	@Test
	void contactChangeAlonePublishesStatusOnlyAndEveryCallAdvancesContactBaselines() {
		PlayerState state = at(0.5, 5.0, 0.5);
		Publisher publisher = Publisher.atBoundary(state);

		state.onGround = true;
		assertEquals(MovementPacket.STATUS_ONLY, publisher.publish(state));
		assertTrue(publisher.lastOnGround);

		assertEquals(
		    MovementPacket.NONE, publisher.publish(state), "the contact baseline advanced with the status packet");

		state.yRot = 1.0F;
		state.horizontalCollision = true;
		assertEquals(MovementPacket.ROT, publisher.publish(state),
		    "rotation outranks a contact change, which still advances its baseline");
		assertTrue(publisher.lastHorizontalCollision);
		assertEquals(MovementPacket.NONE, publisher.publish(state));
	}

	@Test
	void movementAndRotationTogetherPublishPosRotAndResetBothBaselines() {
		PlayerState state = at(0.0, 5.0, 0.0);
		Publisher publisher = Publisher.atBoundary(state);
		publisher.positionReminder = 7;

		state.placeAt(0.001, 5.0, 0.0);
		state.yRot = 15.0F;
		state.xRot = -5.0F;
		assertEquals(MovementPacket.POS_ROT, publisher.publish(state));
		assertRaw(0.001, publisher.xLast, "");
		assertEquals(15.0F, publisher.yRotLast);
		assertEquals(-5.0F, publisher.xRotLast);
		assertEquals(0, publisher.positionReminder);
	}

	@Test
	void packetForReportsTheSelectionWithoutAdvancing() {
		PlayerState state = at(0.5, 5.0, 0.5);
		Publisher publisher = Publisher.atBoundary(state);
		publisher.positionReminder = 18;

		assertEquals(MovementPacket.NONE, publisher.packetFor(state), "the nineteenth call is still silent");
		publisher.positionReminder = 19;
		assertEquals(MovementPacket.POS, publisher.packetFor(state), "the twentieth call forces a position");
		assertEquals(19, publisher.positionReminder, "reporting does not advance");
		Publisher before = publisher.copy();
		assertEquals(MovementPacket.POS, publisher.publish(state));
		assertFalse(Publisher.rawEquals(before, publisher), "publishing does");
	}

	@Test
	void theComposedStepPublishesTheClientTicksResult() {
		PlayerState state = at(0.5, 0.0, 0.5);
		state.onGround = true;
		state.mainSupportingBlockPosPresent = true;
		state.mainSupportingBlockPosY = -1;
		state.deltaMovementZ = 0.3;
		SimulationState boundary = new SimulationState(state, ServerPlayerState.atBoundary(state), 0);

		SimulationState after =
		    new Simulator().advance(boundary, PlayerInput.idle(0.0F, 0.0F), SnapshotView.compile(flatWorld())).state();

		PlayerState client = after.clientState();
		Publisher publisher = after.publisher();
		assertRaw(client.z, publisher.zLast, "the baseline is the tick's final position");
		assertEquals(0, publisher.positionReminder, "so the tick moved and published");
		assertRaw(client.z, after.serverState().z, "and the server accepted it");
	}

	@Test
	void theBookkeepingIsNotPartOfThePlayerState() {
		PlayerState standing = at(0.5, 0.0, 0.5);
		Publisher publisher = Publisher.atBoundary(standing);
		publisher.publish(standing);
		assertEquals(1, publisher.positionReminder);
		assertTrue(PlayerState.rawEquals(standing, standing.copy()),
		    "a standing player is the same state whatever its reminder says");
	}

	private static PlayerState at(final double x, final double y, final double z) {
		PlayerState state = new PlayerState();
		state.placeAt(x, y, z);
		return state;
	}

	private static WorldSnapshot flatWorld() {
		WorldSnapshot.Builder world =
		    WorldSnapshot.builder(WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING, -4, -6, -4, 9, 24, 9)
		        .palette(WorldSnapshot.BlockEntry.builder(0, "minecraft:air")
		                     .suffocation(WorldSnapshot.Suffocation.NO)
		                     .build(),
		            WorldSnapshot.BlockEntry.builder(1, "minecraft:stone")
		                .suffocation(WorldSnapshot.Suffocation.YES)
		                .fullCube()
		                .build());
		for (int z = -4; z <= 4; z++) {
			for (int x = -4; x <= 4; x++) {
				world.set(x, -1, z, 1);
			}
		}
		return world.build();
	}

	private static void assertRaw(final double expected, final double actual, final String message) {
		assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual),
		    () -> message + " expected " + expected + " but was " + actual);
	}
}
