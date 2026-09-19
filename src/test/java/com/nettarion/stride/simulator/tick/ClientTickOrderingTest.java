package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.BlockEntry;

/**
 * Tick-ordering witnesses: three places where vanilla reads a latch from the
 * start of the tick even though the same tick rewrites it.
 *
 * <p>Each expectation is derived from the pinned source rather than from the
 * kernel, and the headless twin {@code TickOrderingVanillaTest} in the
 * vanilla-oracle module runs the same fixtures through a real client.
 *
 * <ul>
 * <li>{@code LivingEntity.aiStep} jumps before {@code travel}; travel then reads
 * tick-start {@code onGround} for both the acceleration formula and the
 * post-move drag, so a sprint-jump takeoff on ice accelerates and decays as
 * ground movement while already airborne, on top of the {@code 0.2} sprint
 * boost {@code jumpFromGround} adds along yaw.</li>
 * <li>{@code Entity.move} writes {@code minorHorizontalCollision} after the
 * move; {@code LocalPlayer.shouldStopRunSprinting} reads it on the next tick,
 * before the next move, so the sprint decision lags the collision by one tick
 * and a glancing collision never stops the sprint.</li>
 * <li>{@code Entity.checkSupportingBlock} retries the support search behind
 * this tick's horizontal travel only when the previous {@code onGroundNoBlocks}
 * latch was false.</li>
 * </ul>
 */
