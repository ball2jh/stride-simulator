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
import java.util.Set;

/** Server-authoritative state values observed before or after named client ticks. */
public record StateEventTrace(String scenario, List<StateEvent> events) {
	private static final String MAGIC = "#stride-state-events";
	private static final int VERSION = 2;
	/**
	 * Every field a server write can install, and the one place that says so.
	 *
	 * <p>Still a whitelist rather than "any field", for the reason it always was:
	 * an authority event overrides the audited vector, so an unreviewed entry can
	 * silently paper over a real physics disagreement. What changed is that
	 * membership is now decided by a rule and stated once, instead of by three
	 * private lists and two switch statements that each had to be taught the same
	 * field separately.
	 *
	 * <p>{@code SPRINTING} is the worked example. Validation concluded it was
	 * client-computed, which holds for sustained inputs; under per-tick sprint
	 * toggling validation measured its value at the head of a tick differing from the
	 * previous tick's post value, which places the write between ticks and
	 * therefore outside the movement path. headless vanilla (real {@code LocalPlayer}, no
	 * server) agrees with the kernel on those same inputs, which is what rules
	 * out a physics defect.
	 *
	 * <p>The rule, rather than the list, is what to check a candidate against:
	 * **a field belongs here when the client itself assigns it from a packet
	 * handler, outside {@code LocalPlayer.tick()}**. Position and velocity
	 * ({@code handleMovePlayer}, {@code handleSetEntityMotion}), the synchronized entity-data
	 * byte and pose, the abilities packet, and the two freeze values are all such
	 * writes.
	 *
	 * <p>Everything else is deliberately absent, and its absence is a gate rather
	 * than an omission. The bounding box is recomputed from position and pose;
	 * the collision flags, the input vector and the fluid heights are computed
	 * inside the transition; rotation is carried by the action, since
	 * {@code MouseHandler} applies it between ticks and every implementation installs it at its
	 * own tick head. A trace asking to inject one of those describes something no
	 * client does, and reading it fails rather than reproducing it.
	 *
	 * <p>Public because it is consumed, not copied: the capture publishes against
	 * it and both replay implementations apply against it. It used to be three private
	 * lists plus two switch statements, and every field discovered by a capture
	 * cost a round trip through all five.
	 */
	public static final Set<StateField> SUPPORTED = Set.of(
	    // handleMovePlayer -> setValuesFromPositionPacket -> Entity.setPos, which
	    // recomputes the box rather than shifting it.
	    StateField.POS_X, StateField.POS_Y, StateField.POS_Z,
	    // handleSetEntityMotion -> Entity.lerpMotion, a wholesale assignment.
	    // ServerEntity publishes it with sendToTrackingPlayersAndSelf whenever
	    // `hurtMarked` is set, so in practice this is damage knockback.
	    StateField.DELTA_X, StateField.DELTA_Y, StateField.DELTA_Z,
	    // The synchronized shared-flags byte, echoed back to its own player
	    // , and the pose that travels with it.
	    StateField.SPRINTING, StateField.SPRINTING_ATTRIBUTE, StateField.FOOD_LEVEL, StateField.SWIMMING,
	    StateField.FALL_FLYING, StateField.SHIFT_KEY_DOWN, StateField.SHARED_FLAGS_RESIDUAL, StateField.POSE,
	    // ClientboundPlayerAbilitiesPacket.
	    StateField.MAY_FLY, StateField.FLYING, StateField.FLYING_SPEED,
	    // Server-owned freeze accounting and the attribute modifier behind it
	    // , plus the equipment-derived facts that arrive with a sync.
	    StateField.TICKS_FROZEN, StateField.FROST_SPEED_TICKS, StateField.CAN_FREEZE, StateField.GLIDER_USABLE,
	    StateField.CAN_WALK_ON_POWDER_SNOW, StateField.SPRINT_WINDOW_TICKS, StateField.AUTO_JUMP_ENABLED,
	    StateField.FAST_LAVA,
	    // Reset by a teleport alongside the position it corrects.
	    StateField.FALL_DISTANCE);

	public StateEventTrace {
		if (scenario == null || scenario.isBlank()) {
			throw new IllegalArgumentException("scenario must not be blank");
		}
		events = List.copyOf(events);
	}

	public static StateEventTrace read(final Path path) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return read(reader);
		}
	}

	public static void write(final StateEventTrace trace, final Path path) throws IOException {
		try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			writer.write(MAGIC + "\t" + VERSION + "\n");
			writer.write("#scenario\t" + trace.scenario() + "\n");
			writer.write("tick\tphase\tfield\traw_value\n");
			for (StateEvent event : trace.events()) {
				writer.write(event.tick() + "\t" + event.phase().name() + "\t" + event.field().name() + "\t"
				    + String.format(java.util.Locale.ROOT, "%016x", event.rawBits()) + "\n");
			}
		}
	}

	static StateEventTrace read(final BufferedReader reader) throws IOException {
		String[] magic = requireLine(reader, "magic").split("\\t", -1);
		if (magic.length != 2 || !MAGIC.equals(magic[0]) || !Integer.toString(VERSION).equals(magic[1])) {
			throw new IOException("expected " + MAGIC + "\t" + VERSION);
		}
		String[] scenarioLine = requireLine(reader, "scenario").split("\\t", -1);
		if (scenarioLine.length != 2 || !"#scenario".equals(scenarioLine[0]) || scenarioLine[1].isBlank()) {
			throw new IOException("invalid state-event scenario header");
		}
		if (!"tick\tphase\tfield\traw_value".equals(requireLine(reader, "columns"))) {
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
				throw new IOException("unsupported authoritative state field " + field);
			}
			// Width is checked against the field's own kind. This used to be a
			// blanket 32-bit bound, which was right while every authoritative
			// field was an int or a flag bit and silently wrong the moment one
			// carried a double: half of every velocity would have been rejected as
			// out of range, and the half that fit would have been read as a
			// different number.
			if (field.kind() != StateField.Kind.DOUBLE && (rawBits & 0xffff_ffff_0000_0000L) != 0L) {
				throw new IOException("authoritative " + field.kind() + " value exceeds 32 raw bits for " + field);
			}
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

	private static String requireLine(final BufferedReader reader, final String what) throws IOException {
		String line = reader.readLine();
		if (line == null) {
			throw new IOException("state-event trace ended before " + what);
		}
		return line;
	}

	/** The boundary at which an externally observed state update takes effect. */
	public enum Phase { PRE, POST }

	/** A raw field update applied at the declared tick boundary. */
	public record StateEvent(int tick, Phase phase, StateField field, long rawBits) {}

	/** Apply one server-written value to a player state, preserving its invariants. */
	public static void apply(final PlayerState state, final StateEvent event) {
		if (!SUPPORTED.contains(event.field())) {
			throw new IllegalArgumentException("unsupported authoritative state field " + event.field());
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
			// The named bits live in their own fields; a byte carrying them would
			// double-count on the way back into an audited vector.
			case SHARED_FLAGS_RESIDUAL -> state.sharedFlagsResidual = SharedFlagBits.residual((byte) value);
			// The client copy has nowhere to put it: the bit is server-written and
			// the client never reads it back, since LocalPlayer overrides
			// isShiftKeyDown() to answer the input, so the audited column is
			// excluded from comparison for the same reason. The server's copy
			// does hold it, as the flag its own tick reads for careful stepping.
			case SHIFT_KEY_DOWN -> {
				if (state instanceof ServerPlayerState server) server.shiftKeyDown = flag;
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
			default -> throw new IllegalArgumentException("unsupported authoritative state field " + event.field());
		}
	}
}
