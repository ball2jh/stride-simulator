package com.nettarion.stride.simulator.trace;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.world.BlockStateCatalog;
import com.nettarion.stride.simulator.world.FluidEntry;
import com.nettarion.stride.simulator.world.WorldSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CaptureTest {
	@TempDir Path temporaryDirectory;

	@Test
	void everyCompanionRoundTripsThroughOneBasePath() throws IOException {
		Trace trace = TraceFixtures.oneTickTrace("round-trip");
		WorldSnapshot world = TraceFixtures.oneCellWorld();
		WorldEventTrace worldEvents = new WorldEventTrace(List.of(new WorldEventTrace.BlockCellEvent(0, 0, 0, 0, 0)));
		StateEventTrace stateEvents = new StateEventTrace("round-trip",
		    List.of(new StateEventTrace.StateEvent(0, StateEventTrace.Phase.PRE, StateField.SPRINTING, 1L)));
		Path base = Capture.basePath(this.temporaryDirectory, TraceProducer.LIVE_GAME, "round-trip");

		Capture.writeUnverified(base, Capture.of(trace, world, worldEvents, stateEvents));
		assertThrows(IOException.class, () -> Capture.read(base), "a world without a catalog cannot be verified");
		Capture decoded = Capture.readUnverified(base);

		assertEquals(trace, decoded.trace());
		WorldSnapshot decodedWorld = decoded.world().orElseThrow();
		assertEquals(world.originX(), decodedWorld.originX());
		assertEquals(world.outside(), decodedWorld.outside());
		assertEquals(world.palette(), decodedWorld.palette());
		assertArrayEquals(world.toDenseCells(), decodedWorld.toDenseCells());
		assertEquals(worldEvents, decoded.worldEvents());
		assertEquals(stateEvents, decoded.stateEvents().orElseThrow());
	}

	@Test
	void aCatalogIsWrittenAndVerifiedOnRead() throws IOException {
		WorldSnapshot world = TraceFixtures.oneCellWorld();
		BlockStateCatalog catalog = new BlockStateCatalog(TraceFixtures.MINECRAFT_VERSION, world.palette());
		Path base = Capture.basePath(this.temporaryDirectory, TraceProducer.LIVE_GAME, "verified");

		Capture.write(base, Capture.of(TraceFixtures.oneTickTrace("verified"), world, null, null, catalog));
		Capture decoded = Capture.read(base);

		assertTrue(Files.exists(Capture.companion(base, ".catalog")));
		assertEquals(catalog.entries(), decoded.catalog().orElseThrow().entries());
	}

	@Test
	void writeRefusesAWorldWithoutItsCatalog() {
		Path base = Capture.basePath(this.temporaryDirectory, TraceProducer.LIVE_GAME, "unverified");
		Capture capture =
		    Capture.of(TraceFixtures.oneTickTrace("unverified"), TraceFixtures.oneCellWorld(), null, null);

		assertThrows(IllegalArgumentException.class, () -> Capture.write(base, capture));
	}

	@Test
	void rewritingRemovesStaleOptionalCompanions() throws IOException {
		Path base = Capture.basePath(this.temporaryDirectory, TraceProducer.LIVE_GAME, "stale");
		Capture.writeUnverified(base,
		    Capture.of(TraceFixtures.oneTickTrace("stale"), TraceFixtures.oneCellWorld(),
		        new WorldEventTrace(List.of(new WorldEventTrace.BlockCellEvent(0, 0, 0, 0, 0))),
		        new StateEventTrace("stale", List.of())));

		Capture.writeUnverified(base, Capture.of(TraceFixtures.oneTickTrace("stale"), null, null, null));

		assertTrue(Files.exists(Capture.companion(base, ".tsv")));
		assertFalse(Files.exists(Capture.companion(base, ".world")));
		assertFalse(Files.exists(Capture.companion(base, ".events")));
		assertFalse(Files.exists(Capture.companion(base, ".state-events")));
	}

	@Test
	void fluidOnlyWorldEventsKeepTheirCompanion() throws IOException {
		Path base = Capture.basePath(this.temporaryDirectory, TraceProducer.LIVE_GAME, "fluid-only");
		WorldEventTrace events =
		    new WorldEventTrace(List.of(), List.of(new WorldEventTrace.FluidCellEvent(0, 0, 0, 0, FluidEntry.EMPTY)));

		Capture.writeUnverified(
		    base, Capture.of(TraceFixtures.oneTickTrace("fluid-only"), TraceFixtures.oneCellWorld(), events, null));

		assertTrue(Files.exists(Capture.companion(base, ".events")));
		assertEquals(events, Capture.readUnverified(base).worldEvents());
	}

	@Test
	void refusesCrossScenarioStateEvents() {
		StateEventTrace wrong = new StateEventTrace("other", List.of());
		assertThrows(IllegalArgumentException.class,
		    () -> Capture.of(TraceFixtures.oneTickTrace("expected"), TraceFixtures.oneCellWorld(), null, wrong));
	}

	@Test
	void refusesEventsOutsideTheTraceOrCapturedWorld() {
		WorldEventTrace late = new WorldEventTrace(List.of(new WorldEventTrace.BlockCellEvent(1, 0, 0, 0, 0)));
		WorldEventTrace outside = new WorldEventTrace(List.of(new WorldEventTrace.BlockCellEvent(0, 1, 0, 0, 0)));

		assertThrows(IllegalArgumentException.class,
		    () -> Capture.of(TraceFixtures.oneTickTrace("invalid"), TraceFixtures.oneCellWorld(), late, null));
		assertThrows(IllegalArgumentException.class,
		    () -> Capture.of(TraceFixtures.oneTickTrace("invalid"), TraceFixtures.oneCellWorld(), outside, null));
	}

	@Test
	void refusesEventStateAbsentFromTheCapturedPalette() {
		WorldEventTrace events = new WorldEventTrace(List.of(new WorldEventTrace.BlockCellEvent(0, 0, 0, 0, 99)));

		assertThrows(IllegalArgumentException.class,
		    () -> Capture.of(TraceFixtures.oneTickTrace("invalid"), TraceFixtures.oneCellWorld(), events, null));
	}

	@Test
	void nullComponentsAreRefusedBeforeAnyVerification() {
		Trace trace = TraceFixtures.oneTickTrace("null");
		BlockStateCatalog catalog =
		    new BlockStateCatalog(TraceFixtures.MINECRAFT_VERSION, TraceFixtures.oneCellWorld().palette());
		// A present catalog with an absent world used to reach verify() before the null checks ran.
		assertThrows(NullPointerException.class,
		    () -> new Capture(trace, null, new WorldEventTrace(List.of()), Optional.empty(), Optional.of(catalog)));
		assertThrows(IllegalArgumentException.class,
		    ()
		        -> new Capture(
		            trace, Optional.empty(), new WorldEventTrace(List.of()), Optional.empty(), Optional.of(catalog)));
	}
}