class ClientTickOrderingTest {
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);
	private static final PlayerInput SPRINT_JUMP =
	    new PlayerInput(true, false, false, false, true, false, true, 0.0F, 0.0F);
	private static final double GROUNDED_DELTA_Y = (0.0 - 0.08) * (double) 0.98F;

	@Test
	void sprintJumpTakeoffUsesTickStartGroundForAccelerationAndDrag() {
		PlayerState state = grounded(0.5, 0.5);

		new ClientTick().tick(state, SPRINT_JUMP, new SnapshotView(iceFloor()));

		assertTrue(state.sprinting, "the sprint key starts sprinting before the jump");
		assertFalse(state.onGround, "the jump left the ground during this tick");
		assertEquals(10, state.noJumpDelay);
		// getFrictionInfluencedSpeed with tick-start onGround and ice friction.
		float groundSpeed = sprintSpeed() * (0.21600002F / (0.98F * 0.98F * 0.98F));
		double acceleration = (double) state.zza * (double) groundSpeed;
		double sprintBoost = (double) Mth.cos(0.0F) * 0.2;
		double takeoffZ = 0.0 + sprintBoost + acceleration;
		assertRaw(0.5 + takeoffZ, state.z, "the move uses boost plus ground acceleration");
		assertRaw(takeoffZ * (double) (0.98F * 0.91F), state.deltaMovementZ,
		    "post-move drag still multiplies by tick-start block friction");
		assertRaw((double) 0.42F, state.y, "jump strength on a unit jump factor");
		assertRaw(((double) 0.42F - 0.08) * (double) 0.98F, state.deltaMovementY);

		float airSpeed = 0.025999999F;
		double airborneZ = sprintBoost + (double) state.zza * (double) airSpeed;
		assertTrue(
		    Double.doubleToRawLongBits(airborneZ * (double) 0.91F) != Double.doubleToRawLongBits(state.deltaMovementZ),
		    "reading post-jump onGround would select air speed and air drag");
	}

	@Test
	void glancingCollisionKeepsSprintingWhileHeadOnStopsItOneTickLater() {
		SnapshotView wall = new SnapshotView(eastWall());
		PlayerInput glancing = sprintForward(-5.0F);
		PlayerState alongWall = grounded(0.7, 0.5);
		PlayerState firstTick = tick(alongWall, glancing, wall);
		assertTrue(firstTick.horizontalCollision, "the east wall clips the X component");
		assertTrue(firstTick.minorHorizontalCollision, "five degrees is inside the 8 degree band");
		assertTrue(firstTick.sprinting);
		PlayerState secondTick = tick(firstTick, glancing, wall);
		assertTrue(secondTick.sprinting, "shouldStopRunSprinting reads the minor latch and keeps sprinting");

		PlayerInput headOn = sprintForward(-90.0F);
		PlayerState intoWall = grounded(0.7, 0.5);
		PlayerState collided = tick(intoWall, headOn, wall);
		assertTrue(collided.horizontalCollision);
		assertFalse(
		    collided.minorHorizontalCollision, "no resolved horizontal movement, so the collision is not minor");
		assertTrue(collided.sprinting, "the sprint decision precedes the move, so this tick still sprints");
		PlayerState stopped = tick(collided, headOn, wall);
		assertFalse(stopped.sprinting, "the next tick's decision reads the latch and stops the sprint");
	}

	@Test
	void supportSearchRetriesBehindTheMoveOnlyWhenTheNoBlocksLatchWasClear() {
		SnapshotView ledge = new SnapshotView(ledgeEndingAtZOne());
		PlayerState retried = grounded(0.5, 1.1);
		retried.deltaMovementY = GROUNDED_DELTA_Y;
		retried.deltaMovementZ = 0.4;
		new ClientTick().tick(retried, IDLE, ledge);
		assertTrue(retried.onGround, "Y collided under the trailing part of the box");
		assertRaw(1.5, retried.z, "the box now lies entirely past the ledge");
		assertTrue(retried.mainSupportingBlockPosPresent,
		    "the first search under the box is empty; the retry behind the move finds the ledge");
		assertEquals(0, retried.mainSupportingBlockPosX);
		assertEquals(-1, retried.mainSupportingBlockPosY);
		assertEquals(0, retried.mainSupportingBlockPosZ);
		assertFalse(retried.onGroundNoBlocks);

		PlayerState latched = grounded(0.5, 1.1);
		latched.deltaMovementY = GROUNDED_DELTA_Y;
		latched.deltaMovementZ = 0.4;
		latched.mainSupportingBlockPosPresent = false;
		latched.onGroundNoBlocks = true;
		new ClientTick().tick(latched, IDLE, ledge);
		assertTrue(latched.onGround);
		assertRaw(1.5, latched.z);
		assertFalse(
		    latched.mainSupportingBlockPosPresent, "a set latch skips the retry, so the empty first search stands");
		assertTrue(latched.onGroundNoBlocks);
	}

	private static PlayerState tick(final PlayerState state, final PlayerInput action, final WorldView world) {
		PlayerState next = state.copy();
		new ClientTick().tick(next, action, world);
		return next;
	}

	private static PlayerInput sprintForward(final float yaw) {
		return new PlayerInput(true, false, false, false, false, false, true, yaw, 0.0F);
	}

	/**
	 * {@code (float) Attributes.MOVEMENT_SPEED} with the sprint modifier applied.
	 * The player's base is the float literal {@code 0.1F} widened, and the
	 * sprint modifier is {@code 0.3F} widened; using the double {@code 0.1}
	 * lands one float ULP low.
	 */
	private static float sprintSpeed() {
		return (float) ((double) 0.1F * (1.0 + (double) 0.3F));
	}

	private static PlayerState grounded(final double x, final double z) {
		PlayerState state = new PlayerState();
		state.placeAt(x, 0.0, z);
		state.onGround = true;
		state.mainSupportingBlockPosPresent = true;
		state.mainSupportingBlockPosX = Mth.floor(x);
		state.mainSupportingBlockPosY = -1;
		state.mainSupportingBlockPosZ = Mth.floor(z);
		return state;
	}

	private static void assertRaw(final double expected, final double actual) {
		assertRaw(expected, actual, "");
	}

	private static void assertRaw(final double expected, final double actual, final String message) {
		assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual),
		    () -> message + ": expected " + expected + " but was " + actual);
	}

	private static WorldSnapshot.Builder region() {
		return WorldSnapshot.builder(OutsidePolicy.REFUSING, -4, -4, -4, 9, 10, 12);
	}

	/** Ice with its top at y=0 everywhere in the region. */
	static WorldSnapshot iceFloor() {
		BlockEntry air = BlockEntry.builder(0, "test:air").build();
		BlockEntry ice =
		    BlockEntry.builder(1, "test:ice").friction(0.98F).fullCube().build();
		WorldSnapshot.Builder world = region().palette(air, ice);
		for (int z = -4; z <= 7; z++) {
			for (int x = -4; x <= 4; x++) {
				world.set(x, -1, z, 1);
			}
		}
		return world.build();
	}

	/** Stone floor at y=-1 and a two-high stone wall filling x=1. */
	static WorldSnapshot eastWall() {
		BlockEntry air = BlockEntry.builder(0, "test:air").build();
		BlockEntry stone = BlockEntry.builder(1, "test:stone").fullCube().build();
		WorldSnapshot.Builder world = region().palette(air, stone);
		for (int z = -4; z <= 7; z++) {
			for (int x = -4; x <= 4; x++) {
				world.set(x, -1, z, 1);
			}
			world.set(1, 0, z, 1);
			world.set(1, 1, z, 1);
		}
		return world.build();
	}

	/** Stone floor at y=-1 for z at or below 0, so the ledge edge is at z=1.0. */
	static WorldSnapshot ledgeEndingAtZOne() {
		BlockEntry air = BlockEntry.builder(0, "test:air").build();
		BlockEntry stone = BlockEntry.builder(1, "test:stone").fullCube().build();
		WorldSnapshot.Builder world = region().palette(air, stone);
		for (int z = -4; z <= 0; z++) {
			for (int x = -4; x <= 4; x++) {
				world.set(x, -1, z, 1);
			}
		}
		return world.build();
	}
}
