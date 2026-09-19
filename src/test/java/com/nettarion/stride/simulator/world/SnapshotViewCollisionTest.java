package com.nettarion.stride.simulator.world;

import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotViewCollisionTest {
	@Test
	void supportingBlockUsesVanillaTieBreakAndStrictBoxFaces() {
		BlockEntry stone = new BlockEntry(
		    1, "test:stone", 0.6F, 1.0F, 1.0F, List.of(new ShapeBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)));
		SnapshotView world = new SnapshotView(new WorldSnapshot(0, -1, 0, 3, 1, 1,
		    OutsidePolicy.REFUSING, List.of(air(), stone), new int[] {1, 1, 1}));
		SupportCell support = new SupportCell();

		world.findSupportingBlock(0.7, -1.0E-6, 0.2, 1.3, 0.0, 0.8, 1.0, 0.0, 0.5, 0.0, false, 0.0, support);
		assertTrue(support.present);
		assertEquals(1, support.x, "equal distances keep the larger Vec3i cell, matching vanilla");
		assertEquals(-1, support.y);

		world.findSupportingBlock(0.4, -1.0E-6, 0.2, 1.0, 0.0, 0.8, 0.7, 0.0, 0.5, 0.0, false, 0.0, support);
		assertTrue(support.present);
		assertEquals(0, support.x, "a cell beginning exactly at the box's max face does not intersect");
	}

	@Test
	void supportingBlockIncludesLargeShapeOwnersFromTheCursorRing() {
		BlockEntry overhanging = new BlockEntry(
		    1, "test:overhanging", 0.6F, 1.0F, 1.0F, List.of(new ShapeBox(0.0, 0.0, 0.0, 2.0, 1.0, 1.0)));
		SnapshotView world = new SnapshotView(new WorldSnapshot(0, -1, 0, 3, 1, 1,
		    OutsidePolicy.REFUSING, List.of(air(), overhanging), new int[] {1, 0, 0}));
		SupportCell support = new SupportCell();

		world.findSupportingBlock(1.2, -1.0E-6, 0.2, 1.8, 0.0, 0.8, 1.5, 0.0, 0.5, 0.0, false, 0.0, support);

		assertTrue(support.present);
		assertEquals(0, support.x, "a shape owned by the adjacent cell can still support the player");
		assertEquals(-1, support.y);
	}

	@Test
	void fallResetRayUsesFullBlockShapeForClimbableTags() {
		BlockEntry climbable = new BlockEntry(1, "test:tagged_climbable", 0.6F, 1.0F, 1.0F,
		    false, false, WorldView.BubbleColumnMode.NONE, true, WorldView.CollisionBehavior.ORDINARY,
		    Suffocation.UNKNOWN, WorldView.InsideEffect.NONE, 0.0F, false, WorldView.StepOn.NONE, false,
		    WorldView.Climbability.CLIMBABLE, List.of());
		SnapshotView world = new SnapshotView(new WorldSnapshot(0, 0, 0, 3, 1, 1,
		    OutsidePolicy.REFUSING, List.of(air(), climbable), new int[] {0, 1, 0}));

		assertTrue(world.resetsFallDistanceAlong(0.2, 0.5, 0.5, 2.8, 0.5, 0.5),
		    "FALL_DAMAGE_RESETTING uses the block's full clip shape, not collision boxes");
		assertTrue(world.resetsFallDistanceAlong(1.2, 0.5, 0.5, 2.8, 0.5, 0.5),
		    "VoxelShape.clip reports a ray that starts inside the reset shape");
	}

	@Test
	void fallResetRayUsesCapturedWaterSurfaceHeight() {
		FluidEntry water =
		    new FluidEntry(1, "test:water", FluidKind.WATER, 0.5, 0.0, 0.0, 0.0, false);
		SnapshotView world = new SnapshotView(
		    new WorldSnapshot(0, 0, 0, 3, 1, 1, OutsidePolicy.REFUSING, List.of(air()),
		        new int[] {0, 0, 0}, List.of(FluidEntry.EMPTY, water), new int[] {0, 1, 0}));

		assertTrue(world.resetsFallDistanceAlong(0.2, 0.25, 0.5, 2.8, 0.25, 0.5));
		assertTrue(world.resetsFallDistanceAlong(1.2, 0.25, 0.5, 2.8, 0.25, 0.5));
		// Above the captured height but inside the cube, the hit depends on the
		// height the client's session-cached shape for this state holds, which
		// may be the full cube: not a world fact, so the segment refuses.
		assertThrows(
		    UnimplementedMechanicException.class, () -> world.resetsFallDistanceAlong(0.2, 0.75, 0.5, 2.8, 0.75, 0.5));
	}

	@Test
	void exposesOnlyExplicitClimbablePaletteBehavior() {
		BlockEntry ordinary =
		    new BlockEntry(0, "minecraft:vine", 0.6F, 1.0F, 1.0F, List.of());
		BlockEntry climbable = new BlockEntry(1, "not-a-known-climbable-name", 0.6F, 1.0F,
		    1.0F, false, false, WorldView.BubbleColumnMode.NONE, true, WorldView.CollisionBehavior.ORDINARY,
		    Suffocation.UNKNOWN, WorldView.InsideEffect.NONE, 0.0F, false, WorldView.StepOn.NONE, false,
		    WorldView.Climbability.CLIMBABLE, List.of());
		SnapshotView world = new SnapshotView(new WorldSnapshot(0, 0, 0, 2, 1, 1,
		    OutsidePolicy.REFUSING, List.of(ordinary, climbable), new int[] {0, 1}));

		assertEquals(false, world.onClimbableAt(0, 0, 0, false));
		assertEquals(true, world.onClimbableAt(1, 0, 0, false));
		assertThrows(UnimplementedMechanicException.class, () -> world.onClimbableAt(2, 0, 0, false));
	}

	@Test
	void terminatingOutsideDoesNotFireForNonIntersectingCursorMargin() {
		BlockEntry air = new BlockEntry(0, "air", 0.6F, 1.0F, 1.0F, List.of());
		SnapshotView world = new SnapshotView(new WorldSnapshot(
		    0, 0, 0, 1, 1, 1, OutsidePolicy.REFUSING, List.of(air), new int[] {0}));
		CollisionBuffer collisions = new CollisionBuffer(1);

		world.collectCollisionBoxes(0.2, 0.2, 0.2, 0.8, 0.8, 0.8, collisions);

		assertEquals(0, collisions.size());
	}

	@Test
	void findsAShapeThatExtendsAboveItsOwningCell() {
		BlockEntry air = new BlockEntry(0, "air", 0.6F, 1.0F, 1.0F, List.of());
		BlockEntry wall = new BlockEntry(
		    1, "wall", 0.6F, 1.0F, 1.0F, List.of(new ShapeBox(0.25, 0.0, 0.25, 0.75, 1.5, 0.75)));
		SnapshotView world =
		    new SnapshotView(WorldSnapshot.builder(OutsidePolicy.SEALED, -1, -1, -1, 3, 3, 3)
		            .palette(air, wall)
		            .set(0, 0, 0, 1)
		            .build());
		CollisionBuffer collisions = new CollisionBuffer(1);

		world.collectCollisionBoxes(0.3, 1.2, 0.3, 0.7, 1.4, 0.7, collisions);

		assertEquals(1, collisions.size(), "query in the cell above must see the wall owned by the lower cell");
		assertEquals(Double.doubleToRawLongBits(1.5), Double.doubleToRawLongBits(collisions.maxY(0)));
	}

	@Test
	void sectionRejectionKeepsLargeShapeOwnersAcrossSectionBoundaries() {
		BlockEntry crossing = new BlockEntry(
		    1, "crossing", 0.6F, 1.0F, 1.0F, List.of(new ShapeBox(0.0, 0.0, 0.0, 2.0, 1.0, 1.0)));
		int[] cells = new int[18];
		cells[15] = 1;
		SnapshotView world = new SnapshotView(
		    new WorldSnapshot(0, 0, 0, 18, 1, 1, OutsidePolicy.SEALED, List.of(air(), crossing), cells));
		CollisionBuffer collisions = new CollisionBuffer(1);

		world.collectCollisionBoxes(16.2, 0.2, 0.2, 16.8, 0.8, 0.8, collisions);

		assertEquals(
		    1, collisions.size(), "an empty query section must still inspect a large-shape owner in its cursor ring");
		assertRawBox(collisions, 0, 15.0, 0.0, 0.0, 17.0, 1.0, 1.0);
	}

	@Test
	void cursorRingNeverScansOwnersMoreThanOneCellAway() {
		BlockEntry air = air();
		BlockEntry overgrown = new BlockEntry(1, "overgrown", 0.6F, 1.0F, 1.0F,
		    List.of(new ShapeBox(-1.25, -1.25, -1.25, 2.25, 2.25, 2.25)));
		SnapshotView world =
		    new SnapshotView(WorldSnapshot.builder(OutsidePolicy.SEALED, 0, 0, 0, 5, 5, 5)
		            .palette(air, overgrown)
		            .set(2, 2, 2, 1)
		            .build());
		CollisionBuffer collisions = new CollisionBuffer(1);

		world.collectCollisionBoxes(0.8, 0.8, 0.8, 1.0, 1.0, 1.0, collisions);
		assertEquals(0, collisions.size(), "vanilla does not generalize beyond its one-cell ring");

		world.collectCollisionBoxes(4.0, 4.0, 4.0, 4.2, 4.2, 4.2, collisions);
		assertEquals(0, collisions.size(), "far lower owners are also outside Cursor3D's ring");
	}

	@Test
	void rejectsUnsupportedMovingPistonEntries() {
		ShapeBox overgrown = new ShapeBox(0.0, 0.0, 0.0, 2.0, 2.0, 1.0);
		BlockEntry merelyNamedPiston = new BlockEntry(1, "minecraft:moving_piston", 0.6F,
		    1.0F, 1.0F, false, false, WorldView.BubbleColumnMode.NONE, false, WorldView.CollisionBehavior.ORDINARY,
		    Suffocation.UNKNOWN, WorldView.InsideEffect.NONE, 0.0F, false, WorldView.StepOn.NONE, false,
		    WorldView.Climbability.NONE, List.of(overgrown));
		BlockEntry explicitPiston = new BlockEntry(2, "ordinary", 0.6F, 1.0F, 1.0F, true,
		    false, WorldView.BubbleColumnMode.NONE, false, WorldView.CollisionBehavior.ORDINARY,
		    Suffocation.UNKNOWN, WorldView.InsideEffect.NONE, 0.0F, false, WorldView.StepOn.NONE, false,
		    WorldView.Climbability.NONE, List.of(overgrown));
		int[] cells = new int[27];
		cells[0] = 1;
		SnapshotView ordinary = new SnapshotView(new WorldSnapshot(
		    0, 0, 0, 3, 3, 3, OutsidePolicy.SEALED, List.of(air(), merelyNamedPiston), cells));
		CollisionBuffer collisions = new CollisionBuffer(1);

		ordinary.collectCollisionBoxes(1.2, 1.2, 0.2, 1.8, 1.8, 0.8, collisions);
		assertEquals(0, collisions.size(), "free-form name must not grant moving-piston behavior");

		assertThrows(IllegalArgumentException.class,
		    ()
		        -> new SnapshotView(new WorldSnapshot(
		            0, 0, 0, 3, 3, 3, OutsidePolicy.SEALED, List.of(air(), explicitPiston), cells)));
	}

	@Test
	void preservesXFastCellOrderAndPaletteShapeOrder() {
		BlockEntry ordered = new BlockEntry(1, "ordered", 0.6F, 1.0F, 1.0F,
		    List.of(new ShapeBox(0.0, 0.0, 0.0, 0.75, 1.0, 1.0),
		        new ShapeBox(0.25, 0.0, 0.0, 1.0, 1.0, 1.0)));
		SnapshotView world = new SnapshotView(new WorldSnapshot(
		    0, 0, 0, 2, 1, 1, OutsidePolicy.SEALED, List.of(ordered), new int[] {0, 0}));
		CollisionBuffer collisions = new CollisionBuffer(1);

		world.collectCollisionBoxes(0.5, 0.2, 0.2, 1.5, 0.8, 0.8, collisions);

		assertEquals(4, collisions.size());
		assertRawBox(collisions, 0, 0.0, 0.0, 0.0, 0.75, 1.0, 1.0);
		assertRawBox(collisions, 1, 0.25, 0.0, 0.0, 1.0, 1.0, 1.0);
		assertRawBox(collisions, 2, 1.0, 0.0, 0.0, 1.75, 1.0, 1.0);
		assertRawBox(collisions, 3, 1.25, 0.0, 0.0, 2.0, 1.0, 1.0);
	}

	@Test
	void strictBoundariesAndOutsideRingRemainFailClosed() {
		SnapshotView terminating = new SnapshotView(new WorldSnapshot(
		    0, 0, 0, 1, 1, 1, OutsidePolicy.REFUSING, List.of(air()), new int[] {0}));
		CollisionBuffer collisions = new CollisionBuffer(1);

		assertDoesNotThrow(()
		                       -> terminating.collectCollisionBoxes(0.2, 0.2, 0.2, 1.0, 0.8, 0.8, collisions),
		    "touching the outside cell at an exact face is not an intersection");
		assertThrows(UnimplementedMechanicException.class,
		    () -> terminating.collectCollisionBoxes(0.2, 0.2, 0.2, Math.nextUp(1.0), 0.8, 0.8, collisions));

		SnapshotView sealed = new SnapshotView(
		    new WorldSnapshot(0, 0, 0, 1, 1, 1, OutsidePolicy.SEALED, List.of(air()), new int[] {0}));
		sealed.collectCollisionBoxes(0.9, 0.2, 0.2, 1.1, 0.8, 0.8, collisions);
		assertEquals(1, collisions.size());
		assertRawBox(collisions, 0, 1.0, 0.0, 0.0, 2.0, 1.0, 1.0);
	}

	@Test
	void serverMutableSupportFailsOnlyWhenMovementReachesItsCell() {
		BlockEntry mutableSupport =
		    new BlockEntry(1, "test:server_mutable_support", 0.6F, 1.0F, 1.0F, false, false,
		        WorldView.BubbleColumnMode.NONE, false, WorldView.CollisionBehavior.SERVER_MUTABLE_SUPPORT,
		        Suffocation.NO, WorldView.InsideEffect.NONE, 0.0F, false, WorldView.StepOn.NONE, false,
		        WorldView.Climbability.NONE, List.of(new ShapeBox(0.0, 0.75, 0.0, 1.0, 1.0, 1.0)));
		SnapshotView world = new SnapshotView(new WorldSnapshot(0, 0, 0, 3, 1, 1,
		    OutsidePolicy.REFUSING, List.of(air(), mutableSupport), new int[] {0, 1, 0}));
		CollisionBuffer collisions = new CollisionBuffer(1);

		assertDoesNotThrow(()
		                       -> world.collectCollisionBoxes(0.1, 0.1, 0.1, 0.8, 0.7, 0.8, 0.1, false, collisions),
		    "an unrelated dynamic block must not invalidate a complete snapshot");
		assertThrows(UnimplementedMechanicException.class,
		    ()
		        -> world.collectCollisionBoxes(1.1, 0.1, 0.1, 1.8, 0.7, 0.8, 0.1, false, collisions),
		    "movement into the dynamic cell must still fail closed");
	}

	@Test
	void exactShapeBoundaryDoesNotProduceACollision() {
		BlockEntry shape = new BlockEntry(
		    1, "shape", 0.6F, 1.0F, 1.0F, List.of(new ShapeBox(-0.5, 0.0, 0.0, 1.5, 1.0, 1.0)));
		SnapshotView world = new SnapshotView(
		    new WorldSnapshot(0, 0, 0, 1, 1, 1, OutsidePolicy.SEALED, List.of(shape), new int[] {0}));
		CollisionBuffer collisions = new CollisionBuffer(1);

		world.collectCollisionBoxes(1.5, 0.2, 0.2, 1.6, 0.8, 0.8, collisions);
		assertEquals(
		    1, collisions.size(), "only the sealed outside cell intersects; the shape ends exactly at query min");
		assertRawBox(collisions, 0, 1.0, 0.0, 0.0, 2.0, 1.0, 1.0);
	}

	@Test
	void rejectsNonFiniteAndInvertedShapes() {
		assertThrows(IllegalArgumentException.class, () -> new ShapeBox(Double.NaN, 0, 0, 1, 1, 1));
		assertThrows(
		    IllegalArgumentException.class, () -> new ShapeBox(0, 0, 0, Double.POSITIVE_INFINITY, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> new ShapeBox(1, 0, 0, 0, 1, 1));
	}

	@Test
	void serverMutableSupportRefusesASupportQueryThatMissesItsCapturedShape() {
		SnapshotView world =
		    new SnapshotView(new WorldSnapshot(0, 0, 0, 3, 1, 1, OutsidePolicy.REFUSING,
		        List.of(air(), serverMutableSupport()), new int[] {0, 1, 0}));
		SupportCell support = new SupportCell();

		// The box passes under the captured top plate. Trusting that shape would
		// answer "nothing supports you here", which is the fact the cell cannot
		// hold, so the query must refuse exactly as movement does.
		assertThrows(UnimplementedMechanicException.class,
		    ()
		        -> world.findSupportingBlock(
		            1.1, 0.1, 0.1, 1.8, 0.7, 0.8, 1.45, 0.4, 0.45, 0.1, false, 0.0, false, support),
		    "a support query overlapping the dynamic cell must fail closed");
		assertDoesNotThrow(()
		                       -> world.findSupportingBlock(
		                           0.1, 0.1, 0.1, 0.8, 0.7, 0.8, 0.45, 0.4, 0.45, 0.1, false, 0.0, false, support),
		    "an unrelated support query must not be refused");
	}

	@Test
	void serverMutableSupportWithdrawsOnlyTheSpansItTouches() {
		int[] cells = new int[8 * 3 * 3];
		cells[(1 * 3 + 1) * 8 + 6] = 1;
		SnapshotView world = new SnapshotView(new WorldSnapshot(0, 0, 0, 8, 3, 3,
		    OutsidePolicy.REFUSING, List.of(air(), serverMutableSupport()), cells));
		CollisionBuffer span = new CollisionBuffer(1);

		// Refusing is not player context, so one dynamic cell no longer makes the
		// whole world context-sensitive.
		assertTrue(world.supportsSpanReuse(), "a server-mutable cell must not disable reuse for the whole world");
		assertTrue(world.collectSpanBoxes(1, 1, 1, 2, 1, 1, span), "a span nowhere near the dynamic cell still reuses");
		assertFalse(world.collectSpanBoxes(5, 1, 1, 6, 1, 1, span),
		    "a span reaching the dynamic cell must decline and leave the refusal "
		        + "to the ordinary path");

		CollisionBuffer collisions = new CollisionBuffer(1);
		assertThrows(UnimplementedMechanicException.class,
		    ()
		        -> world.collectCollisionBoxes(6.1, 1.1, 1.1, 6.8, 1.7, 1.8, 0.1, false, collisions),
		    "declining the span must not lose the refusal");
	}

	private static BlockEntry serverMutableSupport() {
		return new BlockEntry(1, "test:server_mutable_support", 0.6F, 1.0F, 1.0F, false, false,
		    WorldView.BubbleColumnMode.NONE, false, WorldView.CollisionBehavior.SERVER_MUTABLE_SUPPORT,
		    Suffocation.NO, WorldView.InsideEffect.NONE, 0.0F, false, WorldView.StepOn.NONE, false,
		    WorldView.Climbability.NONE, List.of(new ShapeBox(0.0, 0.75, 0.0, 1.0, 1.0, 1.0)));
	}

	private static BlockEntry air() {
		return new BlockEntry(0, "air", 0.6F, 1.0F, 1.0F, List.of());
	}

	private static void assertRawBox(final CollisionBuffer collisions, final int index, final double minX,
	    final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
		assertEquals(Double.doubleToRawLongBits(minX), Double.doubleToRawLongBits(collisions.minX(index)));
		assertEquals(Double.doubleToRawLongBits(minY), Double.doubleToRawLongBits(collisions.minY(index)));
		assertEquals(Double.doubleToRawLongBits(minZ), Double.doubleToRawLongBits(collisions.minZ(index)));
		assertEquals(Double.doubleToRawLongBits(maxX), Double.doubleToRawLongBits(collisions.maxX(index)));
		assertEquals(Double.doubleToRawLongBits(maxY), Double.doubleToRawLongBits(collisions.maxY(index)));
		assertEquals(Double.doubleToRawLongBits(maxZ), Double.doubleToRawLongBits(collisions.maxZ(index)));
	}
}
