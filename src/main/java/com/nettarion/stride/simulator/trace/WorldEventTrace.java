package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.world.WorldSnapshot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.nettarion.stride.simulator.world.FluidKind;
import com.nettarion.stride.simulator.world.FluidEntry;

/**
 * Ordered block and resolved-fluid changes published before a movement tick.
 *
 * <p>Block state ids and exact fluid entries must resolve through the
 * accompanying {@link WorldSnapshot} palettes. Fluid facts are independent
 * events because a block write can alter neighboring height or flow without
 * changing those neighbors' state IDs. This contract records an observed or
 * predicted world successor; it says nothing about the interaction, redstone,
 * entity, or scheduled-tick rules that produced it.
 */
public record WorldEventTrace(List<CellStateEvent> events, List<FluidCellEvent> fluidEvents) {
	private static final String MAGIC = "#stride-world-events";
	static final int FORMAT_VERSION = 3;
	private static final String[] BLOCK_COLUMNS = {"tick", "x", "y", "z", "state_id", "fluid_state_id"};
	private static final String[] V1_COLUMNS = {"tick", "x", "y", "z", "state_id"};
	private static final String[] FLUID_COLUMNS = {"tick", "x", "y", "z", "fluid_state_id", "fluid_name", "fluid_kind",
	    "height_bits", "flow_x_bits", "flow_y_bits", "flow_z_bits", "source"};

	public WorldEventTrace {
		events = List.copyOf(events);
		fluidEvents = List.copyOf(fluidEvents);
		validateTicks(events.stream().mapToInt(CellStateEvent::tick).toArray());
		validateTicks(fluidEvents.stream().mapToInt(FluidCellEvent::tick).toArray());
	}

	public WorldEventTrace(final List<CellStateEvent> events) {
		this(events, List.of());
	}

	public boolean isEmpty() {
		return this.events.isEmpty() && this.fluidEvents.isEmpty();
	}

	public int eventCount() {
		return this.events.size() + this.fluidEvents.size();
	}

	private static void validateTicks(final int[] ticks) {
		int previousTick = -1;
		for (int tick : ticks) {
			if (tick < 0 || tick < previousTick) {
				throw new IllegalArgumentException("world-event ticks must be non-negative and nondecreasing");
			}
			previousTick = tick;
		}
	}

