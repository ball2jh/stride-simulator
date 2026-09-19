package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.world.WorldSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.FluidEntry;

class CaptureTest {
	@TempDir Path temporaryDirectory;

	@Test
	void coreFixtureRoundTripsThroughOneBasePath() throws IOException {
		Trace trace = trace("round-trip");
		WorldSnapshot world = world();
		WorldEventTrace worldEvents = new WorldEventTrace(List.of(new WorldEventTrace.CellStateEvent(0, 0, 0, 0, 0)));
		StateEventTrace stateEvents = new StateEventTrace("round-trip",
		    List.of(new StateEventTrace.StateEvent(0, StateEventTrace.Phase.PRE, StateField.SPRINTING, 1L)));
		Path base = Capture.basePath(this.temporaryDirectory, TraceProducer.LIVE_GAME, "round-trip");

		Capture.writeUnverified(base, Capture.of(trace, world, worldEvents, stateEvents));
		assertThrows(IOException.class, () -> Capture.read(base));
		Capture decoded = Capture.readUnverified(base);

		assertEquals(trace, decoded.trace());
		WorldSnapshot decodedWorld = decoded.world().orElseThrow();
		assertEquals(world.originX(), decodedWorld.originX());
		assertEquals(world.outside(), decodedWorld.outside());
		assertEquals(world.palette(), decodedWorld.palette());
		assertArrayEquals(world.cells(), decodedWorld.cells());
		assertEquals(worldEvents, decoded.worldEvents());
		assertEquals(stateEvents, decoded.stateEvents().orElseThrow());
	}

	@Test
	void rewritingRemovesStaleOptionalCompanions() throws IOException {
		Path base = Capture.basePath(this.temporaryDirectory, TraceProducer.LIVE_GAME, "stale");
		Capture.writeUnverified(base,
		    Capture.of(trace("stale"), world(),
		        new WorldEventTrace(List.of(new WorldEventTrace.CellStateEvent(0, 0, 0, 0, 0))),
		        new StateEventTrace("stale", List.of())));

		Capture.writeUnverified(base, Capture.of(trace("stale"), null, null, null));

		assertTrue(Files.exists(Capture.companion(base, ".tsv")));
		assertFalse(Files.exists(Capture.companion(base, ".world")));
		assertFalse(Files.exists(Capture.companion(base, ".events")));
		assertFalse(Files.exists(Capture.companion(base, ".state-events")));
	}

	@Test
	void fluidOnlyWorldEventsKeepTheirCompanion() throws IOException {
		Path base = Capture.basePath(this.temporaryDirectory, TraceProducer.LIVE_GAME, "fluid-only");
		WorldEventTrace events = new WorldEventTrace(
		    List.of(), List.of(new WorldEventTrace.FluidCellEvent(0, 0, 0, 0, FluidEntry.EMPTY)));

		Capture.writeUnverified(base, Capture.of(trace("fluid-only"), world(), events, null));

		assertTrue(Files.exists(Capture.companion(base, ".events")));
		assertEquals(events, Capture.readUnverified(base).worldEvents());
	}

	@Test
	void refusesCrossScenarioAuthorityEvents() {
		StateEventTrace wrong = new StateEventTrace("other", List.of());
		assertThrows(IllegalArgumentException.class, () -> Capture.of(trace("expected"), world(), null, wrong));
	}

	@Test
	void refusesEventsOutsideTheTraceOrCapturedWorld() {
		WorldEventTrace late = new WorldEventTrace(List.of(new WorldEventTrace.CellStateEvent(1, 0, 0, 0, 0)));
		WorldEventTrace outside = new WorldEventTrace(List.of(new WorldEventTrace.CellStateEvent(0, 1, 0, 0, 0)));

		assertThrows(IllegalArgumentException.class, () -> Capture.of(trace("invalid"), world(), late, null));
		assertThrows(IllegalArgumentException.class, () -> Capture.of(trace("invalid"), world(), outside, null));
	}

	@Test
	void refusesEventStateAbsentFromTheCapturedPalette() {
		WorldEventTrace events = new WorldEventTrace(List.of(new WorldEventTrace.CellStateEvent(0, 0, 0, 0, 99)));

		assertThrows(IllegalArgumentException.class, () -> Capture.of(trace("invalid"), world(), events, null));
	}

	private static Trace trace(final String scenario) {
		StateVector state = state();
		return new Trace(new Trace.TraceHeader(TraceProducer.LIVE_GAME, "26.2", scenario), state,
		    List.of(new Trace.TraceEntry(0, PlayerInput.idle(0.0F, 0.0F), state)));
	}

	private static StateVector state() {
		StateVector.Builder builder = StateVector.builder();
		for (StateField field : StateField.ALL) {
			switch (field.kind()) {
				case DOUBLE -> builder.set(field, 0.0);
				case FLOAT -> builder.set(field, 0.0F);
				case BOOLEAN -> builder.set(field, false);
				case INT -> builder.set(field, 0);
				case BYTE_FLAGS -> builder.setFlags(field, (byte) 0);
				case ENUM -> builder.setEnum(field, 0, "STANDING");
			}
		}
		return builder.build();
	}

	private static WorldSnapshot world() {
		BlockEntry air = new BlockEntry(0, "minecraft:air", 0.6F, 1.0F, 1.0F, List.of());
		return new WorldSnapshot(
		    0, 0, 0, 1, 1, 1, OutsidePolicy.REFUSING, List.of(air), new int[] {0});
	}
}
