package com.nettarion.stride.simulator.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class SnapshotViewCompileTest {
	@Test
	void exposesOneSharedFrozenExactWorld() {
		WorldSnapshot snapshot = new WorldSnapshot(0, 0, 0, 1, 1, 1, WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING,
		    List.of(new WorldSnapshot.BlockEntry(0, "air", 0.6F, 1.0F, 1.0F, List.of())), new int[] {0});

		SnapshotView compiled = SnapshotView.compile(snapshot);

		assertSame(snapshot, compiled.snapshot());
		assertSame(compiled, compiled.frozenFork());
		assertSame(snapshot, compiled.fork().snapshot());
		assertTrue(compiled.movementFactsImmutable());
		assertThrows(IllegalStateException.class, () -> compiled.replaceCell(0, 0, 0, 0));
	}

	@Test
	void sparseForkMutationCannotEscapeIntoTheSharedSnapshotBacking() {
		WorldSnapshot.BlockEntry air = new WorldSnapshot.BlockEntry(0, "air", 0.6F, 1.0F, 1.0F, List.of());
		WorldSnapshot.BlockEntry stone = new WorldSnapshot.BlockEntry(
		    1, "stone", 0.6F, 1.0F, 1.0F, List.of(new WorldSnapshot.ShapeBox(0, 0, 0, 1, 1, 1)));
		WorldSnapshot snapshot = new WorldSnapshot(
		    0, 0, 0, 1, 1, 1, WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING, List.of(air, stone), new int[] {0});
		SnapshotView compiled = SnapshotView.compile(snapshot);

		SnapshotView successor = compiled.fork();
		successor.replaceCell(0, 0, 0, 1);

		assertEquals(1, successor.paletteIndexAt(0, 0, 0));
		assertEquals(0, compiled.paletteIndexAt(0, 0, 0));
		assertEquals(0, snapshot.paletteIndexAt(0, 0, 0));
	}
}
