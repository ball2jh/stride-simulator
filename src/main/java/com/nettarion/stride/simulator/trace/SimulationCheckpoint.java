package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.BlockUpdateWrite;
import com.nettarion.stride.simulator.DamageEvent;
import com.nettarion.stride.simulator.DamageWrite;
import com.nettarion.stride.simulator.EntityDataWrite;
import com.nettarion.stride.simulator.ExternalActor;
import com.nettarion.stride.simulator.HealthWrite;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.HurtMotion;
import com.nettarion.stride.simulator.HurtMotionWrite;
import com.nettarion.stride.simulator.ImpulseWrite;
import com.nettarion.stride.simulator.Interaction;
import com.nettarion.stride.simulator.MovementPacket;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerPositionWrite;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.ScheduledSimulation;
import com.nettarion.stride.simulator.ServerWrite;
import com.nettarion.stride.simulator.SimulationState;
import com.nettarion.stride.simulator.world.WorldSnapshot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Encodes the complete raw state of a {@link ScheduledSimulation} after one event: both player
 * copies, the publisher, the packets in transit, the server writes so far, and both world
 * overlays.
 *
 * <p>The stream is a {@link SimulationStateCodec} boundary followed by a tagged value tree and
 * two world snapshots, all specified in {@code docs/trace-formats.md}. Every value is tagged with
 * a short stable {@link Tag} and every record field is preceded by its name, so a reader in
 * another language can decode a checkpoint without this library and
 * {@link #firstDifference(byte[], byte[])} can name the first field two checkpoints disagree on.
 */
public final class SimulationCheckpoint {
	/** The most bytes an embedded world text may occupy: 64 MiB. */
	private static final int MAX_WORLD_TEXT_BYTES = 64 * 1024 * 1024;

	/** The most elements a list may declare. */
	private static final int MAX_LIST_SIZE = 1_000_000;

	private SimulationCheckpoint() {}

	/**
	 * The wire tag of every value a checkpoint can hold. The wire string is fixed by the format;
	 * renaming a Java class never changes it.
	 */
	enum Tag {
		/** A null reference. */
		NULL("null", Shape.NULL),
		/** A list: an int count then that many values. */
		LIST("list", Shape.LIST),
		/** A double, raw bits as one long. */
		DOUBLE("d", Shape.LONG),
		/** A float, raw bits as one int. */
		FLOAT("f", Shape.INT),
		/** An int. */
		INT("i", Shape.INT),
		/** A long. */
		LONG("l", Shape.LONG),
		/** A byte. */
		BYTE("b", Shape.BYTE),
		/** A boolean, one byte. */
		BOOLEAN("z", Shape.BOOLEAN),
		/** A string, {@code writeUTF}. */
		STRING("s", Shape.UTF),
		/** {@code ExternalActor.Kind} by name. */
		EXTERNAL_ACTOR_KIND("external-actor-kind", Shape.UTF),
		/** {@code HurtCause} by name. */
		HURT_CAUSE("hurt-cause", Shape.UTF),
		/** {@code ImpulseWrite.Operation} by name. */
		IMPULSE_OPERATION("impulse-operation", Shape.UTF),
		/** {@code ImpulseWrite.Phase} by name. */
		IMPULSE_PHASE("impulse-phase", Shape.UTF),
		/** {@code MovementPacket} by name. */
		MOVEMENT_PACKET("movement-packet", Shape.UTF),
		/** {@code PlayerState.Pose} by name. */
		POSE("pose", Shape.UTF),
		/** {@code BlockUpdateWrite}. */
		BLOCK_UPDATE_WRITE("block-update-write", "actionIndex", "x", "y", "z", "paletteIndex"),
		/** {@code DamageEvent}. */
		DAMAGE_EVENT("damage-event", "cause", "attempted", "absorbed", "healthDamage", "healthAfter", "full"),
		/** {@code DamageWrite}. */
		DAMAGE_WRITE("damage-write", "actionIndex", "event"),
		/** {@code EntityDataWrite}. */
		ENTITY_DATA_WRITE("entity-data-write", "actionIndex", "beforeDigest", "dirty", "sharedFlags", "pose",
		    "ticksFrozen", "frostSpeedTicks", "sprintingAttribute"),
		/** {@code ExternalActor}. */
		EXTERNAL_ACTOR("external-actor", "kind", "entityId"),
		/** {@code HealthWrite}. */
		HEALTH_WRITE("health-write", "actionIndex", "beforeDigest", "health", "foodLevel", "saturationLevel"),
		/** {@code HurtMotion}. */
		HURT_MOTION("hurt-motion", "cause", "causeAction", "expectedStateDigest", "writeAfterAction", "writeX",
		    "writeY", "writeZ"),
		/** {@code HurtMotionWrite}. */
		HURT_MOTION_WRITE("hurt-motion-write", "actionIndex", "event"),
		/** {@code ImpulseWrite}. */
		IMPULSE_WRITE("impulse-write", "actionIndex", "actor", "sequence", "phase", "operation", "x", "y", "z"),
		/** {@code PlayerInput}. */
		PLAYER_INPUT("player-input", "forward", "backward", "left", "right", "jump", "sneak", "sprint", "yRot", "xRot"),
		/** {@code PlayerPositionWrite}. */
		PLAYER_POSITION_WRITE(
		    "player-position-write", "actionIndex", "beforeDigest", "teleportId", "x", "y", "z", "yRot", "xRot"),
		/** {@code ScheduledSimulation.AcceptTeleportPacket}. */
		ACCEPT_TELEPORT("accept-teleport", "id"),
		/** {@code ScheduledSimulation.BlockUpdatePacket}. */
		BLOCK_UPDATE("block-update", "write"),
		/** {@code ScheduledSimulation.ClientTickEnd}. */
		CLIENT_TICK_END("client-tick-end"),
		/** {@code ScheduledSimulation.EntityDataPacket}. */
		DATA("data", "value"),
		/** {@code ScheduledSimulation.GlidePacket}. */
		GLIDE("glide"),
		/** {@code ScheduledSimulation.HealthPacket}. */
		HEALTH("health", "health", "food", "saturation"),
		/** {@code ScheduledSimulation.InputPacket}. */
		INPUT("input", "input"),
		/** {@code ScheduledSimulation.MotionPacket}. */
		MOTION("motion", "cause", "action", "x", "y", "z"),
		/** {@code ScheduledSimulation.MovePacket}. */
		MOVE_PACKET("move-packet", "form", "x", "y", "z", "yRot", "xRot", "onGround", "horizontalCollision"),
		/** {@code ScheduledSimulation.PositionPacket}. */
		POSITION("position", "id", "x", "y", "z", "yRot", "xRot"),
		/** {@code ScheduledSimulation.SprintPacket}. */
		SPRINT("sprint", "sprinting"),
		/** {@code ScheduledSimulation.Transport}. */
		TRANSPORT("transport", "serverbound", "clientbound", "pendingHurt", "hurtAction", "mayInteract",
		    "receivedMovementThisTick");

		private static final Map<String, Tag> BY_WIRE = new HashMap<>();

		static {
			for (Tag tag : values()) {
				BY_WIRE.put(tag.wire, tag);
			}
		}

		private final String wire;
		private final Shape shape;
		private final List<String> fields;

		Tag(final String wire, final Shape shape) {
			this.wire = wire;
			this.shape = shape;
			this.fields = List.of();
		}

		Tag(final String wire, final String... fields) {
			this.wire = wire;
			this.shape = Shape.RECORD;
			this.fields = List.of(fields);
		}

		/** The string written to the stream. */
		String wire() {
			return this.wire;
		}

		/** How the value after the tag is laid out. */
		Shape shape() {
			return this.shape;
		}

		/** The field names of a record tag, in stream order; empty for other shapes. */
		List<String> fields() {
			return this.fields;
		}

		static Tag of(final String wire) throws IOException {
			Tag tag = BY_WIRE.get(wire);
			if (tag == null) {
				throw new IOException("unknown checkpoint tag " + wire);
			}
			return tag;
		}
	}

	/** The layout of the bytes following a {@link Tag}. */
	enum Shape {
		/** Nothing follows. */
		NULL,
		/** An int count, then that many tagged values. */
		LIST,
		/** Eight bytes. */
		LONG,
		/** Four bytes. */
		INT,
		/** One byte. */
		BYTE,
		/** One byte, 0 or 1. */
		BOOLEAN,
		/** A {@code writeUTF} string. */
		UTF,
		/** For each field: its name as {@code writeUTF}, then a tagged value. */
		RECORD
	}

	/**
	 * The complete raw state of a scheduled simulation after one event. A checkpoint can also be
	 * built by an independent observer from decoded packets; constructing it runs no mechanics.
	 *
	 * @param players both player copies and the publisher; the pending hurt is carried by {@code transport}
	 * @param transport the packets in transit and their attribution
	 * @param writes every server write recorded so far, in delivery order
	 * @param clientWorld the world as the client sees it
	 * @param serverWorld the world as the server sees it
	 */
	public record Checkpoint(SimulationState players, ScheduledSimulation.Transport transport, List<ServerWrite> writes,
	    WorldSnapshot clientWorld, WorldSnapshot serverWorld) {
		/** Copies the write list; refuses a null component. */
		public Checkpoint {
			Objects.requireNonNull(players, "players");
			Objects.requireNonNull(transport, "transport");
			writes = List.copyOf(writes);
			Objects.requireNonNull(clientWorld, "clientWorld");
			Objects.requireNonNull(serverWorld, "serverWorld");
		}

		/** The stream bytes at {@link SimulationStateCodec#SCHEMA}. */
		public byte[] encode() {
			return encode(SimulationStateCodec.SCHEMA);
		}

		/**
		 * The stream bytes with the boundary at {@code schema}; refuses with
		 * {@link IllegalArgumentException} a schema the boundary cannot carry.
		 */
		public byte[] encode(final int schema) {
			try {
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				DataOutputStream out = new DataOutputStream(bytes);
				SimulationStateCodec.write(new SimulationState(this.players.clientState(), this.players.publisher(),
				                               this.players.serverState(), this.players.completedActions(), null),
				    out, schema);
				value(this.transport, out);
				value(this.writes, out);
				world(this.clientWorld, out);
				world(this.serverWorld, out);
				return bytes.toByteArray();
			} catch (IOException failure) {
				throw new UncheckedIOException(failure);
			}
		}
	}

	/** The checkpoint of {@code simulation} now; copies every part, so the simulation is not shared. */
	public static Checkpoint capture(final ScheduledSimulation simulation) {
		return new Checkpoint(new SimulationState(simulation.clientState(), simulation.publisher(),
		                          simulation.serverState(), simulation.completedActions(), null),
		    simulation.transportState(), simulation.writes(), simulation.clientWorld().materializedSnapshot(),
		    simulation.serverWorld().materializedSnapshot());
	}

	/**
	 * The checkpoint of {@code simulation} encoded at the boundary schema {@code archived} was
	 * written at, so an older archive compares byte for byte against what it could record. A
	 * state the older schema cannot carry, or an unreadable archive, is encoded at the current
	 * schema and the comparison fails honestly.
	 */
	public static byte[] encodeLike(final byte[] archived, final ScheduledSimulation simulation) {
		Checkpoint current = capture(simulation);
		int schema;
		try {
			schema = SimulationStateCodec.peekSchema(archived);
		} catch (IOException unreadable) {
			return current.encode();
		}
		if (!SimulationStateCodec.representable(current.players(), schema)) {
			return current.encode();
		}
		return current.encode(schema);
	}

	/**
	 * Names the first value at which two encoded checkpoints differ, or empty when the bytes are
	 * equal. The description carries the field path, for example
	 * {@code boundary.client.deltaMovementY} or {@code transport.serverbound[2].x}, and both
	 * values. A checkpoint that cannot be decoded is reported as such rather than refused.
	 */
	public static Optional<String> firstDifference(final byte[] expected, final byte[] actual) {
		if (Arrays.equals(expected, actual)) {
			return Optional.empty();
		}
		List<String> expectedValues;
		List<String> actualValues;
		try {
			expectedValues = flatten(expected);
		} catch (IOException undecodable) {
			return Optional.of("expected checkpoint is undecodable: " + undecodable.getMessage());
		}
		try {
			actualValues = flatten(actual);
		} catch (IOException undecodable) {
			return Optional.of("actual checkpoint is undecodable: " + undecodable.getMessage());
		}
		int shared = Math.min(expectedValues.size(), actualValues.size());
		for (int i = 0; i < shared; i++) {
			if (!expectedValues.get(i).equals(actualValues.get(i))) {
				return Optional.of(expectedValues.get(i).replaceFirst("=.*", "") + ": expected "
				    + expectedValues.get(i).replaceFirst("^[^=]*=", "") + ", actual "
				    + actualValues.get(i).replaceFirst("^[^=]*=", ""));
			}
		}
		if (expectedValues.size() != actualValues.size()) {
			return Optional.of("checkpoints hold " + expectedValues.size() + " and " + actualValues.size()
			    + " values; the first " + shared + " agree");
		}
		return Optional.of("checkpoints differ only in bytes their decoded values do not show");
	}

	private static void world(final WorldSnapshot snapshot, final DataOutputStream out) throws IOException {
		StringWriter text = new StringWriter();
		WorldSnapshotCodec.write(snapshot, text);
		byte[] data = text.toString().getBytes(StandardCharsets.UTF_8);
		out.writeInt(data.length);
		out.write(data);
	}

	private static void record(final DataOutputStream out, final Tag tag, final Object... values) throws IOException {
		if (values.length != tag.fields().size()) {
			throw new AssertionError(tag + " declares " + tag.fields().size() + " fields, given " + values.length);
		}
		out.writeUTF(tag.wire());
		for (int i = 0; i < values.length; i++) {
			out.writeUTF(tag.fields().get(i));
			value(values[i], out);
		}
	}

	private static void name(final DataOutputStream out, final Tag tag, final Enum<?> value) throws IOException {
		out.writeUTF(tag.wire());
		out.writeUTF(value.name());
	}

	/** Refuses with {@link IllegalArgumentException} a value outside the declared tag table. */
	private static void value(final Object value, final DataOutputStream out) throws IOException {
		switch (value) {
			case null -> out.writeUTF(Tag.NULL.wire());
			case List<?> list -> {
				out.writeUTF(Tag.LIST.wire());
				out.writeInt(list.size());
				for (Object item : list) {
					value(item, out);
				}
			}
			case Double v -> {
				out.writeUTF(Tag.DOUBLE.wire());
				out.writeLong(Double.doubleToRawLongBits(v));
			}
			case Float v -> {
				out.writeUTF(Tag.FLOAT.wire());
				out.writeInt(Float.floatToRawIntBits(v));
			}
			case Integer v -> {
				out.writeUTF(Tag.INT.wire());
				out.writeInt(v);
			}
			case Long v -> {
				out.writeUTF(Tag.LONG.wire());
				out.writeLong(v);
			}
			case Byte v -> {
				out.writeUTF(Tag.BYTE.wire());
				out.writeByte(v);
			}
			case Boolean v -> {
				out.writeUTF(Tag.BOOLEAN.wire());
				out.writeBoolean(v);
			}
			case String v -> {
				out.writeUTF(Tag.STRING.wire());
				out.writeUTF(v);
			}
			case ExternalActor.Kind v -> name(out, Tag.EXTERNAL_ACTOR_KIND, v);
			case HurtCause v -> name(out, Tag.HURT_CAUSE, v);
			case ImpulseWrite.Operation v -> name(out, Tag.IMPULSE_OPERATION, v);
			case ImpulseWrite.Phase v -> name(out, Tag.IMPULSE_PHASE, v);
			case MovementPacket v -> name(out, Tag.MOVEMENT_PACKET, v);
			case PlayerState.Pose v -> name(out, Tag.POSE, v);
			case BlockUpdateWrite v ->
				record
				(out, Tag.BLOCK_UPDATE_WRITE, v.actionIndex(), v.x(), v.y(), v.z(), v.paletteIndex());
			case DamageEvent v ->
				record
				(out, Tag.DAMAGE_EVENT, v.cause(), v.attempted(), v.absorbed(), v.healthDamage(), v.healthAfter(),
				    v.full());
			case DamageWrite v -> record (out, Tag.DAMAGE_WRITE, v.actionIndex(), v.event());
			case EntityDataWrite v ->
				record
				(out, Tag.ENTITY_DATA_WRITE, v.actionIndex(), v.beforeDigest(), v.dirty(), v.sharedFlags(), v.pose(),
				    v.ticksFrozen(), v.frostSpeedTicks(), v.sprintingAttribute());
			case ExternalActor v -> record (out, Tag.EXTERNAL_ACTOR, v.kind(), v.entityId());
			case HealthWrite v ->
				record
				(out, Tag.HEALTH_WRITE, v.actionIndex(), v.beforeDigest(), v.health(), v.foodLevel(),
				    v.saturationLevel());
			case HurtMotion v ->
				record
				(out, Tag.HURT_MOTION, v.cause(), v.causeAction(), v.expectedStateDigest(), v.writeAfterAction(),
				    v.writeX(), v.writeY(), v.writeZ());
			case HurtMotionWrite v -> record (out, Tag.HURT_MOTION_WRITE, v.actionIndex(), v.event());
			case ImpulseWrite v ->
				record
				(out, Tag.IMPULSE_WRITE, v.actionIndex(), v.actor(), v.sequence(), v.phase(), v.operation(), v.x(),
				    v.y(), v.z());
			case PlayerInput v ->
				record
				(out, Tag.PLAYER_INPUT, v.forward(), v.backward(), v.left(), v.right(), v.jump(), v.sneak(), v.sprint(),
				    v.yRot(), v.xRot());
			case PlayerPositionWrite v ->
				record
				(out, Tag.PLAYER_POSITION_WRITE, v.actionIndex(), v.beforeDigest(), v.teleportId(), v.x(), v.y(), v.z(),
				    v.yRot(), v.xRot());
			case ScheduledSimulation.AcceptTeleportPacket v -> record (out, Tag.ACCEPT_TELEPORT, v.id());
			case ScheduledSimulation.BlockUpdatePacket v -> record (out, Tag.BLOCK_UPDATE, v.write());
			case ScheduledSimulation.ClientTickEnd _ -> record (out, Tag.CLIENT_TICK_END);
			case ScheduledSimulation.EntityDataPacket v -> record (out, Tag.DATA, v.value());
			case ScheduledSimulation.GlidePacket _ -> record (out, Tag.GLIDE);
			case ScheduledSimulation.HealthPacket v -> record (out, Tag.HEALTH, v.health(), v.food(), v.saturation());
			case ScheduledSimulation.InputPacket v -> record (out, Tag.INPUT, v.input());
			case ScheduledSimulation.MotionPacket v ->
				record
				(out, Tag.MOTION, v.cause(), v.action(), v.x(), v.y(), v.z());
			case ScheduledSimulation.MovePacket v ->
				record
				(out, Tag.MOVE_PACKET, v.form(), v.x(), v.y(), v.z(), v.yRot(), v.xRot(), v.onGround(),
				    v.horizontalCollision());
			case ScheduledSimulation.PositionPacket v ->
				record
				(out, Tag.POSITION, v.id(), v.x(), v.y(), v.z(), v.yRot(), v.xRot());
			case ScheduledSimulation.SprintPacket v -> record (out, Tag.SPRINT, v.sprinting());
			case ScheduledSimulation.Transport v ->
				record
				(out, Tag.TRANSPORT, v.serverbound(), v.clientbound(), v.pendingHurt(), v.hurtAction(),
				    mayInteract(v.interaction()), v.receivedMovementThisTick());
			default ->
				throw new IllegalArgumentException(
				    "type is outside the declared checkpoint schema: " + value.getClass());
		}
	}

	/** The wire form of the branch's permission: a boolean, or null for {@link Interaction#UNDECLARED}. */
	private static Boolean mayInteract(final Interaction interaction) {
		return switch (interaction) {
			case ALLOWED -> Boolean.TRUE;
			case DENIED -> Boolean.FALSE;
			case UNDECLARED -> null;
		};
	}

	/** Every value of an encoded checkpoint as {@code path=value} lines, in stream order. */
	static List<String> flatten(final byte[] encoded) throws IOException {
		List<String> values = new ArrayList<>();
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
			flattenBoundary(in, values);
			flattenValue(in, "transport", values);
			flattenValue(in, "writes", values);
			flattenWorld(in, "clientWorld", values);
			flattenWorld(in, "serverWorld", values);
			if (in.read() != -1) {
				throw new IOException("trailing checkpoint data");
			}
		}
		return values;
	}

	private static void flattenBoundary(final DataInputStream in, final List<String> values) throws IOException {
		int schema = in.readInt();
		values.add("boundary.schema=" + schema);
		for (String copy : new String[] {"client", "publisher", "server"}) {
			int count = in.readInt();
			if (count < 0) {
				throw new IOException("negative boundary field count");
			}
			for (int i = 0; i < count; i++) {
				String name = in.readUTF();
				String type = in.readUTF();
				values.add("boundary." + copy + "." + name + "=" + scalar(in, type));
			}
		}
		int movements = in.readInt();
		if (movements < 0) {
			throw new IOException("negative retained movement count");
		}
		for (int i = 0; i < movements; i++) {
			StringBuilder movement = new StringBuilder();
			for (int j = 0; j < 8; j++) {
				movement.append(j == 0 ? "" : ",").append(hex(in.readLong()));
			}
			values.add("boundary.server.additionalMovements[" + i + "]=" + movement);
		}
		values.add("boundary.completedActions=" + in.readInt());
		values.add("boundary.pendingHurt=" + in.readUTF());
	}

	/** Decodes one boundary scalar by its type label; any label not listed is an enum name. */
	private static String scalar(final DataInputStream in, final String type) throws IOException {
		return switch (type) {
			case "double", "long" -> hex(in.readLong());
			case "float", "int" -> hex(in.readInt());
			case "boolean" -> Boolean.toString(in.readBoolean());
			case "byte" -> hex(in.readByte() & 0xFF);
			default -> in.readUTF();
		};
	}

	private static void flattenValue(final DataInputStream in, final String path, final List<String> values)
	    throws IOException {
		Tag tag = Tag.of(in.readUTF());
		switch (tag.shape()) {
			case NULL -> values.add(path + "=null");
			case LIST -> {
				int size = in.readInt();
				if (size < 0 || size > MAX_LIST_SIZE) {
					throw new IOException("checkpoint list size out of range at " + path);
				}
				values.add(path + ".size=" + size);
				for (int i = 0; i < size; i++) {
					flattenValue(in, path + "[" + i + "]", values);
				}
			}
			case LONG -> values.add(path + "=" + tag.wire() + ":" + hex(in.readLong()));
			case INT -> values.add(path + "=" + tag.wire() + ":" + hex(in.readInt()));
			case BYTE -> values.add(path + "=" + tag.wire() + ":" + hex(in.readByte() & 0xFF));
			case BOOLEAN -> values.add(path + "=" + tag.wire() + ":" + in.readBoolean());
			case UTF -> values.add(path + "=" + tag.wire() + ":" + in.readUTF());
			case RECORD -> {
				values.add(path + ".tag=" + tag.wire());
				for (String field : tag.fields()) {
					String name = in.readUTF();
					if (!name.equals(field)) {
						throw new IOException(
						    "checkpoint field " + path + "." + name + " where " + field + " was expected");
					}
					flattenValue(in, path + "." + field, values);
				}
			}
		}
	}

	private static void flattenWorld(final DataInputStream in, final String path, final List<String> values)
	    throws IOException {
		int size = in.readInt();
		if (size < 0 || size > MAX_WORLD_TEXT_BYTES) {
			throw new IOException("checkpoint world size out of range at " + path);
		}
		byte[] text = in.readNBytes(size);
		if (text.length != size) {
			throw new EOFException("checkpoint ended inside " + path);
		}
		String[] lines = new String(text, StandardCharsets.UTF_8).split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			values.add(path + "[" + i + "]=" + lines[i]);
		}
	}

	private static String hex(final long bits) {
		return String.format(Locale.ROOT, "%016x", bits);
	}

	private static String hex(final int bits) {
		return String.format(Locale.ROOT, "%08x", bits);
	}
}
