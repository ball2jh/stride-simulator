package com.nettarion.stride.simulator.geometry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.server.TickAuthority;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.Suffocation;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;

import org.junit.jupiter.api.Test;

/**
 * {@code CollisionGetter.getPreMoveCollisions} queries under a placement
 * context, in which scaffolding and powder snow answer no shape while every
 * other block answers as it does to movement.
 */
final class PreMoveCollisionQueryTest {
	private static final double HALF = 0.3;

	@Test
	void scaffoldingAndBootedPowderSnowVanishUnderThePreMoveQueryOnly() {
		for (WorldView.CollisionBehavior behavior :
		    new WorldView.CollisionBehavior[] {WorldView.CollisionBehavior.SCAFFOLDING_SUPPORTED,
		        WorldView.CollisionBehavior.SCAFFOLDING_UNSTABLE_BOTTOM,
		        WorldView.CollisionBehavior.POWDER_SNOW_NO_BOOTS}) {
			SnapshotView world = SnapshotView.compile(oneBlock(behavior));
			Scratch scratch = new Scratch(TickAuthority.client());
			// A box sunk into the top of the block, queried by a player whose
			// feet were on its top, not descending, with boots and no fall.
			double minY = 0.92;
			CollisionBuffer movement = CollisionCollector.collect(world, scratch, 0.5 - HALF, minY, 0.5 - HALF,
			    0.5 + HALF, minY + 1.8, 0.5 + HALF, 1.0, false, 0.0, true);
			assertFalse(movement.isEmpty(), behavior + " has a shape to movement");
			CollisionBuffer preMove = CollisionCollector.collectPreMove(
			    world, scratch, 0.5 - HALF, minY, 0.5 - HALF, 0.5 + HALF, minY + 1.8, 0.5 + HALF);
			assertTrue(preMove.isEmpty(), behavior + " has no shape to placement");
		}
		SnapshotView stone = SnapshotView.compile(oneBlock(WorldView.CollisionBehavior.ORDINARY));
		assertFalse(CollisionCollector
		                .collectPreMove(stone, new Scratch(TickAuthority.client()), 0.5 - HALF, 0.92, 0.5 - HALF,
		                    0.5 + HALF, 2.72, 0.5 + HALF)
		                .isEmpty(),
		    "an ordinary block answers the same to both");
	}

	/** One block of the behavior at (0,0,0) over a stone floor whose top is at y=-1. */
	private static WorldSnapshot oneBlock(final WorldView.CollisionBehavior behavior) {
		BlockEntry.Builder block = BlockEntry.builder(2, "minecraft:test").suffocation(Suffocation.NO);
		if (behavior == WorldView.CollisionBehavior.ORDINARY) {
			block.fullCube();
		} else if (behavior == WorldView.CollisionBehavior.POWDER_SNOW_NO_BOOTS) {
			block.collisionBehavior(behavior).landing(WorldView.Landing.POWDER_SNOW);
		} else {
			block.collisionBehavior(behavior).boxes(new ShapeBox(0, 0.875, 0, 1, 1, 1),
			    new ShapeBox(0, 0, 0, 0.125, 1, 0.125), new ShapeBox(0.875, 0, 0, 1, 1, 0.125),
			    new ShapeBox(0, 0, 0.875, 0.125, 1, 1), new ShapeBox(0.875, 0, 0.875, 1, 1, 1));
		}
		WorldSnapshot.Builder world =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, -4, -6, -4, 9, 24, 9)
		        .palette(BlockEntry.builder(0, "minecraft:air").suffocation(Suffocation.NO).build(),
		            BlockEntry.builder(1, "minecraft:stone").suffocation(Suffocation.YES).fullCube().build(),
		            block.build());
		for (int z = -4; z <= 4; z++) {
			for (int x = -4; x <= 4; x++) {
				world.set(x, -2, z, 1);
			}
		}
		world.set(0, 0, 0, 2);
		return world.build();
	}
}
