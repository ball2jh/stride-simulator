package com.nettarion.stride.simulator.geometry;

import com.nettarion.stride.simulator.tick.Scratch;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The resolver and the broadphase against line-by-line transcriptions of
 * {@code VoxelShape.collideX}, {@code Shapes.collide} and
 * {@code IndirectMerger}, swept one ulp at a time across every place the
 * source applies its {@code 1.0E-7}.
 *
 * <p>The transcriptions keep the source's operand order and its placement
 * of the epsilon, which is what these sweeps are sensitive to: an expression
 * that is equal in real arithmetic and differs by one rounding is exactly
 * what they find.
 */
class VanillaCollisionReferenceTest {
	private static final double EPSILON = 1.0E-7;
	private static final int ULPS = 64;

	@Test
	void aFaceExactlyOneEpsilonPastTheShapeIsBehindIt() {
		// The moving box already penetrates the cube by fl(1.0E-7). Vanilla
		// floors that face back to 1.0, finds the cube's first cell at index
		// zero, and starts its scan past it: the cube is behind the box.
		Scratch scratch = new Scratch();
		CollisionBuffer shapes = new CollisionBuffer();
		shapes.addCanonicalFullCube(1, 0, 0);
		double maxX = 1.0 + EPSILON;

		ShapeCollision.collideWithShapes(maxX - 0.6, 0.0, 0.2, maxX, 1.8, 0.8, 0.1, 0.0, 0.0, shapes, scratch);

		assertEquals(Double.doubleToRawLongBits(0.1), Double.doubleToRawLongBits(scratch.movedX));
		assertEquals(referenceCollide(fullCube(1, 0, 0), maxX - 0.6, 0.0, 0.2, maxX, 1.8, 0.8, 0.1), scratch.movedX);
	}

	@Test
	void aheadAndBehindFacesMatchVanillaAcrossTheEpsilonBand() {
		double[] cube = fullCube(1, 0, 0);
		for (int ulp = -ULPS; ulp <= ULPS; ulp++) {
			double maxX = step(1.0 + EPSILON, ulp);
			assertResolves(cube, maxX - 0.6, 0.0, 0.2, maxX, 1.8, 0.8, 0.1, "ahead at " + ulp);
			double minX = step(2.0 - EPSILON, ulp);
			assertResolves(cube, minX, 0.0, 0.2, minX + 0.6, 1.8, 0.8, -0.1, "behind at " + ulp);
			double touching = step(1.0, ulp);
			assertResolves(cube, touching - 0.6, 0.0, 0.2, touching, 1.8, 0.8, 0.1, "touching at " + ulp);
		}
	}

	@Test
	void windowBoundsMatchVanillaAcrossTheEpsilonBand() {
		// A slab whose Y extent the moving box only just reaches, or just misses.
		double[] slab = {1.0, 0.5, 0.0, 2.0, 1.5, 1.0};
		for (int ulp = -ULPS; ulp <= ULPS; ulp++) {
			double maxY = step(0.5 + EPSILON, ulp);
			assertResolves(slab, 0.3, -1.0, 0.2, 0.9, maxY, 0.8, 0.1, "window top at " + ulp);
			double minY = step(1.5 - EPSILON, ulp);
			assertResolves(slab, 0.3, minY, 0.2, 0.9, 3.0, 0.8, 0.1, "window bottom at " + ulp);
			double maxZ = step(EPSILON, ulp);
			assertResolves(slab, 0.3, 0.6, -1.0, 0.9, 1.2, maxZ, 0.1, "window side at " + ulp);
		}
	}

	@Test
	void aCompositeShapeStopsAtItsNearestFaceOnly() {
		// Two boxes of one source shape: the nearer one lies exactly one
		// epsilon inside the moving face, so vanilla's scan reaches its slab
		// first, fails the dead-zone test there, and leaves the shape without
		// ever considering the farther box.
		double near = 1.0 - EPSILON;
		double[] composite = {near, 0.0, 0.0, 1.5, 1.0, 1.0, 1.5, 0.0, 0.0, 2.5, 1.0, 1.0};
		for (int ulp = -ULPS; ulp <= ULPS; ulp++) {
			double maxX = step(1.0, ulp);
			assertResolves(composite, maxX - 0.6, 0.0, 0.2, maxX, 1.8, 0.8, 0.9, "composite at " + ulp);
		}
	}

