package com.nettarion.stride.simulator.geometry;

import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.world.CompleteWorldView;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Source-shape facts that cannot be reconstructed from a flattened box list. */
class CollisionShapeIdentityTest {
	private static final double EPSILON = 1.0E-7;

	@Test
	void canonicalAndGeneralUnitCubesUseTheirDistinctBroadphaseRules() {
		WorldSnapshot.BlockEntry canonical = WorldSnapshot.BlockEntry.builder(1, "test:canonical").fullCube().build();
		WorldSnapshot.BlockEntry general = WorldSnapshot.BlockEntry.builder(1, "test:general-unit-cube")
		                                       .boxes(WorldSnapshot.ShapeBox.FULL_CUBE)
		                                       .build();
		double[] overlap = {Math.nextDown(EPSILON), EPSILON, Math.nextUp(EPSILON)};

		assertTrue(canonical.canonicalFullCollisionShape());
		assertFalse(general.canonicalFullCollisionShape());
		for (double maxX : overlap) {
			assertEquals(
			    1, collect(canonical, maxX).size(), "canonical full shape uses strict AABB intersection at " + maxX);
		}
		assertEquals(0, collect(general, overlap[0]).size());
		assertEquals(0, collect(general, overlap[1]).size(), "the general-shape epsilon boundary is strict");
		assertEquals(1, collect(general, overlap[2]).size());
	}

	@Test
	void subEpsilonQueryShapeIsEmptyForGeneralBroadphase() {
		WorldSnapshot.BlockEntry general = WorldSnapshot.BlockEntry.builder(1, "test:general-unit-cube")
		                                       .boxes(WorldSnapshot.ShapeBox.FULL_CUBE)
		                                       .build();
		CollisionBuffer collisions = new CollisionBuffer();
		double thinMaxY = 0.25 + 5.0E-8;

		world(general).collectCollisionBoxes(0.25, 0.25, 0.25, 0.75, thinMaxY, 0.75, collisions);

		assertEquals(0, collisions.size(), "Shapes.create(query) is empty when any dimension is below 1e-7");
	}

	@Test
	void ordinaryAndRetainedCollectionKeepOneCompositeSourceShapeTogether() {
		WorldSnapshot.BlockEntry composite = WorldSnapshot.BlockEntry.builder(1, "test:composite")
		                                         .boxes(new WorldSnapshot.ShapeBox(0.0, 0.0, 0.0, 0.25, 1.0, 1.0),
		                                             new WorldSnapshot.ShapeBox(0.75, 0.0, 0.0, 1.0, 1.0, 1.0))
		                                         .build();
		SnapshotView world = world(composite);
		assertEquals(WorldView.CollisionShapeProtocol.SOURCE_GROUPS_V1, world.collisionShapeProtocol());
		CollisionBuffer direct = new CollisionBuffer();
		world.collectCollisionBoxes(0.8, 0.1, 0.1, 0.9, 0.9, 0.9, direct);

		assertCompositeGroup(direct);

		Scratch scratch = new Scratch();
		assertCompositeGroup(
		    CollisionCollector.collect(world, scratch, 0.8, 0.1, 0.1, 0.9, 0.9, 0.9, 0.1, false, 0.0, false));
	}

	@Test
	void legacyBoxProtocolFallsBackBeforeRetainedRefiltering() {
		LegacySpanProvider legacy =
		    new LegacySpanProvider(world(WorldSnapshot.BlockEntry.builder(1, "test:stone").fullCube().build()));
		Scratch scratch = new Scratch();

		CollisionBuffer collected =
		    CollisionCollector.collect(legacy, scratch, 0.25, 0.1, 0.1, 0.75, 0.9, 0.9, 0.1, false, 0.0, false);

		assertEquals(WorldView.CollisionShapeProtocol.LEGACY_BOXES, legacy.collisionShapeProtocol());
		assertSame(scratch.collisions, collected, "a legacy provider is answered by the world, not a span");
		assertEquals(1, collected.size());
	}

	private static CollisionBuffer collect(final WorldSnapshot.BlockEntry entry, final double maxX) {
		CollisionBuffer result = new CollisionBuffer();
		world(entry).collectCollisionBoxes(-0.5, 0.1, 0.1, maxX, 0.9, 0.9, result);
		return result;
	}

	private static SnapshotView world(final WorldSnapshot.BlockEntry entry) {
		WorldSnapshot.BlockEntry air = WorldSnapshot.BlockEntry.builder(0, "test:air").build();
		WorldSnapshot snapshot =
		    WorldSnapshot.builder(WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING, -2, -2, -2, 5, 5, 5)
		        .palette(air, entry)
		        .set(0, 0, 0, 1)
		        .build();
		return new SnapshotView(snapshot);
	}

	private static void assertCompositeGroup(final CollisionBuffer shapes) {
		assertEquals(1, shapes.selectedGroupCount(), "one source shape is reached");
		int group = shapes.selectedGroup(0);
		assertEquals(2, shapes.groupEnd(group) - shapes.groupStart(group),
		    "admitting any part admits the complete source VoxelShape");
		assertTrue(shapes.startsGroup(shapes.groupStart(group)));
		assertFalse(shapes.startsGroup(shapes.groupStart(group) + 1));
		assertFalse(shapes.groupCanonicalFull(group));
	}

	/** Simulates a pre-grouping provider that advertised span reuse. */
	private record LegacySpanProvider(SnapshotView delegate) implements CompleteWorldView {
		@Override
		public long collisionVersion() {
			return this.delegate.collisionVersion();
		}
		@Override
		public long identity() {
			return this.delegate.identity();
		}

		@Override
		public boolean supportsSpanReuse() {
			return true;
		}

		@Override
		public boolean collectSpanBoxes(final int x0, final int y0, final int z0, final int x1, final int y1,
		    final int z1, final CollisionBuffer target) {
			throw new AssertionError("legacy protocol must not enter retained refiltering");
		}

		@Override
		public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final CollisionBuffer target) {
			this.delegate.collectCollisionBoxes(minX, minY, minZ, maxX, maxY, maxZ, target);
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
