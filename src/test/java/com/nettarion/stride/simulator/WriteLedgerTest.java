package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A ledger matches observed packets against confirmations by exact decoded value, on independent streams. */
final class WriteLedgerTest {
	@Test
	void unmatchedObservationsAreUnattributed() {
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
		// Reduced from a recorded landing at x=265.639308. The integrated
		// server's hidden velocity had advanced far enough to decode two
		// packet bins below the predicted value. These are distinct decoded
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
		// Reduced from a recorded landing beside a block edge: the landing
		// packet retained 20 more Z bins than the replay while its vertical
		// landing value was identical.
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

		assertEquals(WriteLedger.Match.UNATTRIBUTED, ledger.observeVelocity(0.04346, -0.078374, 0.000061));
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
}
