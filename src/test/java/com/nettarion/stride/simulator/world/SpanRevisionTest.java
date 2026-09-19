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
 * A proof keyed on the revisions of the sections it touched survives a
 * republication that kept those sections, and no other.
 */
final class SpanRevisionTest {
	private static final WorldSnapshot.BlockEntry AIR = WorldSnapshot.BlockEntry.builder(0, "minecraft:air").build();
	private static final WorldSnapshot.BlockEntry STONE =
	    WorldSnapshot.BlockEntry.builder(1, "minecraft:stone").fullCube().build();

	private static WorldSnapshot part(final SharedPalette palette, final int x, final long seed) {
		int air = palette.index(AIR);
		int stone = palette.index(STONE);
		Random random = new Random(seed);
		int[] cells = new int[Section.CELLS];
		for (int i = 0; i < cells.length; i++) {
			int y = i >> (2 * Section.SHIFT);
			cells[i] = y == 0 || y > 4 && random.nextInt(9) == 0 ? stone : air;
		}
		return WorldSnapshot.owning(x, 0, 0, 16, 16, 16, WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING,
		    palette.blocks(), cells, List.of(WorldSnapshot.FluidEntry.EMPTY), new int[Section.CELLS]);
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
	void proofsSurviveARepublicationThatKeptTheirSectionsAndNotOneThatReplacedThem() {
		SharedPalette palette = new SharedPalette();
		WorldSnapshot[] parts = new WorldSnapshot[4];
		for (int i = 0; i < parts.length; i++)
			parts[i] = part(palette, i * 16, i);
		SnapshotView first = SnapshotView.compile(WorldSnapshot.compose(0, 0, 0, 48, 16, 16,
		    WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING, List.of(parts[0], parts[1], parts[2])));
		SnapshotView slid = SnapshotView.compile(WorldSnapshot.compose(16, 0, 0, 48, 16, 16,
		    WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING, List.of(parts[1], parts[2], parts[3])));
		assertNotEquals(first.identity(), slid.identity());
		assertEquals(first.spanRevision(20, 0, 3, 24, 3, 6), slid.spanRevision(20, 0, 3, 24, 3, 6),
		    "the same section objects answer the same revision in both publications");
		assertNotEquals(first.spanRevision(20, 0, 3, 24, 3, 6), first.spanRevision(36, 0, 3, 40, 3, 6));

		PlayerState state = standingAt(24.5, 1.0, 8.5);
		state.certifyPoseFit(first, PlayerState.Pose.STANDING);
		assertTrue(state.hasPoseFitCertificate(first, PlayerState.Pose.STANDING));
		assertFalse(state.hasPoseFitCertificate(slid, PlayerState.Pose.STANDING),
		    "a proof not carried out of its view is a miss elsewhere");
		state.carryPoseFitCertificate(first);
		assertTrue(state.hasPoseFitCertificate(slid, PlayerState.Pose.STANDING),
		    "the proof was taken over sections the slid corridor kept");
		assertTrue(
		    state.copy().hasPoseFitCertificate(slid, PlayerState.Pose.STANDING), "a copy carries the carried proof");
		Scratch scratch = new Scratch();
		scratch.span.retain(first, 22, 0, 6, 26, 3, 10);
		assertFalse(scratch.span.covers(slid, 23, 1, 7, 25, 2, 9));
		scratch.carry(first);
		assertTrue(scratch.span.covers(slid, 23, 1, 7, 25, 2, 9));
		assertTrue(scratch.span.covers(first, 23, 1, 7, 25, 2, 9), "carrying does not disturb the home view");

		// A republication with a replaced section under the body is a miss.
		WorldSnapshot replaced = part(palette, 16, 99L);
		SnapshotView changed = SnapshotView.compile(WorldSnapshot.compose(16, 0, 0, 48, 16, 16,
		    WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING, List.of(replaced, parts[2], parts[3])));
		assertFalse(state.hasPoseFitCertificate(changed, PlayerState.Pose.STANDING));
		assertFalse(scratch.span.covers(changed, 23, 1, 7, 25, 2, 9));

		// An edit inside the span in one view is a miss there and not in the other.
		SnapshotView edited = slid.fork();
		edited.replaceCell(24, 2, 8, 1);
		assertFalse(state.hasPoseFitCertificate(edited, PlayerState.Pose.STANDING));
		assertTrue(state.hasPoseFitCertificate(slid, PlayerState.Pose.STANDING));
		// A distant edit leaves the span's revision alone.
		SnapshotView far = slid.fork();
		far.replaceCell(60, 8, 8, 1);
		assertTrue(state.hasPoseFitCertificate(far, PlayerState.Pose.STANDING));

		// A proof stale in its own view is not carried.
		SnapshotView editedFirst = first.fork();
		PlayerState stale = standingAt(24.5, 1.0, 8.5);
		stale.certifyPoseFit(editedFirst, PlayerState.Pose.STANDING);
		editedFirst.replaceCell(24, 2, 8, 1);
		stale.carryPoseFitCertificate(editedFirst);
		assertFalse(stale.hasPoseFitCertificate(slid, PlayerState.Pose.STANDING));
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
		WorldSnapshot joined = WorldSnapshot.compose(
		    0, 0, 0, 32, 16, 16, WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING, List.of(left, right));
		assertEquals(right.sections()[0].revision(), joined.sections()[1].revision());
		assertNotEquals(left.sections()[0].revision(), right.sections()[0].revision());
	}
}
