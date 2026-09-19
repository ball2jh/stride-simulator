package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.ShapeProvenance;
import com.nettarion.stride.simulator.world.FluidEntry;
import com.nettarion.stride.simulator.world.FluidKind;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.Suffocation;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.WorldView.BubbleColumnMode;
import com.nettarion.stride.simulator.world.WorldView.CollisionBehavior;
import com.nettarion.stride.simulator.world.WorldView.InsideEffect;
import com.nettarion.stride.simulator.world.WorldView.StepOn;
import com.nettarion.stride.simulator.world.WorldView;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.world.BlockStateCatalog;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Reads and writes the portable world snapshot a capture carries. */
public final class WorldSnapshotCodec {
	public static final int FORMAT_VERSION = 15;
	/**
	 * The oldest format still read. Formats 13 and 14 predate recorded shape
	 * identity and per-state contact and landing facts, which are derived from
	 * the block name as they were when those formats were current; anything
	 * older is a recapture, not a migration.
	 */
	public static final int OLDEST_FORMAT_VERSION = 13;
	private static final String MAGIC = "#stride-world";

	private WorldSnapshotCodec() {}

	/** Read legacy or current records and reject any block fact absent from the pinned source catalog. */
	public static WorldSnapshot read(final Path path,
	    final com.nettarion.stride.simulator.world.BlockStateCatalog catalog, final String minecraftVersion)
	    throws IOException {
		WorldSnapshot snapshot = read(path);
		catalog.verify(snapshot, minecraftVersion);
		return snapshot;
	}

	/** Import using a pinned independent source extractor. */
	public static WorldSnapshot read(final Path path,
	    final com.nettarion.stride.simulator.world.BlockStateCatalog.Source source) throws IOException {
		WorldSnapshot snapshot = read(path);
		try {
			source.catalog(snapshot);
		} catch (IllegalArgumentException | com.nettarion.stride.simulator.UnimplementedMechanicException invalid) {
			throw new IOException("world differs from source", invalid);
		}
		return snapshot;
	}

