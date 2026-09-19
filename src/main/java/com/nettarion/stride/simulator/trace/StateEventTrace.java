package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.ServerPlayerState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Server-written state values a capture observed arriving at the client, before or after named
 * client ticks.
 *
 * <p>The file layout is specified in {@code docs/trace-formats.md}. Instances are immutable.
 *
 * @param scenario the scenario name shared by every file of the capture
 * @param events the observed writes, in tick order and {@code PRE} before {@code POST} within a tick
 */
public record StateEventTrace(String scenario, List<StateEvent> events) {
	/** The state-event file format this library reads and writes; no other version is read. */
	public static final int FORMAT_VERSION = 2;

	private static final String MAGIC = "#stride-state-events";
	private static final String COLUMNS = "tick\tphase\tfield\traw_value";
	private static final long HIGH_32_BITS = 0xffff_ffff_0000_0000L;

	/**
	 * Every field a server write can install, and the one place that says so.
	 *
	 * <p>A field belongs here when the vanilla client itself assigns it from a packet handler,
	 * outside {@code LocalPlayer.tick()}: position and velocity ({@code handleMovePlayer},
	 * {@code handleSetEntityMotion}), the synchronized entity-data byte and pose, the abilities
	 * packet, and the two freeze values. A state event overrides the recorded vector, so the list
	 * is closed rather than "any field": an unreviewed entry could paper over a real disagreement.
	 *
	 * <p>Everything else is deliberately absent. The bounding box is recomputed from position and
	 * pose; the collision flags, the input vector and the fluid heights are computed inside the
	 * transition; rotation is carried by the action, since {@code MouseHandler} applies it
	 * between ticks. A file asking to inject one of those describes something no client does, and
	 * reading it fails.
	 */
	public static final Set<StateField> SUPPORTED = Set.of(
	    // handleMovePlayer -> setValuesFromPositionPacket -> Entity.setPos, which recomputes the
	    // box rather than shifting it.
	    StateField.POS_X, StateField.POS_Y, StateField.POS_Z,
	    // handleSetEntityMotion -> Entity.lerpMotion, a wholesale assignment. ServerEntity sends
	    // it with sendToTrackingPlayersAndSelf whenever `hurtMarked` is set, so in practice this
	    // is damage knockback.
	    StateField.DELTA_X, StateField.DELTA_Y, StateField.DELTA_Z,
	    // The synchronized shared-flags byte, echoed back to its own player, and the pose that
	    // travels with it.
	    StateField.SPRINTING, StateField.SPRINTING_ATTRIBUTE, StateField.FOOD_LEVEL, StateField.SWIMMING,
	    StateField.FALL_FLYING, StateField.SHIFT_KEY_DOWN, StateField.SHARED_FLAGS_RESIDUAL, StateField.POSE,
	    // ClientboundPlayerAbilitiesPacket.
	    StateField.MAY_FLY, StateField.FLYING, StateField.FLYING_SPEED,
	    // Server-owned freeze accounting and the attribute modifier behind it, plus the
	    // equipment-derived facts that arrive with a sync.
	    StateField.TICKS_FROZEN, StateField.FROST_SPEED_TICKS, StateField.CAN_FREEZE, StateField.GLIDER_USABLE,
	    StateField.CAN_WALK_ON_POWDER_SNOW, StateField.SPRINT_WINDOW_TICKS, StateField.AUTO_JUMP_ENABLED,
	    StateField.FAST_LAVA,
	    // Reset by a teleport alongside the position it corrects.
	    StateField.FALL_DISTANCE);

	/** Copies the event list; refuses a blank scenario. */
	public StateEventTrace {
		if (scenario == null || scenario.isBlank()) {
			throw new IllegalArgumentException("scenario must not be blank");
		}
		events = List.copyOf(events);
	}

