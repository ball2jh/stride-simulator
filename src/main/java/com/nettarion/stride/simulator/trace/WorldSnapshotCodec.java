package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.BlockStateCatalog;
import com.nettarion.stride.simulator.world.FluidEntry;
import com.nettarion.stride.simulator.world.FluidKind;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.ShapeProvenance;
import com.nettarion.stride.simulator.world.Suffocation;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/**
 * Reads and writes the world snapshot a capture carries, as tab-separated text.
 *
 * <p>The layout is specified in {@code docs/trace-formats.md}; the palette row columns are
 * listed by {@link PaletteColumn}. Formats {@link #OLDEST_FORMAT_VERSION} through
 * {@link #FORMAT_VERSION} are read; anything older is refused with an {@link IOException}.
 */
public final class WorldSnapshotCodec {
	/** The world file format this library writes. */
	public static final int FORMAT_VERSION = 15;

	/**
	 * The oldest format still read. Formats 13 and 14 predate recorded shape provenance and
	 * per-state contact and landing facts, which are derived from the block name as they were when
	 * those formats were current.
	 */
	public static final int OLDEST_FORMAT_VERSION = 13;

	/** The format that added the shape-provenance column. */
	private static final int SHAPE_PROVENANCE_VERSION = 14;

	/** The format that added the contact and landing columns. */
	private static final int CONTACT_AND_LANDING_VERSION = 15;

	private static final String MAGIC = "#stride-world";
	private static final String LEGACY_REFUSING_LABEL = "ROLLOUT_TERMINATING";
	private static final int FLUID_ROW_COLUMNS = 10;
	private static final int BOX_COORDINATES = 6;

	/**
	 * The tab-separated columns of one {@code p} palette row, in order. A column's index in a
	 * file of version {@code v} is the number of preceding columns whose {@code since} is at most
	 * {@code v}; the box count is followed by that many boxes, each six comma-separated hex
	 * doubles.
	 */
	enum PaletteColumn {
		/** The literal {@code p}. */
		ROW_TAG(OLDEST_FORMAT_VERSION, "p"),
		/** The zero-based palette index, equal to the row's position. */
		INDEX(OLDEST_FORMAT_VERSION, "int"),
		/** The vanilla block state id. */
		BLOCK_STATE_ID(OLDEST_FORMAT_VERSION, "int"),
		/** The block name, for example {@code minecraft:stone}. */
		NAME(OLDEST_FORMAT_VERSION, "string"),
		/** Friction, raw float bits as eight hex digits. */
		FRICTION(OLDEST_FORMAT_VERSION, "hex32"),
		/** Speed factor, raw float bits. */
		SPEED_FACTOR(OLDEST_FORMAT_VERSION, "hex32"),
		/** Jump factor, raw float bits. */
		JUMP_FACTOR(OLDEST_FORMAT_VERSION, "hex32"),
		/** {@code 0} or {@code 1}. */
		MOVING_PISTON(OLDEST_FORMAT_VERSION, "bit"),
		/** {@code 0} or {@code 1}. */
		SUPPRESSES_SUPPORTING_SPEED_FACTOR(OLDEST_FORMAT_VERSION, "bit"),
		/** A {@code WorldView.BubbleColumnMode} name. */
		BUBBLE_COLUMN_MODE(OLDEST_FORMAT_VERSION, "enum"),
		/** {@code 0} or {@code 1}. */
		FALL_DISTANCE_RESETTING(OLDEST_FORMAT_VERSION, "bit"),
		/** A {@code WorldView.CollisionBehavior} name. */
		COLLISION_BEHAVIOR(OLDEST_FORMAT_VERSION, "enum"),
		/** A {@code Suffocation} name. */
		SUFFOCATION(OLDEST_FORMAT_VERSION, "enum"),
		/** A {@code WorldView.InsideEffect} name. */
		INSIDE_EFFECT(OLDEST_FORMAT_VERSION, "enum"),
		/** Bounce restitution, raw float bits. */
		BOUNCE_RESTITUTION(OLDEST_FORMAT_VERSION, "hex32"),
		/** {@code 0} or {@code 1}. */
		SUPPRESSES_BOUNCE(OLDEST_FORMAT_VERSION, "bit"),
		/** A {@code WorldView.StepOn} name. */
		STEP_ON(OLDEST_FORMAT_VERSION, "enum"),
		/** {@code 0} or {@code 1}. */
		RETAINS_SUPPORT_POS(OLDEST_FORMAT_VERSION, "bit"),
		/** A {@code WorldView.Climbability} name. */
		CLIMBABILITY(OLDEST_FORMAT_VERSION, "enum"),
		/** A {@code ShapeProvenance} name; {@code LEGACY_GEOMETRY} when absent. */
		SHAPE_PROVENANCE(SHAPE_PROVENANCE_VERSION, "enum"),
		/** A {@code WorldView.Contact} name; derived from the name when absent. */
		CONTACT(CONTACT_AND_LANDING_VERSION, "enum"),
		/** A {@code WorldView.Landing} name; derived from the name when absent. */
		LANDING(CONTACT_AND_LANDING_VERSION, "enum"),
		/** The number of collision boxes that follow. */
		BOX_COUNT(OLDEST_FORMAT_VERSION, "int");

