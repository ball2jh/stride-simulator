package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ClientTickDisplacementTest {
	@Test
	void resolvesAReportedDisplacementWithoutReplacingOwnedVelocity() {
		PlayerState state = new PlayerState();
		state.placeAt(0.5, 0.0, 0.5);
		state.onGround = true;
		state.deltaMovementX = 0.7;
		ClientTick kernel = new ClientTick();

		Move.resolve(state, 0.1, 0.0, 0.0, false, new SnapshotView(flatWorld()), new Scratch());

		assertEquals(0.6, state.x, 0.0, "collision resolution must consume the reported displacement");
		assertEquals(
		    0.7, state.deltaMovementX, 0.0, "the server's own velocity is not the movement-packet displacement");
	}

	private static WorldSnapshot flatWorld() {
		int size = 8;
		int origin = -4;
		int originY = -3;
		int[] cells = new int[size * size * size];
		for (int z = -3; z <= 3; z++) {
			for (int x = -3; x <= 3; x++) {
				int localX = x - origin;
				int localY = -1 - originY;
				int localZ = z - origin;
				cells[(localY * size + localZ) * size + localX] = 1;
			}
		}
		WorldSnapshot.BlockEntry air = new WorldSnapshot.BlockEntry(0, "air", 0.6F, 1.0F, 1.0F, List.of());
		WorldSnapshot.BlockEntry stone = new WorldSnapshot.BlockEntry(
		    1, "stone", 0.6F, 1.0F, 1.0F, List.of(new WorldSnapshot.ShapeBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)));
		return new WorldSnapshot(origin, originY, origin, size, size, size,
		    WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING, List.of(air, stone), cells);
	}
}
