package com.nettarion.stride.simulator.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * validation: behaviours are answered per 16³ section, not per view.
 *
 * <p>The world is 48 cells on a side, so it is three sections wide in each
 * direction and a query near the origin and a query at the far corner land in
 * different sections. Each test carries its own negative control: the whole-view
 * predicate the kernel used to read stays *true* throughout, so a passing
 * assertion is about the mask discriminating and not about the fixture being
 * empty.
 */
class SectionPropertyMaskTest {
	private static final int AIR = 0;
	private static final int STONE = 1;
	private static final int ICE = 2;
	private static final int VINE = 3;
	private static final int BUBBLE = 4;

	/** Fluid palette indexes of {@link #fluidWorld}. */
	private static final int WATER = 1;
	private static final int EMPTY_ALIAS = 2;

	private static final int SIZE = 48;
	/** Inside the last section, well clear of the section the player occupies. */
	private static final int FAR = 40;

	@Test
	void boundedSpansMatchSingleCellSummariesAcrossFineAndSectionBoundaries() {
		SnapshotView parent = world();
		parent.replaceCell(15, 15, 15, ICE);
		SnapshotView child = parent.fork();
		child.replaceCell(16, 16, 16, VINE);
		child.replaceCell(40, 40, 40, BUBBLE);
		child.replaceCell(15, 15, 15, AIR);
		int[] starts = {0, 3, 15, 39, 44};
		for (SnapshotView view : List.of(parent, child)) {
			for (int x0 : starts) {
				for (int y0 : starts) {
					for (int z0 : starts) {
						int expected = 0;
						// Four cells per axis touch at most eight fine cubes.
						for (int x = x0; x <= x0 + 3; x++) {
							for (int y = y0; y <= y0 + 3; y++) {
								for (int z = z0; z <= z0 + 3; z++) {
									expected |= view.propertiesIn(WorldView.PROPERTIES_ALL, x, y, z, x, y, z);
								}
							}
						}
						assertEquals(
						    expected, view.propertiesIn(WorldView.PROPERTIES_ALL, x0, y0, z0, x0 + 3, y0 + 3, z0 + 3));
						assertEquals(expected & WorldView.PROPERTY_FRICTION,
						    view.propertiesIn(WorldView.PROPERTY_FRICTION, x0, y0, z0, x0 + 3, y0 + 3, z0 + 3));
					}
				}
			}
		}
	}

	@Test
	void aDistantBehaviourDoesNotTurnOnTheLocalGate() {
		SnapshotView world = world();

		// The control: every whole-view flag the kernel used to gate on is true,
		// so anything that reads them cannot skip a thing. This is validation's defect
		// stated as an assertion.
		assertTrue(world.hasNonDefaultFriction(), "control: ice makes the view-wide flag true");
		assertTrue(world.hasClimbables(), "control: the vine makes the view-wide flag true");
		assertTrue(world.hasBubbleColumns(), "control: the column makes the view-wide flag true");

		// ... and the section the player is actually in has none of them.
		assertEquals(0, world.propertiesIn(WorldView.PROPERTY_FRICTION, 1, 1, 1, 1, 1, 1));
		assertEquals(0, world.propertiesIn(WorldView.PROPERTY_CLIMBABLE, 1, 1, 1, 1, 1, 1));
		assertEquals(0, world.propertiesIn(WorldView.PROPERTY_BUBBLE_COLUMN, 1, 1, 1, 1, 1, 1));
		assertEquals(0, world.propertiesIn(WorldView.PROPERTIES_ALL, 1, 1, 1, 1, 1, 1));
	}

	@Test
	void theSectionThatOwnsTheBehaviourStillReportsIt() {
		SnapshotView world = world();

		assertEquals(
		    WorldView.PROPERTY_FRICTION, world.propertiesIn(WorldView.PROPERTY_FRICTION, FAR, 1, 1, FAR, 1, 1));
		assertEquals(
		    WorldView.PROPERTY_CLIMBABLE, world.propertiesIn(WorldView.PROPERTY_CLIMBABLE, 1, FAR, 1, 1, FAR, 1));
		assertEquals(WorldView.PROPERTY_BUBBLE_COLUMN,
		    world.propertiesIn(WorldView.PROPERTY_BUBBLE_COLUMN, 1, 1, FAR, 1, 1, FAR));

		// A span crossing from an empty section into the owning one must see it,
		// which is what makes the multi-section path more than an optimization.
		assertEquals(WorldView.PROPERTY_FRICTION, world.propertiesIn(WorldView.PROPERTY_FRICTION, 1, 1, 1, FAR, 1, 1));
	}

