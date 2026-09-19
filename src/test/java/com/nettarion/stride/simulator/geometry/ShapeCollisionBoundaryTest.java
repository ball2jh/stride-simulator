package com.nettarion.stride.simulator.geometry;

import com.nettarion.stride.simulator.tick.Scratch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Raw-bit boundaries inherited from Minecraft's Shapes/VoxelShape collision loop. */
class ShapeCollisionBoundaryTest {
	private static final double EPSILON = 1.0E-7;

	@Test
	void emptyAndNonEmptyShapeSetsPlaceTheDeadZoneDifferently() {
		Scratch scratch = new Scratch();
		CollisionBuffer empty = new CollisionBuffer();
		double tiny = Math.nextDown(EPSILON);

		ShapeCollision.collideWithShapes(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, tiny, 0.0, 0.0, empty, scratch);
		assertEquals(Double.doubleToRawLongBits(tiny), Double.doubleToRawLongBits(scratch.movedX));

		CollisionBuffer irrelevant = new CollisionBuffer();
		irrelevant.add(3.0, 0.0, 0.0, 4.0, 1.0, 1.0);
		ShapeCollision.collideWithShapes(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, tiny, 0.0, 0.0, irrelevant, scratch);
		assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(scratch.movedX));

		ShapeCollision.collideWithShapes(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, EPSILON, 0.0, 0.0, irrelevant, scratch);
		assertEquals(Double.doubleToRawLongBits(EPSILON), Double.doubleToRawLongBits(scratch.movedX),
		    "the source cutoff is strict");
	}

	@Test
	void aSingleShapeMayReturnATinyReverseCorrection() {
		Scratch scratch = new Scratch();
		CollisionBuffer shapes = new CollisionBuffer();
		double face = Math.nextDown(1.0);
		shapes.add(face, 0.0, 0.0, 2.0, 1.0, 1.0);

		ShapeCollision.collideWithShapes(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 0.2, 0.0, 0.0, shapes, scratch);
		assertTrue(scratch.movedX < 0.0 && scratch.movedX > -EPSILON);
		assertEquals(Double.doubleToRawLongBits(face - 1.0), Double.doubleToRawLongBits(scratch.movedX));

		shapes.add(3.0, 0.0, 0.0, 4.0, 1.0, 1.0);
		ShapeCollision.collideWithShapes(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 0.2, 0.0, 0.0, shapes, scratch);
		assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(scratch.movedX));
	}

	@Test
	void compositeShapeKeepsTinyCorrectionUntilTheNextSourceShape() {
		Scratch scratch = new Scratch();
		CollisionBuffer shapes = new CollisionBuffer();
		double face = Math.nextDown(1.0);
		shapes.add(face, 0.0, 0.0, 2.0, 1.0, 1.0);
		shapes.addPart(3.0, 0.0, 0.0, 4.0, 1.0, 1.0);

		ShapeCollision.collideWithShapes(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 0.2, 0.0, 0.0, shapes, scratch);

		assertEquals(Double.doubleToRawLongBits(face - 1.0), Double.doubleToRawLongBits(scratch.movedX),
		    "vanilla applies the dead zone once after the whole VoxelShape");
	}

	@Test
	void compositeShapeKeepsItsEntryDirectionAfterATinyReverseCorrection() {
		Scratch scratch = new Scratch();
		CollisionBuffer shapes = new CollisionBuffer();
		double forwardFace = 1.0 - 5.0E-8;
		shapes.add(forwardFace, 0.0, 0.0, 2.0, 1.0, 1.0);
		shapes.addPart(-1.0, 0.0, 0.0, 5.0E-8, 1.0, 1.0);

		ShapeCollision.collideWithShapes(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 0.2, 0.0, 0.0, shapes, scratch);

		assertEquals(Double.doubleToRawLongBits(forwardFace - 1.0), Double.doubleToRawLongBits(scratch.movedX),
		    "VoxelShape selects its positive branch once before visiting parts");
	}

	@Test
	void signedZeroIsPreservedOnlyByTheEmptyFastPath() {
		Scratch scratch = new Scratch();
		CollisionBuffer empty = new CollisionBuffer();
		ShapeCollision.collideWithShapes(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, -0.0, 0.0, 0.0, empty, scratch);
		assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(scratch.movedX));

		CollisionBuffer irrelevant = new CollisionBuffer();
		irrelevant.add(3.0, 0.0, 0.0, 4.0, 1.0, 1.0);
		ShapeCollision.collideWithShapes(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, -0.0, 0.0, 0.0, irrelevant, scratch);
		assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(scratch.movedX));
	}

	@Test
	void horizontalAxisOrderFlipsAtTheExactMagnitudeTie() {
		Scratch scratch = new Scratch();
		CollisionBuffer shapes = new CollisionBuffer();
		shapes.add(1.0, 0.0, 1.0, 2.0, 1.0, 2.0);
		double[] requestedX = {Math.nextDown(0.5), 0.5, Math.nextUp(0.5)};
		double[][] expected = {{0.0, 0.5}, {0.5, 0.0}, {Math.nextUp(0.5), 0.0}};

		for (int row = 0; row < requestedX.length; row++) {
			ShapeCollision.collideWithShapes(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, requestedX[row], 0.0, 0.5, shapes, scratch);
			assertEquals(Double.doubleToRawLongBits(expected[row][0]), Double.doubleToRawLongBits(scratch.movedX));
			assertEquals(Double.doubleToRawLongBits(expected[row][1]), Double.doubleToRawLongBits(scratch.movedZ));
		}
	}
}
