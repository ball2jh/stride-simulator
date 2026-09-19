package com.nettarion.stride.simulator.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.CompleteWorldView;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.Suffocation;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;

import org.junit.jupiter.api.Test;

/**
 * {@code InsideBlockEffectType.CLEAR_FREEZE} on the client's copy: fire and
 * lava collect it and {@code applyAndClear} runs on both sides, so the
 * client's own frozen count clears in the same tick as the server's.
 */
final class ClientTickClearFreezeTest {
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);

	@Test
	void fireContactClearsTheClientsFrozenTicks() {
		PlayerState state = standingAt(0.5, 0.0, 0.5);
		state.ticksFrozen = 37;

		new ClientTick().tick(state, IDLE, fireWorld());

		assertEquals(0, state.ticksFrozen, "BaseFireBlock.entityInside applies CLEAR_FREEZE on the client too");
		assertFalse(state.isInPowderSnow);
	}

	@Test
	void lavaClearsTheClientsFrozenTicks() {
		PlayerState frozen = standingAt(0.5, 0.0, 0.5);
		frozen.ticksFrozen = 37;
		PlayerState thawed = standingAt(0.5, 0.0, 0.5);
		thawed.ticksFrozen = 0;

		new ClientTick().tick(frozen, IDLE, new LavaWorld());
		new ClientTick().tick(thawed, IDLE, new LavaWorld());

		assertEquals(0, frozen.ticksFrozen, "LavaFluid.entityInside applies CLEAR_FREEZE on the client too");
		assertEquals(0, thawed.ticksFrozen);
		assertTrue(frozen.lavaHeight > 0.0, "the fluid travel itself is unchanged");
	}

	private static PlayerState standingAt(final double x, final double y, final double z) {
		PlayerState state = new PlayerState();
		state.placeAt(x, y, z);
		state.onGround = true;
		return state;
	}

	/** A stone floor under y=0 with fire in the cell above the origin. */
	private static SnapshotView fireWorld() {
		BlockEntry air = BlockEntry.builder(0, "minecraft:air").suffocation(Suffocation.NO).build();
		BlockEntry stone = BlockEntry.builder(1, "minecraft:stone").suffocation(Suffocation.YES).fullCube().build();
		BlockEntry fire =
		    BlockEntry.builder(2, "minecraft:fire").contact(WorldView.Contact.FIRE).suffocation(Suffocation.NO).build();
		WorldSnapshot.Builder world =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, -4, -6, -4, 9, 24, 9).palette(air, stone, fire);
		for (int z = -4; z <= 4; z++) {
			for (int x = -4; x <= 4; x++) {
				world.set(x, -1, z, 1);
			}
		}
		world.set(0, 0, 0, 2);
		return SnapshotView.compile(world.build());
	}

	/** Still source lava in every cell of the y=0 layer, over nothing. */
	private static final class LavaWorld implements CompleteWorldView {
		@Override
		public boolean hasFluids() {
			return true;
		}

		@Override
		public void fluidAt(final int x, final int y, final int z, final FluidSample target) {
			if (y == 0) {
				target.set(FluidSample.Kind.LAVA, 8.0 / 9.0, 0.0, 0.0, 0.0, true);
			} else {
				target.clear();
			}
		}

		@Override
		public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final CollisionBuffer target) {
			target.clear();
		}
	}
}
