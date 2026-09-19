package com.nettarion.stride.simulator.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nettarion.stride.simulator.tick.Scratch;

import org.junit.jupiter.api.Test;

final class ShapeCollisionOverlapTest {
	@Test
	void resolvingMovementDoesNotShowTheStartingBoxWasFree() {
		CollisionBuffer shapes = new CollisionBuffer();
		shapes.add(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
		Scratch scratch = new Scratch();

		// The moving box already intersects the cube. Axis clipping prevents new
		// intersections; like vanilla, it does not repair an existing one.
		ShapeCollision.collideWithShapes(0.25, 0.5, 0.25, 0.75, 2.3, 0.75, 0.0, -0.1, 0.0, shapes, scratch);

		assertEquals(-0.1, scratch.movedY, "an unchanged request does not show that the destination pose fits");
	}
}
