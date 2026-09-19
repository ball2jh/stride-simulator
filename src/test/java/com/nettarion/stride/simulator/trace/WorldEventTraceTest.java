package com.nettarion.stride.simulator.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.world.FluidEntry;
import com.nettarion.stride.simulator.world.FluidKind;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WorldEventTraceTest {
	@Test
	void roundTripPreservesSameTickSourceOrder() throws IOException {
		FluidEntry flowingWater =
		    new FluidEntry(12, "minecraft:flowing_water", FluidKind.WATER, 0.625, 0.25, 0.0, -0.5, false);
		FluidEntry neighborDependentWater =
		    new FluidEntry(12, "minecraft:flowing_water", FluidKind.WATER, 0.375, -0.25, 0.0, 0.5, false);
		WorldEventTrace original = new WorldEventTrace(List.of(new WorldEventTrace.BlockCellEvent(8, 1, 2, 3, 100, 200),
		                                                   new WorldEventTrace.BlockCellEvent(8, -4, 5, 6, 101, 201),
		                                                   new WorldEventTrace.BlockCellEvent(27, 1, 2, 3, 102, 202)),
		    List.of(new WorldEventTrace.FluidCellEvent(8, 1, 2, 3, flowingWater),
		        new WorldEventTrace.FluidCellEvent(8, 2, 2, 3, neighborDependentWater)));
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

		assertEquals(
		    WorldEventTrace.BlockCellEvent.KEEP_EXISTING_FLUID, decoded.blockEvents().getFirst().fluidStateId());
	}

	@Test
	void v2BlockAndFluidIdsRemainReadable() throws IOException {
		String legacy = "#stride-world-events\t2\n"
		    + "tick\tx\ty\tz\tstate_id\tfluid_state_id\n"
		    + "4\t1\t2\t3\t99\t17\n";

		WorldEventTrace decoded = WorldEventTrace.read(new BufferedReader(new StringReader(legacy)));

		assertEquals(17, decoded.blockEvents().getFirst().fluidStateId());
		assertTrue(decoded.fluidEvents().isEmpty());
	}

	@Test
	void rejectsDecreasingTicksAndMalformedColumns() {
		assertThrows(IllegalArgumentException.class,
		    ()
		        -> new WorldEventTrace(List.of(new WorldEventTrace.BlockCellEvent(2, 0, 0, 0, 1),
		            new WorldEventTrace.BlockCellEvent(1, 0, 0, 0, 2))));
		assertThrows(IllegalArgumentException.class,
		    ()
		        -> new WorldEventTrace(List.of(),
		            List.of(new WorldEventTrace.FluidCellEvent(2, 0, 0, 0, FluidEntry.EMPTY),
		                new WorldEventTrace.FluidCellEvent(1, 0, 0, 0, FluidEntry.EMPTY))));
		String malformed = "#stride-world-events\t1\n"
		    + "tick\tx\ty\tz\tstate_id\n"
		    + "0\t1\t2\t3\n";
		assertThrows(IOException.class, () -> WorldEventTrace.read(new BufferedReader(new StringReader(malformed))));
	}
}