	@Test
	void broadphaseJoinMatchesTheMergerAcrossTheEpsilonBand() {
		double[] shape = {0.25, 0.0, 0.0, 0.75, 1.0, 1.0};
		for (int ulp = -ULPS; ulp <= ULPS; ulp++) {
			double queryMin = step(0.75 - EPSILON, ulp);
			assertJoins(shape, queryMin, 0.2, 0.2, queryMin + 0.5, 0.8, 0.8, "query start at " + ulp);
			double queryMax = step(0.25 + EPSILON, ulp);
			assertJoins(shape, queryMax - 0.5, 0.2, 0.2, queryMax, 0.8, 0.8, "query end at " + ulp);
			double thin = step(EPSILON, ulp);
			assertJoins(shape, 0.5, 0.2, 0.2, 0.5 + thin, 0.8, 0.8, "thin query at " + ulp);
			double inside = step(0.25, ulp);
			assertJoins(shape, inside, 0.2, 0.2, inside + 0.1, 0.8, 0.8, "start on face at " + ulp);
		}
	}

	private static void assertResolves(final double[] shape, final double minX, final double minY, final double minZ,
	    final double maxX, final double maxY, final double maxZ, final double distance, final String label) {
		Scratch scratch = new Scratch();
		CollisionBuffer shapes = new CollisionBuffer();
		for (int at = 0; at < shape.length; at += 6) {
			if (at == 0) {
				shapes.add(shape[0], shape[1], shape[2], shape[3], shape[4], shape[5]);
			} else {
				shapes.addPart(shape[at], shape[at + 1], shape[at + 2], shape[at + 3], shape[at + 4], shape[at + 5]);
			}
		}
		ShapeCollision.collideWithShapes(minX, minY, minZ, maxX, maxY, maxZ, distance, 0.0, 0.0, shapes, scratch);
		double expected = referenceCollide(shape, minX, minY, minZ, maxX, maxY, maxZ, distance);
		assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(scratch.movedX), label);
	}

	private static void assertJoins(final double[] shape, final double minX, final double minY, final double minZ,
	    final double maxX, final double maxY, final double maxZ, final String label) {
		boolean expected = referenceJoinIsNotEmpty(shape, minX, minY, minZ, maxX, maxY, maxZ);
		assertEquals(expected,
		    CollisionCollector.generalGroupIntersects(shape, 0, 0, 0, minX, minY, minZ, maxX, maxY, maxZ), label);
	}

	private static double[] fullCube(final int x, final int y, final int z) {
		return new double[] {x, y, z, x + 1.0, y + 1.0, z + 1.0};
	}

	private static double step(final double value, final int ulps) {
		double stepped = value;
		for (int i = 0; i < Math.abs(ulps); i++) {
			stepped = ulps > 0 ? Math.nextUp(stepped) : Math.nextDown(stepped);
		}
		return stepped;
	}

	/*
	 * Reference: one VoxelShape built from boxes as a discrete grid, and
	 * Shapes.collide over a list holding that one shape, for the X axis
	 * (AxisCycle.NONE, so a is X, b is Y and c is Z).
	 */

	private static double referenceCollide(final double[] boxes, final double minX, final double minY,
	    final double minZ, final double maxX, final double maxY, final double maxZ, double distance) {
		// Shapes.collide
		if (Math.abs(distance) < 1.0E-7) {
			return 0.0;
		}
		Grid grid = Grid.of(boxes);
		// VoxelShape.collideX
		if (Math.abs(distance) < 1.0E-7) {
			return 0.0;
		}
		double maxA = maxX;
		double minA = minX;
		int aMin = grid.findIndex(grid.xs, minA + 1.0E-7);
		int aMax = grid.findIndex(grid.xs, maxA - 1.0E-7);
		int bMin = Math.max(0, grid.findIndex(grid.ys, minY + 1.0E-7));
		int bMax = Math.min(grid.ys.length - 1, grid.findIndex(grid.ys, maxY - 1.0E-7) + 1);
		int cMin = Math.max(0, grid.findIndex(grid.zs, minZ + 1.0E-7));
		int cMax = Math.min(grid.zs.length - 1, grid.findIndex(grid.zs, maxZ - 1.0E-7) + 1);
		int aSize = grid.xs.length - 1;
		if (distance > 0.0) {
			for (int a = aMax + 1; a < aSize; a++) {
				for (int b = bMin; b < bMax; b++) {
					for (int c = cMin; c < cMax; c++) {
						if (grid.full[a][b][c]) {
							double newDistance = grid.xs[a] - maxA;
							if (newDistance >= -1.0E-7) {
								distance = Math.min(distance, newDistance);
							}
							return distance;
						}
					}
				}
			}
		} else if (distance < 0.0) {
			for (int a = aMin - 1; a >= 0; a--) {
				for (int b = bMin; b < bMax; b++) {
					for (int c = cMin; c < cMax; c++) {
						if (grid.full[a][b][c]) {
							double newDistance = grid.xs[a + 1] - minA;
							if (newDistance <= 1.0E-7) {
								distance = Math.max(distance, newDistance);
							}
							return distance;
						}
					}
				}
			}
		}
		return distance;
	}

	private record Grid(double[] xs, double[] ys, double[] zs, boolean[][][] full) {
		static Grid of(final double[] boxes) {
			double[] xs = coords(boxes, 0);
			double[] ys = coords(boxes, 1);
			double[] zs = coords(boxes, 2);
			boolean[][][] full = new boolean[xs.length - 1][ys.length - 1][zs.length - 1];
			for (int a = 0; a + 1 < xs.length; a++) {
				for (int b = 0; b + 1 < ys.length; b++) {
					for (int c = 0; c + 1 < zs.length; c++) {
						double x = (xs[a] + xs[a + 1]) / 2.0;
						double y = (ys[b] + ys[b + 1]) / 2.0;
						double z = (zs[c] + zs[c + 1]) / 2.0;
						for (int at = 0; at < boxes.length; at += 6) {
							if (boxes[at] < x && x < boxes[at + 3] && boxes[at + 1] < y && y < boxes[at + 4]
							    && boxes[at + 2] < z && z < boxes[at + 5]) {
								full[a][b][c] = true;
							}
						}
					}
				}
			}
			return new Grid(xs, ys, zs, full);
		}

		private static double[] coords(final double[] boxes, final int axis) {
			TreeSet<Double> set = new TreeSet<>();
			for (int at = 0; at < boxes.length; at += 6) {
				set.add(boxes[at + axis]);
				set.add(boxes[at + axis + 3]);
			}
			return set.stream().mapToDouble(Double::doubleValue).toArray();
		}

		/** DiscreteVoxelShape.isFullWide: false outside the grid. */
		boolean isFullWide(final int a, final int b, final int c) {
			return a >= 0 && a < this.full.length && b >= 0 && b < this.full[a].length && c >= 0
			    && c < this.full[a][b].length && this.full[a][b][c];
		}

		/** VoxelShape.findIndex: Mth.binarySearch(0, size + 1, i -> value < get(i)) - 1. */
		int findIndex(final double[] coords, final double value) {
			int min = 0;
			int count = coords.length;
			while (count > 0) {
				int half = count / 2;
				int middle = min + half;
				if (value < coords[middle]) {
					count = half;
				} else {
					min = middle + 1;
					count -= half + 1;
				}
			}
			return min - 1;
		}
	}

	/*
	 * Reference: Shapes.joinIsNotEmpty(shape, Shapes.create(query), AND) with
	 * IndirectMerger on every axis, for a shape held as its grid.
	 */

	private static boolean referenceJoinIsNotEmpty(final double[] boxes, final double minX, final double minY,
	    final double minZ, final double maxX, final double maxY, final double maxZ) {
		// Shapes.create
		if (maxX - minX < 1.0E-7 || maxY - minY < 1.0E-7 || maxZ - minZ < 1.0E-7) {
			return false;
		}
		Grid grid = Grid.of(boxes);
		double[][] first = {grid.xs, grid.ys, grid.zs};
		double[][] second = {{minX, maxX}, {minY, maxY}, {minZ, maxZ}};
		for (int axis = 0; axis < 3; axis++) {
			double[] a = first[axis];
			double[] b = second[axis];
			if (a[a.length - 1] < b[0] - 1.0E-7 || b[1] < a[0] - 1.0E-7) {
				return false;
			}
		}
		Merger x = Merger.of(grid.xs, new double[] {minX, maxX});
		Merger y = Merger.of(grid.ys, new double[] {minY, maxY});
		Merger z = Merger.of(grid.zs, new double[] {minZ, maxZ});
		for (int i = 0; i + 1 < x.length; i++) {
			for (int j = 0; j + 1 < y.length; j++) {
				for (int k = 0; k + 1 < z.length; k++) {
					boolean inFirst = grid.isFullWide(x.first[i], y.first[j], z.first[k]);
					boolean inSecond = x.second[i] == 0 && y.second[j] == 0 && z.second[k] == 0;
					if (inFirst && inSecond) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private record Merger(int[] first, int[] second, int length) {
		/** IndirectMerger(first, second, false, false), or the non-overlapping form. */
		static Merger of(final double[] first, final double[] second) {
			int firstSize = first.length - 1;
			int secondSize = second.length - 1;
			if (first[firstSize] < second[0] - 1.0E-7 || second[secondSize] < first[0] - 1.0E-7) {
				// NonOverlappingMerger: every interval lies in one list only.
				return new Merger(new int[] {-1}, new int[] {-1}, 1);
			}
			if (firstSize == secondSize && Arrays.equals(first, second)) {
				int[] indices = new int[first.length];
				for (int i = 0; i < indices.length; i++) {
					indices[i] = i;
				}
				return new Merger(indices, indices, first.length);
			}
			double lastValue = Double.NaN;
			int capacity = first.length + second.length;
			int[] firstIndices = new int[capacity];
			int[] secondIndices = new int[capacity];
			int resultIndex = 0;
			int firstIndex = 0;
			int secondIndex = 0;
			while (true) {
				boolean ranOutOfFirst = firstIndex >= first.length;
				boolean ranOutOfSecond = secondIndex >= second.length;
				if (ranOutOfFirst && ranOutOfSecond) {
					return new Merger(firstIndices, secondIndices, Math.max(1, resultIndex));
				}
				boolean choseFirst =
				    !ranOutOfFirst && (ranOutOfSecond || first[firstIndex] < second[secondIndex] + 1.0E-7);
				if (choseFirst) {
					firstIndex++;
					if (secondIndex == 0 || ranOutOfSecond) {
						continue;
					}
				} else {
					secondIndex++;
					if (firstIndex == 0 || ranOutOfFirst) {
						continue;
					}
				}
				int currentFirstIndex = firstIndex - 1;
				int currentSecondIndex = secondIndex - 1;
				double nextValue = choseFirst ? first[currentFirstIndex] : second[currentSecondIndex];
				if (!(lastValue >= nextValue - 1.0E-7)) {
					firstIndices[resultIndex] = currentFirstIndex;
					secondIndices[resultIndex] = currentSecondIndex;
					resultIndex++;
					lastValue = nextValue;
				} else {
					firstIndices[resultIndex - 1] = currentFirstIndex;
					secondIndices[resultIndex - 1] = currentSecondIndex;
				}
			}
		}
	}
}
