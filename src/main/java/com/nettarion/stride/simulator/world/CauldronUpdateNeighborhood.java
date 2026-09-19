package com.nettarion.stride.simulator.world;

import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import java.util.List;
import java.util.Set;

/**
 * The game knowledge behind {@link SnapshotView#requireCauldronUpdateClosure}:
 * which blocks a cauldron level change can reach, and which of them the
 * simulator can promise to leave unchanged.
 *
 * <p>Vanilla's {@code LayeredCauldronBlock} lowers its level through
 * {@code Level.setBlock}, which sends neighbor updates to the six neighbors and
 * their neighbors, and emits a {@code GameEvent.BLOCK_CHANGE} vibration that a
 * sculk sensor or shrieker anywhere in range may hear. The simulator models
 * neither the updates nor the vibration, so a cauldron change is admitted only
 * where nothing that could react is present.
 */
final class CauldronUpdateNeighborhood {
	/** Blocks that hear a block-change vibration; one anywhere in the palette refuses. */
	private static final Set<String> VIBRATION_LISTENERS =
	    Set.of("minecraft:sculk_sensor", "minecraft:calibrated_sculk_sensor", "minecraft:sculk_shrieker");

	/**
	 * Blocks whose {@code neighborChanged} body is empty in vanilla, so an update
	 * reaching them changes nothing: the three air states, stone, and the four
	 * cauldron states.
	 */
	private static final Set<String> INERT_NEIGHBORS =
	    Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:stone", "minecraft:cauldron",
	        "minecraft:water_cauldron", "minecraft:powder_snow_cauldron", "minecraft:lava_cauldron");

	/** Neighbor updates reach this many cells out along each axis. */
	private static final int UPDATE_REACH = 2;

	private CauldronUpdateNeighborhood() {}

	/**
	 * Refuses unless every block a level change at {@code (x, y, z)} can reach is inert.
	 *
	 * @throws UnimplementedMechanicException with {@link RefusalCause#UNMODELED_WORLD_WRITE} when a
	 *     vibration listener is anywhere in the palette or a reachable neighbor is not inert, and with
	 *     {@link RefusalCause#OUTSIDE_REGION} when a reachable neighbor lies outside the view
	 */
	static void requireInert(final SnapshotView view, final int x, final int y, final int z) {
		List<BlockEntry> palette = view.snapshot().palette();
		for (BlockEntry entry : palette) {
			if (VIBRATION_LISTENERS.contains(entry.name())) {
				throw new UnimplementedMechanicException(RefusalCause.UNMODELED_WORLD_WRITE,
				    "cauldron block-change vibration reaches an unmodeled listener");
			}
		}
		for (int axis = 0; axis < 3; axis++) {
			for (int sign = -1; sign <= 1; sign += 2) {
				for (int distance = 1; distance <= UPDATE_REACH; distance++) {
					int index = view.paletteIndexAt(x + (axis == 0 ? sign * distance : 0),
					    y + (axis == 1 ? sign * distance : 0), z + (axis == 2 ? sign * distance : 0));
					if (index < 0) {
						throw new UnimplementedMechanicException(
						    RefusalCause.OUTSIDE_REGION, "cauldron neighbor updates leave the captured region");
					}
					String name = palette.get(index).name();
					if (!INERT_NEIGHBORS.contains(name)) {
						throw UnimplementedMechanicException.deferred(RefusalCause.UNMODELED_WORLD_WRITE,
						    () -> "cauldron neighbor updates reach an unmodeled block: " + name);
					}
				}
			}
		}
	}
}