	public static void write(final WorldSnapshot snapshot, final Path path) throws IOException {
		try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			write(snapshot, writer);
		}
	}

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

		int[] cells = snapshot.cells();
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
			int[] fluidCells = snapshot.fluidCells();
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

	/** Decode portable syntax only; normal captures use Capture.read and live imports supply an independent Source. */
	public static WorldSnapshot read(final Path path) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return read(reader);
		}
	}

	/** The format version a written snapshot carries, without decoding it. */
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

		if (paletteSize <= 0) throw new IOException("empty or negative block palette");
		List<BlockEntry> palette = new ArrayList<>();
		for (int i = 0; i < paletteSize; i++) {
			String[] cells = require(reader, "palette entry").split("\t", -1);
			if (!"p".equals(cells[0]) || Integer.parseInt(cells[1]) != i) {
				throw new IOException("block palette row index differs");
			}
			boolean movingPiston = parseBooleanFlag(cells[7], "moving-piston");
			boolean suppressesSupportingSpeedFactor = parseBooleanFlag(cells[8], "support-speed suppression");
			BubbleColumnMode bubbleColumnMode = BubbleColumnMode.valueOf(cells[9]);
			boolean fallDistanceResetting = parseBooleanFlag(cells[10], "fall-distance-resetting");
			CollisionBehavior collisionBehavior = CollisionBehavior.valueOf(cells[11]);
			Suffocation suffocation = Suffocation.valueOf(cells[12]);
			InsideEffect insideEffect = InsideEffect.valueOf(cells[13]);
			float bounceRestitution = Float.intBitsToFloat(Integer.parseUnsignedInt(cells[14], 16));
			boolean suppressesBounce = parseBooleanFlag(cells[15], "suppresses-bounce");
			StepOn stepOn = StepOn.valueOf(cells[16]);
			boolean retainsSupportPos = parseBooleanFlag(cells[17], "retains-support-pos");
			WorldView.Climbability climbability = WorldView.Climbability.valueOf(cells[18]);
			int collisionIdentityIndex = 19;
			int boxCountIndex = version >= 15 ? 22 : version >= 14 ? 20 : 19;
			int boxesIndex = boxCountIndex + 1;
			int boxCount = Integer.parseInt(cells[boxCountIndex]);
			if (boxCount < 0 || boxCount != cells.length - boxesIndex) {
				throw new IOException("shape box count differs from palette row");
			}
			List<ShapeBox> boxes = new ArrayList<>();
			for (int b = 0; b < boxCount; b++) {
				String[] parts = cells[boxesIndex + b].split(",", -1);
				if (parts.length != 6) throw new IOException("shape box requires six coordinates");
				boxes.add(new ShapeBox(unhex64(parts[0]), unhex64(parts[1]), unhex64(parts[2]), unhex64(parts[3]),
				    unhex64(parts[4]), unhex64(parts[5])));
			}
			ShapeProvenance shapeProvenance = version >= 14
			    ? ShapeProvenance.valueOf(cells[collisionIdentityIndex])
			    : ShapeProvenance.LEGACY_GEOMETRY;
			String name = cells[3];
			WorldView.Contact contact =
			    version >= 15 ? WorldView.Contact.valueOf(cells[20]) : BlockEntry.legacyContact(name);
			WorldView.Landing landing =
			    version >= 15 ? WorldView.Landing.valueOf(cells[21]) : BlockEntry.legacyLanding(name);
			palette.add(new BlockEntry(Integer.parseInt(cells[2]), name,
			    Float.intBitsToFloat(Integer.parseUnsignedInt(cells[4], 16)),
			    Float.intBitsToFloat(Integer.parseUnsignedInt(cells[5], 16)),
			    Float.intBitsToFloat(Integer.parseUnsignedInt(cells[6], 16)), movingPiston,
			    suppressesSupportingSpeedFactor, bubbleColumnMode, fallDistanceResetting, collisionBehavior,
			    suffocation, insideEffect, bounceRestitution, suppressesBounce, stepOn, retainsSupportPos, climbability,
			    shapeProvenance, contact, landing, boxes));
		}

		String[] fluidHeader = header(reader, "#fluid-palette", 2);
		int fluidPaletteSize = Integer.parseInt(fluidHeader[1]);
		if (fluidPaletteSize <= 0) throw new IOException("empty or negative fluid palette");
		List<FluidEntry> fluidPalette = new ArrayList<>();
		for (int i = 0; i < fluidPaletteSize; i++) {
			String[] entry = require(reader, "fluid palette entry").split("\t", -1);
			if (entry.length != 10 || !"f".equals(entry[0]) || Integer.parseInt(entry[1]) != i) {
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
			if (!trailing.isBlank()) throw new IOException("trailing world snapshot data");
		}
		return WorldSnapshot.owning(
		    originX, originY, originZ, sizeX, sizeY, sizeZ, outsideRegion, palette, cells, fluidPalette, fluidCells);
	}

	private static int[] readCells(final BufferedReader reader, final String what, final int sizeX, final int sizeY,
	    final int sizeZ) throws IOException {
		int[] cells = new int[checkedCellCount(sizeX, sizeY, sizeZ)];
		BitSet seen = new BitSet();
		for (int row = 0; row < sizeY * sizeZ; row++) {
			String[] parts = require(reader, what).split("\t", -1);
			if (parts.length != 4 || !what.equals(parts[0])) throw new IOException("invalid " + what + " row");
			int localY = Integer.parseInt(parts[1]);
			int localZ = Integer.parseInt(parts[2]);
			if (localY < 0 || localY >= sizeY || localZ < 0 || localZ >= sizeZ) {
				throw new IOException("world row coordinate outside dimensions");
			}
			int rowIndex = localY * sizeZ + localZ;
			if (seen.get(rowIndex)) throw new IOException("duplicate " + what + " row");
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
		if (line == null) throw new IOException("world snapshot ended before " + what);
		return line;
	}

	private static String hex32(final int bits) {
		return String.format("%08x", bits);
	}

	private static String hex64(final double value) {
		return String.format("%016x", Double.doubleToRawLongBits(value));
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
	/** Decodes the outside policy label, accepting the pre-0.1 name {@code ROLLOUT_TERMINATING}. */
	private static OutsidePolicy outsidePolicy(final String label) throws IOException {
		if (label.equals("ROLLOUT_TERMINATING")) return OutsidePolicy.REFUSING;
		try {
			return OutsidePolicy.valueOf(label);
		} catch (IllegalArgumentException e) {
			throw new IOException("unknown outside policy: " + label, e);
		}
	}
}