		private final int since;
		private final String encoding;

		PaletteColumn(final int since, final String encoding) {
			this.since = since;
			this.encoding = encoding;
		}

		/** The first format that carries this column. */
		int since() {
			return this.since;
		}

		/** A short name for the cell encoding, as used in the format specification. */
		String encoding() {
			return this.encoding;
		}

		/** Whether a file of {@code version} carries this column. */
		boolean presentIn(final int version) {
			return version >= this.since;
		}

		/** This column's index in a row of {@code version}; the column must be present. */
		int indexIn(final int version) {
			int index = 0;
			for (PaletteColumn column : values()) {
				if (column == this) {
					return index;
				}
				if (column.presentIn(version)) {
					index++;
				}
			}
			throw new AssertionError(this);
		}
	}

	private WorldSnapshotCodec() {}

	/**
	 * Reads the snapshot at {@code path} and verifies every palette entry against
	 * {@code catalog} for {@code minecraftVersion}; a differing block fact refuses.
	 */
	public static WorldSnapshot read(final Path path, final BlockStateCatalog catalog, final String minecraftVersion)
	    throws IOException {
		WorldSnapshot snapshot = read(path);
		catalog.verify(snapshot, minecraftVersion);
		return snapshot;
	}

	/**
	 * Reads the snapshot at {@code path} and verifies it through {@code source}; a differing
	 * block fact is wrapped in an {@link IOException}.
	 */
	public static WorldSnapshot read(final Path path, final BlockStateCatalog.Source source) throws IOException {
		WorldSnapshot snapshot = read(path);
		try {
			source.catalog(snapshot);
		} catch (IllegalArgumentException | UnimplementedMechanicException invalid) {
			throw new IOException("world differs from the block catalog", invalid);
		}
		return snapshot;
	}

