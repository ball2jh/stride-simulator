package com.nettarion.stride.simulator.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.StateDigest;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.world.CompleteWorldView;
import com.nettarion.stride.simulator.world.WorldIdentity;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The pose-fit cache on {@link PlayerState}: when it answers, when it is cleared, and what it is
 * keyed on. The query-count tests guard the caching, not behavior; they are tagged
 * {@code optimization}.
 */
final class PoseFitCacheTest {
	@Tag("optimization")
	@Test
	void aFreshFitAtTheSamePositionRenewsTheWorldVersion() {
		MutableWorld world = new MutableWorld(false);
		PlayerState state = stateAtOrigin();
		Scratch scratch = new Scratch();
		Clearance.fitPoses(state, world, PlayerState.Pose.STANDING, scratch);
		assertEquals(1, world.collisionQueries);
		world.version++;
		Clearance.fitPoses(state, world, PlayerState.Pose.STANDING, scratch);
		assertEquals(2, world.collisionQueries);
		Clearance.fitPoses(state, world, PlayerState.Pose.STANDING, scratch);
		assertEquals(2, world.collisionQueries, "the renewed fit must avoid another collision query");
	}

	@Test
	void aRenewedShorterFitCannotReviveAnOldStandingFit() {
		MutableWorld world = new MutableWorld(false);
		PlayerState state = stateAtOrigin();
		Scratch scratch = new Scratch();
		Clearance.fitPoses(state, world, PlayerState.Pose.STANDING, scratch);
		world.version++;
		world.ceiling = 1.6;
		Clearance.fitPoses(state, world, PlayerState.Pose.CROUCHING, scratch);
		assertTrue(state.hasCachedPoseFit(world, PlayerState.Pose.CROUCHING));
		assertFalse(state.hasCachedPoseFit(world, PlayerState.Pose.STANDING));
		assertEquals(0, Clearance.fitPoses(state, world, PlayerState.Pose.STANDING, scratch) & Clearance.FIT_STANDING);
	}

	@Tag("optimization")
	@Test
	void aPositionMismatchAvoidsReadingTheWorldVersionEvenWhenCopied() {
		MutableWorld world = new MutableWorld(false);
		PlayerState state = stateAtOrigin();
		state.cachePoseFit(world, PlayerState.Pose.STANDING);
		world.versionReads = 0;
		state.x = Math.nextUp(state.x);
		assertFalse(state.hasCachedPoseFit(world, PlayerState.Pose.STANDING));
		PlayerState copy = state.copy();
		assertFalse(copy.hasCachedPoseFit(world, PlayerState.Pose.STANDING));
		assertEquals(0, world.versionReads);
	}

	@Test
	void theCacheRequiresTheSameWorldIdentityAndCollisionVersion() {
		MutableWorld first = new MutableWorld(false);
		MutableWorld sameGeometryDifferentIdentity = new MutableWorld(false);
		PlayerState state = stateAtOrigin();
		long semanticDigest = StateDigest.state(state);

		state.cachePoseFit(first, PlayerState.Pose.STANDING);

		assertEquals(
		    semanticDigest, StateDigest.state(state), "optimization metadata must not enter the semantic digest");
		assertTrue(state.hasCachedPoseFit(first, PlayerState.Pose.STANDING));
		assertTrue(
		    state.hasCachedPoseFit(first, PlayerState.Pose.CROUCHING), "a taller fit covers a same-width shorter pose");
		assertFalse(state.hasCachedPoseFit(sameGeometryDifferentIdentity, PlayerState.Pose.STANDING));

		first.version++;
		assertFalse(state.hasCachedPoseFit(first, PlayerState.Pose.STANDING));
	}

