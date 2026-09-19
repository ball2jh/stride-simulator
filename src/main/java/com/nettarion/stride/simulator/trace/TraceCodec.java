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
 * <p>Text rather than a packed binary format, on purpose. The differential
 * gate's job is not only to answer "do these agree" but to say which tick and
 * which field first disagreed, and a format that {@code diff} and {@code grep}
 * can read makes a failing gate diagnosable without writing a tool first.
 * Floating-point fields are written as raw bits, so exactness is not traded
 * away for that legibility.
 */
public final class TraceCodec {
	private static final String MAGIC = "#stride-trace";
	private static final String INITIAL_TICK = "init";
	private static final String NO_ACTION = "-";
	private static final int ACTION_COLUMNS = 3;
	/**
	 * The oldest format still read. Formats 11 and 12 lack the movement
	 * attribute and the food and sprint attribute fields, which take the
	 * values every capture of theirs had; anything older is a recapture.
	 */
	static final int OLDEST_VERSION = 11;
	private static final int MOVEMENT_ATTRIBUTE_STATE_VERSION = 12;
	private static final int FOOD_AND_SPRINT_ATTRIBUTE_VERSION = 13;
	private static final int MOVEMENT_ATTRIBUTE_STATE_FIELDS = 2;
	private static final int FOOD_AND_SPRINT_ATTRIBUTE_FIELDS = 2;

	private TraceCodec() {}

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

		StringBuilder columns = new StringBuilder("tick\tinput\tyRot\txRot");
		for (StateField field : StateField.ALL) {
			columns.append('\t').append(field.name());
		}
		writer.write(columns.append('\n').toString());

		writer.write(INITIAL_TICK);
		for (int i = 0; i < ACTION_COLUMNS; i++) {
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

	/**
	 * One current-format state row: every {@link StateField#ALL} cell, tab
	 * separated, in the same encoding trace rows use. Other fixture kinds
	 * embed a state this way so one codec owns the raw-bit text form.
	 */
	public static String encodeState(final StateVector state) {
		StringBuilder row = new StringBuilder();
		for (StateField field : StateField.ALL) {
			if (!row.isEmpty()) {
				row.append('\t');
			}
			row.append(encode(field, state));
		}
		return row.toString();
	}

	/** Reads a row written by {@link #encodeState}; {@code cells[offset]} is the first field. */
	public static StateVector decodeState(final String[] cells, final int offset) throws IOException {
		if (cells.length - offset != StateField.ALL.length) {
			throw new IOException(
			    "state row has " + (cells.length - offset) + " cells, expected " + StateField.ALL.length);
		}
		String[] shifted = new String[ACTION_COLUMNS + 1 + StateField.ALL.length];
		System.arraycopy(cells, offset, shifted, ACTION_COLUMNS + 1, StateField.ALL.length);
		return decodeState(shifted, StateField.ALL.length, Trace.FORMAT_VERSION);
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

	public static Trace read(final Path path) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return read(reader);
		}
	}

	/** The format version a written trace carries, without decoding it. */
	public static int formatVersion(final Path path) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			String magicLine = requireLine(reader, "magic");
			String[] magic = magicLine.split("\t", -1);
			if (magic.length != 2 || !MAGIC.equals(magic[0])) {
				throw new IOException("not a stride trace: " + magicLine);
			}
			return Integer.parseInt(magic[1]);
		}
	}

	static Trace read(final BufferedReader reader) throws IOException {
		String magicLine = requireLine(reader, "magic");
		String[] magic = magicLine.split("\t", -1);
		if (magic.length != 2 || !MAGIC.equals(magic[0])) {
			throw new IOException("not a stride trace: " + magicLine);
		}
		int version = Integer.parseInt(magic[1]);
		if (version < OLDEST_VERSION || version > Trace.FORMAT_VERSION) {
			throw new IOException("trace format version " + version + ", expected " + OLDEST_VERSION + " through "
			    + Trace.FORMAT_VERSION + "; older traces must be recaptured");
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
		int stateFieldCount = StateField.ALL.length
		    - (version < FOOD_AND_SPRINT_ATTRIBUTE_VERSION ? FOOD_AND_SPRINT_ATTRIBUTE_FIELDS : 0)
		    - (version < MOVEMENT_ATTRIBUTE_STATE_VERSION ? MOVEMENT_ATTRIBUTE_STATE_FIELDS : 0);
		if (columns.length != ACTION_COLUMNS + 1 + stateFieldCount) {
			throw new IOException(
			    "trace has " + columns.length + " columns, expected " + (ACTION_COLUMNS + 1 + stateFieldCount));
		}
		for (int i = 0; i < stateFieldCount; i++) {
			String expected = StateField.ALL[i].name();
			String actual = columns[ACTION_COLUMNS + 1 + i];
			if (!expected.equals(actual)) {
				// A same-version trace whose vector differs means one producer
				// was built against a different audited field set. Silently
				// tolerating it would compare misaligned columns.
				throw new IOException("column " + i + " is " + actual + ", expected " + expected);
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
				initialState = decodeState(cells, stateFieldCount, version);
			} else {
				int tick = Integer.parseInt(cells[0]);
				PlayerInput action = decodeAction(cells[1], cells[2], cells[3]);
				StateVector state = decodeState(cells, stateFieldCount, version);
				entries.add(new Trace.TraceEntry(tick, action, state));
			}
		}
		if (initialState == null) {
			throw new IOException("trace has no initial state row");
		}
		return new Trace(new Trace.TraceHeader(producer, minecraftVersion, scenario, version), initialState, entries);
	}

	/** Decodes the three packed-action cells used by trace-derived TSV formats. */
	public static PlayerInput decodeAction(final String input, final String yRot, final String xRot)
	    throws IOException {
		try {
			int flags = Integer.parseUnsignedInt(input, 16);
			if ((flags & ~0x7f) != 0) {
				throw new IOException("packed action contains unknown input bits: " + input);
			}
			return PlayerInput.ofPacked((byte) flags, Float.intBitsToFloat(Integer.parseUnsignedInt(yRot, 16)),
			    Float.intBitsToFloat(Integer.parseUnsignedInt(xRot, 16)));
		} catch (NumberFormatException malformed) {
			throw new IOException("cannot decode packed action " + input + "," + yRot + "," + xRot, malformed);
		}
	}

	private static StateVector decodeState(final String[] cells, final int stateFieldCount, final int version)
	    throws IOException {
		StateVector.Builder builder = StateVector.builder();
		for (int i = 0; i < stateFieldCount; i++) {
			StateField field = StateField.ALL[i];
			String cell = cells[ACTION_COLUMNS + 1 + i];
			try {
				switch (field.kind()) {
					case DOUBLE, FLOAT, BYTE_FLAGS -> builder.setRawBits(field, Long.parseUnsignedLong(cell, 16), null);
					case BOOLEAN -> builder.setRawBits(field, "1".equals(cell) ? 1L : 0L, null);
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
		if (version < MOVEMENT_ATTRIBUTE_STATE_VERSION) {
			builder.set(StateField.MOVEMENT_SPEED_MULTIPLIER, 1.0).set(StateField.JUMP_BOOST_POWER, 0.0F);
		}
		if (version < FOOD_AND_SPRINT_ATTRIBUTE_VERSION) {
			// Earlier admission required enough food and coupled the sprint attribute to bit 3.
			builder
			    .set(StateField.SPRINTING_ATTRIBUTE,
			        "1".equals(cells[ACTION_COLUMNS + 1 + StateField.SPRINTING.ordinal()]))
			    .set(StateField.FOOD_LEVEL, 20);
		}
		return builder.build();
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