	/** Writes {@code snapshot} to {@code path} as UTF-8 in {@link #FORMAT_VERSION}, replacing any existing file. */
	public static void write(final WorldSnapshot snapshot, final Path path) throws IOException {
		try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			write(snapshot, writer);
		}
	}

	/** Writes {@code snapshot} in {@link #FORMAT_VERSION} without closing {@code writer}. */
	public static void write(final WorldSnapshot snapshot, final Writer writer) throws IOException {
		writer.write(MAGIC + "\t" + FORMAT_VERSION + "\n");
		writer.write("#origin\t" + snapshot.originX() + "\t" + snapshot.originY() + "\t" + snapshot.originZ() + "\n");
		writer.write("#size\t" + snapshot.sizeX() + "\t" + snapshot.sizeY() + "\t" + snapshot.sizeZ() + "\n");
		writer.write("#outside\t" + snapshot.outside().name() + "\n");
		writer.write("#palette\t" + snapshot.palette().size() + "\n");

		for (int i = 0; i < snapshot.palette().size(); i++) {
			BlockEntry entry = snapshot.palette().get(i);
			StringBuilder line = new StringBuilder("p\t")
			                         .append(i)
			                         .append('\t')
			                         .append(entry.blockStateId())
			                         .append('\t')
			                         .append(entry.name())
			                         .append('\t')
			                         .append(hex32(Float.floatToRawIntBits(entry.friction())))
			                         .append('\t')
			                         .append(hex32(Float.floatToRawIntBits(entry.speedFactor())))
			                         .append('\t')
			                         .append(hex32(Float.floatToRawIntBits(entry.jumpFactor())))
			                         .append('\t')
			                         .append(entry.movingPiston() ? '1' : '0')
			                         .append('\t')
			                         .append(entry.suppressesSupportingSpeedFactor() ? '1' : '0')
			                         .append('\t')
			                         .append(entry.bubbleColumnMode().name())
			                         .append('\t')
			                         .append(entry.fallDistanceResetting() ? '1' : '0')
			                         .append('\t')
			                         .append(entry.collisionBehavior().name())
			                         .append('\t')
			                         .append(entry.suffocation().name())
			                         .append('\t')
			                         .append(entry.insideEffect().name())
			                         .append('\t')
			                         .append(hex32(Float.floatToRawIntBits(entry.bounceRestitution())))
			                         .append('\t')
			                         .append(entry.suppressesBounce() ? '1' : '0')
			                         .append('\t')
			                         .append(entry.stepOn().name())
			                         .append('\t')
			                         .append(entry.retainsSupportPos() ? '1' : '0')
			                         .append('\t')
			                         .append(entry.climbability().name())
			                         .append('\t')
			                         .append(entry.shapeProvenance().name())
			                         .append('\t')
			                         .append(entry.contact().name())
			                         .append('\t')
			                         .append(entry.landing().name())
			                         .append('\t')
			                         .append(entry.boxes().size());
			for (ShapeBox box : entry.boxes()) {
				line.append('\t')
				    .append(hex64(box.minX()))
				    .append(',')
				    .append(hex64(box.minY()))
				    .append(',')
				    .append(hex64(box.minZ()))
				    .append(',')
				    .append(hex64(box.maxX()))
				    .append(',')
				    .append(hex64(box.maxY()))
				    .append(',')
				    .append(hex64(box.maxZ()));
			}
			writer.write(line.append('\n').toString());
		}

		writer.write("#fluid-palette\t" + snapshot.fluidPalette().size() + "\n");
		for (int i = 0; i < snapshot.fluidPalette().size(); i++) {
			FluidEntry entry = snapshot.fluidPalette().get(i);
			writer.write(new StringBuilder("f\t")
			        .append(i)
			        .append('\t')
			        .append(entry.fluidStateId())
			        .append('\t')
			        .append(entry.name())
			        .append('\t')
			        .append(entry.kind().name())
			        .append('\t')
			        .append(hex64(entry.height()))
			        .append('\t')
			        .append(hex64(entry.flowX()))
			        .append('\t')
			        .append(hex64(entry.flowY()))
			        .append('\t')
			        .append(hex64(entry.flowZ()))
			        .append('\t')
			        .append(entry.source() ? '1' : '0')
			        .append('\n')
			        .toString());
		}

		int[] cells = snapshot.toDenseCells();
		for (int localY = 0; localY < snapshot.sizeY(); localY++) {
			for (int localZ = 0; localZ < snapshot.sizeZ(); localZ++) {
				StringBuilder row = new StringBuilder("c\t").append(localY).append('\t').append(localZ);
				for (int localX = 0; localX < snapshot.sizeX(); localX++) {
					row.append(localX == 0 ? '\t' : ',')
					    .append(cells[(localY * snapshot.sizeZ() + localZ) * snapshot.sizeX() + localX]);
				}
				writer.write(row.append('\n').toString());
			}
		}

		writer.write("#fluid-cells\t" + (snapshot.hasFluids() ? '1' : '0') + "\n");
		if (snapshot.hasFluids()) {
			int[] fluidCells = snapshot.toDenseFluidCells();
			for (int localY = 0; localY < snapshot.sizeY(); localY++) {
				for (int localZ = 0; localZ < snapshot.sizeZ(); localZ++) {
					StringBuilder row = new StringBuilder("fc\t").append(localY).append('\t').append(localZ);
					for (int localX = 0; localX < snapshot.sizeX(); localX++) {
						row.append(localX == 0 ? '\t' : ',')
						    .append(fluidCells[(localY * snapshot.sizeZ() + localZ) * snapshot.sizeX() + localX]);
					}
					writer.write(row.append('\n').toString());
				}
			}
		}
	}

	/**
	 * Reads the snapshot at {@code path} without verifying its block facts against a catalog.
	 * {@link Capture#read} verifies; use this for synthetic worlds and tests.
	 */
	public static WorldSnapshot read(final Path path) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return read(reader);
		}
	}

	/** The format version the file at {@code path} carries, read from its first line only. */
	public static int formatVersion(final Path path) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			String[] magic = require(reader, "magic").split("\t", -1);
			if (magic.length != 2 || !MAGIC.equals(magic[0])) {
				throw new IOException("not a stride world snapshot: " + path);
			}
			return Integer.parseInt(magic[1]);
		} catch (NumberFormatException invalid) {
			throw new IOException("invalid world format version in " + path, invalid);
		}
	}

	/** Reads one snapshot from {@code reader} without closing it; a malformed file refuses with {@link IOException}. */
	public static WorldSnapshot read(final BufferedReader reader) throws IOException {
		try {
			return decode(reader);
		} catch (IllegalArgumentException | IndexOutOfBoundsException | ArithmeticException invalid) {
			throw new IOException("invalid world snapshot: " + invalid.getMessage(), invalid);
		}
	}

	private static WorldSnapshot decode(final BufferedReader reader) throws IOException {
		String[] magic = require(reader, "magic").split("\t", -1);
		if (magic.length != 2 || !MAGIC.equals(magic[0])) {
			throw new IOException("not a stride world snapshot");
		}
		int version = Integer.parseInt(magic[1]);
		if (version < OLDEST_FORMAT_VERSION || version > FORMAT_VERSION) {
			throw new IOException("world snapshot format version " + version + ", expected " + OLDEST_FORMAT_VERSION
			    + " through " + FORMAT_VERSION + "; older captures must be recaptured");
		}

		String[] origin = header(reader, "#origin", 4);
		String[] size = header(reader, "#size", 4);
		String[] outside = header(reader, "#outside", 2);
		String[] paletteHeader = header(reader, "#palette", 2);
		int originX = Integer.parseInt(origin[1]);
		int originY = Integer.parseInt(origin[2]);
		int originZ = Integer.parseInt(origin[3]);
		int sizeX = Integer.parseInt(size[1]);
		int sizeY = Integer.parseInt(size[2]);
		int sizeZ = Integer.parseInt(size[3]);
		checkedCellCount(sizeX, sizeY, sizeZ);
		if ((long) originX + sizeX > Integer.MAX_VALUE || (long) originY + sizeY > Integer.MAX_VALUE
		    || (long) originZ + sizeZ > Integer.MAX_VALUE) {
			throw new IOException("world region exceeds coordinate domain");
		}
		OutsidePolicy outsideRegion = outsidePolicy(outside[1]);
		int paletteSize = Integer.parseInt(paletteHeader[1]);

		if (paletteSize <= 0) {
			throw new IOException("empty or negative block palette");
		}
		List<BlockEntry> palette = new ArrayList<>();
		for (int i = 0; i < paletteSize; i++) {
			String[] cells = require(reader, "palette entry").split("\t", -1);
			if (!"p".equals(cells[0]) || Integer.parseInt(cell(cells, PaletteColumn.INDEX, version)) != i) {
				throw new IOException("block palette row index differs");
			}
			palette.add(paletteEntry(cells, version));
		}

		String[] fluidHeader = header(reader, "#fluid-palette", 2);
		int fluidPaletteSize = Integer.parseInt(fluidHeader[1]);
		if (fluidPaletteSize <= 0) {
			throw new IOException("empty or negative fluid palette");
		}
		List<FluidEntry> fluidPalette = new ArrayList<>();
		for (int i = 0; i < fluidPaletteSize; i++) {
			String[] entry = require(reader, "fluid palette entry").split("\t", -1);
			if (entry.length != FLUID_ROW_COLUMNS || !"f".equals(entry[0]) || Integer.parseInt(entry[1]) != i) {
				throw new IOException("invalid fluid palette row");
			}
			fluidPalette.add(new FluidEntry(Integer.parseInt(entry[2]), entry[3], FluidKind.valueOf(entry[4]),
			    unhex64(entry[5]), unhex64(entry[6]), unhex64(entry[7]), unhex64(entry[8]),
			    parseBooleanFlag(entry[9], "fluid source")));
		}

		int[] cells = readCells(reader, "c", sizeX, sizeY, sizeZ);
		int[] fluidCells = new int[0];
		String[] fluidCellsHeader = header(reader, "#fluid-cells", 2);
		if (parseBooleanFlag(fluidCellsHeader[1], "fluid-cells")) {
			fluidCells = readCells(reader, "fc", sizeX, sizeY, sizeZ);
		}

		String trailing;
		while ((trailing = reader.readLine()) != null) {
			if (!trailing.isBlank()) {
				throw new IOException("trailing world snapshot data");
			}
		}
		return WorldSnapshot.owning(
		    originX, originY, originZ, sizeX, sizeY, sizeZ, outsideRegion, palette, cells, fluidPalette, fluidCells);
	}

	private static BlockEntry paletteEntry(final String[] cells, final int version) throws IOException {
		String name = cell(cells, PaletteColumn.NAME, version);
		int boxCountIndex = PaletteColumn.BOX_COUNT.indexIn(version);
		int boxesIndex = boxCountIndex + 1;
		int boxCount = Integer.parseInt(cells[boxCountIndex]);
		if (boxCount < 0 || boxCount != cells.length - boxesIndex) {
			throw new IOException("shape box count differs from palette row");
		}
		List<ShapeBox> boxes = new ArrayList<>();
		for (int b = 0; b < boxCount; b++) {
			String[] parts = cells[boxesIndex + b].split(",", -1);
			if (parts.length != BOX_COORDINATES) {
				throw new IOException("shape box requires six coordinates");
			}
			boxes.add(new ShapeBox(unhex64(parts[0]), unhex64(parts[1]), unhex64(parts[2]), unhex64(parts[3]),
			    unhex64(parts[4]), unhex64(parts[5])));
		}
		ShapeProvenance shapeProvenance = PaletteColumn.SHAPE_PROVENANCE.presentIn(version)
		    ? ShapeProvenance.valueOf(cell(cells, PaletteColumn.SHAPE_PROVENANCE, version))
		    : ShapeProvenance.LEGACY_GEOMETRY;
		WorldView.Contact contact = PaletteColumn.CONTACT.presentIn(version)
		    ? WorldView.Contact.valueOf(unrecorded(cell(cells, PaletteColumn.CONTACT, version)))
		    : BlockEntry.legacyContact(name);
		WorldView.Landing landing = PaletteColumn.LANDING.presentIn(version)
		    ? WorldView.Landing.valueOf(unrecorded(cell(cells, PaletteColumn.LANDING, version)))
		    : BlockEntry.legacyLanding(name);
		return new BlockEntry(Integer.parseInt(cell(cells, PaletteColumn.BLOCK_STATE_ID, version)), name,
		    unhex32(cell(cells, PaletteColumn.FRICTION, version)),
		    unhex32(cell(cells, PaletteColumn.SPEED_FACTOR, version)),
		    unhex32(cell(cells, PaletteColumn.JUMP_FACTOR, version)),
		    parseBooleanFlag(cell(cells, PaletteColumn.MOVING_PISTON, version), "moving-piston"),
		    parseBooleanFlag(
		        cell(cells, PaletteColumn.SUPPRESSES_SUPPORTING_SPEED_FACTOR, version), "support-speed suppression"),
		    WorldView.BubbleColumnMode.valueOf(cell(cells, PaletteColumn.BUBBLE_COLUMN_MODE, version)),
		    parseBooleanFlag(cell(cells, PaletteColumn.FALL_DISTANCE_RESETTING, version), "fall-distance-resetting"),
		    WorldView.CollisionBehavior.valueOf(cell(cells, PaletteColumn.COLLISION_BEHAVIOR, version)),
		    Suffocation.valueOf(cell(cells, PaletteColumn.SUFFOCATION, version)),
		    WorldView.InsideEffect.valueOf(cell(cells, PaletteColumn.INSIDE_EFFECT, version)),
		    unhex32(cell(cells, PaletteColumn.BOUNCE_RESTITUTION, version)),
		    parseBooleanFlag(cell(cells, PaletteColumn.SUPPRESSES_BOUNCE, version), "suppresses-bounce"),
		    WorldView.StepOn.valueOf(cell(cells, PaletteColumn.STEP_ON, version)),
		    parseBooleanFlag(cell(cells, PaletteColumn.RETAINS_SUPPORT_POS, version), "retains-support-pos"),
		    WorldView.Climbability.valueOf(cell(cells, PaletteColumn.CLIMBABILITY, version)), shapeProvenance, contact,
		    landing, boxes);
	}

	private static String cell(final String[] cells, final PaletteColumn column, final int version) {
		return cells[column.indexIn(version)];
	}

	private static int[] readCells(final BufferedReader reader, final String what, final int sizeX, final int sizeY,
	    final int sizeZ) throws IOException {
		int[] cells = new int[checkedCellCount(sizeX, sizeY, sizeZ)];
		BitSet seen = new BitSet();
		for (int row = 0; row < sizeY * sizeZ; row++) {
			String[] parts = require(reader, what).split("\t", -1);
			if (parts.length != 4 || !what.equals(parts[0])) {
				throw new IOException("invalid " + what + " row");
			}
			int localY = Integer.parseInt(parts[1]);
			int localZ = Integer.parseInt(parts[2]);
			if (localY < 0 || localY >= sizeY || localZ < 0 || localZ >= sizeZ) {
				throw new IOException("world row coordinate outside dimensions");
			}
			int rowIndex = localY * sizeZ + localZ;
			if (seen.get(rowIndex)) {
				throw new IOException("duplicate " + what + " row");
			}
			seen.set(rowIndex);
			String[] ids = parts[3].split(",", -1);
			if (ids.length != sizeX) {
				throw new IOException(what + " has " + ids.length + " entries, expected " + sizeX);
			}
			for (int localX = 0; localX < sizeX; localX++) {
				cells[(localY * sizeZ + localZ) * sizeX + localX] = Integer.parseInt(ids[localX]);
			}
		}
		return cells;
	}

	private static int checkedCellCount(final int sizeX, final int sizeY, final int sizeZ) throws IOException {
		if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
			throw new IOException("world dimensions must be positive");
		}
		return Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
	}

	private static String[] header(final BufferedReader reader, final String label, final int columns)
	    throws IOException {
		String[] cells = require(reader, label).split("\t", -1);
		if (cells.length != columns || !label.equals(cells[0])) {
			throw new IOException("invalid world header " + label);
		}
		return cells;
	}

	private static String require(final BufferedReader reader, final String what) throws IOException {
		String line = reader.readLine();
		if (line == null) {
			throw new IOException("world snapshot ended before " + what);
		}
		return line;
	}

	private static String hex32(final int bits) {
		return String.format(Locale.ROOT, "%08x", bits);
	}

	private static String hex64(final double value) {
		return String.format(Locale.ROOT, "%016x", Double.doubleToRawLongBits(value));
	}

	private static float unhex32(final String hex) {
		return Float.intBitsToFloat(Integer.parseUnsignedInt(hex, 16));
	}

	private static double unhex64(final String hex) {
		return Double.longBitsToDouble(Long.parseUnsignedLong(hex, 16));
	}

	private static boolean parseBooleanFlag(final String value, final String what) throws IOException {
		return switch (value) {
			case "0" -> false;
			case "1" -> true;
			default -> throw new IOException("invalid " + what + " flag: " + value);
		};
	}

	/** Maps the pre-0.1 label {@code UNKNOWN} of the contact and landing columns to {@code UNRECORDED}. */
	private static String unrecorded(final String label) {
		return label.equals("UNKNOWN") ? "UNRECORDED" : label;
	}

	/** Decodes the outside policy label, accepting the pre-0.1 name {@code ROLLOUT_TERMINATING} for {@code REFUSING}. */
	private static OutsidePolicy outsidePolicy(final String label) throws IOException {
		if (label.equals(LEGACY_REFUSING_LABEL)) {
			return OutsidePolicy.REFUSING;
		}
		try {
			return OutsidePolicy.valueOf(label);
		} catch (IllegalArgumentException e) {
			throw new IOException("unknown outside policy: " + label, e);
		}
	}
}