	public static void write(final WorldEventTrace trace, final Path path) throws IOException {
		try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			write(trace, writer);
		}
	}

	static void write(final WorldEventTrace trace, final Writer writer) throws IOException {
		writer.write(MAGIC + "\t" + FORMAT_VERSION + "\n");
		writer.write("#block-events\t" + trace.events().size() + "\n");
		writer.write(String.join("\t", BLOCK_COLUMNS) + "\n");
		for (CellStateEvent event : trace.events()) {
			writer.write(event.tick() + "\t" + event.x() + "\t" + event.y() + "\t" + event.z() + "\t"
			    + event.blockStateId() + "\t" + event.fluidStateId() + "\n");
		}
		writer.write("#fluid-events\t" + trace.fluidEvents().size() + "\n");
		writer.write(String.join("\t", FLUID_COLUMNS) + "\n");
		for (FluidCellEvent event : trace.fluidEvents()) {
			FluidEntry fluid = event.fluid();
			writer.write(event.tick() + "\t" + event.x() + "\t" + event.y() + "\t" + event.z() + "\t"
			    + fluid.fluidStateId() + "\t" + fluid.name() + "\t" + fluid.kind().name() + "\t" + hex64(fluid.height())
			    + "\t" + hex64(fluid.flowX()) + "\t" + hex64(fluid.flowY()) + "\t" + hex64(fluid.flowZ()) + "\t"
			    + fluid.source() + "\n");
		}
	}

	public static WorldEventTrace read(final Path path) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return read(reader);
		}
	}

	/** The format version a written world-event trace carries, without decoding it. */
	public static int formatVersion(final Path path) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			String[] magic = require(reader, "magic").split("\t", -1);
			if (magic.length != 2 || !MAGIC.equals(magic[0])) {
				throw new IOException("not a stride world-event trace: " + path);
			}
			return parseInt(magic[1], "format version");
		}
	}

	static WorldEventTrace read(final BufferedReader reader) throws IOException {
		String[] magic = require(reader, "magic").split("\t", -1);
		if (magic.length != 2 || !MAGIC.equals(magic[0])) {
			throw new IOException("not a stride world-event trace");
		}
		int version = parseInt(magic[1], "format version");
		if (version < 1 || version > FORMAT_VERSION) {
			throw new IOException("world-event format version " + version + ", expected 1 through " + FORMAT_VERSION);
		}

		if (version == 3) {
			return readV3(reader);
		}
		return readLegacy(reader, version);
	}

	private static WorldEventTrace readLegacy(final BufferedReader reader, final int version) throws IOException {
		String[] header = require(reader, "column header").split("\t", -1);
		String[] expectedColumns = version == 1 ? V1_COLUMNS : BLOCK_COLUMNS;
		if (header.length != expectedColumns.length) {
			throw new IOException(
			    "world-event header has " + header.length + " columns, expected " + expectedColumns.length);
		}
		for (int i = 0; i < expectedColumns.length; i++) {
			if (!expectedColumns[i].equals(header[i])) {
				throw new IOException(
				    "world-event column " + i + " is " + header[i] + ", expected " + expectedColumns[i]);
			}
		}

		List<CellStateEvent> events = new ArrayList<>();
		int previousTick = -1;
		String line;
		int lineNumber = 2;
		while ((line = reader.readLine()) != null) {
			lineNumber++;
			if (line.isBlank()) {
				continue;
			}
			String[] values = line.split("\t", -1);
			if (values.length != expectedColumns.length) {
				throw new IOException("world-event line " + lineNumber + " has " + values.length + " columns, expected "
				    + expectedColumns.length);
			}
			int tick = parseInt(values[0], "tick at line " + lineNumber);
			if (tick < 0 || tick < previousTick) {
				throw new IOException("world-event ticks must be non-negative and nondecreasing"
				    + " at line " + lineNumber);
			}
			previousTick = tick;
			events.add(new CellStateEvent(tick, parseInt(values[1], "x at line " + lineNumber),
			    parseInt(values[2], "y at line " + lineNumber), parseInt(values[3], "z at line " + lineNumber),
			    parseInt(values[4], "state_id at line " + lineNumber),
			    version >= 2 ? parseInt(values[5], "fluid_state_id at line " + lineNumber)
			                 : CellStateEvent.KEEP_EXISTING_FLUID));
		}
		return new WorldEventTrace(events);
	}

	private static WorldEventTrace readV3(final BufferedReader reader) throws IOException {
		int blockCount = readSectionCount(reader, "#block-events");
		requireColumns(reader, BLOCK_COLUMNS, "block-event");
		List<CellStateEvent> blocks = new ArrayList<>(blockCount);
		for (int i = 0; i < blockCount; i++) {
			String[] values = require(reader, "block event " + i).split("\t", -1);
			if (values.length != BLOCK_COLUMNS.length) {
				throw new IOException(
				    "world block-event " + i + " has " + values.length + " columns, expected " + BLOCK_COLUMNS.length);
			}
			blocks.add(new CellStateEvent(parseInt(values[0], "block tick " + i), parseInt(values[1], "block x " + i),
			    parseInt(values[2], "block y " + i), parseInt(values[3], "block z " + i),
			    parseInt(values[4], "block state id " + i), parseInt(values[5], "block fluid state id " + i)));
		}
		int fluidCount = readSectionCount(reader, "#fluid-events");
		requireColumns(reader, FLUID_COLUMNS, "fluid-event");
		List<FluidCellEvent> fluids = new ArrayList<>(fluidCount);
		for (int i = 0; i < fluidCount; i++) {
			String[] values = require(reader, "fluid event " + i).split("\t", -1);
			if (values.length != FLUID_COLUMNS.length) {
				throw new IOException(
				    "world fluid-event " + i + " has " + values.length + " columns, expected " + FLUID_COLUMNS.length);
			}
			FluidKind kind;
			try {
				kind = FluidKind.valueOf(values[6]);
			} catch (IllegalArgumentException invalid) {
				throw new IOException("invalid fluid kind at event " + i + ": " + values[6], invalid);
			}
			fluids.add(new FluidCellEvent(parseInt(values[0], "fluid tick " + i), parseInt(values[1], "fluid x " + i),
			    parseInt(values[2], "fluid y " + i), parseInt(values[3], "fluid z " + i),
			    new FluidEntry(parseInt(values[4], "fluid state id " + i), values[5], kind,
			        parseHexDouble(values[7], "fluid height " + i), parseHexDouble(values[8], "fluid flow x " + i),
			        parseHexDouble(values[9], "fluid flow y " + i), parseHexDouble(values[10], "fluid flow z " + i),
			        parseBoolean(values[11], "fluid source " + i))));
		}
		String trailing;
		while ((trailing = reader.readLine()) != null) {
			if (!trailing.isBlank()) {
				throw new IOException("unexpected content after world-event sections");
			}
		}
		try {
			return new WorldEventTrace(blocks, fluids);
		} catch (IllegalArgumentException invalid) {
			throw new IOException(invalid.getMessage(), invalid);
		}
	}

	private static int readSectionCount(final BufferedReader reader, final String section) throws IOException {
		String[] header = require(reader, section).split("\t", -1);
		if (header.length != 2 || !section.equals(header[0])) {
			throw new IOException("expected " + section + " section");
		}
		int count = parseInt(header[1], section + " count");
		if (count < 0) {
			throw new IOException(section + " count must be non-negative");
		}
		return count;
	}

	private static void requireColumns(final BufferedReader reader, final String[] expected, final String section)
	    throws IOException {
		String[] actual = require(reader, section + " columns").split("\t", -1);
		if (!java.util.Arrays.equals(expected, actual)) {
			throw new IOException(section + " columns do not match format " + FORMAT_VERSION);
		}
	}

	private static String require(final BufferedReader reader, final String what) throws IOException {
		String line = reader.readLine();
		if (line == null) {
			throw new IOException("world-event trace ended before " + what);
		}
		return line;
	}

	private static int parseInt(final String value, final String what) throws IOException {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException malformed) {
			throw new IOException("invalid world-event " + what + ": " + value, malformed);
		}
	}

	private static String hex64(final double value) {
		return String.format(java.util.Locale.ROOT, "%016x", Double.doubleToRawLongBits(value));
	}

	private static double parseHexDouble(final String value, final String what) throws IOException {
		try {
			return Double.longBitsToDouble(Long.parseUnsignedLong(value, 16));
		} catch (NumberFormatException malformed) {
			throw new IOException("invalid " + what + ": " + value, malformed);
		}
	}

	private static boolean parseBoolean(final String value, final String what) throws IOException {
		if ("true".equals(value)) {
			return true;
		}
		if ("false".equals(value)) {
			return false;
		}
		throw new IOException("invalid " + what + ": " + value);
	}

	/** One effective block-state publication at the head of {@code tick}. */
	public record CellStateEvent(int tick, int x, int y, int z, int blockStateId, int fluidStateId) {
		/** V1 migration marker. The old protocol did not publish a fluid change. */
		public static final int KEEP_EXISTING_FLUID = -1;

		public CellStateEvent(final int tick, final int x, final int y, final int z, final int blockStateId) {
			this(tick, x, y, z, blockStateId, KEEP_EXISTING_FLUID);
		}
	}

	/** One fully resolved fluid fact published after all block changes for a tick. */
	public record FluidCellEvent(int tick, int x, int y, int z, FluidEntry fluid) {
		public FluidCellEvent {
			if (fluid == null) {
				throw new IllegalArgumentException("fluid event requires resolved facts");
			}
		}
	}
}
