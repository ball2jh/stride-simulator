package com.nettarion.stride.simulator.geometry;

import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.world.CompleteWorldView;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.StateDigest;
import com.nettarion.stride.simulator.block.BlockBehavior;
import com.nettarion.stride.simulator.tick.ClientTick;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.SupportCell;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.Suffocation;

/**
 * Retained-span reuse must be an acceleration and nothing else.
 *
 * <p>The risk is not that a box is missed outright — the fuzz suite would see
 * that — but that reuse returns the right boxes in the wrong order, or drops a
 * box the *building* query's filter excluded and a later one needs. Both are
 * invisible until a specific query shape reaches them, so they are pinned here.
 */
class SpanReuseTest {
	/**
	 * The retained set must be unfiltered. A slab lying in the lower half of a
	 * cell is inside the span of a query whose own bounds start above it, so a
	 * span retained from that query would omit the slab and a later, lower query
	 * over the same span would then miss a collision that exists.
	 */
	@Test
	void retainedSpanCarriesBoxesTheBuildingQueryWouldHaveFiltered() {
		SnapshotView world = slabWorld();
		CollisionBuffer span = new CollisionBuffer();

		assertTrue(world.collectSpanBoxes(0, 0, 0, 0, 0, 0, span));
		assertEquals(1, span.size(), "the span owns the slab regardless of any query");

		// A query confined above the slab legitimately returns nothing...
		CollisionBuffer high = new CollisionBuffer();
		world.collectCollisionBoxes(0.1, 0.6, 0.1, 0.9, 0.9, 0.9, high);
		assertTrue(high.isEmpty());

		// ...but its span still had to retain the slab, or this query, which lies
		// in the very same span, would wrongly come back empty.
		CollisionBuffer low = new CollisionBuffer();
		world.collectCollisionBoxes(0.1, 0.1, 0.1, 0.9, 0.9, 0.9, low);
		assertEquals(1, low.size());
	}

	@Test
	void expandedFaceContainmentMatchesIntegerCursorAtBoundariesAndAfterEdits() {
		SnapshotView world = terrainWorld();
		Scratch scratch = new Scratch();
		scratch.span.retain(world, -2, -2, -2, 2, 2, 2);
		double[] faces = {-3.0, -2.0000001, Math.nextDown(-2.0), -2.0, Math.nextUp(-2.0), -1.0, -0.0, 0.0, 1.0, 2.0,
		    Math.nextDown(3.0), 3.0, Math.nextUp(3.0), 3.0000001};
		for (double minimum : faces)
			for (double maximum : faces) {
				if (minimum > maximum) continue;
				for (int axis = 0; axis < 3; axis++) {
					double[] low = {0.0, 0.0, 0.0};
					double[] high = {1.0, 1.0, 1.0};
					low[axis] = minimum - 1.0E-7;
					high[axis] = maximum + 1.0E-7;
					assertEquals(Mth.floor(low[0]) >= -2 && Mth.floor(low[1]) >= -2 && Mth.floor(low[2]) >= -2
					        && Mth.floor(high[0]) <= 2 && Mth.floor(high[1]) <= 2 && Mth.floor(high[2]) <= 2,
					    scratch.span.covers(world, low[0], low[1], low[2], high[0], high[1], high[2]));
				}
			}
		assertTrue(scratch.span.covers(world, -1, -1, -1, 1, 1, 1));
		assertFalse(scratch.span.covers(terrainWorld(), -1, -1, -1, 1, 1, 1));
		world.replaceCell(0, 0, 0, 1);
		assertFalse(scratch.span.covers(world, -1, -1, -1, 1, 1, 1));
	}

