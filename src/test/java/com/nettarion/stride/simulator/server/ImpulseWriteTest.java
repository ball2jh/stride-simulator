package com.nettarion.stride.simulator.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nettarion.stride.simulator.ExternalActor;
import com.nettarion.stride.simulator.ImpulseWrite;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.Simulator;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.WriteLedger;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * The impulse write's algebra on the stream: add and replace do not commute, equal payloads are distinct events, a
 * collision-resolved move is not a velocity, and the one ledger attributes each operation in its own space. A
 * root-package type tested here beside the other write kinds.
 */
final class ImpulseWriteTest {
	private static final ExternalActor ROCKET = ExternalActor.fireworkRocket(42);

	@Test
	void overflowingAnAddPreservesTheWholePreviousVector() {
		for (int axis = 0; axis < 3; axis++) {
			PlayerState state = moving();
			double[] before = {state.deltaMovementX, state.deltaMovementY, state.deltaMovementZ};
			double[] impulse = {0.125, 0.25, -0.5};
			before[axis] = axis == 1 ? -Double.MAX_VALUE : Double.MAX_VALUE;
			impulse[axis] = before[axis];
			state.deltaMovementX = before[0];
			state.deltaMovementY = before[1];
			state.deltaMovementZ = before[2];
			new ImpulseWrite(0, ExternalActor.explosion(), 1L, ImpulseWrite.Phase.PACKET, ImpulseWrite.Operation.ADD,
			    impulse[0], impulse[1], impulse[2])
			    .applyAfterAction(0, state);
			assertEquals(before[0], state.deltaMovementX);
			assertEquals(before[1], state.deltaMovementY);
			assertEquals(before[2], state.deltaMovementZ);
		}
	}

	@Test
	void addThenReplaceDiffersFromReplaceThenAdd() {
		ImpulseWrite add = new ImpulseWrite(
		    3, ExternalActor.explosion(), 1L, ImpulseWrite.Phase.PACKET, ImpulseWrite.Operation.ADD, 0.125, 0.25, -0.5);
		ImpulseWrite replace = new ImpulseWrite(
		    3, ROCKET, 2L, ImpulseWrite.Phase.ACTOR_TICK, ImpulseWrite.Operation.REPLACE, 0.85, 0.0, 0.1);

		PlayerState addFirst = moving();
		add.applyAfterAction(3, addFirst);
		replace.applyAfterAction(3, addFirst);
		PlayerState replaceFirst = moving();
		replace.applyAfterAction(3, replaceFirst);
		add.applyAfterAction(3, replaceFirst);

		assertEquals(0.85, addFirst.deltaMovementX, "a replacement erases the add before it");
		assertEquals(0.975, replaceFirst.deltaMovementX, "an add after the replacement survives");
		assertNotEquals(Double.doubleToRawLongBits(addFirst.deltaMovementX),
		    Double.doubleToRawLongBits(replaceFirst.deltaMovementX));
	}

	@Test
	void equalPayloadsWithDistinctSequencesBothApply() {
		ImpulseWrite first = new ImpulseWrite(
		    0, ExternalActor.explosion(), 7L, ImpulseWrite.Phase.PACKET, ImpulseWrite.Operation.ADD, 0.1, 0.2, 0.3);
		ImpulseWrite second = new ImpulseWrite(
		    0, ExternalActor.explosion(), 8L, ImpulseWrite.Phase.PACKET, ImpulseWrite.Operation.ADD, 0.1, 0.2, 0.3);
		assertNotEquals(first, second, "the sequence is part of the identity");

		PlayerState state = new PlayerState();
		first.applyAfterAction(0, state);
		second.applyAfterAction(0, state);

		assertEquals(0.2, state.deltaMovementX);
		assertEquals(0.4, state.deltaMovementY);
		assertEquals(0.6, state.deltaMovementZ);
	}

	@Test
	void aWriteAppliesOnlyAtItsOwnActionIndexAndRebasesWithTheActionList() {
		ImpulseWrite write = new ImpulseWrite(
		    5, ROCKET, 1L, ImpulseWrite.Phase.ACTOR_TICK, ImpulseWrite.Operation.REPLACE, 1.0, 0.0, 0.0);
		PlayerState state = moving();

		write.applyAfterAction(4, state);
		assertEquals(0.25, state.deltaMovementX, "another action index leaves the state alone");

		assertEquals(Optional.empty(), write.afterConsuming(6));
		assertEquals(3, write.afterConsuming(2).orElseThrow().actionIndex());
		assertEquals(9, write.delayedBy(4).actionIndex());
		assertEquals(write.actor(), assertInstanceOf(ImpulseWrite.class, write.delayedBy(4)).actor());
	}

	@Test
	void aCollisionResolvedMoveRefusesInsteadOfBecomingAVelocity() {
		ImpulseWrite move =
		    new ImpulseWrite(0, ROCKET, 1L, ImpulseWrite.Phase.ACTOR_TICK, ImpulseWrite.Operation.MOVE, 0.0, 0.0, 0.5);

		assertThrows(UnimplementedMechanicException.class, () -> move.applyAfterAction(0, new PlayerState()));
		assertThrows(IllegalArgumentException.class,
		    () -> new Simulator.ImpulseConfirmation(0, ROCKET, ImpulseWrite.Operation.MOVE, 0.0, 0.0, 0.5));
	}

	@Test
	void anActorIsNamedByKindAndEntity() {
		assertEquals(ExternalActor.NO_ENTITY, ExternalActor.explosion().entityId());
		assertThrows(IllegalArgumentException.class, () -> new ExternalActor(ExternalActor.Kind.EXPLOSION, 3));
		assertThrows(IllegalArgumentException.class, () -> ExternalActor.fireworkRocket(-1));
	}

	@Test
	void theLedgerAttributesAnAddBitForBitAndAReplacementInPacketSpace() {
		WriteLedger ledger = new WriteLedger();
		ledger.expect(new Simulator.ImpulseConfirmation(
		    0, ExternalActor.explosion(), ImpulseWrite.Operation.ADD, 0.125, 0.25, -0.5));
		ledger.expect(new Simulator.ImpulseConfirmation(
		    0, ROCKET, ImpulseWrite.Operation.REPLACE, 0.031274, -0.0784000015258789, -0.031274));

		assertEquals(WriteLedger.Match.UNATTRIBUTED,
		    ledger.observeImpulse(ImpulseWrite.Operation.ADD, 0.125, 0.25, -0.5 + 1.0E-9),
		    "the explode packet carries full doubles, so an add matches bit for bit");
		assertEquals(WriteLedger.Match.CONFIRMED, ledger.observeImpulse(ImpulseWrite.Operation.ADD, 0.125, 0.25, -0.5));
		assertEquals(WriteLedger.Match.DUPLICATE, ledger.observeImpulse(ImpulseWrite.Operation.ADD, 0.125, 0.25, -0.5));
		assertEquals(WriteLedger.Match.UNATTRIBUTED,
		    ledger.observeImpulse(
		        ImpulseWrite.Operation.REPLACE, 0.031274 + 2.0 / 32766.0, -0.0784000015258789, -0.031274),
		    "a different decoded motion packet is a different replacement");
		assertEquals(WriteLedger.Match.UNATTRIBUTED,
		    ledger.observeImpulse(ImpulseWrite.Operation.REPLACE, 1.0, 2.0, 3.0),
		    "an impulse nobody predicted is a correction");
	}

	private static PlayerState moving() {
		PlayerState state = new PlayerState();
		state.deltaMovementX = 0.25;
		state.deltaMovementY = -0.5;
		state.deltaMovementZ = 0.75;
		return state;
	}
}
