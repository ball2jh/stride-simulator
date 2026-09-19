package com.nettarion.stride.simulator.world;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.tick.Scratch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * An edit invalidates the section proofs it can reach, and no others .
 *
 * <p>Every test here carries the same negative control — the *global*
 * {@code collisionVersion} is asserted to have changed. That is the behaviour
 * being replaced, so a passing locality assertion cannot be explained by the
 * edit having quietly done nothing.
 */
class SectionVersionStampTest {
	private static final int AIR = 0;
	private static final int STONE = 1;

	private static final int SIZE = 48;
	/** Three sections away from the player, in every axis. */
	private static final int FAR = 40;

	@Test
	void aDistantEditLeavesALocalSpanUntouched() {
		SnapshotView world = world();
		long before = world.collisionVersionIn(0, 0, 0, 3, 3, 3);

		world.replaceCell(FAR, FAR, FAR, STONE);

		assertNotEquals(
		    0L, world.collisionVersion(), "control: the edit really happened and the global counter saw it");
		assertEquals(before, world.collisionVersionIn(0, 0, 0, 3, 3, 3),
		    "a local span must not see an edit three sections away");
		assertNotEquals(
		    before, world.collisionVersionIn(FAR, FAR, FAR, FAR, FAR, FAR), "the edited section itself must see it");
	}

	@Test
	void anEditInsideTheSpanIsSeen() {
		SnapshotView world = world();
		long before = world.collisionVersionIn(0, 0, 0, 3, 3, 3);

		world.replaceCell(2, 2, 2, STONE);

		assertNotEquals(before, world.collisionVersionIn(0, 0, 0, 3, 3, 3));
	}

	@Test
	void aSpanCrossingIntoTheEditedSectionIsSeen() {
		SnapshotView world = world();
		world.replaceCell(FAR, 1, 1, STONE);

		// Spanning from an untouched section into the edited one must report the
		// edit; taking the maximum over the span is what makes that true.
		assertNotEquals(0L, world.collisionVersionIn(0, 1, 1, FAR, 1, 1));
		assertEquals(0L, world.collisionVersionIn(0, 1, 1, 3, 1, 1));
	}

	@Test
	void aDistantEditDoesNotDiscardAPoseFitProof() {
		SnapshotView world = world();
		PlayerState state = standingAt(1.5, 1.0, 1.5);
		state.certifyPoseFit(world, PlayerState.Pose.STANDING);
		assertTrue(state.hasPoseFitCertificate(world, PlayerState.Pose.STANDING));

		world.replaceCell(FAR, FAR, FAR, STONE);

		assertNotEquals(
		    0L, world.collisionVersion(), "control: a global-version certificate would have been discarded here");
		assertTrue(state.hasPoseFitCertificate(world, PlayerState.Pose.STANDING),
		    "validation: a chunk arriving far away must not discard the player's local proof");
	}

	@Test
	void aNearEditStillDiscardsThePoseFitProof() {
		SnapshotView world = world();
		PlayerState state = standingAt(1.5, 1.0, 1.5);
		state.certifyPoseFit(world, PlayerState.Pose.STANDING);
		assertTrue(state.hasPoseFitCertificate(world, PlayerState.Pose.STANDING));

		// Inside the certified box's own span, so the proof is genuinely at risk.
		world.replaceCell(1, 2, 1, STONE);

		assertFalse(state.hasPoseFitCertificate(world, PlayerState.Pose.STANDING));
	}

	@Test
	void aRetainedSpanSurvivesADistantEditAndNotANearOne() {
		SnapshotView world = world();
		Scratch scratch = new Scratch();
		scratch.span.retain(world, 0, 0, 0, 3, 3, 3);
		assertTrue(scratch.span.covers(world, 1, 1, 1, 2, 2, 2));

		world.replaceCell(FAR, FAR, FAR, STONE);
		assertTrue(scratch.span.covers(world, 1, 1, 1, 2, 2, 2),
		    "validation's retained span must not be discarded by a distant chunk edit");

		world.replaceCell(2, 2, 2, STONE);
		assertFalse(scratch.span.covers(world, 1, 1, 1, 2, 2, 2));
	}

	@Test
	void aForkKeepsItsOwnStamps() {
		SnapshotView parent = world();
		SnapshotView child = parent.fork();

		child.replaceCell(2, 2, 2, STONE);

		assertNotEquals(0L, child.collisionVersionIn(0, 0, 0, 3, 3, 3));
		assertEquals(0L, parent.collisionVersionIn(0, 0, 0, 3, 3, 3),
		    "stamps must be per-instance, or a sibling rollout would invalidate this one");
	}

	private static PlayerState standingAt(final double x, final double y, final double z) {
		PlayerState state = new PlayerState();
		state.x = x;
		state.y = y;
		state.z = z;
		state.setBox(AABB.around(x, y, z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));
		return state;
	}

	private static SnapshotView world() {
		return new SnapshotView(new WorldSnapshot(0, 0, 0, SIZE, SIZE, SIZE, WorldSnapshot.OutsideRegion.SEALED,
		    List.of(new WorldSnapshot.BlockEntry(AIR, "air", 0.6F, 1.0F, 1.0F, List.of()),
		        new WorldSnapshot.BlockEntry(
		            STONE, "stone", 0.6F, 1.0F, 1.0F, List.of(new WorldSnapshot.ShapeBox(0, 0, 0, 1, 1, 1)))),
		    new int[SIZE * SIZE * SIZE]));
	}
}
