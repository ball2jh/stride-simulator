package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.WorldSnapshot;

import java.util.List;

/** Small values shared by the trace tests: an all-zero state vector, a one-tick trace and a one-cell world. */
final class TraceFixtures {
	/** The Minecraft version every fixture declares. */
	static final String MINECRAFT_VERSION = "26.2";

	private TraceFixtures() {}

	/** A builder with every field assigned zero, false or {@code STANDING}, ready for overrides. */
	static StateVector.Builder zeroStateBuilder() {
		StateVector.Builder builder = StateVector.builder();
		for (StateField field : StateField.ALL) {
			switch (field.kind()) {
				case DOUBLE -> builder.set(field, 0.0);
				case FLOAT -> builder.set(field, 0.0F);
				case BOOLEAN -> builder.set(field, false);
				case INT -> builder.set(field, 0);
				case BYTE_FLAGS -> builder.setFlags(field, (byte) 0);
				case ENUM -> builder.setEnum(field, 0, "STANDING");
			}
		}
		return builder;
	}

	/** The all-zero state vector. */
	static StateVector zeroState() {
		return zeroStateBuilder().build();
	}

	/** A live-game trace of {@code scenario} with one idle tick whose states are both {@code state}. */
	static Trace oneTickTrace(final String scenario, final StateVector state) {
		return new Trace(new Trace.TraceHeader(TraceProducer.LIVE_GAME, MINECRAFT_VERSION, scenario), state,
		    List.of(new Trace.TraceEntry(0, PlayerInput.idle(0.0F, 0.0F), state)));
	}

	/** A live-game trace of {@code scenario} with one idle tick over the all-zero state. */
	static Trace oneTickTrace(final String scenario) {
		return oneTickTrace(scenario, zeroState());
	}

	/** A 12 by 12 stone floor at y = -1 under eight blocks of air, refusing outside its region. */
	static WorldSnapshot flatFloorWorld() {
		WorldSnapshot.Builder world = WorldSnapshot.builder(OutsidePolicy.REFUSING, -4, -2, -4, 12, 8, 12)
		                                  .palette(BlockEntry.builder(0, "minecraft:air").build(),
		                                      BlockEntry.builder(1, "minecraft:stone").fullCube().build());
		for (int x = -4; x < 8; x++) {
			for (int z = -4; z < 8; z++) {
				world.set(x, -1, z, 1);
			}
		}
		return world.build();
	}

	/** A one-cell world of air at the origin that refuses outside its region. */
	static WorldSnapshot oneCellWorld() {
		return WorldSnapshot.builder(OutsidePolicy.REFUSING, 0, 0, 0, 1, 1, 1)
		    .palette(BlockEntry.builder(0, "minecraft:air").build())
		    .build();
	}
}