	@Test
	void onlyTheRequestedBitsComeBack() {
		SnapshotView world = world();

		assertEquals(WorldView.PROPERTY_CLIMBABLE,
		    world.propertiesIn(WorldView.PROPERTY_CLIMBABLE | WorldView.PROPERTY_POWDER_SNOW, 1, FAR, 1, 1, FAR, 1),
		    "a bit outside `wanted` must never be returned");
		assertEquals(0, world.propertiesIn(WorldView.PROPERTY_POWDER_SNOW, 1, FAR, 1, 1, FAR, 1));
	}

	@Test
	void aSpanLeavingTheRegionFallsBackToTheWholeViewAnswer() {
		SnapshotView world = world();

		// Outside the region is unknown, and the accessors own that semantic —
		// answering zero here would skip the lookup that is supposed to refuse.
		assertNotEquals(0, world.propertiesIn(WorldView.PROPERTY_CLIMBABLE, -4, 1, 1, 1, 1, 1),
		    "a span reaching outside must not be answered empty");
		// A behaviour the snapshot does not have anywhere still answers zero, so
		// a dry world is not made to start scanning at the region edge.
		assertEquals(0, world.propertiesIn(WorldView.PROPERTY_FLUID, -4, 1, 1, 1, 1, 1));
	}

	@Test
	void replacementIntroducesTheBehaviourIntoItsOwnSection() {
		SnapshotView world = world();
		assertEquals(0, world.propertiesIn(WorldView.PROPERTY_FRICTION, 1, 1, 1, 1, 1, 1));

		world.replaceCell(1, 1, 1, ICE);

		assertEquals(WorldView.PROPERTY_FRICTION, world.propertiesIn(WorldView.PROPERTY_FRICTION, 1, 1, 1, 1, 1, 1));
		// The mask is set-only by design: removing the ice again may leave the bit
		// standing, which costs a lookup that answers the default. The reverse
		// error would silently drop a real behaviour, so it is the one ruled out.
		world.replaceCell(1, 1, 1, AIR);
		assertEquals(0.6F, world.friction(1, 1, 1), "the fact itself must be exact");
	}

	@Test
	void aForkDoesNotInheritItsParentsLaterReplacements() {
		SnapshotView parent = world();
		SnapshotView child = parent.fork();

		child.replaceCell(1, 1, 1, ICE);

		assertEquals(WorldView.PROPERTY_FRICTION, child.propertiesIn(WorldView.PROPERTY_FRICTION, 1, 1, 1, 1, 1, 1));
		assertEquals(0, parent.propertiesIn(WorldView.PROPERTY_FRICTION, 1, 1, 1, 1, 1, 1),
		    "the section mask must be per-instance, like collisionSectionCounts");
	}

	@Test
	void aViewWithoutSectionDetailKeepsTheWholeViewAnswer() {
		// FlatFloorView is one block everywhere, so its answer is a constant --
		// and an ordinary floor has no non-default coefficient to report.
		assertEquals(0, FlatFloorView.ordinary(0, -64).propertiesIn(WorldView.PROPERTIES_ALL, 0, 0, 0, 0, 0, 0));
		assertEquals(WorldView.PROPERTY_FRICTION,
		    new FlatFloorView(0, -64, 0.98F, 1.0F, 1.0F).propertiesIn(WorldView.PROPERTIES_ALL, 0, 0, 0, 0, 0, 0));
		assertFalse(FlatFloorView.ordinary(0, -64).hasFluids());
	}

	@Test
	void aFluidPocketIsReportedByItsOwnSectionOnly() {
		SnapshotView world = fluidWorld(WATER);

		assertTrue(world.hasFluids(), "control: the view-wide flag stays true");
		assertEquals(
		    WorldView.PROPERTY_FLUID, world.propertiesIn(WorldView.PROPERTY_FLUID, FAR, FAR, FAR, FAR, FAR, FAR));
		assertEquals(0, world.propertiesIn(WorldView.PROPERTY_FLUID, 1, 1, 1, 1, 1, 1));
		assertEquals(WorldView.PROPERTY_FLUID, world.propertiesIn(WorldView.PROPERTY_FLUID, 1, 1, 1, FAR, FAR, FAR),
		    "a span crossing into the pocket's section must see it");
	}

