package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.world.BlockStateCatalog;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.WorldSnapshot;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads and writes the {@code .catalog} companion of a capture: the block facts of one Minecraft
 * version that the capture's world is verified against.
 *
 * <p>The stream is binary ({@code DataOutput} big-endian) and specified in
 * {@code docs/trace-formats.md}. Its palette section embeds a one-cell world in the text format
 * of {@link WorldSnapshotCodec}, because that codec already owns the palette row encoding; the
 * bundled captures depend on this layout.
 */
public final class BlockStateCatalogCodec {
	/**
	 * The magic string opening every catalog stream. The byte value is fixed by the bundled
	 * captures and predates the {@code BlockStateCatalog} name.
	 */
	static final String MAGIC = "stride-block-source";

	/** The catalog stream format this library reads and writes; no other version is read. */
	public static final int FORMAT_VERSION = 1;

	/** The most bytes the embedded palette text may occupy: 64 MiB. */
	private static final int MAX_PALETTE_TEXT_BYTES = 64 * 1024 * 1024;

	/** The most cauldrons, inside shapes, or boxes per shape a stream may declare. */
	private static final int MAX_LIST_SIZE = 100_000;

	private BlockStateCatalogCodec() {}

	/** Writes {@code catalog} to {@code path}, replacing any existing file. */
	public static void write(final BlockStateCatalog catalog, final Path path) throws IOException {
		Files.write(path, bytes(catalog));
	}

	/**
	 * The canonical stream bytes of {@code catalog}. The same bytes are what a
	 * {@link ScheduledRecording} fingerprints to pin the catalog it was recorded against.
	 */
	public static byte[] bytes(final BlockStateCatalog catalog) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(buffer);

		out.writeUTF(MAGIC);
		out.writeInt(FORMAT_VERSION);
		out.writeUTF(catalog.minecraftVersion());
		WorldSnapshot snapshot =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, 0, 0, 0, 1, 1, 1).palette(catalog.entries()).build();
		StringWriter text = new StringWriter();
		WorldSnapshotCodec.write(snapshot, text);
		byte[] paletteText = text.toString().getBytes(StandardCharsets.UTF_8);
		out.writeInt(paletteText.length);
		out.write(paletteText);
		out.writeInt(catalog.cauldrons().size());
		for (BlockStateCatalog.Cauldron cauldron : catalog.cauldrons()) {
			out.writeInt(cauldron.blockStateId());
			out.writeInt(cauldron.successorStateId());
			boxes(out, cauldron.insideBoxes());
		}
		out.writeInt(catalog.insideShapes().size());
		for (Map.Entry<Integer, BlockStateCatalog.InsideShape> entry :
		    new TreeMap<>(catalog.insideShapes()).entrySet()) {
			out.writeInt(entry.getKey());
			out.writeBoolean(entry.getValue().canonicalFull());
			boxes(out, entry.getValue().boxes());
		}
		return buffer.toByteArray();
	}

	/** Reads the catalog at {@code path}; see {@link #read(byte[], String)}. */
	public static BlockStateCatalog read(final Path path, final String expectedVersion) throws IOException {
		return read(Files.readAllBytes(path), expectedVersion);
	}

	/**
	 * Decodes one catalog stream. Refuses with an {@link IOException} a stream with another magic
	 * or format version, a Minecraft version other than {@code expectedVersion}, a duplicate inside
	 * shape, trailing bytes, or facts the catalog itself rejects.
	 */
	public static BlockStateCatalog read(final byte[] encoded, final String expectedVersion) throws IOException {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
			if (!in.readUTF().equals(MAGIC) || in.readInt() != FORMAT_VERSION) {
				throw new IOException("unsupported block catalog stream");
			}
			String version = in.readUTF();
			if (!version.equals(expectedVersion)) {
				throw new IOException("block catalog version mismatch");
			}
			int size = count(in, MAX_PALETTE_TEXT_BYTES);
			byte[] paletteText = in.readNBytes(size);
			if (paletteText.length != size) {
				throw new EOFException();
			}
			WorldSnapshot world = WorldSnapshotCodec.read(
			    new BufferedReader(new StringReader(new String(paletteText, StandardCharsets.UTF_8))));
			List<BlockStateCatalog.Cauldron> cauldrons = new ArrayList<>();
			for (int n = count(in, MAX_LIST_SIZE); n > 0; n--) {
				cauldrons.add(new BlockStateCatalog.Cauldron(in.readInt(), in.readInt(), boxes(in)));
			}
			Map<Integer, BlockStateCatalog.InsideShape> inside = new HashMap<>();
			for (int n = count(in, MAX_LIST_SIZE); n > 0; n--) {
				int id = in.readInt();
				boolean full = in.readBoolean();
				BlockStateCatalog.InsideShape shape = new BlockStateCatalog.InsideShape(boxes(in), full);
				if (inside.put(id, shape) != null) {
					throw new IOException("duplicate inside shape");
				}
			}
			if (in.read() != -1) {
				throw new IOException("trailing block catalog data");
			}
			return new BlockStateCatalog(version, world.palette(), cauldrons, inside);
		} catch (IllegalArgumentException invalid) {
			throw new IOException("invalid block catalog", invalid);
		}
	}

	private static List<ShapeBox> boxes(final DataInputStream in) throws IOException {
		List<ShapeBox> result = new ArrayList<>();
		for (int n = count(in, MAX_LIST_SIZE); n > 0; n--) {
			result.add(new ShapeBox(Double.longBitsToDouble(in.readLong()), Double.longBitsToDouble(in.readLong()),
			    Double.longBitsToDouble(in.readLong()), Double.longBitsToDouble(in.readLong()),
			    Double.longBitsToDouble(in.readLong()), Double.longBitsToDouble(in.readLong())));
		}
		return List.copyOf(result);
	}

	private static void boxes(final DataOutputStream out, final List<ShapeBox> boxes) throws IOException {
		out.writeInt(boxes.size());
		for (ShapeBox box : boxes) {
			for (double value : new double[] {box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()}) {
				out.writeLong(Double.doubleToRawLongBits(value));
			}
		}
	}

	private static int count(final DataInputStream in, final int max) throws IOException {
		int value = in.readInt();
		if (value < 0 || value > max) {
			throw new IOException("block catalog count out of range");
		}
		return value;
	}
}
