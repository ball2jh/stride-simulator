package com.nettarion.stride.simulator.tick;

import static com.nettarion.stride.simulator.tick.RawBits.assertRaw;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.Suffocation;
import com.nettarion.stride.simulator.world.SupportCell;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;

import java.util.List;

import org.junit.jupiter.api.Test;

final class ClientTickScaffoldingTest {
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);
	private static final PlayerInput SHIFT =
	    new PlayerInput(false, false, false, false, false, true, false, 0.0F, 0.0F);
	private static final PlayerInput JUMP = new PlayerInput(false, false, false, false, true, false, false, 0.0F, 0.0F);

	@Test
	void supportedTopIsSolidOnlyFromAboveWhenNotDescending() {
		SnapshotView world = scaffoldingColumn();
		CollisionBuffer boxes = new CollisionBuffer(4);

		world.collectCollisionBoxes(0.2, 2.8, 0.2, 0.8, 3.2, 0.8, 3.0, false, boxes);
		assertTrue(boxes.size() >= 1);
		assertRaw(2.875, boxes.minY(0));
		assertRaw(3.0, boxes.maxY(0));

		world.collectCollisionBoxes(0.2, 2.8, 0.2, 0.8, 3.2, 0.8, 3.0, true, boxes);
		assertEquals(0, boxes.size());
		assertThrows(UnimplementedMechanicException.class,
		    () -> world.collectCollisionBoxes(0.2, 2.8, 0.2, 0.8, 3.2, 0.8, boxes));
	}

	@Test
	void unstableBottomPlateIsSolidFromInsideButNotFromBelow() {
		SnapshotView world = scaffoldingColumn(WorldView.CollisionBehavior.SCAFFOLDING_UNSTABLE_BOTTOM);
		CollisionBuffer boxes = new CollisionBuffer(4);

		world.collectCollisionBoxes(0.2, 0.0, 0.2, 0.8, 0.2, 0.8, 0.5, true, boxes);
		assertEquals(1, boxes.size());
		assertRaw(0.0, boxes.minY(0));
		assertRaw(0.125, boxes.maxY(0));

		world.collectCollisionBoxes(0.2, 0.0, 0.2, 0.8, 0.2, 0.8, -0.01, true, boxes);
		assertEquals(0, boxes.size());

		world.collectCollisionBoxes(0.2, 0.8, 0.2, 0.8, 1.2, 0.8, 1.0, false, boxes);
		assertTrue(boxes.size() >= 1, "an unstable-bottom state still exposes the stable shape from above");
		assertRaw(0.875, boxes.minY(0));

		SupportCell support = new SupportCell();
		world.findSupportingBlock(
		    0.2, 0.124999, 0.2, 0.8, 0.125, 0.8, 0.5, 0.125, 0.5, 0.125, true, 0.0, false, support);
		assertTrue(support.present);
		assertEquals(0, support.x);
		assertEquals(0, support.y);
		assertEquals(0, support.z);
	}

	@Test
	void standingPlayerRemainsSupportedOnColumnTop() {
		PlayerState state = stateAt(3.0);
		state.onGround = true;
		state.verticalCollision = true;
		state.verticalCollisionBelow = true;
		state.deltaMovementY = -0.08 * 0.98F;

		new ClientTick().tick(state, IDLE, scaffoldingColumn());

		assertRaw(3.0, state.y);
		assertTrue(state.onGround);
		assertTrue(state.verticalCollisionBelow);
	}

	@Test
	void currentShiftDropsThroughTopEvenWhenPreviousInputWasIdle() {
		PlayerState state = stateAt(3.0);
		state.onGround = true;
		state.verticalCollision = true;
		state.verticalCollisionBelow = true;
		state.deltaMovementY = -0.08 * 0.98F;
		assertEquals(0, Byte.toUnsignedInt(state.inputKeyPresses));

		new ClientTick().tick(state, SHIFT, scaffoldingColumn());

		assertTrue(state.y < 3.0, "movement collision must use current shift, not previous inputKeyPresses");
		assertFalse(state.onGround);
	}

	@Test
	void shiftInsideScaffoldingKeepsNegativeClampAndPassesThroughSupport() {
		PlayerState state = stateAt(2.5);
		state.deltaMovementY = -0.4;

		new ClientTick().tick(state, SHIFT, scaffoldingColumn());

		assertRaw(2.5 - (double) 0.15F, state.y);
		assertRaw((-0.15F - 0.08) * 0.98F, state.deltaMovementY);
		assertFalse(state.onGround);
		assertRaw((double) 0.15F, state.fallDistance);
	}

	@Test
	void shiftInsideUnstableBottomScaffoldingKeepsNegativeClamp() {
		PlayerState state = stateAt(0.5);
		state.deltaMovementY = -0.4;

		new ClientTick().tick(state, SHIFT, scaffoldingColumn(WorldView.CollisionBehavior.SCAFFOLDING_UNSTABLE_BOTTOM));

		assertRaw(0.5 - (double) 0.15F, state.y);
		assertRaw((-0.15F - 0.08) * 0.98F, state.deltaMovementY);
		assertFalse(state.onGround);
	}

	@Test
	void jumpInsideScaffoldingUsesClimbAscentImpulse() {
		PlayerState state = stateAt(1.5);

		new ClientTick().tick(state, JUMP, scaffoldingColumn());

		assertRaw((0.2 - 0.08) * 0.98F, state.deltaMovementY);
		assertRaw(0.0, state.fallDistance);
	}

	@Test
	void releasedDescentLandsBackOnSupportedTop() {
		PlayerState state = stateAt(3.45);
		state.deltaMovementY = -0.25;
		ClientTick simulator = new ClientTick();
		SnapshotView world = scaffoldingColumn();

		for (int i = 0; i < 8 && !state.onGround; i++) {
			simulator.tick(state, IDLE, world);
		}

		assertTrue(state.onGround);
		assertRaw(3.0, state.y);
	}

	private static PlayerState stateAt(final double y) {
		PlayerState state = new PlayerState();
		state.x = 0.5;
		state.y = y;
		state.z = 0.5;
		state.setBox(
		    AABB.around(state.x, state.y, state.z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));
		return state;
	}

	private static SnapshotView scaffoldingColumn() {
		return scaffoldingColumn(WorldView.CollisionBehavior.SCAFFOLDING_SUPPORTED);
	}

	private static SnapshotView scaffoldingColumn(final WorldView.CollisionBehavior collisionBehavior) {
		WorldSnapshot.Builder grid = WorldSnapshot.builder(OutsidePolicy.REFUSING, -2, -2, -2, 5, 8, 5)
		                                 .palette(BlockEntry.builder(0, "minecraft:air").build(),
		                                     BlockEntry.builder(1, "minecraft:stone").fullCube().build(),
		                                     BlockEntry.builder(2, "minecraft:scaffolding")
		                                         .fallDistanceResetting(true)
		                                         .collisionBehavior(collisionBehavior)
		                                         // Not a full cube in any context, so isSuffocating's default
		                                         // predicate answers no and moveTowardsClosestSpace never sees
		                                         // this column.
		                                         .suffocation(Suffocation.NO)
		                                         .climbability(WorldView.Climbability.CLIMBABLE)
		                                         .boxes(stableScaffoldingBoxes())
		                                         .build());
		for (int z = -2; z <= 2; z++) {
			for (int x = -2; x <= 2; x++) {
				grid.set(x, -1, z, 1);
			}
		}
		for (int y = 0; y < 3; y++) {
			grid.set(0, y, 0, 2);
		}
		return new SnapshotView(grid.build());
	}

	private static List<ShapeBox> stableScaffoldingBoxes() {
		return List.of(new ShapeBox(0, 0.875, 0, 1, 1, 1), new ShapeBox(0, 0, 0, 0.125, 1, 0.125),
		    new ShapeBox(0.875, 0, 0, 1, 1, 0.125), new ShapeBox(0, 0, 0.875, 0.125, 1, 1),
		    new ShapeBox(0.875, 0, 0.875, 1, 1, 1));
	}
}
