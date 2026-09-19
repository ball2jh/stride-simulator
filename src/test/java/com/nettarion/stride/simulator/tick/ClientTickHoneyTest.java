package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.Suffocation;

/**
 * {@code HoneyBlock.entityInside} on the client: the wall slide of a player
 * falling pressed against a honey block's side, paired against the same
 * column with the body removed so every gate and every write is witnessed
 * on its own.
 */
class ClientTickHoneyTest {
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);

	/** The slide's resting fall: {@code getNewDeltaY(-0.05)}. */
	private static final double SLIDING_DELTA_Y = (-0.05 - 0.08) * (double) 0.98F;

	@Test
	void fastFallAgainstTheSideSlidesAndReducesHorizontalVelocity() {
		PlayerState honey = fallingBesideColumn(-0.5);
		PlayerState control = fallingBesideColumn(-0.5);

		new ClientTick().tick(honey, IDLE, column(true));
		new ClientTick().tick(control, IDLE, column(false));

		double oldDeltaY = control.deltaMovementY / (double) 0.98F + 0.08;
		assertTrue(oldDeltaY < -0.13, "the fall must be fast enough to reduce horizontal speed");
		assertFalse(honey.onGround);
		assertNotEquals(0.0, control.deltaMovementZ);
		assertRaw(control.deltaMovementZ * (-0.05 / oldDeltaY), honey.deltaMovementZ);
		assertRaw(control.deltaMovementX * (-0.05 / oldDeltaY), honey.deltaMovementX);
		assertRaw(SLIDING_DELTA_Y, honey.deltaMovementY);
		assertRaw(0.0, honey.fallDistance);
		assertTrue(control.fallDistance > 0.0);
		assertRaw(control.x, honey.x);
		assertRaw(control.y, honey.y);
		assertRaw(control.z, honey.z);
	}

	@Test
	void moderateFallAgainstTheSideSlidesWithoutTouchingHorizontalVelocity() {
		PlayerState honey = fallingBesideColumn(-0.1);
		PlayerState control = fallingBesideColumn(-0.1);

		new ClientTick().tick(honey, IDLE, column(true));
		new ClientTick().tick(control, IDLE, column(false));

		double oldDeltaY = control.deltaMovementY / (double) 0.98F + 0.08;
		assertTrue(oldDeltaY < -0.08 && !(oldDeltaY < -0.13),
		    "the fall must be inside the slide window but outside the reduction");
		assertRaw(control.deltaMovementX, honey.deltaMovementX);
		assertRaw(control.deltaMovementZ, honey.deltaMovementZ);
		assertRaw(SLIDING_DELTA_Y, honey.deltaMovementY);
		assertRaw(0.0, honey.fallDistance);
	}

	@Test
	void slowFallAgainstTheSideDoesNotSlide() {
		PlayerState honey = fallingBesideColumn(-0.05);
		PlayerState control = fallingBesideColumn(-0.05);

		new ClientTick().tick(honey, IDLE, column(true));
		new ClientTick().tick(control, IDLE, column(false));

		double oldDeltaY = control.deltaMovementY / (double) 0.98F + 0.08;
		assertFalse(oldDeltaY < -0.08);
		assertRaw(control.deltaMovementX, honey.deltaMovementX);
		assertRaw(control.deltaMovementY, honey.deltaMovementY);
		assertRaw(control.deltaMovementZ, honey.deltaMovementZ);
		assertRaw(control.fallDistance, honey.fallDistance);
	}

	@Test
	void aSlidingPlayerKeepsSlidingAtTheSameSpeedEachTick() {
		PlayerState honey = fallingBesideColumn(SLIDING_DELTA_Y);
		PlayerState control = fallingBesideColumn(SLIDING_DELTA_Y);

		new ClientTick().tick(honey, IDLE, column(true));
		new ClientTick().tick(control, IDLE, column(false));

		assertRaw(SLIDING_DELTA_Y, honey.deltaMovementY);
		assertRaw(control.deltaMovementX, honey.deltaMovementX);
		assertRaw(control.deltaMovementZ, honey.deltaMovementZ);
		assertRaw(0.0, honey.fallDistance);
		assertTrue(honey.y < 1.0);
	}

	@Test
	void landingBesideTheColumnDoesNotSlide() {
		PlayerState honey = fallingBesideColumn(-0.5);
		honey.y = 1.3;
		honey.setBox(
		    AABB.around(honey.x, honey.y, honey.z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));

		new ClientTick().tick(honey, IDLE, columnWithLedge());

		assertTrue(honey.onGround);
		assertRaw(1.0, honey.y);
		assertRaw((0.0 - 0.08) * (double) 0.98F, honey.deltaMovementY);
	}

	/**
	 * Falling at {@code deltaMovementY} with the west face of the box on the honey
	 * shape's east face, one sixteenth inside the cell: the position a player
	 * pressed against the wall occupies, and the one the {@code 1.0E-7}
	 * widening of the overlap test exists to admit.
	 */
	private static PlayerState fallingBesideColumn(final double deltaMovementY) {
		PlayerState state = new PlayerState();
		state.x = 0.9375 + PlayerState.Pose.STANDING.width / 2.0F;
		state.y = 1.0;
		state.z = 0.5;
		state.deltaMovementX = 0.02;
		state.deltaMovementY = deltaMovementY;
		state.deltaMovementZ = 0.1;
		state.fallDistance = 2.0;
		state.setBox(
		    AABB.around(state.x, state.y, state.z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));
		return state;
	}

	private static WorldSnapshot.Builder columnGrid(final boolean honey) {
		BlockEntry.Builder wall =
		    BlockEntry.builder(1, honey ? "minecraft:honey_block" : "test:honey_without_inside")
		        .boxes(new ShapeBox(0.0625, 0.0, 0.0625, 0.9375, 0.9375, 0.9375))
		        .speedFactor(0.4F)
		        .jumpFactor(0.5F)
		        .suppressesBounce(true)
		        .landing(honey ? WorldView.Landing.HONEY : WorldView.Landing.ORDINARY)
		        .suffocation(Suffocation.NO);
		if (honey) {
			wall.insideEffect(WorldView.InsideEffect.HONEY);
		}
		WorldSnapshot.Builder grid =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, -3, -3, -3, 7, 7, 7)
		        .palette(BlockEntry.builder(0, "minecraft:air").build(), wall.build(),
		            BlockEntry.builder(2, "minecraft:stone")
		                .fullCube()
		                .suffocation(Suffocation.YES)
		                .build());
		for (int y = -3; y <= 3; y++) {
			grid.set(0, y, 0, 1);
		}
		return grid;
	}

	private static SnapshotView column(final boolean honey) {
		return new SnapshotView(columnGrid(honey).build());
	}

	/** The honey column with a stone ledge at y = 0 under the player's column. */
	private static SnapshotView columnWithLedge() {
		WorldSnapshot.Builder grid = columnGrid(true);
		for (int z = -1; z <= 1; z++) {
			grid.set(1, 0, z, 2);
			grid.set(2, 0, z, 2);
		}
		return new SnapshotView(grid.build());
	}

	private static void assertRaw(final double expected, final double actual) {
		assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
	}
}
