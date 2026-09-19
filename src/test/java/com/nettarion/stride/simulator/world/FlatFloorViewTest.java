package com.nettarion.stride.simulator.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;

import org.junit.jupiter.api.Test;

/** The uniform floor admits cells as {@code BlockCollisions} does: open on every face. */
final class FlatFloorViewTest {
	@Test
	void aFaceRestingOnACellBoundaryReachesNoCellBeyondItOnAnyAxis() {
		FlatFloorView world = FlatFloorView.ordinary(4, 0);
		CollisionBuffer target = new CollisionBuffer();

		world.collectCollisionBoxes(0.4, 1.0, 0.4, 1.0, 2.0, 1.0, target);
		assertEquals(1, target.selectedGroupCount(), "only the cell the box's interior reaches");
		assertEquals(0.0, target.minX(0));
		assertEquals(1.0, target.minY(0));
		assertEquals(0.0, target.minZ(0));

		world.collectCollisionBoxes(0.4, 1.0, 0.4, 1.0, 2.0, 1.0 + 1.0E-9, target);
		assertEquals(2, target.selectedGroupCount(), "an interior past the boundary reaches the next cell");

		world.collectCollisionBoxes(0.4, 4.0, 0.4, 1.0, 5.0, 1.0, target);
		assertTrue(target.isEmpty(), "the box resting on the floor top touches no floor cell");
	}

	@Test
	void aSupportSlabRestingOnACellBoundaryDoesNotNameTheCellBeyondIt() {
		FlatFloorView world = FlatFloorView.ordinary(4, 0);
		SupportCell out = new SupportCell();

		// The slab reaches x = 1.0 exactly; the cell at x = 1 is not a candidate
		// on X any more than it is on Z, whichever is nearer the center.
		world.findSupportingBlock(0.4, 4.0 - 1.0E-6, 0.4, 1.0, 4.0, 1.0, 0.99, 4.0, 0.5, out);
		assertTrue(out.present);
		assertEquals(0, out.x);
		assertEquals(3, out.y);
		assertEquals(0, out.z);

		world.findSupportingBlock(0.4, 4.0 - 1.0E-6, 0.4, 1.0, 4.0, 1.0, 0.5, 4.0, 0.99, out);
		assertEquals(0, out.x);
		assertEquals(0, out.z);
	}

	@Test
	void theFloorHasCoefficientsButNoIdentitySoSuffocationInsideItRefuses() {
		FlatFloorView world = new FlatFloorView(4, -8, 0.98F, 1.0F, 1.0F);
		assertEquals(0.98F, world.friction(0, 3, 0));
		assertEquals(0.6F, world.friction(0, 4, 0), "air above the floor");
		assertEquals(0.6F, world.friction(0, -9, 0), "nothing below minY");
		assertTrue(world.hasNonDefaultFriction());
		assertFalse(world.suffocatesAt(0, 0, 4.0, 5.8), "the column above the floor is air");
		assertThrows(UnimplementedMechanicException.class, () -> world.suffocatesAt(0, 0, 3.5, 5.3));
		assertEquals(-8, world.minY());
		assertTrue(world.hasChunkAt(1_000_000, -1_000_000), "unbounded horizontally");
	}
}
