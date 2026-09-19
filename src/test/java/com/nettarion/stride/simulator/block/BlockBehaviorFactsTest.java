package com.nettarion.stride.simulator.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.server.TickAuthority;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.FlatFloorView;
import com.nettarion.stride.simulator.world.WorldView;

import org.junit.jupiter.api.Test;

/** How {@link BlockBehavior#of} admits, or refuses, one palette entry's facts. */
final class BlockBehaviorFactsTest {
	@Test
	void incompatibleOwnersCannotBecomeASyntheticBlock() {
		BlockEntry entry = BlockEntry.builder(1, "test:mixed")
		                       .insideEffect(WorldView.InsideEffect.HONEY)
		                       .landing(WorldView.Landing.HONEY)
		                       .contact(WorldView.Contact.CACTUS)
		                       .build();
		UnimplementedMechanicException refusal =
		    assertThrows(UnimplementedMechanicException.class, () -> BlockBehavior.of(entry));
		assertEquals(RefusalCause.INADMISSIBLE_BLOCK_FACTS, refusal.cause());
	}

	@Test
	void aSlimeLandingWithoutTheStepOnFactRefusesAsIncomplete() {
		BlockEntry entry = BlockEntry.builder(1, "test:partial").landing(WorldView.Landing.SLIME).build();
		UnimplementedMechanicException refusal =
		    assertThrows(UnimplementedMechanicException.class, () -> BlockBehavior.of(entry));
		assertEquals(RefusalCause.INADMISSIBLE_BLOCK_FACTS, refusal.cause());
	}

	@Test
	void aBushOfUnrecordedAgeHoldsTheClientAndRefusesTheServersVisit() {
		// A capture made before the age fact names the inside effect and leaves
		// the contact unrecorded: the stuck vector is age-independent, the hit is not.
		BlockEntry legacy = BlockEntry.builder(1, "minecraft:sweet_berry_bush")
		                        .insideEffect(WorldView.InsideEffect.SWEET_BERRY_BUSH)
		                        .contact(WorldView.Contact.UNRECORDED)
		                        .build();
		BlockBehavior behavior = BlockBehavior.of(legacy);
		assertSame(SweetBerryBushBlock.UNRECORDED_AGE, behavior);
		WorldView world = new FlatFloorView(-1, -64, 0.6F, 1.0F, 1.0F);

		PlayerState client = new PlayerState();
		behavior.entityInside(client, 0, 0, 0, true, world, new Scratch());
		assertEquals((double) 0.8F, client.stuckSpeedMultiplierX, "the client is held by the bush's vector");
		assertEquals(0.75, client.stuckSpeedMultiplierY);
		assertEquals((double) 0.8F, client.stuckSpeedMultiplierZ);

		ServerPlayerState server = new ServerPlayerState();
		Scratch scratch = new Scratch(TickAuthority.server());
		scratch.authority.begin(server);
		UnimplementedMechanicException refusal = assertThrows(
		    UnimplementedMechanicException.class, () -> behavior.entityInside(server, 0, 0, 0, true, world, scratch));
		assertEquals(RefusalCause.UNDECLARED_BLOCK_STATE, refusal.cause());
		assertEquals((double) 0.8F, server.stuckSpeedMultiplierX, "the hold precedes the refusal on the server too");
	}

	@Test
	void youngAndGrownBushesAreDistinctVanillaStates() {
		BlockEntry young = BlockEntry.builder(1, "minecraft:sweet_berry_bush")
		                       .insideEffect(WorldView.InsideEffect.SWEET_BERRY_BUSH)
		                       .contact(WorldView.Contact.NONE)
		                       .build();
		BlockEntry grown = BlockEntry.builder(2, "minecraft:sweet_berry_bush")
		                       .insideEffect(WorldView.InsideEffect.SWEET_BERRY_BUSH)
		                       .contact(WorldView.Contact.SWEET_BERRY_BUSH)
		                       .build();
		assertSame(SweetBerryBushBlock.YOUNG, BlockBehavior.of(young));
		assertSame(SweetBerryBushBlock.INSTANCE, BlockBehavior.of(grown));
	}
}