	@Test
	void canonicalSupportOwnersMatchDirectQueriesAcrossNegativeCellsAndTies() {
		var builder = WorldSnapshot.builder(OutsidePolicy.REFUSING, -4, -3, -4, 8, 6, 8)
		                  .palette(BlockEntry.builder(0, "minecraft:air").build(),
		                      BlockEntry.builder(1, "minecraft:stone").fullCube().build());
		for (int x = -3; x <= 2; x++)
			for (int z = -3; z <= 2; z++) {
				builder.set(x, -1, z, 1);
			}
		SnapshotView world = new SnapshotView(builder.build());
		Scratch scratch = new Scratch();
		assertTrue(world.collectSpanBoxes(-3, -2, -3, 2, 1, 2, scratch.span.boxes));
		scratch.span.retain(world, -3, -2, -3, 2, 1, 2);
		for (double x = -2.0; x <= 1.0; x += 0.25) {
			for (double z = -2.0; z <= 1.0; z += 0.25) {
				SupportCell reused = new SupportCell();
				SupportCell direct = new SupportCell();
				assertTrue(CollisionCollector.tryFindSupportingBlock(
				    world, scratch, x - 0.3, -1.0E-6, z - 0.3, x + 0.3, 0.0, z + 0.3, x, 0.0, z, reused));
				world.findSupportingBlock(x - 0.3, -1.0E-6, z - 0.3, x + 0.3, 0.0, z + 0.3, x, 0.0, z, direct);
				assertEquals(direct.present, reused.present);
				assertEquals(direct.x, reused.x);
				assertEquals(direct.y, reused.y);
				assertEquals(direct.z, reused.z);
			}
		}
	}

	@Test
	void retainedSpanAnswersTheSupportingBlockQueryWithoutChangingItsResult() {
		SnapshotView world = slabWorld();
		Scratch scratch = new Scratch();
		CollisionCollector.collect(world, scratch, 0.1, 0.1, 0.1, 0.9, 0.9, 0.9, 0.5, false, 0.0, false);
		SupportCell reused = new SupportCell();
		SupportCell direct = new SupportCell();

		assertTrue(CollisionCollector.tryFindSupportingBlock(
		    world, scratch, 0.2, 0.5 - 1.0E-6, 0.2, 0.8, 0.5, 0.8, 0.5, 0.5, 0.5, reused));
		world.findSupportingBlock(0.2, 0.5 - 1.0E-6, 0.2, 0.8, 0.5, 0.8, 0.5, 0.5, 0.5, direct);

		assertEquals(direct.present, reused.present);
		assertEquals(direct.x, reused.x);
		assertEquals(direct.y, reused.y);
		assertEquals(direct.z, reused.z);
	}

	/**
	 * Span reuse may not change a transition. Stepping the same schedule against
	 * a view that offers reuse and one that refuses it must agree raw-bit, which
	 * is the same equality the cross-implementation suites use.
	 */
	@Test
	void reuseAgreesRawBitWithTheOrdinaryPathOverARandomizedWalk() {
		SnapshotView reusing = terrainWorld();
		WorldView ordinary = new NoSpanReuse(terrainWorld());
		assertTrue(reusing.supportsSpanReuse());
		assertFalse(ordinary.supportsSpanReuse());

		ClientTick kernel = new ClientTick();
		PlayerState reused = seed();
		PlayerState plain = seed();
		Scratch reusedScratch = new Scratch();
		Scratch plainScratch = new Scratch();

		Random random = new Random(20260812L);
		for (int tick = 0; tick < 400; tick++) {
			PlayerInput action =
			    new PlayerInput(random.nextBoolean(), random.nextBoolean(), random.nextBoolean(), random.nextBoolean(),
			        random.nextBoolean(), random.nextBoolean(), random.nextBoolean(), random.nextInt(8) * 45.0F, 0.0F);
			kernel.tick(reused, action, reusing, reusedScratch);
			kernel.tick(plain, action, ordinary, plainScratch);
			assertEquals(StateDigest.state(plain), StateDigest.state(reused), "span reuse diverged at tick " + tick);
			// The digest is the audited vector; compare the resolved box and the
			// collision flags raw as well, since those are what a gathering change
			// would corrupt first.
			assertEquals(rawState(plain), rawState(reused), "span reuse diverged outside the digest at tick " + tick);
		}
	}

