package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.PlayerInput;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes traces as tab-separated text with raw-bit fields.
 *
 * <p>Text rather than a packed binary format, on purpose: a consumer comparing two traces wants
 * to know which tick and which field first disagreed, and a format that {@code diff} and
 * {@code grep} can read makes that diagnosable without writing a tool first. Floating-point
 * fields are written as raw hexadecimal bits, so exactness is not traded away for legibility.
 * The layout is specified in {@code docs/trace-formats.md}.
 *
 * <p>Only {@link Trace#FORMAT_VERSION} is read; an older trace is refused with an
 * {@link IOException} naming its version.
 */
public final class TraceCodec {
	private static final String MAGIC = "#stride-trace";
	private static final String INITIAL_TICK = "init";
	private static final String NO_ACTION = "-";
	private static final String[] ACTION_COLUMNS = {"tick", "input", "yRot", "xRot"};
	/** The bits a packed input may carry: seven keys, {@link PlayerInput#ofPacked}. */
	private static final int PACKED_INPUT_MASK = 0x7f;

	private TraceCodec() {}

	/** Writes {@code trace} to {@code path} as UTF-8, replacing any existing file. */
	public static void write(final Trace trace, final Path path) throws IOException {
		try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			write(trace, writer);
		}
	}

	static void write(final Trace trace, final Writer writer) throws IOException {
		Trace.TraceHeader header = trace.header();
		writer.write(MAGIC + "\t" + Trace.FORMAT_VERSION + "\n");
		writer.write("#minecraft\t" + header.minecraftVersion() + "\n");
		writer.write("#producer\t" + header.producer().name() + "\n");
		writer.write("#scenario\t" + header.scenario() + "\n");

		StringBuilder columns = new StringBuilder(String.join("\t", ACTION_COLUMNS));
		for (StateField field : StateField.ALL) {
			columns.append('\t').append(field.name());
		}
		writer.write(columns.append('\n').toString());

		writer.write(INITIAL_TICK);
		for (int i = 1; i < ACTION_COLUMNS.length; i++) {
			writer.write('\t');
			writer.write(NO_ACTION);
		}
		writeState(writer, trace.initialState());

		for (Trace.TraceEntry entry : trace.entries()) {
			PlayerInput action = entry.action();
			writer.write(Integer.toString(entry.tick()));
			writer.write('\t');
			writer.write(hex(Byte.toUnsignedLong(action.packedInput()), 2));
			writer.write('\t');
			writer.write(hex(Integer.toUnsignedLong(Float.floatToRawIntBits(action.yRot())), 8));
			writer.write('\t');
			writer.write(hex(Integer.toUnsignedLong(Float.floatToRawIntBits(action.xRot())), 8));
			writeState(writer, entry.state());
		}
	}

	private static void writeState(final Writer writer, final StateVector state) throws IOException {
		for (StateField field : StateField.ALL) {
			writer.write('\t');
			writer.write(encode(field, state));
		}
		writer.write('\n');
	}

	private static String encode(final StateField field, final StateVector state) {
		long bits = state.rawBits(field);
		return switch (field.kind()) {
			case DOUBLE -> hex(bits, 16);
			case FLOAT -> hex(bits, 8);
			case BYTE_FLAGS -> hex(bits, 2);
			case BOOLEAN -> bits != 0L ? "1" : "0";
			case INT -> Integer.toString((int) bits);
			case ENUM -> bits + ":" + state.label(field);
		};
	}

	/**
	 * Reads the trace at {@code path}. Refuses with an {@link IOException} a file that is not a
	 * trace, carries another format version, names unknown columns, or has a malformed row.
	 */
	public static Trace read(final Path path) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return read(reader);
		}
	}

	/** The format version the trace at {@code path} carries, read from its first line only. */
	public static int formatVersion(final Path path) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return formatVersion(reader);
		}
	}

	private static int formatVersion(final BufferedReader reader) throws IOException {
		String magicLine = requireLine(reader, "magic");
		String[] magic = magicLine.split("\t", -1);
		if (magic.length != 2 || !MAGIC.equals(magic[0])) {
			throw new IOException("not a stride trace: " + magicLine);
		}
		return parseInt(magic[1], "format version");
	}

	static Trace read(final BufferedReader reader) throws IOException {
		int version = formatVersion(reader);
		if (version != Trace.FORMAT_VERSION) {
			throw new IOException("trace format version is " + version + "; this library reads version "
			    + Trace.FORMAT_VERSION + " only");
		}

		String minecraftVersion = headerValue(reader, "#minecraft");
		TraceProducer producer;
		try {
			producer = TraceProducer.valueOf(headerValue(reader, "#producer"));
		} catch (IllegalArgumentException invalidProducer) {
			throw new IOException("invalid trace producer", invalidProducer);
		}
		String scenario = headerValue(reader, "#scenario");

		String[] columns = requireLine(reader, "column header").split("\t", -1);
		int expectedColumns = ACTION_COLUMNS.length + StateField.ALL.size();
		if (columns.length != expectedColumns) {
			throw new IOException("trace has " + columns.length + " columns, expected " + expectedColumns);
		}
		for (int i = 0; i < ACTION_COLUMNS.length; i++) {
			if (!ACTION_COLUMNS[i].equals(columns[i])) {
				throw new IOException("column " + i + " is " + columns[i] + ", expected " + ACTION_COLUMNS[i]);
			}
		}
		for (int i = 0; i < StateField.ALL.size(); i++) {
			String expected = StateField.ALL.get(i).name();
			String actual = columns[ACTION_COLUMNS.length + i];
			if (!expected.equals(actual)) {
				// A same-version trace whose vector differs means one producer was built against
				// a different field set. Silently tolerating it would compare misaligned columns.
				throw new IOException(
				    "column " + (ACTION_COLUMNS.length + i) + " is " + actual + ", expected " + expected);
			}
		}

		StateVector initialState = null;
		List<Trace.TraceEntry> entries = new ArrayList<>();
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.isEmpty()) {
				continue;
			}
			String[] cells = line.split("\t", -1);
			if (cells.length != columns.length) {
				throw new IOException("row has " + cells.length + " cells, expected " + columns.length);
			}
			if (INITIAL_TICK.equals(cells[0])) {
				if (initialState != null) {
					throw new IOException("trace has more than one initial state row");
				}
				initialState = decodeState(cells);
			} else {
				int tick = parseInt(cells[0], "tick");
				PlayerInput action = decodeAction(cells[1], cells[2], cells[3]);
				entries.add(new Trace.TraceEntry(tick, action, decodeState(cells)));
			}
		}
		if (initialState == null) {
			throw new IOException("trace has no initial state row");
		}
		return new Trace(new Trace.TraceHeader(producer, minecraftVersion, scenario), initialState, entries);
	}

	/**
	 * Decodes the three action cells of a trace row: the packed input as two hex digits and the
	 * yaw and pitch as eight hex digits of raw float bits each. Refuses unknown input bits.
	 */
	public static PlayerInput decodeAction(final String input, final String yRot, final String xRot)
	    throws IOException {
		try {
			int flags = Integer.parseUnsignedInt(input, 16);
			if ((flags & ~PACKED_INPUT_MASK) != 0) {
				throw new IOException("packed action contains unknown input bits: " + input);
			}
			return PlayerInput.ofPacked((byte) flags, Float.intBitsToFloat(Integer.parseUnsignedInt(yRot, 16)),
			    Float.intBitsToFloat(Integer.parseUnsignedInt(xRot, 16)));
		} catch (NumberFormatException malformed) {
			throw new IOException("cannot decode packed action " + input + "," + yRot + "," + xRot, malformed);
		}
	}

	private static StateVector decodeState(final String[] cells) throws IOException {
		StateVector.Builder builder = StateVector.builder();
		for (int i = 0; i < StateField.ALL.size(); i++) {
			StateField field = StateField.ALL.get(i);
			String cell = cells[ACTION_COLUMNS.length + i];
			try {
				switch (field.kind()) {
					case DOUBLE, FLOAT, BYTE_FLAGS -> builder.setRawBits(field, Long.parseUnsignedLong(cell, 16), null);
					case BOOLEAN -> builder.setRawBits(field, parseBoolean(cell, field), null);
					case INT -> builder.setRawBits(field, Integer.parseInt(cell), null);
					case ENUM -> {
						int separator = cell.indexOf(':');
						if (separator < 0) {
							throw new IOException(field + " enum cell lacks a label: " + cell);
						}
						builder.setRawBits(
						    field, Long.parseLong(cell.substring(0, separator)), cell.substring(separator + 1));
					}
				}
			} catch (NumberFormatException malformed) {
				throw new IOException("cannot parse " + field + " cell " + cell, malformed);
			}
		}
		return builder.build();
	}

	private static long parseBoolean(final String cell, final StateField field) throws IOException {
		return switch (cell) {
			case "0" -> 0L;
			case "1" -> 1L;
			default -> throw new IOException(field + " cell must be 0 or 1: " + cell);
		};
	}

	private static int parseInt(final String value, final String what) throws IOException {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException malformed) {
			throw new IOException("invalid trace " + what + ": " + value, malformed);
		}
	}

	private static String headerValue(final BufferedReader reader, final String key) throws IOException {
		String line = requireLine(reader, key);
		String[] parts = line.split("\t", -1);
		if (parts.length != 2 || !key.equals(parts[0])) {
			throw new IOException("expected header " + key + ", got: " + line);
		}
		return parts[1];
	}

	private static String requireLine(final BufferedReader reader, final String what) throws IOException {
		String line = reader.readLine();
		if (line == null) {
			throw new IOException("trace ended before " + what);
		}
		return line;
	}

	private static String hex(final long value, final int digits) {
		String unpadded = Long.toHexString(value);
		return "0".repeat(Math.max(0, digits - unpadded.length())) + unpadded;
	}
}
