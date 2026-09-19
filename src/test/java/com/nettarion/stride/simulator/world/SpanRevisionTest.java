package com.nettarion.stride.simulator.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.tick.Scratch;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * A pose-fit cache entry keyed on the revisions of the sections it touched
 * survives a later snapshot that kept those sections, and no other.
 */
final class SpanRevisionTest {
	private static final BlockEntry AIR = BlockEntry.builder(0, "minecraft:air").build();

	private static final BlockEntry STONE = BlockEntry.builder(1, "minecraft:stone").solid().build();

	private static WorldSnapshot part(final SharedPalette palette, final int x, final long seed) {
		int air = palette.index(AIR);
		int stone = palette.index(STONE);
		Random random = new Random(seed);
		int[] cells = new int[Section.CELLS];
		for (int i = 0; i < cells.length; i++) {
			int y = i >> (2 * Section.SHIFT);
			cells[i] = y == 0 || y > 4 && random.nextInt(9) == 0 ? stone : air;
		}
		return WorldSnapshot.owning(x, 0, 0, 16, 16, 16, OutsidePolicy.REFUSING, palette.blocks(), cells,
		    List.of(FluidEntry.EMPTY), new int[Section.CELLS]);
	}

	private static PlayerState standingAt(final double x, final double y, final double z) {
		PlayerState state = new PlayerState();
		state.x = x;
		state.y = y;
		state.z = z;
		state.setBox(AABB.around(x, y, z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));
		return state;
	}

	@Test
	void cacheEntriesSurviveALaterSnapshotThatKeptTheirSectionsAndNotOneThatReplacedThem() {
		SharedPalette palette = new SharedPalette();
		WorldSnapshot[] parts = new WorldSnapshot[4];
		for (int i = 0; i < parts.length; i++) {
			parts[i] = part(palette, i * 16, i);
		}
		SnapshotView first = SnapshotView.compile(
		    WorldSnapshot.compose(0, 0, 0, 48, 16, 16, OutsidePolicy.REFUSING, List.of(parts[0], parts[1], parts[2])));
		SnapshotView slid = SnapshotView.compile(
		    WorldSnapshot.compose(16, 0, 0, 48, 16, 16, OutsidePolicy.REFUSING, List.of(parts[1], parts[2], parts[3])));
		assertNotEquals(first.identity(), slid.identity());
		assertEquals(first.spanRevision(20, 0, 3, 24, 3, 6), slid.spanRevision(20, 0, 3, 24, 3, 6),
		    "the same section objects answer the same revision in both snapshots");
		assertNotEquals(first.spanRevision(20, 0, 3, 24, 3, 6), first.spanRevision(36, 0, 3, 40, 3, 6));

		PlayerState state = standingAt(24.5, 1.0, 8.5);
		state.cachePoseFit(first, PlayerState.Pose.STANDING);
		assertTrue(state.hasCachedPoseFit(first, PlayerState.Pose.STANDING));
		assertFalse(state.hasCachedPoseFit(slid, PlayerState.Pose.STANDING),
		    "a cache entry not carried out of its view is a miss elsewhere");
		state.carryPoseFitCache(first);
		assertTrue(state.hasCachedPoseFit(slid, PlayerState.Pose.STANDING),
		    "the entry was computed over sections the slid snapshot kept");
		assertTrue(state.copy().hasCachedPoseFit(slid, PlayerState.Pose.STANDING), "a copy carries the carried entry");
		Scratch scratch = new Scratch();
		scratch.span.retain(first, 22, 0, 6, 26, 3, 10);
		assertFalse(scratch.span.covers(slid, 23, 1, 7, 25, 2, 9));
		scratch.carry(first);
		assertTrue(scratch.span.covers(slid, 23, 1, 7, 25, 2, 9));
		assertTrue(scratch.span.covers(first, 23, 1, 7, 25, 2, 9), "carrying does not disturb the home view");

		// A later snapshot with a replaced section under the body is a miss.
		WorldSnapshot replaced = part(palette, 16, 99L);
		SnapshotView changed = SnapshotView.compile(
		    WorldSnapshot.compose(16, 0, 0, 48, 16, 16, OutsidePolicy.REFUSING, List.of(replaced, parts[2], parts[3])));
		assertFalse(state.hasCachedPoseFit(changed, PlayerState.Pose.STANDING));
		assertFalse(scratch.span.covers(changed, 23, 1, 7, 25, 2, 9));

		// An edit inside the span in one view is a miss there and not in the other.
		SnapshotView edited = slid.fork();
		edited.replaceCell(24, 2, 8, 1);
		assertFalse(state.hasCachedPoseFit(edited, PlayerState.Pose.STANDING));
		assertTrue(state.hasCachedPoseFit(slid, PlayerState.Pose.STANDING));
		// A distant edit leaves the span's revision alone.
		SnapshotView far = slid.fork();
		far.replaceCell(60, 8, 8, 1);
		assertTrue(state.hasCachedPoseFit(far, PlayerState.Pose.STANDING));

		// An entry stale in its own view is not carried.
		SnapshotView editedFirst = first.fork();
		PlayerState stale = standingAt(24.5, 1.0, 8.5);
		stale.cachePoseFit(editedFirst, PlayerState.Pose.STANDING);
		editedFirst.replaceCell(24, 2, 8, 1);
		stale.carryPoseFitCache(editedFirst);
		assertFalse(stale.hasCachedPoseFit(slid, PlayerState.Pose.STANDING));
	}

	@Test
	void aReindexedSectionKeepsItsRevision() {
		SharedPalette a = new SharedPalette();
		a.index(AIR);
		a.index(STONE);
		SharedPalette b = new SharedPalette();
		b.index(STONE);
		b.index(AIR);
		WorldSnapshot left = part(a, 0, 1);
		WorldSnapshot right = part(b, 16, 2);
		WorldSnapshot joined = WorldSnapshot.compose(0, 0, 0, 32, 16, 16, OutsidePolicy.REFUSING, List.of(left, right));
		assertEquals(right.sections()[0].revision(), joined.sections()[1].revision());
		assertNotEquals(left.sections()[0].revision(), right.sections()[0].revision());
	}
}
