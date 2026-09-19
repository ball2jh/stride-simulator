package com.nettarion.stride.simulator.tick;

import static com.nettarion.stride.simulator.tick.RawBits.assertRaw;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.world.CompleteWorldView;
import com.nettarion.stride.simulator.world.SupportCell;

import org.junit.jupiter.api.Test;

final class ClientTickClimbingTest {
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);

	@Test
	void climbableClampsHorizontalAndSuppressesPlayerSlide() {
		PlayerState state = stateAt(0.5, 5.0, 0.5);
		state.deltaMovementX = 0.4;
		state.deltaMovementY = -0.4;
		state.deltaMovementZ = -0.4;
		PlayerInput shift = new PlayerInput(false, false, false, false, false, false, true, 0.0F, 0.0F);

		new ClientTick().tick(state, shift, new ClimbWorld(true, false, true));

		assertRaw(0.5 + (double) 0.15F, state.x);
		assertRaw(5.0, state.y);
		assertRaw(0.5 - (double) 0.15F, state.z);
		assertRaw((double) 0.15F * 0.91F, state.deltaMovementX);
		assertRaw((-0.08) * 0.98F, state.deltaMovementY);
		assertRaw((double) -0.15F * 0.91F, state.deltaMovementZ);
		assertRaw(0.0, state.fallDistance);
	}

	@Test
	void creativeFlightIgnoresTheClimbable() {
		// Player.onClimbable is false while abilities.flying, so neither the
		// climb clamp nor the fall reset applies to a flying player in a ladder.
		PlayerState state = stateAt(0.5, 5.0, 0.5);
		state.flying = true;
		state.mayfly = true;
		state.deltaMovementX = 0.4;
		state.fallDistance = 2.0;

		new ClientTick().tick(state, IDLE, new ClimbWorld(true, false));

		assertTrue(state.deltaMovementX > 0.15, "no climb clamp while flying: " + state.deltaMovementX);
		assertTrue(state.x > 0.5 + 0.15, "the flight kept its speed through the ladder cell");
	}

	@Test
	void unclutchedLadderFallRetainsDownwardRequestAndGroundCollisionFlags() {
		PlayerState state = stateAt(0.5, 5.0, 0.5);
		state.deltaMovementY = -0.4;

		new ClientTick().tick(state, IDLE, new ClimbWorld(true, false, true));

		assertRaw(5.0, state.y);
		assertRaw((-0.08) * 0.98F, state.deltaMovementY);
		assertTrue(state.onGround);
		assertTrue(state.verticalCollision);
		assertTrue(state.verticalCollisionBelow);
	}

	@Test
	void forwardCollisionOnLadderCreatesPointTwoAscentBeforeGravity() {
		PlayerState state = stateAt(0.5, 5.0, 0.5);
		PlayerInput forward = new PlayerInput(true, false, false, false, false, false, false, 0.0F, 0.0F);

		new ClientTick().tick(state, forward, new ClimbWorld(true, true));

		assertRaw((0.2 - 0.08) * 0.98F, state.deltaMovementY);
		assertRaw(0.0, state.fallDistance);
	}

	@Test
	void jumpingOnVineAscendsWithoutCollisionShape() {
		PlayerState state = stateAt(0.5, 5.0, 0.5);
		PlayerInput jump = new PlayerInput(false, false, false, false, true, false, false, 0.0F, 0.0F);

		new ClientTick().tick(state, jump, new ClimbWorld(true, false));

		assertRaw((0.2 - 0.08) * 0.98F, state.deltaMovementY);
	}

	@Test
	void ordinaryAirCellRetainsUnclampedFall() {
		PlayerState state = stateAt(0.5, 5.0, 0.5);
		state.deltaMovementY = -0.4;

		new ClientTick().tick(state, IDLE, new ClimbWorld(false, false));

		assertRaw((-0.4 - 0.08) * 0.98F, state.deltaMovementY);
	}

	private static PlayerState stateAt(final double x, final double y, final double z) {
		PlayerState state = new PlayerState();
		state.x = x;
		state.y = y;
		state.z = z;
		state.setBox(AABB.around(x, y, z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));
		state.fallDistance = 3.0;
		return state;
	}

	private static final class ClimbWorld implements CompleteWorldView {
		private final boolean climbable;
		private final boolean wall;
		private final boolean floor;

		private ClimbWorld(final boolean climbable, final boolean wall) {
			this(climbable, wall, false);
		}

		private ClimbWorld(final boolean climbable, final boolean wall, final boolean floor) {
			this.climbable = climbable;
			this.wall = wall;
			this.floor = floor;
		}

		@Override
		public boolean onClimbableAt(final int x, final int y, final int z, final boolean fallFlying) {
			return this.climbable && x == 0 && y == 5 && z == 0;
		}
		@Override
		public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final CollisionBuffer target) {
			target.clear();
			if (this.wall) {
				target.add(0.0, 0.0, 0.8125, 1.0, 20.0, 1.0);
			}
			if (this.floor) {
				target.add(-10.0, 4.0, -10.0, 10.0, 5.0, 10.0);
			}
		}
		@Override
		public void findSupportingBlock(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final double atX, final double atY, final double atZ,
		    final SupportCell out) {
			out.clear();
			if (this.floor && minY < 5.0 && maxY >= 5.0) {
				out.set(Mth.floor(atX), 4, Mth.floor(atZ));
			}
		}
	}
}
