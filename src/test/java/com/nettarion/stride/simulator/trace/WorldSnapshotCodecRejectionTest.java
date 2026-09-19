package com.nettarion.stride.simulator.trace;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.FluidEntry;
import com.nettarion.stride.simulator.world.FluidKind;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.WorldSnapshot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A malformed world file must refuse rather than manufacture default cells. */
final class WorldSnapshotCodecRejectionTest {
	private static String world() throws IOException {
		FluidEntry water = new FluidEntry(1, "minecraft:water", FluidKind.WATER, 1.0, 0.0, 0.0, 0.0, true);
		WorldSnapshot snapshot = WorldSnapshot.builder(OutsidePolicy.SEALED, 0, 0, 0, 1, 2, 1)
		                             .palette(BlockEntry.builder(0, "minecraft:air").build())
		                             .fluidPalette(FluidEntry.EMPTY, water)
		                             .setFluid(0, 0, 0, 1)
		                             .setFluid(0, 1, 0, 1)
		                             .build();
		StringWriter text = new StringWriter();
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
		List<String> lines = new ArrayList<>(original.lines().toList());
		for (String prefix : List.of("c", "fc")) {
			int first = lines.indexOf(row(prefix, 0));
			int second = lines.indexOf(row(prefix, 1));
			Collections.swap(lines, first, second);
		}
		WorldSnapshot reordered = read(String.join("\n", lines));
		assertArrayEquals(read(original).toDenseCells(), reordered.toDenseCells());
		assertArrayEquals(read(original).toDenseFluidCells(), reordered.toDenseFluidCells());
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

	@Test
	void aFormatOlderThanTheOldestReadIsRefused() throws IOException {
		String older = world().replace("#stride-world\t" + WorldSnapshotCodec.FORMAT_VERSION,
		    "#stride-world\t" + (WorldSnapshotCodec.OLDEST_FORMAT_VERSION - 1));

		assertThrows(IOException.class, () -> read(older));
	}
}
