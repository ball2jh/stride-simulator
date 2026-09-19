package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.*;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.Publisher;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.SimulationState;
import java.io.*;
import java.util.*;

/** Serializes a declared transport-empty starting boundary without rounding any scalar state. */
public final class BoundaryCodec {
	/** Schema 2 adds the server's four damage gamerules; schema 1 files read them as vanilla's defaults. */
	private static final int SCHEMA = StateFields.SCHEMA;

	private BoundaryCodec() {}

	/** Writes the complete boundary in the current binary schema without closing the output. */
	public static void write(final SimulationState state, final DataOutput out) throws IOException {
		write(state, out, SCHEMA);
	}

	/** The current schema, the newest an archive can carry. */
	public static int schema() {
		return SCHEMA;
	}

	/** Whether {@code schema} can carry {@code state} without loss: schema 1 holds only vanilla's default damage rules. */
	public static boolean representable(final SimulationState state, final int schema) {
		var s = state.serverState();
		return schema >= 2 || s.fallDamage && s.fireDamage && s.drowningDamage && s.freezeDamage;
	}

	/**
     * Encode at an older schema so fresh evidence compares byte for byte against an archive
     * written at it; refuses a state the schema cannot carry.
     */
	public static void write(final SimulationState state, final DataOutput out, final int schema) throws IOException {
		if (schema != 1 && schema != SCHEMA)
			throw new IllegalArgumentException("unsupported boundary schema " + schema);
		if (!representable(state, schema))
			throw new IllegalArgumentException("schema " + schema + " cannot carry non-default damage rules");
		out.writeInt(schema);
		writeScalars(state.clientState(), out);
		writeScalars(state.publisher(), out);
		writeScalars(state.serverState(), out, schema);
		var tail = state.serverState().additionalMovements();
		out.writeInt(tail.size());
		for (var m : tail)
			for (double value : new double[] {
			         m.fromX(), m.fromY(), m.fromZ(), m.toX(), m.toY(), m.toZ(), m.requestedX(), m.requestedZ()})
				out.writeLong(Double.doubleToRawLongBits(value));
		out.writeInt(state.completedActions());
		out.writeUTF(state.pendingHurt().map(Enum::name).orElse(""));
	}

	/** Reads and validates one complete boundary, preserving raw floating-point bits; does not close the input. */
	public static SimulationState read(final DataInput in) throws IOException {
		int schema = in.readInt();
		if (schema != 1 && schema != SCHEMA) throw new IOException("unsupported boundary schema");
		var client = new PlayerState();
		var publisher = new Publisher();
		var server = new ServerPlayerState();
		readScalars(client, in);
		readScalars(publisher, in);
		readScalars(server, in, schema);
		int count = in.readInt();
		if (count < 0 || count > 99) throw new IOException("invalid retained movement count");
		var tail = new ArrayList<ServerPlayerState.Movement>();
		for (int i = 0; i < count; i++) {
			double[] values = new double[8];
			for (int j = 0; j < 8; j++)
				values[j] = Double.longBitsToDouble(in.readLong());
			tail.add(new ServerPlayerState.Movement(
			    values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7]));
		}
		server.restoreAdditionalMovements(tail);
		int actions = in.readInt();
		String hurt = in.readUTF();
		try {
			// Every copy is admitted as it would be at a transition; the server's
			// is by the composed state below.
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

	// The field inventory and wire labels come from the declared table,
	// expanded into StateFields; Java layout never defines the format.
	private static void writeScalars(final PlayerState s, final DataOutput out) throws IOException {
		out.writeInt(StateFields.wireCount(s));
		StateFields.write(s, out);
	}

	private static void readScalars(final PlayerState s, final DataInput in) throws IOException {
		if (in.readInt() != StateFields.wireCount(s)) throw new IOException("boundary field count differs");
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
		if (in.readInt() != StateFields.wireCount(s)) throw new IOException("boundary field count differs");
		StateFields.read(s, in);
	}

	private static void writeScalars(final ServerPlayerState s, final DataOutput out, final int schema)
	    throws IOException {
		out.writeInt(StateFields.wireCount(s, schema));
		StateFields.write(s, out, schema);
	}

	private static void readScalars(final ServerPlayerState s, final DataInput in, final int schema)
	    throws IOException {
		if (in.readInt() != StateFields.wireCount(s, schema)) throw new IOException("boundary field count differs");
		try {
			StateFields.read(s, in, schema);
		} catch (IllegalArgumentException invalid) {
			throw new IOException("invalid boundary field", invalid);
		}
	}
}
