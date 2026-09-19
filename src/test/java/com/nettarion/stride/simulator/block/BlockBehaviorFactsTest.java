package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.nettarion.stride.simulator.world.ShapeProvenance;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.Suffocation;

class BlockBehaviorFactsTest {
	@Test
	void incompatibleOwnersCannotBecomeASyntheticBlock() {
		var entry = BlockEntry.builder(1, "test:mixed")
		                .insideEffect(WorldView.InsideEffect.HONEY)
		                .landing(WorldView.Landing.HONEY)
		                .contact(WorldView.Contact.CACTUS)
		                .build();
		assertThrows(UnimplementedMechanicException.class, () -> BlockBehavior.of(entry));
	}

	@Test
	void incompleteKnownBehaviorRequiresExplicitMigration() {
		var entry = BlockEntry.builder(1, "test:partial").landing(WorldView.Landing.SLIME).build();
		assertThrows(UnimplementedMechanicException.class, () -> BlockBehavior.of(entry));
	}

	@Test
	void aBushOfUnrecordedAgeHoldsTheClientAndRefusesTheServersVisit() {
		// A legacy row names the inside effect and leaves the contact unknown:
		// the stuck vector is age-independent, the hit is not.
		var legacy = BlockEntry.builder(1, "minecraft:sweet_berry_bush")
		                 .insideEffect(WorldView.InsideEffect.SWEET_BERRY_BUSH)
		                 .contact(WorldView.Contact.UNKNOWN)
		                 .build();
		var behavior = BlockBehavior.of(legacy);
		assertEquals("sweet berry bush of unknown age", behavior.toString());
		assertEquals(behavior,
		    BlockBehavior.of(new BlockEntry(1, "minecraft:sweet_berry_bush", 0.6F, 1.0F, 1.0F, false,
		        false, WorldView.BubbleColumnMode.NONE, false, WorldView.CollisionBehavior.ORDINARY,
		        Suffocation.NO, WorldView.InsideEffect.SWEET_BERRY_BUSH, 0.0F, false,
		        WorldView.StepOn.NONE, false, WorldView.Climbability.NONE, ShapeProvenance.GENERAL,
		        java.util.List.of())),
		    "the compatibility constructor's legacy contact compiles to the same bush");
	}

	@Test
	void youngAndGrownBushesAreDistinctSourceStates() {
		var young = BlockEntry.builder(1, "minecraft:sweet_berry_bush")
		                .insideEffect(WorldView.InsideEffect.SWEET_BERRY_BUSH)
		                .contact(WorldView.Contact.NONE)
		                .build();
		var grown = BlockEntry.builder(2, "minecraft:sweet_berry_bush")
		                .insideEffect(WorldView.InsideEffect.SWEET_BERRY_BUSH)
		                .contact(WorldView.Contact.SWEET_BERRY_BUSH)
		                .build();
		assertSame(SweetBerryBushBlock.YOUNG, BlockBehavior.of(young));
		assertSame(SweetBerryBushBlock.INSTANCE, BlockBehavior.of(grown));
	}
}
