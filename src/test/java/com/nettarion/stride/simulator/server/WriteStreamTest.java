package com.nettarion.stride.simulator.server;

import static com.nettarion.stride.simulator.server.ServerTestWorlds.EXTRA;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.STONE;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.floor;
import static com.nettarion.stride.simulator.server.ServerTestWorlds.region;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.BlockUpdateWrite;
import com.nettarion.stride.simulator.DamageEvent;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.Simulator;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.WriteLedger;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.SnapshotView;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The write kinds with no test of their own: the block update, the damage event's flags, and damage attribution. */
final class WriteStreamTest {
	@Test
	void aBlockUpdateRefusesAPlayerOnlyDeliveryAndReplacesTheCellInAWorld() {
		BlockUpdateWrite write = new BlockUpdateWrite(3, 0, 0, 0, EXTRA);
		PlayerState client = new PlayerState();
		write.applyAfterAction(2, client);
		UnimplementedMechanicException refusal =
		    assertThrows(UnimplementedMechanicException.class, () -> write.applyAfterAction(3, client));
		assertEquals(RefusalCause.UNMODELED_WORLD_WRITE, refusal.cause());

		BlockEntry lowered = BlockEntry.builder(EXTRA, "minecraft:water_cauldron").build();
		SnapshotView world = SnapshotView.compile(floor(region(lowered), STONE).set(0, 0, 0, STONE).build()).fork();
		assertEquals(STONE, world.paletteIndexAt(0, 0, 0));
		write.applyTo(world);
		assertEquals(EXTRA, world.paletteIndexAt(0, 0, 0));

		assertEquals(Optional.empty(), write.afterConsuming(4));
		assertEquals(1, write.afterConsuming(2).orElseThrow().actionIndex());
		assertEquals(5, write.delayedBy(2).actionIndex());
		assertThrows(IllegalArgumentException.class, () -> write.afterConsuming(-1));
		assertThrows(IllegalArgumentException.class, () -> write.delayedBy(-1));
		assertThrows(IllegalArgumentException.class, () -> new BlockUpdateWrite(-1, 0, 0, 0, 1));
		assertThrows(IllegalArgumentException.class, () -> new BlockUpdateWrite(0, 0, 0, 0, -1));
	}

	@Test
	void aDamageEventTellsABlockedHitFromAPartialOneAndAMarkingOne() {
		DamageEvent blocked = new DamageEvent(HurtCause.CACTUS, 1.0F, 0.0F, 0.0F, 19.0F, false);
		assertTrue(blocked.blockedByCooldown());
		assertFalse(blocked.marks());
		DamageEvent partial = new DamageEvent(HurtCause.LAVA, 4.0F, 0.0F, 3.0F, 16.0F, false);
		assertFalse(partial.blockedByCooldown());
		assertFalse(partial.marks());
		DamageEvent full = new DamageEvent(HurtCause.CACTUS, 1.0F, 0.0F, 1.0F, 19.0F, true);
		assertFalse(full.blockedByCooldown());
		assertTrue(full.marks());
		DamageEvent drowned = new DamageEvent(HurtCause.DROWN, 2.0F, 0.0F, 2.0F, 18.0F, true);
		assertFalse(drowned.marks(), "drowning is in no_impact");
		assertThrows(IllegalArgumentException.class, () -> new DamageEvent(HurtCause.CACTUS, -1.0F, 0, 0, 20, true));
	}

	@Test
	void theLedgerAttributesADamageEventPacketByCause() {
		WriteLedger ledger = new WriteLedger();
		ledger.expect(new Simulator.DamageConfirmation(0, HurtCause.CACTUS));
		assertEquals(WriteLedger.Match.UNATTRIBUTED, ledger.observeDamage(HurtCause.LAVA));
		assertEquals(WriteLedger.Match.CONFIRMED, ledger.observeDamage(HurtCause.CACTUS));
		assertEquals(WriteLedger.Match.DUPLICATE, ledger.observeDamage(HurtCause.CACTUS));
		assertTrue(WriteLedger.Match.DUPLICATE.predicted());
		assertFalse(WriteLedger.Match.UNATTRIBUTED.predicted());
		assertFalse(WriteLedger.Match.ABSENT.present());
	}
}