	@Test
	void copiesCarryTheCacheAndRevalidateItOnUse() {
		MutableWorld world = new MutableWorld(false);
		PlayerState source = stateAtOrigin();
		source.cachePoseFit(world, PlayerState.Pose.STANDING);
		PlayerState validFork = source.copy();
		assertTrue(validFork.hasCachedPoseFit(world, PlayerState.Pose.STANDING));

		world.version++;
		PlayerState staleFork = source.copy();
		assertFalse(staleFork.hasCachedPoseFit(world, PlayerState.Pose.STANDING));
		assertFalse(validFork.hasCachedPoseFit(world, PlayerState.Pose.STANDING),
		    "a copy made before the edit is as stale as its source");
	}

	@Test
	void aViewWithoutAnIdentityIsNeverCached() {
		CompleteWorldView anonymous = new MutableWorld(false) {
			@Override
			public long identity() {
				return 0L;
			}
		};
		PlayerState state = stateAtOrigin();
		state.cachePoseFit(anonymous, PlayerState.Pose.STANDING);
		assertFalse(state.hasCachedPoseFit(anonymous, PlayerState.Pose.STANDING));
	}

	@Test
	void aCacheNamesItsWorldByNumberSoASuccessorWorldNeverMatches() {
		// The cache holds the world's identity number, not the object, so a world
		// that is no longer referenced anywhere is neither kept alive nor confused
		// with the next one created.
		PlayerState state = stateAtOrigin();
		long identity;
		{
			MutableWorld world = new MutableWorld(false);
			identity = world.identity();
			state.cachePoseFit(world, PlayerState.Pose.STANDING);
		}
		MutableWorld successor = new MutableWorld(false);
		assertTrue(identity != successor.identity(), "identities never repeat");
		assertFalse(state.hasCachedPoseFit(successor, PlayerState.Pose.STANDING));
		PlayerState copy = state.copy();
		assertFalse(copy.hasCachedPoseFit(successor, PlayerState.Pose.STANDING));
	}

	@Test
	void setBoxAndAPositionMismatchClearTheCache() {
		MutableWorld world = new MutableWorld(false);
		PlayerState state = stateAtOrigin();
		state.cachePoseFit(world, PlayerState.Pose.STANDING);

		state.setBox(state.box());
		assertFalse(state.hasCachedPoseFit(world, PlayerState.Pose.STANDING));

		state.cachePoseFit(world, PlayerState.Pose.STANDING);
		state.x = Math.nextUp(state.x);
		assertFalse(state.hasCachedPoseFit(world, PlayerState.Pose.STANDING));
	}

	@Test
	void anOverlappingStartCannotAcquireAFitFromMovement() {
		MutableWorld overlapping = new MutableWorld(true);
		PlayerState state = stateAtOrigin();

		new ClientTick().tick(state, PlayerInput.idle(0.0F, 0.0F), overlapping);

		assertFalse(state.hasCachedPoseFit(overlapping, state.pose));
	}

	private static PlayerState stateAtOrigin() {
		PlayerState state = new PlayerState();
		state.x = 0.5;
		state.y = 0.0;
		state.z = 0.5;
		state.setBox(
		    AABB.around(state.x, state.y, state.z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));
		state.onGround = true;
		return state;
	}

	private static class MutableWorld implements CompleteWorldView {
		private final long identity = WorldIdentity.next();

		private final boolean overlapping;
		private long version;
		private int versionReads;
		private int collisionQueries;
		private double ceiling = Double.POSITIVE_INFINITY;

		private MutableWorld(final boolean overlapping) {
			this.overlapping = overlapping;
		}

		@Override
		public long identity() {
			return this.identity;
		}

		@Override
		public long collisionVersion() {
			this.versionReads++;
			return this.version;
		}

		@Override
		public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final CollisionBuffer target) {
			this.collisionQueries++;
			target.clear();
			if (maxY > this.ceiling) {
				target.add(0.0, this.ceiling, 0.0, 1.0, this.ceiling + 1.0, 1.0);
			}
			if (this.overlapping && maxX > 0.0 && minX < 1.0 && maxY > 0.0 && minY < 1.0 && maxZ > 0.0 && minZ < 1.0) {
				target.add(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
			}
		}
	}
}
