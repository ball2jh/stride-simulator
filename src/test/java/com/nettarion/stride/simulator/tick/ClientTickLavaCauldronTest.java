package com.nettarion.stride.simulator.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.Suffocation;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;

import java.util.List;

import org.junit.jupiter.api.Test;

final class ClientTickLavaCauldronTest {
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
		BlockEntry air = BlockEntry.builder(0, "minecraft:air").build();
		BlockEntry lavaCauldron = BlockEntry.builder(1, "minecraft:lava_cauldron")
		                              .suffocation(Suffocation.NO)
		                              .insideEffect(WorldView.InsideEffect.LAVA_CAULDRON)
		                              .build();
		BlockEntry powder = BlockEntry.builder(2, "minecraft:powder_snow")
		                        .collisionBehavior(WorldView.CollisionBehavior.POWDER_SNOW_NO_BOOTS)
		                        .suffocation(Suffocation.NO)
		                        .build();
		int size = 5;
		int[] cells = new int[size * size * size];
		cells[index(2, 2, 2, size)] = 1;
		if (powderBesideCauldron) {
			cells[index(1, 2, 2, size)] = 2;
		}
		return SnapshotView.compile(WorldSnapshot.owning(
		    -2, -2, -2, size, size, size, OutsidePolicy.REFUSING, List.of(air, lavaCauldron, powder), cells));
	}

	private static int index(final int x, final int y, final int z, final int size) {
		return (y * size + z) * size + x;
	}
}