	/**
	 * Order is part of the answer. A span's boxes are emitted in Cursor3D order,
	 * and restricting that order to a sub-span must give the sub-span's own
	 * order, which is what makes refiltering a retained span sound.
	 */
	@Test
	void refilteringARetainedSpanReproducesEverySubQueryExactly() {
		SnapshotView world = terrainWorld();
		CollisionBuffer retained = new CollisionBuffer();
		CollisionBuffer direct = new CollisionBuffer();
		Scratch scratch = new Scratch();

		int x0 = -1;
		int y0 = -1;
		int z0 = -1;
		int x1 = 2;
		int y1 = 2;
		int z1 = 2;
		assertTrue(world.collectSpanBoxes(x0, y0, z0, x1, y1, z1, retained));

		Random random = new Random(4242L);
		int compared = 0;
		for (int trial = 0; trial < 4000; trial++) {
			double minX = x0 + 0.05 + random.nextDouble() * 2.4;
			double minY = y0 + 0.05 + random.nextDouble() * 2.4;
			double minZ = z0 + 0.05 + random.nextDouble() * 2.4;
			double maxX = minX + random.nextDouble() * 0.9;
			double maxY = minY + random.nextDouble() * 1.8;
			double maxZ = minZ + random.nextDouble() * 0.9;
			// Only spans this retention actually covers are its responsibility.
			if (Mth.floor(maxX + 1.0E-7) > x1 || Mth.floor(maxY + 1.0E-7) > y1 || Mth.floor(maxZ + 1.0E-7) > z1) {
				continue;
			}
			compared++;

			world.collectCollisionBoxes(minX, minY, minZ, maxX, maxY, maxZ, direct);
			CollisionBuffer refiltered =
			    CollisionCollector.collect(world, scratch, minX, minY, minZ, maxX, maxY, maxZ, minY, false, 0.0, false);
			assertEquals(raw(direct), raw(refiltered),
			    "retained-span refilter differed from the ordinary scan on trial " + trial);
		}
		assertTrue(compared > 500, "the trial generator covered too few queries: " + compared);
	}

	private static String rawState(final PlayerState state) {
		return Double.doubleToRawLongBits(state.x) + "," + Double.doubleToRawLongBits(state.y) + ","
		    + Double.doubleToRawLongBits(state.z) + "," + Double.doubleToRawLongBits(state.deltaMovementX) + ","
		    + Double.doubleToRawLongBits(state.deltaMovementY) + "," + Double.doubleToRawLongBits(state.deltaMovementZ)
		    + "," + Double.doubleToRawLongBits(state.boundingBoxMinX) + ","
		    + Double.doubleToRawLongBits(state.boundingBoxMinY) + ","
		    + Double.doubleToRawLongBits(state.boundingBoxMinZ) + ","
		    + Double.doubleToRawLongBits(state.boundingBoxMaxX) + ","
		    + Double.doubleToRawLongBits(state.boundingBoxMaxY) + ","
		    + Double.doubleToRawLongBits(state.boundingBoxMaxZ) + "," + Double.doubleToRawLongBits(state.fallDistance)
		    + "," + state.onGround + "," + state.horizontalCollision + "," + state.verticalCollision + ","
		    + state.verticalCollisionBelow + "," + state.minorHorizontalCollision + "," + state.pose;
	}

	/** Raw bits, so a -0.0 or a one-ulp difference cannot compare equal. */
	private static String raw(final CollisionBuffer buffer, final int index) {
		return Double.doubleToRawLongBits(buffer.minX(index)) + "," + Double.doubleToRawLongBits(buffer.minY(index))
		    + "," + Double.doubleToRawLongBits(buffer.minZ(index)) + ","
		    + Double.doubleToRawLongBits(buffer.maxX(index)) + "," + Double.doubleToRawLongBits(buffer.maxY(index))
		    + "," + Double.doubleToRawLongBits(buffer.maxZ(index));
	}

