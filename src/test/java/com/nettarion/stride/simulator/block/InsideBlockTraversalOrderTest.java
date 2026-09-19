package com.nettarion.stride.simulator.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.tick.ClientTick;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.Suffocation;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;
import org.junit.jupiter.api.Test;

/**
 * Inside-block traversal order is observable through the stuck vector, because {@code Entity.makeStuckInBlock}
 * overwrites it and the last visited stuck block wins.
 *
 * <p>{@code Entity.checkInsideBlocks} splits a recorded movement into axis sub-movements ordered by the requested
 * movement's magnitudes ({@code Direction.axisStepOrder}: Y, then X before Z unless |X| &lt; |Z|), each swept
 * with its own origin box, DDA corner ray, and destination box over one shared visited set. A cobweb on the Z arm
 * and a sweet berry bush on the X arm of the same diagonal therefore swap which vector survives when the requested
 * Z grows from equal to one hundredth larger than X. A chest face clips the resolved Z to well under the resolved
 * X in both cases, so the order is provably the requested one and not the resolved one.
 */
final class InsideBlockTraversalOrderTest {
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);

	@Test
	void requestedAxisOrderDecidesWhichStuckVectorSurvives() {
		SnapshotView world = SnapshotView.compile(cobwebOnZArmBushOnXArm());

		PlayerState xThenZ = grounded(0.5, 0.5);
		xThenZ.deltaMovementX = 0.6;
		xThenZ.deltaMovementZ = 0.6;
		new ClientTick().tick(xThenZ, IDLE, world);
		assertStuck(0.25, (double) 0.05F, 0.25, xThenZ,
		    "equal magnitudes walk X first, so the cobweb on the Z arm writes last");

		PlayerState zThenX = grounded(0.5, 0.5);
		zThenX.deltaMovementX = 0.6;
		zThenX.deltaMovementZ = 0.61;
		new ClientTick().tick(zThenX, IDLE, world);
		assertTrue(Math.abs(zThenX.z - 0.5) < Math.abs(zThenX.x - 0.5),
		    "the chest must clip Z below X, or requested and resolved order agree");
		assertStuck((double) 0.8F, 0.75, (double) 0.8F, zThenX,
		    "a larger requested Z walks Z first even though the resolved Z is smaller,"
		        + " so the bush on the X arm writes last");
	}

	@Test
	void stationaryTraversalCoversTheTickStartBoxAndNothingElse() {
		SnapshotView world = SnapshotView.compile(cobwebOnZArmBushOnXArm());

		PlayerState inside = grounded(0.5, 1.5);
		inside.deltaMovementY = (0.0 - 0.08) * (double) 0.98F;
		new ClientTick().tick(inside, IDLE, world);
		assertStuck(0.25, (double) 0.05F, 0.25, inside,
		    "a grounded tick records no segment, so the box's own cells are visited");

		PlayerState beside = grounded(0.5, 2.5);
		beside.deltaMovementY = (0.0 - 0.08) * (double) 0.98F;
		new ClientTick().tick(beside, IDLE, world);
		assertStuck(0.0, 0.0, 0.0, beside, "nothing outside the box is visited");
	}

	private static void assertStuck(
	    final double x, final double y, final double z, final PlayerState state, final String message) {
		assertEquals(Double.doubleToRawLongBits(x), Double.doubleToRawLongBits(state.stuckSpeedMultiplierX),
		    () -> message + " (x=" + state.stuckSpeedMultiplierX + ")");
		assertEquals(Double.doubleToRawLongBits(y), Double.doubleToRawLongBits(state.stuckSpeedMultiplierY),
		    () -> message + " (y=" + state.stuckSpeedMultiplierY + ")");
		assertEquals(Double.doubleToRawLongBits(z), Double.doubleToRawLongBits(state.stuckSpeedMultiplierZ),
		    () -> message + " (z=" + state.stuckSpeedMultiplierZ + ")");
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

	/**
	 * Stone floor, a cobweb at (0,0,1), a sweet berry bush at (1,0,0), and a single chest at (0,1,1) whose near
	 * face at z=1.0625 clips the diagonal's Z.
	 */
	static WorldSnapshot cobwebOnZArmBushOnXArm() {
		BlockEntry air = BlockEntry.builder(0, "test:air").build();
		BlockEntry stone = BlockEntry.builder(1, "test:stone").fullCube().build();
		BlockEntry cobweb = BlockEntry.builder(2, "test:cobweb")
		                        .suffocation(Suffocation.NO)
		                        .insideEffect(WorldView.InsideEffect.COBWEB)
		                        .build();
		BlockEntry bush = BlockEntry.builder(3, "test:bush")
		                      .suffocation(Suffocation.NO)
		                      .insideEffect(WorldView.InsideEffect.SWEET_BERRY_BUSH)
		                      .build();
		BlockEntry chest = BlockEntry.builder(4, "test:chest")
		                       .suffocation(Suffocation.NO)
		                       .boxes(new ShapeBox(0.0625, 0.0, 0.0625, 0.9375, 0.875, 0.9375))
		                       .build();
		WorldSnapshot.Builder world = WorldSnapshot.builder(OutsidePolicy.REFUSING, -4, -4, -4, 9, 10, 12)
		                                  .palette(air, stone, cobweb, bush, chest);
		for (int z = -4; z <= 7; z++) {
			for (int x = -4; x <= 4; x++) {
				world.set(x, -1, z, 1);
			}
		}
		world.set(0, 0, 1, 2);
		world.set(1, 0, 0, 3);
		world.set(0, 1, 1, 4);
		return world.build();
	}
}
