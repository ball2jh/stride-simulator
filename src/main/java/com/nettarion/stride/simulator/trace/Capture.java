package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.world.BlockStateCatalog;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.UnimplementedMechanicException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A recording from a live session: the trace, the world it happened in, and
 * the block and server-state events that arrived while it was recorded, as
 * one bundle of reviewable text files behind one extension-free base path.
 *
 * <p>The on-disk representation remains a small set of reviewable text files,
 * but callers address them through one extension-free base path. This type owns
 * companion naming, stale-file cleanup, and cross-file scenario validation.
 */
public record Capture(Trace trace, Optional<WorldSnapshot> world, WorldEventTrace worldEvents,
    Optional<StateEventTrace> stateEvents, Optional<BlockStateCatalog> catalog) {
	public Capture(Trace trace, Optional<WorldSnapshot> world, WorldEventTrace worldEvents,
	    Optional<StateEventTrace> stateEvents) {
		this(trace, world, worldEvents, stateEvents, Optional.empty());
	}
	/** Associates independently extracted source evidence with this recording. */
	public Capture withCatalog(BlockStateCatalog source) {
		source.verify(world.orElseThrow(), trace.header().minecraftVersion());
		return new Capture(trace, world, worldEvents, stateEvents, Optional.of(source));
	}
	public Capture {
		catalog = Objects.requireNonNull(catalog, "catalog");
		if (catalog.isPresent()) catalog.orElseThrow().verify(world.orElseThrow(), trace.header().minecraftVersion());
		Objects.requireNonNull(trace, "trace");
		world = Objects.requireNonNull(world, "world");
		worldEvents = Objects.requireNonNull(worldEvents, "worldEvents");
		stateEvents = Objects.requireNonNull(stateEvents, "stateEvents");
		if (!worldEvents.isEmpty() && world.isEmpty()) {
			throw new IllegalArgumentException("world events require a world snapshot");
		}
		Set<Integer> traceTicks =
		    trace.entries().stream().map(Trace.TraceEntry::tick).collect(Collectors.toUnmodifiableSet());
		stateEvents.ifPresent(events -> {
			if (!events.scenario().equals(trace.header().scenario())) {
				throw new IllegalArgumentException(
				    "state-event scenario " + events.scenario() + " does not match trace " + trace.header().scenario());
			}
			for (StateEventTrace.StateEvent event : events.events()) {
				requireTraceTick(traceTicks, event.tick(), "state event");
			}
		});
		if (world.isPresent()) {
			validateWorldEvents(world.orElseThrow(), worldEvents, traceTicks);
		}
	}

	public static Capture of(final Trace trace, final WorldSnapshot world, final WorldEventTrace worldEvents,
	    final StateEventTrace stateEvents) {
		return new Capture(trace, Optional.ofNullable(world),
		    worldEvents == null ? new WorldEventTrace(List.of()) : worldEvents, Optional.ofNullable(stateEvents));
	}

	/** Base path for a producer and scenario, without a file extension. */
	public static Path basePath(final Path directory, final TraceProducer producer, final String scenario) {
		Objects.requireNonNull(directory, "directory");
		Objects.requireNonNull(producer, "producer");
		if (scenario == null || scenario.isBlank() || scenario.contains("/") || scenario.contains("\\")) {
			throw new IllegalArgumentException("invalid capture scenario " + scenario);
		}
		return directory.resolve(producer.filePrefix() + "-" + scenario);
	}

	public static Capture read(final Path basePath) throws IOException {
		return read(basePath, true);
	}

	/** Explicit legacy/synthetic import; it makes no source-verification claim. */
	public static Capture readUnverified(final Path basePath) throws IOException {
		return read(basePath, false);
	}

	private static Capture read(final Path basePath, final boolean verifySource) throws IOException {
		Path base = requireBasePath(basePath);
		Trace trace = TraceCodec.read(companion(base, ".tsv"));
		Path worldPath = companion(base, ".world");
		Path worldEventsPath = companion(base, ".events");
		Path stateEventsPath = companion(base, ".state-events");

		WorldSnapshot world = Files.exists(worldPath) ? WorldSnapshotCodec.read(worldPath) : null;
		BlockStateCatalog catalog = null;
		if (verifySource && world != null) {
			catalog = BlockStateCatalogCodec.read(companion(base, ".catalog"), trace.header().minecraftVersion());
			try {
				catalog.verify(world, trace.header().minecraftVersion());
			} catch (IllegalArgumentException | com.nettarion.stride.simulator.UnimplementedMechanicException invalid) {
				throw new IOException("captured block facts differ from source", invalid);
			}
		}
		WorldEventTrace worldEvents =
		    Files.exists(worldEventsPath) ? WorldEventTrace.read(worldEventsPath) : new WorldEventTrace(List.of());
		StateEventTrace stateEvents = Files.exists(stateEventsPath) ? StateEventTrace.read(stateEventsPath) : null;
		try {
			return new Capture(trace, Optional.ofNullable(world), worldEvents, Optional.ofNullable(stateEvents),
			    Optional.ofNullable(catalog));
		} catch (IllegalArgumentException | com.nettarion.stride.simulator.UnimplementedMechanicException invalid) {
			throw new IOException("invalid capture " + base + ": " + invalid.getMessage(), invalid);
		}
	}

	/** Resolves the unique block-palette entry named by a timed event. */
	public int blockPaletteIndex(final int blockStateId) {
		WorldSnapshot snapshot =
		    this.world.orElseThrow(() -> new IllegalStateException("capture has no world snapshot"));
		return uniqueBlockPaletteIndex(snapshot, blockStateId);
	}

	/** Resolves the unique fluid-palette entry named by a timed block event. */
	public int fluidPaletteIndex(final int fluidStateId) {
		WorldSnapshot snapshot =
		    this.world.orElseThrow(() -> new IllegalStateException("capture has no world snapshot"));
		return uniqueFluidPaletteIndex(snapshot, fluidStateId);
	}

	/** Resolves the exact fluid-palette entry named by a timed fluid event. */
	public int fluidPaletteIndex(final WorldSnapshot.FluidEntry fluid) {
		WorldSnapshot snapshot =
		    this.world.orElseThrow(() -> new IllegalStateException("capture has no world snapshot"));
		int index = snapshot.fluidPalette().indexOf(fluid);
		if (index < 0) {
			throw new IllegalArgumentException("resolved fluid event is absent from the captured palette: " + fluid);
		}
		return index;
	}

	/** Writes the capture and removes stale optional companions. */
	public static void write(final Path basePath, final Capture fixture) throws IOException {
		if (fixture.world().isPresent() && fixture.catalog().isEmpty())
			throw new IOException(
			    "recording a world requires its independent catalog; use writeUnverified for synthetic archives");
		writeUnverified(basePath, fixture);
	}

	/** Writes an explicitly unverified synthetic/archive recording; it is not independent evidence. */
	public static void writeUnverified(final Path basePath, final Capture fixture) throws IOException {
		Path base = requireBasePath(basePath);
		Path parent = base.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		TraceCodec.write(fixture.trace(), companion(base, ".tsv"));
		writeOptional(companion(base, ".catalog"), fixture.catalog(), BlockStateCatalogCodec::write);
		writeOptional(companion(base, ".world"), fixture.world(), WorldSnapshotCodec::write);
		if (fixture.worldEvents().isEmpty()) {
			Files.deleteIfExists(companion(base, ".events"));
		} else {
			WorldEventTrace.write(fixture.worldEvents(), companion(base, ".events"));
		}
		writeOptional(companion(base, ".state-events"), fixture.stateEvents(), StateEventTrace::write);
	}

	public static Path companion(final Path basePath, final String extension) {
		Path base = requireBasePath(basePath);
		if (extension == null || !extension.startsWith(".") || extension.length() < 2) {
			throw new IllegalArgumentException("invalid capture extension " + extension);
		}
		return base.resolveSibling(base.getFileName() + extension);
	}

	private static Path requireBasePath(final Path basePath) {
		Objects.requireNonNull(basePath, "basePath");
		Path name = basePath.getFileName();
		if (name == null || name.toString().isBlank() || name.toString().contains(".")) {
			throw new IllegalArgumentException(
			    "capture base path must have a non-empty, extension-free file name: " + basePath);
		}
		return basePath;
	}

	private static void validateWorldEvents(
	    final WorldSnapshot snapshot, final WorldEventTrace events, final Set<Integer> traceTicks) {
		for (WorldEventTrace.CellStateEvent event : events.events()) {
			requireTraceTick(traceTicks, event.tick(), "world event");
			requireInside(snapshot, event.x(), event.y(), event.z(), "world event");
			uniqueBlockPaletteIndex(snapshot, event.blockStateId());
			if (event.fluidStateId() != WorldEventTrace.CellStateEvent.KEEP_EXISTING_FLUID) {
				uniqueFluidPaletteIndex(snapshot, event.fluidStateId());
			}
		}
		for (WorldEventTrace.FluidCellEvent event : events.fluidEvents()) {
			requireTraceTick(traceTicks, event.tick(), "fluid event");
			requireInside(snapshot, event.x(), event.y(), event.z(), "fluid event");
			if (!snapshot.fluidPalette().contains(event.fluid())) {
				throw new IllegalArgumentException("resolved fluid event is absent from the captured palette at "
				    + event.x() + "," + event.y() + "," + event.z());
			}
		}
	}

	private static void requireTraceTick(final Set<Integer> traceTicks, final int tick, final String eventKind) {
		if (!traceTicks.contains(tick)) {
			throw new IllegalArgumentException(eventKind + " tick " + tick + " is absent from the movement trace");
		}
	}

	private static void requireInside(
	    final WorldSnapshot snapshot, final int x, final int y, final int z, final String eventKind) {
		if (!snapshot.contains(x, y, z)) {
			throw new IllegalArgumentException(
			    eventKind + " cell lies outside captured world: " + x + "," + y + "," + z);
		}
	}

	private static int uniqueBlockPaletteIndex(final WorldSnapshot snapshot, final int blockStateId) {
		int found = -1;
		for (int i = 0; i < snapshot.palette().size(); i++) {
			if (snapshot.palette().get(i).blockStateId() == blockStateId) {
				if (found >= 0) {
					throw new IllegalArgumentException(
					    "block state id " + blockStateId + " is ambiguous in the captured palette");
				}
				found = i;
			}
		}
		if (found < 0) {
			throw new IllegalArgumentException(
			    "block state id " + blockStateId + " is absent from the captured palette");
		}
		return found;
	}

	private static int uniqueFluidPaletteIndex(final WorldSnapshot snapshot, final int fluidStateId) {
		int found = -1;
		for (int i = 0; i < snapshot.fluidPalette().size(); i++) {
			if (snapshot.fluidPalette().get(i).fluidStateId() == fluidStateId) {
				if (found >= 0) {
					throw new IllegalArgumentException(
					    "fluid state id " + fluidStateId + " is ambiguous in the captured palette");
				}
				found = i;
			}
		}
		if (found < 0) {
			throw new IllegalArgumentException(
			    "fluid state id " + fluidStateId + " is absent from the captured palette");
		}
		return found;
	}

	private static <T> void writeOptional(final Path path, final Optional<T> value, final CaptureWriter<T> writer)
	    throws IOException {
		if (value.isPresent()) {
			writer.write(value.get(), path);
		} else {
			Files.deleteIfExists(path);
		}
	}

	@FunctionalInterface
	private interface CaptureWriter<T> {
		void write(T value, Path path) throws IOException;
	}
}
