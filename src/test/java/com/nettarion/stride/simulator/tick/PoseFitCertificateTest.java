package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.world.CompleteWorldView;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.StateDigest;
import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PoseFitCertificateTest {
	@Test
	void freshProofAtTheSamePositionRenewsTheWorldVersion() {
		MutableWorld world = new MutableWorld(false);
		PlayerState state = stateAtOrigin();
		Scratch scratch = new Scratch();
		Clearance.fitPoses(state, world, PlayerState.Pose.STANDING, scratch);
		assertEquals(1, world.collisionQueries);
		world.version++;
		Clearance.fitPoses(state, world, PlayerState.Pose.STANDING, scratch);
		assertEquals(2, world.collisionQueries);
		Clearance.fitPoses(state, world, PlayerState.Pose.STANDING, scratch);
		assertEquals(2, world.collisionQueries, "the renewed proof must avoid another collision query");
	}

	@Test
	void renewedShorterProofCannotReviveAnOldStandingProof() {
		MutableWorld world = new MutableWorld(false);
		PlayerState state = stateAtOrigin();
		Scratch scratch = new Scratch();
		Clearance.fitPoses(state, world, PlayerState.Pose.STANDING, scratch);
		world.version++;
		world.ceiling = 1.6;
		Clearance.fitPoses(state, world, PlayerState.Pose.CROUCHING, scratch);
		assertTrue(state.hasPoseFitCertificate(world, PlayerState.Pose.CROUCHING));
		assertFalse(state.hasPoseFitCertificate(world, PlayerState.Pose.STANDING));
		assertEquals(0, Clearance.fitPoses(state, world, PlayerState.Pose.STANDING, scratch) & Clearance.FIT_STANDING);
	}

	@Test
	void aPositionMismatchAvoidsReadingTheWorldVersionEvenWhenCopied() {
		MutableWorld world = new MutableWorld(false);
		PlayerState state = stateAtOrigin();
		state.certifyPoseFit(world, PlayerState.Pose.STANDING);
		world.versionReads = 0;
		state.x = Math.nextUp(state.x);
		assertFalse(state.hasPoseFitCertificate(world, PlayerState.Pose.STANDING));
		PlayerState copy = state.copy();
		assertFalse(copy.hasPoseFitCertificate(world, PlayerState.Pose.STANDING));
		assertEquals(0, world.versionReads);
	}

	@Test
	void certificateRequiresIdenticalWorldObjectAndCollisionVersion() {
		MutableWorld first = new MutableWorld(false);
		MutableWorld sameGeometryDifferentIdentity = new MutableWorld(false);
		PlayerState state = stateAtOrigin();
		long semanticDigest = StateDigest.state(state);

		state.certifyPoseFit(first, PlayerState.Pose.STANDING);

		assertEquals(
		    semanticDigest, StateDigest.state(state), "optimization metadata must not enter the semantic digest");
		assertTrue(state.hasPoseFitCertificate(first, PlayerState.Pose.STANDING));
		assertTrue(state.hasPoseFitCertificate(first, PlayerState.Pose.CROUCHING),
		    "a taller proof covers a same-width shorter pose");
		assertFalse(state.hasPoseFitCertificate(sameGeometryDifferentIdentity, PlayerState.Pose.STANDING));

		first.version++;
		assertFalse(state.hasPoseFitCertificate(first, PlayerState.Pose.STANDING));
	}

	@Test
	void forksCarryTheCertificateAndRevalidateItOnUse() {
		MutableWorld world = new MutableWorld(false);
		PlayerState source = stateAtOrigin();
		source.certifyPoseFit(world, PlayerState.Pose.STANDING);
		PlayerState validFork = source.copy();
		assertTrue(validFork.hasPoseFitCertificate(world, PlayerState.Pose.STANDING));

		world.version++;
		PlayerState staleFork = source.copy();
		assertFalse(staleFork.hasPoseFitCertificate(world, PlayerState.Pose.STANDING));
		assertFalse(validFork.hasPoseFitCertificate(world, PlayerState.Pose.STANDING),
		    "a copy made before the edit is as stale as its source");
	}

	@Test
	void aViewWithoutAnIdentityIsNeverCertified() {
		CompleteWorldView anonymous = new MutableWorld(false) {
			@Override
			public long identity() {
				return 0L;
			}
		};
		PlayerState state = stateAtOrigin();
		state.certifyPoseFit(anonymous, PlayerState.Pose.STANDING);
		assertFalse(state.hasPoseFitCertificate(anonymous, PlayerState.Pose.STANDING));
	}

	@Test
	void aCertificateSurvivesTheWorldItNamesBeingCollected() {
		PlayerState state = stateAtOrigin();
		long identity;
		{
			MutableWorld world = new MutableWorld(false);
			identity = world.identity();
			state.certifyPoseFit(world, PlayerState.Pose.STANDING);
		}
		System.gc();
		MutableWorld successor = new MutableWorld(false);
		assertTrue(identity != successor.identity(), "identities never repeat");
		assertFalse(state.hasPoseFitCertificate(successor, PlayerState.Pose.STANDING));
		PlayerState copy = state.copy();
		assertFalse(copy.hasPoseFitCertificate(successor, PlayerState.Pose.STANDING));
	}

	@Test
	void setBoxAndPositionMismatchInvalidateProof() {
		MutableWorld world = new MutableWorld(false);
		PlayerState state = stateAtOrigin();
		state.certifyPoseFit(world, PlayerState.Pose.STANDING);

		state.setBox(state.box());
		assertFalse(state.hasPoseFitCertificate(world, PlayerState.Pose.STANDING));

		state.certifyPoseFit(world, PlayerState.Pose.STANDING);
		state.x = Math.nextUp(state.x);
		assertFalse(state.hasPoseFitCertificate(world, PlayerState.Pose.STANDING));
	}

	@Test
	void overlappingRawRestoreCannotAcquireProofFromMovement() {
		MutableWorld overlapping = new MutableWorld(true);
		PlayerState state = stateAtOrigin();

		new ClientTick().tick(state, PlayerInput.idle(0.0F, 0.0F), overlapping);

		assertFalse(state.hasPoseFitCertificate(overlapping, state.pose));
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
		/** Defined, not derived: no block in this fixture suffocates. */
		@Override
		public boolean suffocatesAt(
		    final int cellX, final int cellZ, final double boundingBoxMinY, final double boundingBoxMaxY) {
			return false;
		}

		private final long identity = com.nettarion.stride.simulator.world.WorldIdentity.next();
		@Override
		public long identity() {
			return this.identity;
		}
		private final boolean overlapping;
		private long version;
		private int versionReads;
		private int collisionQueries;
		private double ceiling = Double.POSITIVE_INFINITY;

		private MutableWorld(final boolean overlapping) {
			this.overlapping = overlapping;
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

		@Override
		public float friction(final int x, final int y, final int z) {
			return 0.6F;
		}
		@Override
		public float speedFactor(final int x, final int y, final int z) {
			return 1.0F;
		}
		@Override
		public float jumpFactor(final int x, final int y, final int z) {
			return 1.0F;
		}
		@Override
		public boolean hasChunkAt(final int x, final int z) {
			return true;
		}
		@Override
		public int minY() {
			return -64;
		}
	}
}
