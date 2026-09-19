package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockBehaviourAdmissionTest {
	@Test
	void incompatibleOwnersCannotBecomeASyntheticBlock() {
		var entry = WorldSnapshot.BlockEntry.builder(1, "test:mixed")
		                .insideEffect(WorldView.InsideEffect.HONEY)
		                .landing(WorldView.Landing.HONEY)
		                .contact(WorldView.Contact.CACTUS)
		                .build();
		assertThrows(UnimplementedMechanicException.class, () -> BlockBehaviour.of(entry));
	}

	@Test
	void incompleteKnownBehaviourRequiresExplicitMigration() {
		var entry = WorldSnapshot.BlockEntry.builder(1, "test:partial").landing(WorldView.Landing.SLIME).build();
		assertThrows(UnimplementedMechanicException.class, () -> BlockBehaviour.of(entry));
	}

	@Test
	void aBushOfUnrecordedAgeHoldsTheClientAndRefusesTheServersVisit() {
		// A legacy row names the inside effect and leaves the contact unknown:
		// the stuck vector is age-independent, the hit is not.
		var legacy = WorldSnapshot.BlockEntry.builder(1, "minecraft:sweet_berry_bush")
		                 .insideEffect(WorldView.InsideEffect.SWEET_BERRY_BUSH)
		                 .contact(WorldView.Contact.UNKNOWN)
		                 .build();
		var behaviour = BlockBehaviour.of(legacy);
		assertEquals("sweet berry bush of unknown age", behaviour.toString());
		assertEquals(behaviour,
		    BlockBehaviour.of(new WorldSnapshot.BlockEntry(1, "minecraft:sweet_berry_bush", 0.6F, 1.0F, 1.0F, false,
		        false, WorldView.BubbleColumnMode.NONE, false, WorldView.CollisionBehavior.ORDINARY,
		        WorldSnapshot.Suffocation.NO, WorldView.InsideEffect.SWEET_BERRY_BUSH, 0.0F, false,
		        WorldView.StepOn.NONE, false, WorldView.Climbability.NONE, WorldSnapshot.CollisionShapeIdentity.GENERAL,
		        java.util.List.of())),
		    "the compatibility constructor's legacy contact compiles to the same bush");
	}

	@Test
	void youngAndGrownBushesAreDistinctSourceStates() {
		var young = WorldSnapshot.BlockEntry.builder(1, "minecraft:sweet_berry_bush")
		                .insideEffect(WorldView.InsideEffect.SWEET_BERRY_BUSH)
		                .contact(WorldView.Contact.NONE)
		                .build();
		var grown = WorldSnapshot.BlockEntry.builder(2, "minecraft:sweet_berry_bush")
		                .insideEffect(WorldView.InsideEffect.SWEET_BERRY_BUSH)
		                .contact(WorldView.Contact.SWEET_BERRY_BUSH)
		                .build();
		assertSame(SweetBerryBushBlock.YOUNG, BlockBehaviour.of(young));
		assertSame(SweetBerryBushBlock.INSTANCE, BlockBehaviour.of(grown));
	}
}