	/** The selected groups in order, which is all of an ordinary collection's groups. */
	private static String raw(final CollisionBuffer buffer) {
		StringBuilder result = new StringBuilder();
		for (int k = 0; k < buffer.selectedGroupCount(); k++) {
			int group = buffer.selectedGroup(k);
			for (int index = buffer.groupStart(group); index < buffer.groupEnd(group); index++) {
				result.append(result.isEmpty() ? "" : ";")
				    .append(buffer.startsGroup(index) ? 'g' : 'p')
				    .append(buffer.canonicalFullGroup(index) ? 'c' : '-')
				    .append(':')
				    .append(raw(buffer, index));
			}
		}
		return result.toString();
	}

	private static PlayerState seed() {
		PlayerState state = new PlayerState();
		state.x = 0.5;
		state.y = 1.0;
		state.z = 0.5;
		state.setBox(
		    AABB.around(state.x, state.y, state.z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));
		state.onGround = true;
		return state;
	}

	private static SnapshotView slabWorld() {
		return new SnapshotView(WorldSnapshot.builder(OutsidePolicy.REFUSING, 0, 0, 0, 1, 1, 1)
		        .palette(BlockEntry.builder(0, "minecraft:stone_slab")
		                .boxes(new ShapeBox(0.0, 0.0, 0.0, 1.0, 0.5, 1.0))
		                .build())
		        .build());
	}

