package com.nettarion.stride.simulator.geometry;

import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.Suffocation;

/**
 * The evidence for asking about large shapes per section instead of per world.
 *
 * <p>validation replaced a palette-wide "does this world contain a shape that leaves its
 * own cell" with "is there one near this query", which lets the Cursor3D ring and
 * the span-reuse refusal be skipped where no such shape is in reach. That is only
 * sound if the ring truly contributes nothing when the neighborhood is clear, and
	 * `:vanilla-oracle:fuzzCheck` cannot say so: of twenty-nine captures exactly one holds a
 * large shape at all, and a negative control claiming *no* world had one still
 * passed all 58 cases, because that capture's fence is nowhere near the route. The
 * ring path had no differential coverage in this project before this test.
 *
 * <p>The oracle is therefore a reference implementation rather than another implementation —
 * the full Cursor3D walk with its {@code cursorType} rules, written out here and
 * reading a plain model of the world the test built rather than the view under
 * test. Reading shapes back through {@code collectCollisionBoxes} was tried first
 * and is wrong for exactly the reason this test exists: a query inside one cell
 * legitimately returns boxes owned by its neighbors, so the oracle counted the
 * large shape once per cell it reaches into.
 *
 * <p>Placements are swept, not chosen. The interesting ones straddle a 16-cube
 * boundary, since a shape in the section next door is what a per-section answer can
 * get wrong and a per-world answer cannot.
 */
final class LargeShapeNeighborhoodTest {
	private static final int SIZE = 40;
	private static final int AIR = 0;
	private static final int STONE = 1;
	/** Taller than its own cell, like a fence: the reason the ring exists. */
	private static final int TALL = 2;
	/** Reaches out of its cell sideways, so a neighbor's query must find it. */
	private static final int WIDE = 3;

	private static final double[][] SHAPES = {
	    {},
	    {0.0, 0.0, 0.0, 1.0, 1.0, 1.0},
	    {0.375, 0.0, 0.375, 0.625, 1.5, 0.625},
	    {-0.25, 0.0, 0.375, 0.5, 1.0, 0.625},
	};

	/** The world under test beside the plain model the oracle reads. */
	private record Fixture(SnapshotView world, int[] cells) {
		int at(final int x, final int y, final int z) {
			if (x < 0 || x >= SIZE || y < 0 || y >= SIZE || z < 0 || z >= SIZE) {
				return AIR;
			}
			return this.cells[(y * SIZE + z) * SIZE + x];
		}
	}

