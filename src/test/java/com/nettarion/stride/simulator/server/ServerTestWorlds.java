package com.nettarion.stride.simulator.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nettarion.stride.simulator.MovementPacket;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.Publisher;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.Suffocation;
import com.nettarion.stride.simulator.world.WorldSnapshot;

/**
 * The small worlds and states the server-tick tests share: a 9 by 9 region from y=-6 to y=17 with air at palette
 * index 0 and stone at 1, a third entry when a test declares one, and the floor and wall fillers.
 */
final class ServerTestWorlds {
	/** The palette index of air in every region built here. */
	static final int AIR = 0;

	/** The palette index of stone in every region built here. */
	static final int STONE = 1;

	/** The palette index of the entry a test passes to {@link #region(BlockEntry)}. */
	static final int EXTRA = 2;

	static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);

	static final double HALF_WIDTH = (double) 0.6F / 2.0;

	private ServerTestWorlds() {}

	static BlockEntry air() {
		return BlockEntry.builder(AIR, "minecraft:air").suffocation(Suffocation.NO).build();
	}

	static BlockEntry stone() {
		return BlockEntry.builder(STONE, "minecraft:stone").suffocation(Suffocation.YES).fullCube().build();
	}

	/** The region with air and stone. */
	static WorldSnapshot.Builder region() {
		return WorldSnapshot.builder(OutsidePolicy.REFUSING, -4, -6, -4, 9, 24, 9).palette(air(), stone());
	}

	/** The region with air, stone and {@code extra} at index {@link #EXTRA}. */
	static WorldSnapshot.Builder region(final BlockEntry extra) {
		return WorldSnapshot.builder(OutsidePolicy.REFUSING, -4, -6, -4, 9, 24, 9).palette(air(), stone(), extra);
	}

	/** Fill the whole region's layer at {@code y} with {@code entryId}. */
	static WorldSnapshot.Builder floor(final WorldSnapshot.Builder builder, final int y, final int entryId) {
		for (int z = -4; z <= 4; z++) {
			for (int x = -4; x <= 4; x++) {
				builder.set(x, y, z, entryId);
			}
		}
		return builder;
	}

	/** Fill the layer at y=-1 with {@code entryId}, so its top is at y=0. */
	static WorldSnapshot.Builder floor(final WorldSnapshot.Builder builder, final int entryId) {
		return floor(builder, -1, entryId);
	}

	static SnapshotView view(final WorldSnapshot.Builder builder) {
		return SnapshotView.compile(builder.build());
	}

	/** No blocks at all. */
	static SnapshotView emptyWorld() {
		return view(region());
	}

	/** Stone with its top at y=0 everywhere. */
	static SnapshotView floorWorld() {
		return view(floor(region(), STONE));
	}

	/** Stone floor at y=-1 and a two-high stone wall filling x=0. */
	static SnapshotView wallWorld() {
		WorldSnapshot.Builder world = floor(region(), STONE);
		for (int z = -4; z <= 4; z++) {
			world.set(0, 0, z, STONE);
			world.set(0, 1, z, STONE);
		}
		return view(world);
	}

	static PlayerState airborneAt(final double x, final double y, final double z) {
		PlayerState state = new PlayerState();
		state.placeAt(x, y, z);
		return state;
	}

	/** An established glide toward +x at {@code speed} blocks per tick. */
	static PlayerState gliding(final double x, final double y, final double z, final double speed) {
		PlayerState state = new PlayerState();
		state.pose = PlayerState.Pose.FALL_FLYING;
		state.placeAt(x, y, z);
		state.fallFlying = true;
		state.gliderUsable = true;
		state.fallFlyTicks = 5;
		state.deltaMovementX = speed;
		state.yRot = -90.0F;
		return state;
	}

	/** The packet the client tick would send for {@code after} from a publisher last synced at {@code before}. */
	static MovementPacket packet(final PlayerState before, final PlayerState after) {
		return Publisher.atBoundary(before).publish(after);
	}

	static void assertRaw(final double expected, final double actual, final String message) {
		assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual),
		    () -> message + " expected " + expected + " but was " + actual);
	}
}