	/**
	 * Reads the state events at {@code path}. Refuses with an {@link IOException} any other format
	 * version, an unsupported field, an out-of-order or duplicate event, or a value wider than the
	 * field's kind.
	 */
	public static StateEventTrace read(final Path path) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return read(reader);
		}
	}

	/** Writes {@code trace} to {@code path} as UTF-8, replacing any existing file. */
	public static void write(final StateEventTrace trace, final Path path) throws IOException {
		try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			write(trace, writer);
		}
	}

	static void write(final StateEventTrace trace, final Writer writer) throws IOException {
		writer.write(MAGIC + "\t" + FORMAT_VERSION + "\n");
		writer.write("#scenario\t" + trace.scenario() + "\n");
		writer.write(COLUMNS + "\n");
		for (StateEvent event : trace.events()) {
			writer.write(event.tick() + "\t" + event.phase().name() + "\t" + event.field().name() + "\t"
			    + String.format(Locale.ROOT, "%016x", event.rawBits()) + "\n");
		}
	}

	static StateEventTrace read(final BufferedReader reader) throws IOException {
		String[] magic = requireLine(reader, "magic").split("\\t", -1);
		if (magic.length != 2 || !MAGIC.equals(magic[0]) || !Integer.toString(FORMAT_VERSION).equals(magic[1])) {
			throw new IOException("expected " + MAGIC + "\t" + FORMAT_VERSION);
		}
		String[] scenarioLine = requireLine(reader, "scenario").split("\\t", -1);
		if (scenarioLine.length != 2 || !"#scenario".equals(scenarioLine[0]) || scenarioLine[1].isBlank()) {
			throw new IOException("invalid state-event scenario header");
		}
		if (!COLUMNS.equals(requireLine(reader, "columns"))) {
			throw new IOException("invalid state-event columns");
		}

		List<StateEvent> events = new ArrayList<>();
		Set<Long> tickPhaseFields = new HashSet<>();
		int previousTick = -1;
		Phase previousPhase = Phase.PRE;
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.isEmpty()) {
				continue;
			}
			String[] cells = line.split("\\t", -1);
			if (cells.length != 4) {
				throw new IOException("state-event row has " + cells.length + " cells, expected 4");
			}
			int tick;
			Phase phase;
			StateField field;
			long rawBits;
			try {
				tick = Integer.parseInt(cells[0]);
				phase = Phase.valueOf(cells[1]);
				field = StateField.valueOf(cells[2]);
				rawBits = Long.parseUnsignedLong(cells[3], 16);
			} catch (IllegalArgumentException invalid) {
				throw new IOException("invalid state-event row: " + line, invalid);
			}
			if (tick < 0 || tick < previousTick) {
				throw new IOException("state-event ticks must be non-negative and ordered");
			}
			if (tick == previousTick && phase.ordinal() < previousPhase.ordinal()) {
				throw new IOException("PRE state events must precede POST events at a tick");
			}
			if (!SUPPORTED.contains(field)) {
				throw new IOException("unsupported server-written state field " + field);
			}
			requireWidth(field, rawBits);
			long identity = ((long) tick << 33) | ((long) phase.ordinal() << 32) | field.ordinal();
			if (!tickPhaseFields.add(identity)) {
				throw new IOException("duplicate " + phase + " state event for " + field + " at tick " + tick);
			}
			events.add(new StateEvent(tick, phase, field, rawBits));
			previousTick = tick;
			previousPhase = phase;
		}
		return new StateEventTrace(scenarioLine[1], events);
	}

	/**
	 * Checks the raw value against the field's own kind: a double may use all 64 bits, a boolean
	 * only 0 or 1, everything else the low 32 bits. A blanket 32-bit bound would silently reject
	 * half of every velocity and misread the other half.
	 */
	private static void requireWidth(final StateField field, final long rawBits) throws IOException {
		switch (field.kind()) {
			case DOUBLE -> {
			}
			case BOOLEAN -> {
				if (rawBits != 0L && rawBits != 1L) {
					throw new IOException("server-written BOOLEAN value must be 0 or 1 for " + field);
				}
			}
			default -> {
				if ((rawBits & HIGH_32_BITS) != 0L) {
					throw new IOException("server-written " + field.kind() + " value exceeds 32 raw bits for " + field);
				}
			}
		}
	}

	private static String requireLine(final BufferedReader reader, final String what) throws IOException {
		String line = reader.readLine();
		if (line == null) {
			throw new IOException("state-event trace ended before " + what);
		}
		return line;
	}

	/** When, relative to the named client tick, a server-written value takes effect. */
	public enum Phase {
		/** Before the tick runs. */
		PRE,
		/** After the tick completed. */
		POST
	}

	/**
	 * One server-written field value, in the raw-bit form of the field's {@link StateField.Kind}.
	 *
	 * @param tick the zero-based client tick the write is attached to
	 * @param phase whether the write lands before or after that tick
	 * @param field the written field, one of {@link #SUPPORTED}
	 * @param rawBits the value in the field's raw-bit encoding
	 */
	public record StateEvent(int tick, Phase phase, StateField field, long rawBits) {}

	/**
	 * Installs one server-written value on {@code state}, preserving its invariants: a position
	 * write recomputes the bounding box, a pose write recomputes it too. Refuses a field outside
	 * {@link #SUPPORTED} with {@link IllegalArgumentException}. The sneak flag is installed only
	 * on a {@code ServerPlayerState}, since the client copy has nowhere to keep it.
	 */
	public static void apply(final PlayerState state, final StateEvent event) {
		if (!SUPPORTED.contains(event.field())) {
			throw new IllegalArgumentException("unsupported server-written state field " + event.field());
		}
		int value = (int) event.rawBits();
		double raw = Double.longBitsToDouble(event.rawBits());
		boolean flag = event.rawBits() != 0L;
		switch (event.field()) {
			case TICKS_FROZEN -> state.ticksFrozen = value;
			case FROST_SPEED_TICKS -> state.frostSpeedTicks = value;
			case SPRINTING -> state.sprinting = flag;
			case SPRINTING_ATTRIBUTE -> state.sprintingAttribute = flag;
			case FOOD_LEVEL -> state.foodLevel = value;
			case SWIMMING -> state.swimming = flag;
			case FALL_FLYING -> state.fallFlying = flag;
			// The named bits live in their own fields; a byte carrying them would double-count on
			// the way back into a recorded vector.
			case SHARED_FLAGS_RESIDUAL -> state.sharedFlagsResidual = SharedFlagBits.residual((byte) value);
			// The client copy has nowhere to put it: the bit is server-written and the client never
			// reads it back, since LocalPlayer overrides isShiftKeyDown() to answer the input, so
			// the recorded column is excluded from comparison for the same reason. The server's
			// copy does hold it, as the flag its own tick reads.
			case SHIFT_KEY_DOWN -> {
				if (state instanceof ServerPlayerState server) {
					server.shiftKeyDown = flag;
				}
			}
			case POSE -> {
				state.pose = PlayerState.Pose.fromVanillaId(value);
				state.placeAt(state.x, state.y, state.z);
			}
			case MAY_FLY -> state.mayfly = flag;
			case FLYING -> state.flying = flag;
			case FLYING_SPEED -> state.flyingSpeed = Float.intBitsToFloat(value);
			case CAN_FREEZE -> state.canFreeze = flag;
			case CAN_WALK_ON_POWDER_SNOW -> state.canWalkOnPowderSnow = flag;
			case SPRINT_WINDOW_TICKS -> state.sprintWindowTicks = value;
			case AUTO_JUMP_ENABLED -> state.autoJumpEnabled = flag;
			case FAST_LAVA -> state.fastLava = flag;
			case GLIDER_USABLE -> state.gliderUsable = flag;
			case FALL_DISTANCE -> state.fallDistance = raw;
			case DELTA_X -> state.deltaMovementX = raw;
			case DELTA_Y -> state.deltaMovementY = raw;
			case DELTA_Z -> state.deltaMovementZ = raw;
			case POS_X -> state.placeAt(raw, state.y, state.z);
			case POS_Y -> state.placeAt(state.x, raw, state.z);
			case POS_Z -> state.placeAt(state.x, state.y, raw);
			default -> throw new IllegalArgumentException("unsupported server-written state field " + event.field());
		}
	}
}