	@Test
	void collisionAgreesWithTheFullWalkAtEveryPlacement() {
		int checked = 0;
		int fromRing = 0;
		for (int block : new int[] {TALL, WIDE}) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					for (int base : new int[] {8, 15, 16, 17, 24}) {
						Fixture fixture = fixture(block, base + dx, 17, 16 + dz);
						double minX = base + 0.2;
						double minZ = 16.2;
						CollisionBuffer actual = new CollisionBuffer();
						fixture.world().collectCollisionBoxes(
						    minX, 17.0, minZ, minX + 0.6, 18.8, minZ + 0.6, 17.0, false, 0.0, actual);
						List<double[]> expected = fullWalk(fixture, minX, 17.0, minZ, minX + 0.6, 18.8, minZ + 0.6);
						assertBoxes(expected, actual, "block " + block + " dx=" + dx + " dz=" + dz + " base=" + base);
						checked++;
						if (!expected.isEmpty() && (dx != 0 || dz != 0)) {
							fromRing++;
						}
					}
				}
			}
		}
		assertTrue(checked >= 250, "swept too few placements: " + checked);
		// The control on the sweep: without this the test could be asserting that
		// the ring is always empty rather than that it is handled when it is not.
		assertTrue(fromRing > 0,
		    "no placement put a box in the query's ring, so the ring was never"
		        + " exercised and agreement here means nothing");
	}

	@Test
	void suffocationAgreesWithTheFullWalkAtEveryPlacement() {
		int yes = 0;
		int no = 0;
		for (int block : new int[] {TALL, WIDE}) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					for (int base : new int[] {8, 15, 16, 17, 24}) {
						Fixture fixture = fixture(block, base + dx, 17, 16 + dz);
						boolean actual = fixture.world().suffocatesAt(base, 16, 17.0, 18.8);
						boolean expected = fullWalkSuffocates(fixture, base, 16, 17.0, 18.8);
						assertEquals(expected, actual, "block " + block + " dx=" + dx + " dz=" + dz + " base=" + base);
						if (expected) {
							yes++;
						} else {
							no++;
						}
					}
				}
			}
		}
		assertTrue(yes > 0 && no > 0,
		    "suffocation sweep was one-sided, so one direction is untested: yes=" + yes + " no=" + no);
	}

	/**
	 * A retained span must never be served where a shape outside it can reach in:
	 * sub-queries are answered by refiltering the retained boxes, so a box owned
	 * outside would be missing from every one of them.
	 */
	@Test
	void spanReuseRefusesOnlyWhenAShapeCanReachTheSpan() {
		int served = 0;
		int refused = 0;
		// The span (16,17,16)-(16,18,16) rings out to (15,16,15)-(17,19,17), which
		// touches four 16-cubes: x in {0,1}, y in {1}, z in {0,1}. "Far" therefore
		// means outside all four, not merely a few cells away — 16-cube granularity
		// is what the refusal is asked at.
		int[][] placements = {
		    {16, 17, 16},
		    {17, 17, 16},
		    {15, 17, 16},
		    {16, 17, 17},
		    {1, 17, 16},
		    {30, 17, 16},
		    {35, 17, 16},
		    {16, 1, 16},
		    {16, 35, 16},
		    {35, 1, 35},
		};
		for (int block : new int[] {TALL, WIDE}) {
			for (int[] at : placements) {
				Fixture fixture = fixture(block, at[0], at[1], at[2]);
				CollisionBuffer span = new CollisionBuffer();
				boolean ok = fixture.world().collectSpanBoxes(16, 17, 16, 16, 18, 16, span);
				if (!ok) {
					refused++;
					continue;
				}
				served++;
				// Anything it serves must contain every box the walk can find inside
				// the span, since a refilter can only remove boxes, never add one.
				List<double[]> expected = fullWalk(fixture, 16.001, 17.001, 16.001, 16.999, 18.999, 16.999);
				for (double[] want : expected) {
					assertTrue(contains(span, want),
					    "served span missing a box the walk finds, block " + block + " at " + at[0] + "," + at[1] + ","
					        + at[2]);
				}
			}
		}
		assertTrue(refused > 0, "never refused, so the reach test rejects nothing");
		assertTrue(served > 0, "never served, so the optimization is inert here");
	}

	private static boolean contains(final CollisionBuffer buffer, final double[] want) {
		for (int i = 0; i < buffer.size(); i++) {
			if (buffer.minX(i) == want[0] && buffer.minY(i) == want[1] && buffer.minZ(i) == want[2]
			    && buffer.maxX(i) == want[3] && buffer.maxY(i) == want[4] && buffer.maxZ(i) == want[5]) {
				return true;
			}
		}
		return false;
	}

	/** Independent Cursor3D walk over the plain model: the reference implementation. */
	private static List<double[]> fullWalk(final Fixture fixture, final double minX, final double minY,
	    final double minZ, final double maxX, final double maxY, final double maxZ) {
		int x0 = Mth.floor(minX - 1.0E-7) - 1;
		int x1 = Mth.floor(maxX + 1.0E-7) + 1;
		int y0 = Mth.floor(minY - 1.0E-7) - 1;
		int y1 = Mth.floor(maxY + 1.0E-7) + 1;
		int z0 = Mth.floor(minZ - 1.0E-7) - 1;
		int z1 = Mth.floor(maxZ + 1.0E-7) + 1;
		List<double[]> boxes = new ArrayList<>();
		// X fastest, then Y, then Z, as vanilla's Cursor3D visits them.
		for (int z = z0; z <= z1; z++) {
			for (int y = y0; y <= y1; y++) {
				for (int x = x0; x <= x1; x++) {
					int cursorType =
					    (x == x0 || x == x1 ? 1 : 0) + (y == y0 || y == y1 ? 1 : 0) + (z == z0 || z == z1 ? 1 : 0);
					// No moving piston in these worlds, so type 2 and 3 are both skipped.
					if (cursorType >= 2) {
						continue;
					}
					int state = fixture.at(x, y, z);
					if (cursorType == 1 && !isLarge(SHAPES[state])) {
						continue;
					}
					double[] shape = SHAPES[state];
					for (int at = 0; at < shape.length; at += 6) {
						double bMinX = shape[at] + x;
						double bMinY = shape[at + 1] + y;
						double bMinZ = shape[at + 2] + z;
						double bMaxX = shape[at + 3] + x;
						double bMaxY = shape[at + 4] + y;
						double bMaxZ = shape[at + 5] + z;
						if (bMinX < maxX && bMaxX > minX && bMinY < maxY && bMaxY > minY && bMinZ < maxZ
						    && bMaxZ > minZ) {
							boxes.add(new double[] {bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ});
						}
					}
				}
			}
		}
		return boxes;
	}

	/**
	 * The same walk over {@code suffocatesAt}'s column box, with its first-hit exit.
	 * Every non-air entry here is declared to suffocate, so reaching the box is the
	 * whole predicate and no UNKNOWN can be hit.
	 */
	private static boolean fullWalkSuffocates(final Fixture fixture, final int cellX, final int cellZ,
	    final double boundingBoxMinY, final double boundingBoxMaxY) {
		return !fullWalk(fixture, cellX + 1.0E-7, boundingBoxMinY + 1.0E-7, cellZ + 1.0E-7, cellX + 1.0 - 1.0E-7,
		    boundingBoxMaxY - 1.0E-7, cellZ + 1.0 - 1.0E-7)
		            .isEmpty();
	}

	private static boolean isLarge(final double[] shape) {
		for (int at = 0; at < shape.length; at += 6) {
			if (shape[at] < 0.0 || shape[at + 1] < 0.0 || shape[at + 2] < 0.0 || shape[at + 3] > 1.0
			    || shape[at + 4] > 1.0 || shape[at + 5] > 1.0) {
				return true;
			}
		}
		return false;
	}

	private static void assertBoxes(final List<double[]> expected, final CollisionBuffer actual, final String where) {
		assertEquals(expected.size(), actual.size(), "box count at " + where);
		for (int i = 0; i < expected.size(); i++) {
			double[] want = expected.get(i);
			assertEquals(want[0], actual.minX(i), 0.0, "minX " + i + " at " + where);
			assertEquals(want[1], actual.minY(i), 0.0, "minY " + i + " at " + where);
			assertEquals(want[2], actual.minZ(i), 0.0, "minZ " + i + " at " + where);
			assertEquals(want[3], actual.maxX(i), 0.0, "maxX " + i + " at " + where);
			assertEquals(want[4], actual.maxY(i), 0.0, "maxY " + i + " at " + where);
			assertEquals(want[5], actual.maxZ(i), 0.0, "maxZ " + i + " at " + where);
		}
	}

	/** Stone floor at y=16, one large-shaped block placed where asked. */
	private static Fixture fixture(final int block, final int atX, final int atY, final int atZ) {
		int[] cells = new int[SIZE * SIZE * SIZE];
		for (int x = 0; x < SIZE; x++) {
			for (int z = 0; z < SIZE; z++) {
				cells[(16 * SIZE + z) * SIZE + x] = STONE;
			}
		}
		cells[(atY * SIZE + atZ) * SIZE + atX] = block;
		SnapshotView world =
		    new SnapshotView(new WorldSnapshot(0, 0, 0, SIZE, SIZE, SIZE, OutsidePolicy.SEALED,
		        List.of(solid(AIR, "air", SHAPES[AIR], Suffocation.NO),
		            solid(STONE, "stone", SHAPES[STONE], Suffocation.YES),
		            solid(TALL, "tall", SHAPES[TALL], Suffocation.YES),
		            solid(WIDE, "wide", SHAPES[WIDE], Suffocation.YES)),
		        cells));
		return new Fixture(world, cells);
	}

	private static BlockEntry solid(
	    final int id, final String name, final double[] shape, final Suffocation suffocation) {
		List<ShapeBox> boxes = new ArrayList<>();
		for (int at = 0; at < shape.length; at += 6) {
			boxes.add(new ShapeBox(
			    shape[at], shape[at + 1], shape[at + 2], shape[at + 3], shape[at + 4], shape[at + 5]));
		}
		return new BlockEntry(id, name, 0.6F, 1.0F, 1.0F, false, false, WorldView.BubbleColumnMode.NONE,
		    false, WorldView.CollisionBehavior.ORDINARY, suffocation, WorldView.InsideEffect.NONE, 0.0F, false,
		    WorldView.StepOn.NONE, false, WorldView.Climbability.NONE, boxes);
	}
}
