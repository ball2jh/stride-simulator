package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.*;
import com.nettarion.stride.simulator.world.BlockStateCatalog;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Portable independently extracted source records accompanying a captured world. */
public final class BlockStateCatalogCodec {
	private BlockStateCatalogCodec() {}
	public static void write(final BlockStateCatalog catalog, final Path path) throws IOException {
		Files.write(path, bytes(catalog));
	}
	/** Canonical source bytes used both for sidecars and trace provenance fingerprints. */
	public static byte[] bytes(final BlockStateCatalog catalog) throws IOException {
		var buffer = new ByteArrayOutputStream();
		var out = new DataOutputStream(buffer);

		out.writeUTF("stride-block-source");
		out.writeInt(1);
		out.writeUTF(catalog.minecraftVersion());
		var snapshot = new WorldSnapshot(
		    0, 0, 0, 1, 1, 1, WorldSnapshot.OutsideRegion.ROLLOUT_TERMINATING, catalog.entries(), new int[] {0});
		var text = new StringWriter();
		WorldSnapshotCodec.write(snapshot, text);
		byte[] bytes = text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
		out.writeInt(bytes.length);
		out.write(bytes);
		out.writeInt(catalog.cauldrons().size());
		for (var cauldron : catalog.cauldrons()) {
			out.writeInt(cauldron.blockStateId());
			out.writeInt(cauldron.successorStateId());
			boxes(out, cauldron.insideBoxes());
		}
		out.writeInt(catalog.insideShapes().size());
		for (var entry : new TreeMap<>(catalog.insideShapes()).entrySet()) {
			out.writeInt(entry.getKey());
			out.writeBoolean(entry.getValue().canonicalFull());
			boxes(out, entry.getValue().boxes());
		}
		return buffer.toByteArray();
	}

	public static BlockStateCatalog read(final Path path, final String expectedVersion) throws IOException {
		return read(Files.readAllBytes(path), expectedVersion);
	}
	/** Decodes independent source records without any simulator execution. */
	public static BlockStateCatalog read(final byte[] encoded, final String expectedVersion) throws IOException {
		try (var in = new DataInputStream(new ByteArrayInputStream(encoded))) {
			if (!in.readUTF().equals("stride-block-source") || in.readInt() != 1)
				throw new IOException("unsupported block source schema");
			String version = in.readUTF();
			if (!version.equals(expectedVersion)) throw new IOException("block source version mismatch");
			int size = count(in, 64 * 1024 * 1024);
			byte[] bytes = in.readNBytes(size);
			if (bytes.length != size) throw new EOFException();
			var world = WorldSnapshotCodec.read(
			    new BufferedReader(new StringReader(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))));
			var cauldrons = new ArrayList<BlockStateCatalog.Cauldron>();
			for (int n = count(in, 100000); n > 0; n--)
				cauldrons.add(new BlockStateCatalog.Cauldron(in.readInt(), in.readInt(), boxes(in)));
			var inside = new HashMap<Integer, BlockStateCatalog.InsideShape>();
			for (int n = count(in, 100000); n > 0; n--) {
				int id = in.readInt();
				boolean full = in.readBoolean();
				var shape = new BlockStateCatalog.InsideShape(boxes(in), full);
				if (inside.put(id, shape) != null) throw new IOException("duplicate source shape");
			}
			if (in.read() != -1) throw new IOException("trailing block source data");
			return new BlockStateCatalog(version, world.palette(), cauldrons, inside);
		} catch (IllegalArgumentException invalid) {
			throw new IOException("invalid source records", invalid);
		}
	}
	private static List<WorldSnapshot.ShapeBox> boxes(final DataInputStream in) throws IOException {
		var result = new ArrayList<WorldSnapshot.ShapeBox>();
		for (int n = count(in, 100000); n > 0; n--)
			result.add(new WorldSnapshot.ShapeBox(Double.longBitsToDouble(in.readLong()),
			    Double.longBitsToDouble(in.readLong()), Double.longBitsToDouble(in.readLong()),
			    Double.longBitsToDouble(in.readLong()), Double.longBitsToDouble(in.readLong()),
			    Double.longBitsToDouble(in.readLong())));
		return List.copyOf(result);
	}
	private static void boxes(final DataOutputStream out, final List<WorldSnapshot.ShapeBox> boxes) throws IOException {
		out.writeInt(boxes.size());
		for (var b : boxes)
			for (double v : new double[] {b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()})
				out.writeLong(Double.doubleToRawLongBits(v));
	}
	private static int count(final DataInputStream in, final int max) throws IOException {
		int value = in.readInt();
		if (value < 0 || value > max) throw new IOException("source count out of range");
		return value;
	}
}
