package com.nettarion.stride.simulator.tick;

import static com.nettarion.stride.simulator.tick.RawBits.assertRaw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;

import org.junit.jupiter.api.Test;

final class ClientTickPowderSnowTest {
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);
	private static final PlayerInput FORWARD =
	    new PlayerInput(true, false, false, false, false, false, false, 0.0F, 0.0F);

	@Test
	void noBootsPassesThroughAndInstallsDelayedStuckAndFreezeState() {
		PlayerState state = stateAt(0.4);
		state.deltaMovementZ = 0.2;

		new ClientTick().tick(state, IDLE, powderWorld());

		assertFalse(state.onGround);
		assertTrue(state.isInPowderSnow);
		assertFalse(state.wasInPowderSnow);
		assertEquals(1, state.ticksFrozen, "the client-side FREEZE effect advances the contact tick");
		assertRaw((double) 0.9F, state.stuckSpeedMultiplierX);
		assertRaw(1.5, state.stuckSpeedMultiplierY);
		assertRaw((double) 0.9F, state.stuckSpeedMultiplierZ);
		assertRaw(0.0, state.fallDistance);
	}

	@Test
	void nextMoveConsumesStuckMultiplierZerosOldVelocityAndReinstallsOnContact() {
		PlayerState state = stateAt(0.2);
		state.deltaMovementX = 0.1;
		state.deltaMovementY = -0.1;
		state.deltaMovementZ = 0.2;
		state.stuckSpeedMultiplierX = (double) 0.9F;
		state.stuckSpeedMultiplierY = 1.5;
		state.stuckSpeedMultiplierZ = (double) 0.9F;

		new ClientTick().tick(state, IDLE, powderWorld());

		assertRaw(0.0, state.deltaMovementX);
		assertRaw(0.0, state.deltaMovementZ);
		assertRaw((double) 0.9F, state.stuckSpeedMultiplierX);
		assertRaw(1.5, state.stuckSpeedMultiplierY);
		assertRaw((double) 0.9F, state.stuckSpeedMultiplierZ);
	}

	@Test
	void baseTickCarriesPreviousContactAndDryExitDoesNotLocallyThaw() {
		PlayerState state = stateAt(3.0);
		state.isInPowderSnow = true;
		state.ticksFrozen = 17;

		new ClientTick().tick(state, IDLE, powderWorld());

		assertTrue(state.wasInPowderSnow);
		assertFalse(state.isInPowderSnow);
		assertEquals(17, state.ticksFrozen, "client LivingEntity does not run server-only thaw decrement");
	}

	@Test
	void freezeImmunePlayerStillGetsStuckButTicksDoNotAdvance() {
		PlayerState state = stateAt(0.4);
		state.canFreeze = false;
		state.ticksFrozen = 9;

		new ClientTick().tick(state, IDLE, powderWorld());

		assertTrue(state.isInPowderSnow);
		assertEquals(9, state.ticksFrozen);
		assertRaw((double) 0.9F, state.stuckSpeedMultiplierX);
	}

	@Test
	void freezeCapableClientAdvancesFromTheLatestAuthoritativeHeadValue() {
		PlayerState state = stateAt(0.4);
		state.canFreeze = true;
		state.ticksFrozen = 17;
		state.frostSpeedTicks = 11;

		new ClientTick().tick(state, IDLE, powderWorld());

		assertTrue(state.isInPowderSnow);
		assertEquals(18, state.ticksFrozen);
		assertEquals(11, state.frostSpeedTicks);
	}

	@Test
	void sweptExitStillFreezesButDoesNotReinstallFeetOnlyStuckSpeed() {
		PlayerState state = stateAt(0.4);
		state.deltaMovementZ = 0.81;
		state.ticksFrozen = 23;

		new ClientTick().tick(state, IDLE, powderWorld());

		assertTrue(state.boundingBoxMinZ > 1.0, "the final deflated pose must be wholly beyond the powder cell");
		assertTrue(state.isInPowderSnow, "PowderSnowBlock ignores isPrecise for the swept origin callback");
		assertEquals(24, state.ticksFrozen);
		assertRaw(0.0, state.stuckSpeedMultiplierX);
		assertRaw(0.0, state.stuckSpeedMultiplierY);
		assertRaw(0.0, state.stuckSpeedMultiplierZ);
	}

	@Test
	void synchronizedFrostModifierSlowsGroundAccelerationWithoutDerivingFromLocalTicks() {
		PlayerState state = stateAt(3.0);
		state.onGround = true;
		state.verticalCollision = true;
		state.verticalCollisionBelow = true;
		state.ticksFrozen = 140;
		state.frostSpeedTicks = 70;

		new ClientTick().tick(state, FORWARD, powderWorld());

		float expectedSpeed = (float) ((double) 0.1F - (double) (0.05F * Math.min(1.0F, 70 / 140.0F)));
		assertRaw((double) 0.98F * expectedSpeed * (0.6F * 0.91F), state.deltaMovementZ);
	}

	@Test
	void fallDistanceAboveThresholdUsesExactPointNineFShape() {
		SnapshotView world = powderWorld();
		CollisionBuffer collisions = new CollisionBuffer(1);

		world.collectCollisionBoxes(0.2, 0.85, 0.2, 0.8, 1.1, 0.8, 1.1, false, 2.5, collisions);
		assertEquals(0, collisions.size());
		world.collectCollisionBoxes(0.2, 0.85, 0.2, 0.8, 1.1, 0.8, 1.1, false, Math.nextUp(2.5), collisions);
		assertEquals(1, collisions.size());
		assertRaw((double) 0.9F, collisions.maxY(0));
	}

	@Test
	void leatherBootsMakePowderSnowAFullSupportingBlock() {
		PlayerState state = stateAt(1.05);
		state.canWalkOnPowderSnow = true;
		state.deltaMovementY = -0.2;

		new ClientTick().tick(state, IDLE, powderWorld());

		assertTrue(state.onGround);
		assertRaw(1.0, state.y);
	}

	@Test
	void powderWalkerGetsCollisionLiftFromPreviousPowderContact() {
		PlayerState state = stateAt(2.0);
		state.z = 0.7;
		state.placeAt(state.x, state.y, state.z);
		state.deltaMovementZ = 0.4;
		state.isInPowderSnow = true;
		state.canWalkOnPowderSnow = true;

		new ClientTick().tick(state, IDLE, powderWallWorld());

		assertTrue(state.horizontalCollision);
		assertRaw((0.2 - 0.08) * (double) 0.98F, state.deltaMovementY);
	}

	/**
	 * Identical geometry, identical movement, and a tenth of a block of {@code fallDistance} between
	 * opposite outcomes. At or below 2.5 the collision shape is empty, so
	 * {@code getEntityInsideCollisionShape} substitutes the full cube and the identity
	 * short-circuit makes the visit unconditional. Above it the shape is the 0.9-high falling box,
	 * the short-circuit fails, and {@code collidedWithShapeMovingFrom} finds that a box which ended at
	 * 0.95 never reached it, so nothing applies and the cell is not marked visited.
	 *
	 * <p>The two runs are the same movement: both resolve collision against an empty shape, because
	 * {@code move} reads {@code fallDistance} before its own {@code checkFallDamage} adds this tick's
	 * descent and the traversal reads it after. Only the traversal's read crosses the threshold,
	 * which is why the positions are bit-identical and the effects are not.
	 */
	@Test
	void fallingShapeLeavesTheTopOfTheCellUntouchedWhereTheCubeWouldNot() {
		PlayerState above = stateAt(1.5);
		above.deltaMovementY = -0.55;
		above.fallDistance = 2.0;
		above.ticksFrozen = 5;

		new ClientTick().tick(above, IDLE, powderWorld());

		assertTrue(above.y > 0.9 && above.y < 1.0, "the tick must end inside the cell but above the falling shape");
		assertTrue(above.fallDistance > 2.5, "the traversal must read a fall distance that selects the falling shape");
		assertFalse(above.onGround);
		assertFalse(above.isInPowderSnow, "the box never reached the 0.9 shape, so entityInside never ran");
		assertEquals(5, above.ticksFrozen);
		assertRaw(0.0, above.stuckSpeedMultiplierX);
		assertRaw(0.0, above.stuckSpeedMultiplierY);
		assertRaw(0.0, above.stuckSpeedMultiplierZ);

		PlayerState atThreshold = stateAt(1.5);
		atThreshold.deltaMovementY = -0.55;
		atThreshold.fallDistance = 1.9;
		atThreshold.ticksFrozen = 5;

		new ClientTick().tick(atThreshold, IDLE, powderWorld());

		assertRaw(above.y, atThreshold.y);
		assertTrue(atThreshold.isInPowderSnow);
		assertEquals(6, atThreshold.ticksFrozen);
		assertRaw((double) 0.9F, atThreshold.stuckSpeedMultiplierX);
		assertRaw(1.5, atThreshold.stuckSpeedMultiplierY);
		assertRaw((double) 0.9F, atThreshold.stuckSpeedMultiplierZ);
		assertRaw(0.0, atThreshold.fallDistance);
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

	private static SnapshotView powderWorld() {
		WorldSnapshot.Builder grid = WorldSnapshot.builder(OutsidePolicy.REFUSING, -2, -2, -2, 5, 8, 5)
		                                 .palette(BlockEntry.builder(0, "minecraft:air").build(),
		                                     BlockEntry.builder(1, "minecraft:stone").fullCube().build(),
		                                     BlockEntry.builder(2, "minecraft:powder_snow")
		                                         .collisionBehavior(WorldView.CollisionBehavior.POWDER_SNOW_NO_BOOTS)
		                                         .build());
		for (int z = -2; z <= 2; z++) {
			for (int x = -2; x <= 2; x++) {
				grid.set(x, -1, z, 1);
			}
		}
		grid.set(0, 0, 0, 2);
		return SnapshotView.compile(grid.build());
	}

	private static SnapshotView powderWallWorld() {
		WorldSnapshot.Builder grid = WorldSnapshot.builder(OutsidePolicy.REFUSING, -2, -2, -2, 5, 8, 5)
		                                 .palette(BlockEntry.builder(0, "minecraft:air").build(),
		                                     BlockEntry.builder(1, "minecraft:stone").fullCube().build());
		for (int y = 1; y <= 3; y++) {
			grid.set(0, y, 1, 1);
		}
		return SnapshotView.compile(grid.build());
	}
}
