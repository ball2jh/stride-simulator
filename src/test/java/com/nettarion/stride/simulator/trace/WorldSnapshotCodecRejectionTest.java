package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.world.WorldSnapshot;
import static org.junit.jupiter.api.Assertions.*;

import com.nettarion.stride.simulator.world.WorldSnapshot;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.FluidKind;
import com.nettarion.stride.simulator.world.FluidEntry;

/** Controls preventing malformed world evidence from manufacturing default cells. */
class WorldSnapshotCodecRejectionTest {
	private static String world() throws IOException {
		var air = BlockEntry.builder(0, "minecraft:air").build();
		var water =
		    new FluidEntry(1, "minecraft:water", FluidKind.WATER, 1.0, 0.0, 0.0, 0.0, true);
		var snapshot = new WorldSnapshot(0, 0, 0, 1, 2, 1, OutsidePolicy.SEALED, List.of(air),
		    new int[] {0, 0}, List.of(FluidEntry.EMPTY, water), new int[] {1, 1});
		var text = new StringWriter();
		WorldSnapshotCodec.write(snapshot, text);
		return text.toString();
	}

	private static String row(final String prefix, final int y) {
		return prefix + "\t" + y + "\t0\t" + (prefix.equals("fc") ? 1 : 0);
	}

	private static WorldSnapshot read(final String text) throws IOException {
		return WorldSnapshotCodec.read(new BufferedReader(new StringReader(text)));
	}

	@Test
	void duplicateAndOutOfBoundsRowsCannotSupplyMissingBlockOrFluidCells() throws IOException {
		String original = world();
		for (String prefix : List.of("c", "fc")) {
			String last = row(prefix, 1);
			assertThrows(IOException.class, () -> read(original.replace(last, row(prefix, 0))));
			assertThrows(IOException.class, () -> read(original.replace(last, row(prefix, 2))));
			assertThrows(IOException.class, () -> read(original.replace(last + "\n", "")));
		}
	}

	@Test
	void rowsMayBeReorderedWhenEachCoordinateIsPresentExactlyOnce() throws IOException {
		String original = world();
		var lines = new ArrayList<>(original.lines().toList());
		for (String prefix : List.of("c", "fc")) {
			int first = lines.indexOf(row(prefix, 0));
			int second = lines.indexOf(row(prefix, 1));
			java.util.Collections.swap(lines, first, second);
		}
		var reordered = read(String.join("\n", lines));
		assertArrayEquals(read(original).cells(), reordered.cells());
		assertArrayEquals(read(original).fluidCells(), reordered.fluidCells());
	}

	@Test
	void dimensionsAreCheckedBeforeAllocation() throws IOException {
		String original = world();
		for (String size : List.of("0\t2\t1", "-1\t2\t1", "46341\t46341\t1")) {
			assertThrows(IOException.class, () -> read(original.replace("#size\t1\t2\t1", "#size\t" + size)));
		}
	}

	@Test
	void headerAndPaletteIdentityAndTrailingDataAreValidated() throws IOException {
		String original = world();
		for (String changed : List.of(original.replace("#origin", "#unknown"), original.replace("p\t0\t", "p\t9\t"),
		         original.replace("f\t0\t", "f\t9\t"), original.replace("#outside\tSEALED", "#outside"),
		         original.replace("3f19999a", "13f19999a"), original + "unbound trailing data\n")) {
			assertThrows(IOException.class, () -> read(changed));
		}
	}
}
