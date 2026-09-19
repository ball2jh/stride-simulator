package com.nettarion.stride.simulator.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.CompleteWorldView;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.SupportCell;
import com.nettarion.stride.simulator.world.WorldSnapshot;

import org.junit.jupiter.api.Test;

final class ClientTickStepAndEdgeTest {
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);
	private static final PlayerInput SNEAK =
	    new PlayerInput(false, false, false, false, false, true, false, 0.0F, 0.0F);

	@Test
	void defaultPlayerStepsOntoBottomSlab() {
		PlayerState state = stateAt(0.5, 0.0, 0.65);
		state.deltaMovementZ = 0.2;

		new ClientTick().tick(state, IDLE, new SlabWorld());

		assertEquals(Double.doubleToRawLongBits(0.5), Double.doubleToRawLongBits(state.y));
		assertEquals(Double.doubleToRawLongBits(0.65 + 0.2), Double.doubleToRawLongBits(state.z));
		assertFalse(state.horizontalCollision, "the step candidate preserves full horizontal travel");
	}

	@Test
	void stepUsesFirstImprovingHeightRatherThanGloballyBestHeight() {
		PlayerState state = stateAt(0.5, 0.0, 0.5);
		state.deltaMovementZ = 1.0;

		new ClientTick().tick(state, IDLE, new TwoHeightStepWorld());

		assertEquals(Double.doubleToRawLongBits(0.25), Double.doubleToRawLongBits(state.y),
		    "the first 0.25-high candidate wins before the 0.5 candidate is tried");
		double firstImprovementCenterZ = 1.3 - (double) PlayerState.Pose.STANDING.width / 2.0;
		assertEquals(Double.doubleToRawLongBits(firstImprovementCenterZ), Double.doubleToRawLongBits(state.z),
		    "the first candidate improves travel to 0.5; the later candidate could travel 1.0");
		assertTrue(state.horizontalCollision, "the selected first improvement still collides with the second obstacle");
	}

	@Test
	void landingStepUsesGroundedOriginAndSubtractsFallToGround() {
		PlayerState state = stateAt(0.5, 0.1, 0.5);
		state.onGround = false;
		state.deltaMovementY = -0.2;
		state.deltaMovementZ = 1.0;

		new ClientTick().tick(state, IDLE, new TwoHeightStepWorld());

		assertEquals(Double.doubleToRawLongBits(0.25), Double.doubleToRawLongBits(state.y),
		    "0.25 from the grounded trial origin becomes net +0.15 from the falling origin");
		double firstImprovementCenterZ = 1.3 - (double) PlayerState.Pose.STANDING.width / 2.0;
		assertEquals(Double.doubleToRawLongBits(firstImprovementCenterZ), Double.doubleToRawLongBits(state.z));
		assertTrue(state.verticalCollisionBelow,
		    "the ordinary downward clip remains the support latch even though the step moves up");
		assertTrue(state.horizontalCollision);
	}

	/**
	 * {@code Entity.collide}: when the player is not landing this tick but the
	 * ground latch is still set, the step query box is extended downward by
	 * widened float {@code -1.0E-5F}. That is what lets a block whose top is
	 * exactly at foot level be collected at all, since block broadphase is
	 * strict at the query's minimum Y. Its top then contributes a zero-height
	 * candidate, which is only tried because the ordinary resolved Y (full
	 * gravity over the gap) is not zero. The trial from the un-lowered box
	 * clears the far block's side, so the player crosses the gap at foot level
	 * instead of dropping into it.
	 */
	@Test
	void alreadyGroundedStepQueryExtendsBelowTheFeetByWidenedFloat() {
		PlayerState state = stateAt(0.5, 0.0, 1.5);
		state.deltaMovementY = -0.08 * 0.98;
		state.deltaMovementZ = 0.3;

		new ClientTick().tick(state, IDLE, SnapshotView.compile(oneBlockGapWorld()));

		assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(state.y),
		    "the zero-height candidate keeps the player at foot level over the gap");
		assertEquals(Double.doubleToRawLongBits(1.8), Double.doubleToRawLongBits(state.z),
		    "full requested travel, so the box now overlaps the far block");
		assertFalse(state.horizontalCollision);
		assertTrue(state.verticalCollision, "requested gravity became zero");
		assertTrue(state.verticalCollisionBelow);
		assertTrue(state.onGround, "the ground latch survives the crossing");
	}

	/**
	 * Stone floor with its top at y=0 everywhere except a one-block gap at z=1.
	 * A standing box centered at z=1.5 lies entirely over the gap.
	 */
	static WorldSnapshot oneBlockGapWorld() {
		BlockEntry air = BlockEntry.builder(0, "test:air").build();
		BlockEntry stone = BlockEntry.builder(1, "test:stone").fullCube().build();
		WorldSnapshot.Builder world =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, -4, -4, -4, 9, 10, 12).palette(air, stone);
		for (int z = -4; z <= 7; z++) {
			if (z == 1) {
				continue;
			}
			for (int x = -4; x <= 4; x++) {
				world.set(x, -1, z, 1);
			}
		}
		return world.build();
	}

	@Test
	void exactClipCanOpenStepBelowThePublicHorizontalCollisionEpsilon() {
		double playerHalfWidth = (double) PlayerState.Pose.STANDING.width / 2.0;
		double startX = 1.0 - playerHalfWidth;
		double requestX = Math.nextDown((double) Mth.EPSILON);
		PlayerState state = stateAt(startX, 0.0, 0.5);

		Move.resolve(state, requestX, 0.0, 0.0, false, new SideSlabWorld(), new Scratch());

		assertEquals(Double.doubleToRawLongBits(startX + requestX), Double.doubleToRawLongBits(state.x),
		    "exact ordinary clipping opens the step, which restores full X travel");
		assertEquals(Double.doubleToRawLongBits(0.5), Double.doubleToRawLongBits(state.y));
		assertFalse(state.horizontalCollision, "final request/result difference is below strict widened-float 1e-5");
		assertTrue(state.verticalCollision, "the step changes requested numerical Y zero to 0.5");
		assertFalse(state.verticalCollisionBelow);
		assertFalse(state.onGround);
	}

	@Test
	void currentTickSneakBacksOffAtLedgeWithoutCollision() {
		PlayerState state = stateAt(0.5, 0.0, 0.95);
		state.deltaMovementZ = 0.4;
		assertEquals(0, state.inputKeyPresses,
		    "previous input is deliberately not sneak; the current action must drive edge safety");

		new ClientTick().tick(state, SNEAK, new LedgeWorld());

		assertTrue(state.z < 1.35, "untrimmed movement would advance the center to 1.35");
		assertEquals(Double.doubleToRawLongBits(1.25), Double.doubleToRawLongBits(state.z));
		assertFalse(state.horizontalCollision, "edge back-off changes the request before collision");
		assertTrue(state.deltaMovementZ > 0.0, "edge back-off does not restitute or zero velocity");
	}

	private static PlayerState stateAt(final double x, final double y, final double z) {
		PlayerState state = new PlayerState();
		state.x = x;
		state.y = y;
		state.z = z;
		state.setBox(AABB.around(x, y, z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));
		state.onGround = true;
		return state;
	}

	private abstract static class BoxWorld implements CompleteWorldView {
		@Override
		public final void collectCollisionBoxes(final double minX, final double minY, final double minZ,
		    final double maxX, final double maxY, final double maxZ, final CollisionBuffer target) {
			target.clear();
			collect(minX, minY, minZ, maxX, maxY, maxZ, target);
		}

		abstract void collect(
		    double minX, double minY, double minZ, double maxX, double maxY, double maxZ, CollisionBuffer target);

		final void addIfIntersecting(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final CollisionBuffer target, final double shapeMinX,
		    final double shapeMinY, final double shapeMinZ, final double shapeMaxX, final double shapeMaxY,
		    final double shapeMaxZ) {
			if (shapeMaxX > minX && shapeMinX < maxX && shapeMaxY > minY && shapeMinY < maxY && shapeMaxZ > minZ
			    && shapeMinZ < maxZ) {
				target.add(shapeMinX, shapeMinY, shapeMinZ, shapeMaxX, shapeMaxY, shapeMaxZ);
			}
		}
	}

	private static final class SlabWorld extends BoxWorld {
		@Override
		void collect(final double minX, final double minY, final double minZ, final double maxX, final double maxY,
		    final double maxZ, final CollisionBuffer target) {
			addIfIntersecting(minX, minY, minZ, maxX, maxY, maxZ, target, -10.0, -1.0, -10.0, 10.0, 0.0, 10.0);
			addIfIntersecting(minX, minY, minZ, maxX, maxY, maxZ, target, 0.0, 0.0, 1.0, 1.0, 0.5, 2.0);
		}
	}

	private static final class SideSlabWorld extends BoxWorld {
		@Override
		void collect(final double minX, final double minY, final double minZ, final double maxX, final double maxY,
		    final double maxZ, final CollisionBuffer target) {
			addIfIntersecting(minX, minY, minZ, maxX, maxY, maxZ, target, 1.0, 0.0, 0.0, 2.0, 0.5, 1.0);
		}
	}

	/**
	 * Ordinary travel stops after 0.1 at the first obstacle. Stepping 0.25
	 * clears it and travels 0.5 before the second obstacle; stepping 0.5 would
	 * clear both and travel the full requested 1.0. Vanilla deliberately returns
	 * the first strict improvement, so this geometry distinguishes that rule
	 * from a global-best selector.
	 */
	private static final class TwoHeightStepWorld extends BoxWorld {
		@Override
		void collect(final double minX, final double minY, final double minZ, final double maxX, final double maxY,
		    final double maxZ, final CollisionBuffer target) {
			addIfIntersecting(minX, minY, minZ, maxX, maxY, maxZ, target, -10.0, -1.0, -10.0, 10.0, 0.0, 10.0);
			addIfIntersecting(minX, minY, minZ, maxX, maxY, maxZ, target, 0.0, 0.0, 0.9, 1.0, 0.25, 1.1);
			addIfIntersecting(minX, minY, minZ, maxX, maxY, maxZ, target, 0.0, 0.0, 1.3, 1.0, 0.5, 1.5);
		}

		@Override
		public void findSupportingBlock(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final double atX, final double atY, final double atZ,
		    final SupportCell out) {
			out.clear();
			if (minY < 0.25 && maxY >= 0.25 && minZ < 1.1 && maxZ > 0.9) {
				out.set(0, 0, 0);
			} else if (minY < 0.0 && maxY >= 0.0) {
				out.set(Mth.floor(atX), -1, Mth.floor(atZ));
			}
		}
	}

	private static final class LedgeWorld extends BoxWorld {
		@Override
		void collect(final double minX, final double minY, final double minZ, final double maxX, final double maxY,
		    final double maxZ, final CollisionBuffer target) {
			addIfIntersecting(minX, minY, minZ, maxX, maxY, maxZ, target, -10.0, -1.0, -10.0, 10.0, 0.0, 1.0);
		}
	}
}