	/**
	 * A fluid plane whose only selected entry resolves to {@code EMPTY} kind. The
	 * plane exists, so the whole-view answer must still admit fluid; no cell
	 * carries a fluid, so no section may claim one.
	 */
	@Test
	void aFluidPlaneResolvingToEmptySetsNoSectionBit() {
		SnapshotView world = fluidWorld(EMPTY_ALIAS);

		assertTrue(world.hasFluids(), "control: the plane itself is present");
		assertEquals(WorldView.PROPERTY_FLUID, world.propertiesIn(WorldView.PROPERTY_FLUID, -4, 1, 1, 1, 1, 1));
		assertEquals(0, world.propertiesIn(WorldView.PROPERTY_FLUID, FAR, FAR, FAR, FAR, FAR, FAR));
		assertEquals(0, world.propertiesIn(WorldView.PROPERTY_FLUID, 1, 1, 1, 1, 1, 1));
	}

	private static SnapshotView fluidWorld(final int fluidIndex) {
		return new SnapshotView(WorldSnapshot.builder(WorldSnapshot.OutsideRegion.SEALED, 0, 0, 0, SIZE, SIZE, SIZE)
		        .palette(plain(AIR, "air", List.of()), plain(STONE, "stone", cube()))
		        .fluidPalette(WorldSnapshot.FluidEntry.EMPTY,
		            new WorldSnapshot.FluidEntry(
		                1, "minecraft:water", WorldSnapshot.FluidKind.WATER, 1.0, 0.0, 0.0, 0.0, true),
		            new WorldSnapshot.FluidEntry(
		                2, "stride:empty_alias", WorldSnapshot.FluidKind.EMPTY, 0.0, 0.0, 0.0, 0.0, false))
		        .setFluid(FAR, FAR, FAR, fluidIndex)
		        .build());
	}

	private static SnapshotView world() {
		int[] cells = new int[SIZE * SIZE * SIZE];
		cells[cell(FAR, 1, 1)] = ICE;
		cells[cell(1, FAR, 1)] = VINE;
		cells[cell(1, 1, FAR)] = BUBBLE;
		return new SnapshotView(new WorldSnapshot(0, 0, 0, SIZE, SIZE, SIZE, WorldSnapshot.OutsideRegion.SEALED,
		    List.of(plain(AIR, "air", List.of()), plain(STONE, "stone", cube()),
		        new WorldSnapshot.BlockEntry(ICE, "ice", 0.98F, 1.0F, 1.0F, false, false,
		            WorldView.BubbleColumnMode.NONE, false, WorldView.CollisionBehavior.ORDINARY,
		            WorldSnapshot.Suffocation.UNKNOWN, WorldView.InsideEffect.NONE, 0.0F, false, WorldView.StepOn.NONE,
		            false, WorldView.Climbability.NONE, cube()),
		        new WorldSnapshot.BlockEntry(VINE, "vine", 0.6F, 1.0F, 1.0F, false, false,
		            WorldView.BubbleColumnMode.NONE, true, WorldView.CollisionBehavior.ORDINARY,
		            WorldSnapshot.Suffocation.UNKNOWN, WorldView.InsideEffect.NONE, 0.0F, false, WorldView.StepOn.NONE,
		            false, WorldView.Climbability.CLIMBABLE, List.of()),
		        new WorldSnapshot.BlockEntry(BUBBLE, "bubble_column", 0.6F, 1.0F, 1.0F, false, false,
		            WorldView.BubbleColumnMode.PUSH_UP, false, WorldView.CollisionBehavior.ORDINARY,
		            WorldSnapshot.Suffocation.UNKNOWN, WorldView.InsideEffect.NONE, 0.0F, false, WorldView.StepOn.NONE,
		            false, WorldView.Climbability.NONE, List.of())),
		    cells));
	}

	private static WorldSnapshot.BlockEntry plain(
	    final int id, final String name, final List<WorldSnapshot.ShapeBox> boxes) {
		return new WorldSnapshot.BlockEntry(id, name, 0.6F, 1.0F, 1.0F, boxes);
	}

	private static List<WorldSnapshot.ShapeBox> cube() {
		return List.of(new WorldSnapshot.ShapeBox(0, 0, 0, 1, 1, 1));
	}

	private static int cell(final int x, final int y, final int z) {
		return (y * SIZE + z) * SIZE + x;
	}
}
