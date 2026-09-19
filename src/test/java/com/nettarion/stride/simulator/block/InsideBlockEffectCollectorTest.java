package com.nettarion.stride.simulator.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.DamageEvent;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.server.TickAuthority;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ordering and reuse contracts for the retained inside-block effect collector. */
final class InsideBlockEffectCollectorTest {
	@Test
	void stepChangesQueueEffectsUntilAllImmediateBlockHooksHaveRun() {
		TickAuthority authority = TickAuthority.server();
		ServerPlayerState state = new ServerPlayerState();
		state.remainingFireTicks = -1;
		authority.begin(state);
		InsideBlockEffectCollector effects = new InsideBlockEffectCollector(authority);
		effects.begin();
		effects.advanceStep(0);
		effects.collectFireContact(1.0F);
		effects.advanceStep(1);
		assertEquals(20.0F, state.health, "advanceStep queues; it does not apply");
		authority.damage().hurtServer(HurtCause.CACTUS, 1.0F);
		effects.applyAndClear(state);
		assertEquals(HurtCause.CACTUS, authority.damage().marked().orElseThrow());
		assertEquals(19.0F, state.health);
	}

	@Test
	void freezeIsDeduplicatedPerStepAndOnlyStepsAfterClearSurvive() {
		InsideBlockEffectCollector effects = new InsideBlockEffectCollector(TickAuthority.client());
		PlayerState state = new PlayerState();
		state.ticksFrozen = 80;
		effects.begin();
		effects.advanceStep(0);
		effects.collectFreeze();
		effects.collectFreeze();
		effects.advanceStep(1);
		effects.collectClearFreeze();
		effects.collectFreeze();
		effects.advanceStep(2);
		effects.collectFreeze();
		effects.collectFreeze();
		effects.advanceStep(3);
		effects.collectFreeze();
		effects.applyAndClear(state);
		assertEquals(2, state.ticksFrozen);
		assertTrue(state.isInPowderSnow);
	}

	@Test
	void effectTypeOrderWinsOverVisitOrderAndRepeatedHitsAreRetained() {
		TickAuthority authority = TickAuthority.server();
		ServerPlayerState state = new ServerPlayerState();
		state.remainingFireTicks = -1;
		authority.begin(state);
		InsideBlockEffectCollector effects = new InsideBlockEffectCollector(authority);
		effects.begin();
		effects.advanceStep(0);
		// Visit in reverse type order; ignition happens once but both fire hits run.
		effects.collectExtinguish();
		effects.collectLavaContact();
		effects.collectFireContact(1.0F);
		effects.collectFireContact(2.0F);
		effects.applyAndClear(state);
		assertEquals(0, state.remainingFireTicks);
		assertEquals(16.0F, state.health);
		assertEquals(List.of(HurtCause.IN_FIRE, HurtCause.IN_FIRE, HurtCause.LAVA),
		    authority.damage().dealt().stream().map(DamageEvent::cause).toList());
		assertEquals(
		    List.of(1.0F, 2.0F, 4.0F), authority.damage().dealt().stream().map(DamageEvent::attempted).toList());
	}

	@Test
	void newTraversalDiscardsPendingEffectsAfterARefusedFlush() {
		TickAuthority authority = TickAuthority.server();
		ServerPlayerState refused = new ServerPlayerState();
		refused.remainingFireTicks = 159;
		authority.begin(refused);
		InsideBlockEffectCollector effects = new InsideBlockEffectCollector(authority);
		effects.begin();
		effects.advanceStep(0);
		effects.collectFreeze();
		effects.collectFireContact(1.0F);
		effects.collectLavaContact();
		assertThrows(PendingServerWriteException.class, () -> effects.applyAndClear(refused));

		ServerPlayerState next = new ServerPlayerState();
		next.ticksFrozen = 7;
		authority.begin(next);
		effects.begin();
		effects.advanceStep(0);
		effects.collectFreeze();
		effects.applyAndClear(next);
		assertEquals(8, next.ticksFrozen);
		assertEquals(-20, next.remainingFireTicks);
		assertEquals(20.0F, next.health);
		assertTrue(authority.damage().dealt().isEmpty());
	}

	@Test
	void ambiguousSweepGroupingRefusesServerEffectsAsItRefusesFreeze() {
		// Water at step 2 of one sub-movement, then fire at step 2 of the next:
		// vanilla flushes EXTINGUISH at the inert cell between them and ends the
		// tick burning; merged into one step, FIRE_IGNITE would run before
		// EXTINGUISH and end it extinguished. The pending EXTINGUISH alone must
		// make the next same-step collect refuse.
		TickAuthority authority = TickAuthority.server();
		ServerPlayerState state = new ServerPlayerState();
		state.remainingFireTicks = -1;
		authority.begin(state);
		InsideBlockEffectCollector effects = new InsideBlockEffectCollector(authority);
		effects.begin();
		effects.beginSweep();
		effects.advanceStep(2);
		effects.collectExtinguish();
		effects.beginSweep();
		assertThrows(UnimplementedMechanicException.class, () -> effects.collectFireContact(1.0F));

		effects.begin();
		effects.beginSweep();
		effects.advanceStep(2);
		effects.collectLavaContact();
		effects.beginSweep();
		assertThrows(UnimplementedMechanicException.class, effects::collectExtinguish);

		// A step change between them is a flush on both sides, so no refusal.
		effects.begin();
		effects.beginSweep();
		effects.advanceStep(2);
		effects.collectExtinguish();
		effects.beginSweep();
		effects.advanceStep(1);
		effects.collectFireContact(1.0F);
		effects.applyAndClear(state);
		assertEquals(160, state.remainingFireTicks, "extinguished, then ignited");
	}

	@Test
	void ambiguousSweepGroupingRefusesAndDoesNotPoisonTheNextTraversal() {
		InsideBlockEffectCollector effects = new InsideBlockEffectCollector(TickAuthority.client());
		effects.begin();
		effects.beginSweep();
		effects.advanceStep(2);
		effects.collectFreeze();
		effects.beginSweep();
		assertThrows(UnimplementedMechanicException.class, effects::collectFreeze);

		PlayerState next = new PlayerState();
		effects.begin();
		effects.beginSweep();
		effects.advanceStep(0);
		effects.collectFreeze();
		effects.applyAndClear(next);
		assertEquals(1, next.ticksFrozen);
	}

	@Test
	void anAmbiguousCauldronOrPowderSnowContactQueuesNothingBeforeRefusing() {
		// The ambiguity check runs before the runBefore(EXTINGUISH) closure is
		// queued, so a refused collect leaves no closure behind for the flush.
		TickAuthority authority = TickAuthority.server();
		ServerPlayerState state = new ServerPlayerState();
		state.remainingFireTicks = 100;
		authority.begin(state);
		InsideBlockEffectCollector effects = new InsideBlockEffectCollector(authority);
		effects.begin();
		effects.beginSweep();
		effects.advanceStep(2);
		effects.collectFreeze();
		effects.beginSweep();
		assertThrows(UnimplementedMechanicException.class, effects::collectPowderSnowContact);

		effects.begin();
		effects.beginSweep();
		effects.advanceStep(0);
		effects.collectFreeze();
		effects.applyAndClear(state);
		assertEquals(100, state.remainingFireTicks, "no melt refusal and no extinguish leaked from the refused step");
	}
}
