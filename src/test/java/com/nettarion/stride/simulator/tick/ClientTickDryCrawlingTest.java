package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.world.CompleteWorldView;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.world.SupportCell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** Exact ordinary-player dry visual-crawl semantics under sub-crouch clearance. */
class ClientTickDryCrawlingTest {
	private static final PlayerInput FORWARD =
	    new PlayerInput(true, false, false, false, false, false, false, 0.0F, 0.0F);
	private static final PlayerInput SPRINT_FORWARD =
	    new PlayerInput(true, false, false, false, false, false, true, 0.0F, 0.0F);
	private static final PlayerInput SHIFT_FORWARD =
	    new PlayerInput(true, false, false, false, false, true, false, 0.0F, 0.0F);

	@Test
	void dryForcedSwimmingPoseIsVisualCrawlingWithoutSwimmingOrLocalCrouching() {
		PlayerState state = crawlingAt(0.5);

		new ClientTick().tick(state, SPRINT_FORWARD, new CrawlCourseWorld(100.0));

		assertEquals(PlayerState.Pose.SWIMMING, state.pose);
		assertFalse(state.swimming, "dry visual crawling is not Entity swimming");
		assertFalse(state.crouching, "LocalPlayer.crouching requires the 1.5-high crouching pose to fit");
		assertFalse(state.sprinting, "visual crawling blocks dry sprint start");
		assertEquals(Float.floatToRawIntBits(0.29400003F), Float.floatToRawIntBits(state.zza));
		assertRaw((double) PlayerState.Pose.SWIMMING.height, state.boundingBoxMaxY - state.boundingBoxMinY);
	}

	@Test
	void shiftStillFallsBackToSwimmingWhenCrouchingPoseCannotFit() {
		PlayerState state = crawlingAt(0.5);

		new ClientTick().tick(state, SHIFT_FORWARD, new CrawlCourseWorld(100.0));

		assertEquals(PlayerState.Pose.SWIMMING, state.pose);
		assertFalse(state.crouching);
		assertEquals(Float.floatToRawIntBits(0.29400003F), Float.floatToRawIntBits(state.zza));
	}

	@Test
	void exitRestoresStandingAtEndOfTickAndNormalInputSpeedOnFollowingTick() {
		PlayerState state = crawlingAt(0.5);
		CrawlCourseWorld world = new CrawlCourseWorld(1.0);
		ClientTick kernel = new ClientTick();

		int exitTick = -1;
		for (int tick = 0; tick < 80; tick++) {
			kernel.tick(state, FORWARD, world);
			assertFalse(state.swimming);
			if (state.pose == PlayerState.Pose.STANDING) {
				exitTick = tick;
				break;
			}
		}

		if (exitTick < 0) {
			throw new AssertionError("crawl course did not reach its open exit");
		}
		assertEquals(Float.floatToRawIntBits(0.29400003F), Float.floatToRawIntBits(state.zza),
		    "input was sampled while the old SWIMMING pose was still installed");
		assertRaw((double) PlayerState.Pose.STANDING.height, state.boundingBoxMaxY - state.boundingBoxMinY);

		kernel.tick(state, FORWARD, world);
		assertEquals(Float.floatToRawIntBits(0.98F), Float.floatToRawIntBits(state.zza),
		    "visual-crawl slowdown ends one tick after the pose is restored");
	}

	private static PlayerState crawlingAt(final double z) {
		PlayerState state = new PlayerState();
		state.x = 0.5;
		state.y = 0.0;
		state.z = z;
		state.pose = PlayerState.Pose.SWIMMING;
		state.setBox(AABB.around(state.x, state.y, state.z, state.pose.width, state.pose.height));
		state.onGround = true;
		state.verticalCollision = true;
		state.verticalCollisionBelow = true;
		state.deltaMovementY = -0.08 * 0.98F;
		return state;
	}

	private static void assertRaw(final double expected, final double actual) {
		assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
	}

	/** Full-cube floor plus a one-block-high tunnel ending at {@code ceilingEndZ}. */
	private static final class CrawlCourseWorld implements CompleteWorldView {
		/** Defined, not derived: no block in this fixture suffocates. */
		@Override
		public boolean suffocatesAt(
		    final int cellX, final int cellZ, final double boundingBoxMinY, final double boundingBoxMaxY) {
			return false;
		}

		private final double ceilingEndZ;

		private CrawlCourseWorld(final double ceilingEndZ) {
			this.ceilingEndZ = ceilingEndZ;
		}

		@Override
		public long collisionVersion() {
			return 0L;
		}

		@Override
		public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final CollisionBuffer target) {
			target.clear();
			if (intersects(minX, minY, minZ, maxX, maxY, maxZ, -20.0, -1.0, -20.0, 20.0, 0.0, 20.0)) {
				target.add(-20.0, -1.0, -20.0, 20.0, 0.0, 20.0);
			}
			if (intersects(minX, minY, minZ, maxX, maxY, maxZ, -20.0, 1.0, -20.0, 20.0, 2.0, this.ceilingEndZ)) {
				target.add(-20.0, 1.0, -20.0, 20.0, 2.0, this.ceilingEndZ);
			}
		}

		private static boolean intersects(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final double shapeMinX, final double shapeMinY,
		    final double shapeMinZ, final double shapeMaxX, final double shapeMaxY, final double shapeMaxZ) {
			return maxX > shapeMinX && minX < shapeMaxX && maxY > shapeMinY && minY < shapeMaxY && maxZ > shapeMinZ
			    && minZ < shapeMaxZ;
		}

		@Override
		public void findSupportingBlock(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final double atX, final double atY, final double atZ,
		    final SupportCell out) {
			out.clear();
			if (minY < 0.0 && maxY >= 0.0) {
				out.set(Mth.floor(atX), -1, Mth.floor(atZ));
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
