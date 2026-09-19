package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.Publisher;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.SimulationState;
import com.nettarion.stride.simulator.StateFields;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes one {@link SimulationState}, a boundary between two actions, as a binary
 * stream without rounding any scalar.
 *
 * <p>The stream is self-describing: every scalar of the client, publisher and server copies is
 * written as a name, a type label and a value, in the order generated from
 * {@code src/main/fields/state-fields.tsv}, so a reader in another language needs no Java
 * layout. The layout is specified in {@code docs/trace-formats.md}. Neither method closes its
 * stream.
 */
public final class SimulationStateCodec {
	/** The schema this library writes by default, the newest an archive can carry. */
	public static final int SCHEMA = StateFields.SCHEMA;

	/**
	 * The oldest schema still read and written. Schema 1 lacks the server's four damage game
	 * rules, which read as vanilla's defaults (all enabled).
	 */
	public static final int OLDEST_SCHEMA = 1;

	/** The most retained server movements a stream may declare. */
	private static final int MAX_RETAINED_MOVEMENTS = 99;

	/** The doubles of one {@code ServerPlayerState.Movement}. */
	private static final int MOVEMENT_DOUBLES = 8;

	private SimulationStateCodec() {}

	/** Writes {@code state} in {@link #SCHEMA}. */
	public static void write(final SimulationState state, final DataOutput out) throws IOException {
		write(state, out, SCHEMA);
	}

	/**
	 * Whether {@code schema} can carry {@code state} without loss: schema 1 holds only vanilla's
	 * default damage rules.
	 */
	public static boolean representable(final SimulationState state, final int schema) {
		ServerPlayerState server = state.serverState();
		return schema >= 2 || server.fallDamage && server.fireDamage && server.drowningDamage && server.freezeDamage;
	}

	/**
	 * Writes {@code state} at {@code schema}, so a fresh encoding compares byte for byte against
	 * an archive written at an older schema. Refuses with {@link IllegalArgumentException} an
	 * unsupported schema or a state the schema cannot carry.
	 */
	public static void write(final SimulationState state, final DataOutput out, final int schema) throws IOException {
		if (schema < OLDEST_SCHEMA || schema > SCHEMA) {
			throw new IllegalArgumentException("unsupported boundary schema " + schema);
		}
		if (!representable(state, schema)) {
			throw new IllegalArgumentException("schema " + schema + " cannot carry non-default damage rules");
		}
		out.writeInt(schema);
		writeScalars(state.clientState(), out);
		writeScalars(state.publisher(), out);
		writeScalars(state.serverState(), out, schema);
		List<ServerPlayerState.Movement> tail = state.serverState().additionalMovements();
		out.writeInt(tail.size());
		for (ServerPlayerState.Movement m : tail) {
			for (double value : new double[] {
			         m.fromX(), m.fromY(), m.fromZ(), m.toX(), m.toY(), m.toZ(), m.requestedX(), m.requestedZ()}) {
				out.writeLong(Double.doubleToRawLongBits(value));
			}
		}
		out.writeInt(state.completedActions());
		out.writeUTF(state.pendingHurt().map(Enum::name).orElse(""));
	}

	/**
	 * The schema an encoded stream was written at, from its first four bytes. Refuses with
	 * {@link IOException} a stream shorter than that or a schema this codec does not read.
	 */
	public static int peekSchema(final byte[] encoded) throws IOException {
		if (encoded.length < Integer.BYTES) {
			throw new EOFException("boundary stream is shorter than its schema");
		}
		int schema = new DataInputStream(new ByteArrayInputStream(encoded)).readInt();
		if (schema < OLDEST_SCHEMA || schema > SCHEMA) {
			throw new IOException("unsupported boundary schema " + schema);
		}
		return schema;
	}

	/**
	 * Reads one state, preserving raw floating-point bits, and validates it as it would be at a
	 * transition. Refuses with {@link IOException} an unsupported schema, a field inventory that
	 * differs from this library's, or a state that fails validation.
	 */
	public static SimulationState read(final DataInput in) throws IOException {
		int schema = in.readInt();
		if (schema < OLDEST_SCHEMA || schema > SCHEMA) {
			throw new IOException("unsupported boundary schema " + schema);
		}
		PlayerState client = new PlayerState();
		Publisher publisher = new Publisher();
		ServerPlayerState server = new ServerPlayerState();
		readScalars(client, in);
		readScalars(publisher, in);
		readScalars(server, in, schema);
		int count = in.readInt();
		if (count < 0 || count > MAX_RETAINED_MOVEMENTS) {
			throw new IOException("invalid retained movement count");
		}
		List<ServerPlayerState.Movement> tail = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			double[] values = new double[MOVEMENT_DOUBLES];
			for (int j = 0; j < MOVEMENT_DOUBLES; j++) {
				values[j] = Double.longBitsToDouble(in.readLong());
			}
			tail.add(new ServerPlayerState.Movement(
			    values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7]));
		}
		server.restoreAdditionalMovements(tail);
		int actions = in.readInt();
		String hurt = in.readUTF();
		try {
			// Every copy is admitted as it would be at a transition; the server's is by the
			// composed state below.
			client.requireValidForTransition();
			publisher.requireValid();
			if ((Byte.toUnsignedInt(client.sharedFlagsResidual) & ~SharedFlagBits.RESIDUAL_MASK) != 0
			    || (Byte.toUnsignedInt(server.sharedFlagsResidual) & ~SharedFlagBits.RESIDUAL_MASK) != 0) {
				throw new IllegalStateException("shared-flags residual carries a named bit");
			}
			return new SimulationState(
			    client, publisher, server, actions, hurt.isEmpty() ? null : HurtCause.valueOf(hurt));
		} catch (IllegalArgumentException | IllegalStateException invalid) {
			throw new IOException("invalid boundary", invalid);
		}
	}

	// The field inventory and wire labels come from the declared table, expanded into
	// StateFields; Java layout never defines the format.
	private static void writeScalars(final PlayerState s, final DataOutput out) throws IOException {
		out.writeInt(StateFields.wireCount(s));
		StateFields.write(s, out);
	}

	private static void readScalars(final PlayerState s, final DataInput in) throws IOException {
		if (in.readInt() != StateFields.wireCount(s)) {
			throw new IOException("boundary field count differs");
		}
		try {
			StateFields.read(s, in);
		} catch (IllegalArgumentException invalid) {
			throw new IOException("invalid boundary field", invalid);
		}
	}

	private static void writeScalars(final Publisher s, final DataOutput out) throws IOException {
		out.writeInt(StateFields.wireCount(s));
		StateFields.write(s, out);
	}

	private static void readScalars(final Publisher s, final DataInput in) throws IOException {
		if (in.readInt() != StateFields.wireCount(s)) {
			throw new IOException("boundary field count differs");
		}
		try {
			StateFields.read(s, in);
		} catch (IllegalArgumentException invalid) {
			throw new IOException("invalid boundary field", invalid);
		}
	}

	private static void writeScalars(final ServerPlayerState s, final DataOutput out, final int schema)
	    throws IOException {
		out.writeInt(StateFields.wireCount(s, schema));
		StateFields.write(s, out, schema);
	}

	private static void readScalars(final ServerPlayerState s, final DataInput in, final int schema)
	    throws IOException {
		if (in.readInt() != StateFields.wireCount(s, schema)) {
			throw new IOException("boundary field count differs");
		}
		try {
			StateFields.read(s, in, schema);
		} catch (IllegalArgumentException invalid) {
			throw new IOException("invalid boundary field", invalid);
		}
	}
}
