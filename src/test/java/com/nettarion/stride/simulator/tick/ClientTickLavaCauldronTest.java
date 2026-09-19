package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.Suffocation;

class ClientTickLavaCauldronTest {
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);

	@Test
	void contactWithLavaContentsClearsFrozenTicks() {
		PlayerState state = stateAt(0.3);
		state.ticksFrozen = 37;
		state.canFreeze = false;

		new ClientTick().tick(state, IDLE, lavaCauldronWorld());

		assertEquals(0, state.ticksFrozen, "CLEAR_FREEZE applies even when the player cannot accumulate FREEZE");
	}

	@Test
	void centerOpeningAboveLavaContentsDoesNotClearFrozenTicks() {
		PlayerState state = stateAt(0.95);
		state.mayfly = true;
		state.flying = true;
		state.flyingSpeed = 0.05F;
		state.ticksFrozen = 37;

		new ClientTick().tick(state, IDLE, lavaCauldronWorld());

		assertEquals(37, state.ticksFrozen, "the entity-inside shape stops at 15/16 in the cauldron's center");
	}

	@Test
	void freezeThenClearInOneCollectorStepEndsCleared() {
		PlayerState state = stateAt(0.3);
		state.x = 0.0;
		state.setBox(
		    AABB.around(state.x, state.y, state.z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));
		state.ticksFrozen = 19;
		state.canFreeze = true;

		new ClientTick().tick(state, IDLE, powderAndCauldronWorld());

		assertTrue(state.isInPowderSnow);
		assertEquals(0, state.ticksFrozen, "InsideBlockEffectType order is FREEZE followed by CLEAR_FREEZE");
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

	private static SnapshotView lavaCauldronWorld() {
		return worldWithCells(false);
	}

	private static SnapshotView powderAndCauldronWorld() {
		return worldWithCells(true);
	}

	private static SnapshotView worldWithCells(final boolean powderBesideCauldron) {
		BlockEntry air = new BlockEntry(0, "minecraft:air", 0.6F, 1.0F, 1.0F, List.of());
		BlockEntry lavaCauldron = new BlockEntry(1, "minecraft:lava_cauldron", 0.6F, 1.0F,
		    1.0F, false, false, WorldView.BubbleColumnMode.NONE, false, WorldView.CollisionBehavior.ORDINARY,
		    Suffocation.NO, WorldView.InsideEffect.LAVA_CAULDRON, 0.0F, false, WorldView.StepOn.NONE,
		    false, WorldView.Climbability.NONE, List.of());
		BlockEntry powder = new BlockEntry(2, "minecraft:powder_snow", 0.6F, 1.0F, 1.0F,
		    false, false, WorldView.BubbleColumnMode.NONE, false, WorldView.CollisionBehavior.POWDER_SNOW_NO_BOOTS,
		    Suffocation.NO, WorldView.InsideEffect.NONE, 0.0F, false, WorldView.StepOn.NONE, false,
		    WorldView.Climbability.NONE, List.of());
		int size = 5;
		int[] cells = new int[size * size * size];
		cells[index(2, 2, 2, size)] = 1;
		if (powderBesideCauldron) {
			cells[index(1, 2, 2, size)] = 2;
		}
		return new SnapshotView(new WorldSnapshot(-2, -2, -2, size, size, size,
		    OutsidePolicy.REFUSING, List.of(air, lavaCauldron, powder), cells));
	}

	private static int index(final int x, final int y, final int z, final int size) {
		return (y * size + z) * size + x;
	}
}
