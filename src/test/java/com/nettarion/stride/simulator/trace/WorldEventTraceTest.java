package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.world.WorldSnapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

class WorldEventTraceTest {
	@Test
	void roundTripPreservesSameTickSourceOrder() throws IOException {
		WorldSnapshot.FluidEntry flowingWater = new WorldSnapshot.FluidEntry(
		    12, "minecraft:flowing_water", WorldSnapshot.FluidKind.WATER, 0.625, 0.25, 0.0, -0.5, false);
		WorldSnapshot.FluidEntry neighbourDependentWater = new WorldSnapshot.FluidEntry(
		    12, "minecraft:flowing_water", WorldSnapshot.FluidKind.WATER, 0.375, -0.25, 0.0, 0.5, false);
		WorldEventTrace original = new WorldEventTrace(List.of(new WorldEventTrace.CellStateEvent(8, 1, 2, 3, 100, 200),
		                                                   new WorldEventTrace.CellStateEvent(8, -4, 5, 6, 101, 201),
		                                                   new WorldEventTrace.CellStateEvent(27, 1, 2, 3, 102, 202)),
		    List.of(new WorldEventTrace.FluidCellEvent(8, 1, 2, 3, flowingWater),
		        new WorldEventTrace.FluidCellEvent(8, 2, 2, 3, neighbourDependentWater)));
		StringWriter writer = new StringWriter();

		WorldEventTrace.write(original, writer);
		WorldEventTrace decoded = WorldEventTrace.read(new BufferedReader(new StringReader(writer.toString())));

		assertEquals(original, decoded);
	}

	@Test
	void v1EventsExplicitlyPreserveTheExistingFluid() throws IOException {
		String legacy = "#stride-world-events\t1\n"
		    + "tick\tx\ty\tz\tstate_id\n"
		    + "4\t1\t2\t3\t99\n";

		WorldEventTrace decoded = WorldEventTrace.read(new BufferedReader(new StringReader(legacy)));

		assertEquals(WorldEventTrace.CellStateEvent.KEEP_EXISTING_FLUID, decoded.events().getFirst().fluidStateId());
	}

	@Test
	void v2BlockAndFluidIdsRemainReadable() throws IOException {
		String legacy = "#stride-world-events\t2\n"
		    + "tick\tx\ty\tz\tstate_id\tfluid_state_id\n"
		    + "4\t1\t2\t3\t99\t17\n";

		WorldEventTrace decoded = WorldEventTrace.read(new BufferedReader(new StringReader(legacy)));

		assertEquals(17, decoded.events().getFirst().fluidStateId());
		assertTrue(decoded.fluidEvents().isEmpty());
	}

	@Test
	void rejectsDecreasingTicksAndMalformedColumns() {
		assertThrows(IllegalArgumentException.class,
		    ()
		        -> new WorldEventTrace(List.of(new WorldEventTrace.CellStateEvent(2, 0, 0, 0, 1),
		            new WorldEventTrace.CellStateEvent(1, 0, 0, 0, 2))));
		assertThrows(IllegalArgumentException.class,
		    ()
		        -> new WorldEventTrace(List.of(),
		            List.of(new WorldEventTrace.FluidCellEvent(2, 0, 0, 0, WorldSnapshot.FluidEntry.EMPTY),
		                new WorldEventTrace.FluidCellEvent(1, 0, 0, 0, WorldSnapshot.FluidEntry.EMPTY))));
		String malformed = "#stride-world-events\t1\n"
		    + "tick\tx\ty\tz\tstate_id\n"
		    + "0\t1\t2\t3\n";
		assertThrows(IOException.class, () -> WorldEventTrace.read(new BufferedReader(new StringReader(malformed))));
	}
}