	/** Mixed geometry so full cubes, slabs and partial shapes all take part. */
	private static SnapshotView terrainWorld() {
		// The four states carry their real 26.2 suffocation answers, because a
		// randomized walk through this terrain does put a box corner in a column
		// these occupy.
		WorldSnapshot.Builder grid =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, -8, -8, -8, 16, 16, 16)
		        .palette(BlockEntry.builder(0, "minecraft:air")
		                     .suffocation(Suffocation.NO)
		                     .build(),
		            BlockEntry.builder(1, "minecraft:stone")
		                .fullCube()
		                .suffocation(Suffocation.YES)
		                .build(),
		            BlockEntry.builder(2, "minecraft:stone_slab")
		                .boxes(new ShapeBox(0.0, 0.0, 0.0, 1.0, 0.5, 1.0))
		                .suffocation(Suffocation.NO)
		                .build(),
		            BlockEntry.builder(3, "minecraft:fence")
		                .boxes(new ShapeBox(0.375, 0.0, 0.375, 0.625, 1.0, 0.625))
		                .suffocation(Suffocation.NO)
		                .build());
		Random random = new Random(99L);
		for (int y = -8; y < 8; y++) {
			for (int z = -8; z < 8; z++) {
				for (int x = -8; x < 8; x++) {
					grid.set(x, y, z,
					    y < 0                        ? 1 + random.nextInt(3)
					        : random.nextInt(8) == 0 ? 1 + random.nextInt(3)
					                                 : 0);
				}
			}
		}
		return new SnapshotView(grid.build());
	}

	/** A view that refuses span reuse, so the ordinary path can be compared to. */
	private record NoSpanReuse(SnapshotView delegate) implements CompleteWorldView {
		@Override
		public boolean supportsSpanReuse() {
			return false;
		}

		@Override
		public boolean suffocatesAt(
		    final int cellX, final int cellZ, final double boundingBoxMinY, final double boundingBoxMaxY) {
			return this.delegate.suffocatesAt(cellX, cellZ, boundingBoxMinY, boundingBoxMaxY);
		}

		@Override
		public long collisionVersion() {
			return this.delegate.collisionVersion();
		}
		@Override
		public long identity() {
			return this.delegate.identity();
		}

		@Override
		public boolean hasFluids() {
			return this.delegate.hasFluids();
		}

		@Override
		public void fluidAt(final int x, final int y, final int z, final FluidSample target) {
			this.delegate.fluidAt(x, y, z, target);
		}

		@Override
		public BubbleColumnMode bubbleColumnModeAt(final int x, final int y, final int z) {
			return this.delegate.bubbleColumnModeAt(x, y, z);
		}

		@Override
		public BlockBehavior behaviorAt(final int x, final int y, final int z) {
			return this.delegate.behaviorAt(x, y, z);
		}

		@Override
		public boolean hasBubbleColumns() {
			return this.delegate.hasBubbleColumns();
		}

		@Override
		public boolean hasCollisionShapeAt(final int x, final int y, final int z) {
			return this.delegate.hasCollisionShapeAt(x, y, z);
		}

		@Override
		public boolean onClimbableAt(final int x, final int y, final int z, final boolean fallFlying) {
			return this.delegate.onClimbableAt(x, y, z, fallFlying);
		}

		@Override
		public boolean hasClimbables() {
			return this.delegate.hasClimbables();
		}

		@Override
		public boolean hasNonDefaultFriction() {
			return this.delegate.hasNonDefaultFriction();
		}

		@Override
		public boolean hasNonDefaultSpeedFactor() {
			return this.delegate.hasNonDefaultSpeedFactor();
		}

		@Override
		public boolean hasNonDefaultJumpFactor() {
			return this.delegate.hasNonDefaultJumpFactor();
		}

		@Override
		public CollisionBehavior collisionBehaviorAt(final int x, final int y, final int z) {
			return this.delegate.collisionBehaviorAt(x, y, z);
		}

		@Override
		public boolean hasContextSensitiveCollision() {
			return this.delegate.hasContextSensitiveCollision();
		}

		@Override
		public boolean hasPowderSnow() {
			return this.delegate.hasPowderSnow();
		}

		@Override
		public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final CollisionBuffer target) {
			this.delegate.collectCollisionBoxes(minX, minY, minZ, maxX, maxY, maxZ, target);
		}

		@Override
		public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final double entityBottom, final boolean descending,
		    final CollisionBuffer target) {
			this.delegate.collectCollisionBoxes(minX, minY, minZ, maxX, maxY, maxZ, entityBottom, descending, target);
		}

		@Override
		public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final double entityBottom, final boolean descending,
		    final double fallDistance, final CollisionBuffer target) {
			this.delegate.collectCollisionBoxes(
			    minX, minY, minZ, maxX, maxY, maxZ, entityBottom, descending, fallDistance, target);
		}

		@Override
		public float friction(final int x, final int y, final int z) {
			return this.delegate.friction(x, y, z);
		}

		@Override
		public float speedFactor(final int x, final int y, final int z) {
			return this.delegate.speedFactor(x, y, z);
		}

		@Override
		public boolean suppressesSupportingSpeedFactor(final int x, final int y, final int z) {
			return this.delegate.suppressesSupportingSpeedFactor(x, y, z);
		}

		@Override
		public void findSupportingBlock(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final double atX, final double atY, final double atZ,
		    final double entityBottom, final boolean descending, final double fallDistance, final SupportCell out) {
			this.delegate.findSupportingBlock(
			    minX, minY, minZ, maxX, maxY, maxZ, atX, atY, atZ, entityBottom, descending, fallDistance, out);
		}

		@Override
		public boolean resetsFallDistanceAlong(final double fromX, final double fromY, final double fromZ,
		    final double toX, final double toY, final double toZ) {
			return this.delegate.resetsFallDistanceAlong(fromX, fromY, fromZ, toX, toY, toZ);
		}

		@Override
		public float jumpFactor(final int x, final int y, final int z) {
			return this.delegate.jumpFactor(x, y, z);
		}

		@Override
		public boolean hasChunkAt(final int x, final int z) {
			return this.delegate.hasChunkAt(x, z);
		}

		@Override
		public int minY() {
			return this.delegate.minY();
		}
	}
}
