package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.world.BlockStateCatalog;
import com.nettarion.stride.simulator.world.WorldSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The files of one recorded session behind one extension-free base path: the trace, the world it
 * happened in, the block and server-written events that arrived while it was recorded, and the
 * block catalog the world is verified against.
 *
 * <p>Companion files share the base name and differ by extension: {@code .tsv} (trace, required),
 * {@code .world}, {@code .catalog}, {@code .events} and {@code .state-events}. This type owns
 * companion naming, stale-file cleanup on write, and cross-file validation: every event must name
 * a tick the trace has and a cell and palette entry the world has. Instances are immutable.
 *
 * @param trace the movement trace, always present
 * @param world the world the trace was recorded in, if captured
 * @param worldEvents block and fluid changes observed during the trace; empty when none were
 * @param stateEvents server-written state values observed during the trace, if captured
 * @param catalog the block catalog the world was verified against; empty for an unverified capture
 */
public record Capture(Trace trace, Optional<WorldSnapshot> world, WorldEventTrace worldEvents,
    Optional<StateEventTrace> stateEvents, Optional<BlockStateCatalog> catalog) {
	/**
	 * Validates the bundle. Refuses with {@link IllegalArgumentException} a null component, world
	 * events without a world, an event at a tick the trace lacks or a cell the world lacks, a
	 * state-event scenario other than the trace's, or a catalog without a world; a catalog whose
	 * facts differ from the world refuses through {@code BlockStateCatalog.verify}.
	 */
	public Capture {
		Objects.requireNonNull(trace, "trace");
		Objects.requireNonNull(world, "world");
		Objects.requireNonNull(worldEvents, "worldEvents");
		Objects.requireNonNull(stateEvents, "stateEvents");
		Objects.requireNonNull(catalog, "catalog");
		if (!worldEvents.isEmpty() && world.isEmpty()) {
			throw new IllegalArgumentException("world events require a world snapshot");
		}
		if (catalog.isPresent()) {
			if (world.isEmpty()) {
				throw new IllegalArgumentException("a block catalog requires a world snapshot to verify");
			}
			catalog.orElseThrow().verify(world.orElseThrow(), trace.header().minecraftVersion());
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

	/**
	 * A capture from nullable parts: a null {@code world}, {@code stateEvents} or {@code catalog}
	 * is absent and null {@code worldEvents} means none. Validates as the constructor does.
	 */
	public static Capture of(final Trace trace, final WorldSnapshot world, final WorldEventTrace worldEvents,
	    final StateEventTrace stateEvents, final BlockStateCatalog catalog) {
		return new Capture(trace, Optional.ofNullable(world),
		    worldEvents == null ? new WorldEventTrace(List.of()) : worldEvents, Optional.ofNullable(stateEvents),
		    Optional.ofNullable(catalog));
	}

	/** {@link #of(Trace, WorldSnapshot, WorldEventTrace, StateEventTrace, BlockStateCatalog)} without a catalog. */
	public static Capture of(final Trace trace, final WorldSnapshot world, final WorldEventTrace worldEvents,
	    final StateEventTrace stateEvents) {
		return of(trace, world, worldEvents, stateEvents, null);
	}

	/** This capture with {@code catalog} attached and verified against the world. */
	public Capture withCatalog(final BlockStateCatalog catalog) {
		return new Capture(this.trace, this.world, this.worldEvents, this.stateEvents, Optional.of(catalog));
	}

	/**
	 * The extension-free base path of a capture in {@code directory}, named
	 * {@code <producer prefix>-<scenario>}. Refuses a blank scenario or one containing a path
	 * separator.
	 */
	public static Path basePath(final Path directory, final TraceProducer producer, final String scenario) {
		Objects.requireNonNull(directory, "directory");
		Objects.requireNonNull(producer, "producer");
		if (scenario == null || scenario.isBlank() || scenario.contains("/") || scenario.contains("\\")) {
			throw new IllegalArgumentException("invalid capture scenario " + scenario);
		}
		return directory.resolve(producer.filePrefix() + "-" + scenario);
	}

	/**
	 * Reads the capture at {@code basePath} and verifies its world against the {@code .catalog}
	 * companion, which must be present when the world is. Refuses with {@link IOException} a
	 * missing or unreadable file, or a capture the constructor rejects.
	 */
	public static Capture read(final Path basePath) throws IOException {
		return read(basePath, true);
	}

	/**
	 * Reads the capture at {@code basePath} without a catalog, so the world's block facts are
	 * taken as written. For synthetic captures; {@link #read} is for recorded ones.
	 */
	public static Capture readUnverified(final Path basePath) throws IOException {
		return read(basePath, false);
	}

	private static Capture read(final Path basePath, final boolean verifyCatalog) throws IOException {
		Path base = requireBasePath(basePath);
		Trace trace = TraceCodec.read(companion(base, ".tsv"));
		Path worldPath = companion(base, ".world");
		Path worldEventsPath = companion(base, ".events");
		Path stateEventsPath = companion(base, ".state-events");

		WorldSnapshot world = Files.exists(worldPath) ? WorldSnapshotCodec.read(worldPath) : null;
		BlockStateCatalog catalog = null;
		if (verifyCatalog && world != null) {
			catalog = BlockStateCatalogCodec.read(companion(base, ".catalog"), trace.header().minecraftVersion());
		}
		WorldEventTrace worldEvents =
		    Files.exists(worldEventsPath) ? WorldEventTrace.read(worldEventsPath) : new WorldEventTrace(List.of());
		StateEventTrace stateEvents = Files.exists(stateEventsPath) ? StateEventTrace.read(stateEventsPath) : null;
		try {
			return of(trace, world, worldEvents, stateEvents, catalog);
		} catch (IllegalArgumentException | UnimplementedMechanicException invalid) {
			throw new IOException("invalid capture " + base + ": " + invalid.getMessage(), invalid);
		}
	}

	/**
	 * Writes every present companion of {@code capture} and deletes stale absent ones. Refuses
	 * with {@link IllegalArgumentException} a capture whose world has no catalog; use
	 * {@link #writeUnverified} for synthetic worlds.
	 */
	public static void write(final Path basePath, final Capture capture) throws IOException {
		if (capture.world().isPresent() && capture.catalog().isEmpty()) {
			throw new IllegalArgumentException(
			    "writing a world requires its block catalog; use writeUnverified for synthetic captures");
		}
		writeUnverified(basePath, capture);
	}

	/** Writes every present companion of {@code capture}, catalog or not, and deletes stale absent ones. */
	public static void writeUnverified(final Path basePath, final Capture capture) throws IOException {
		Path base = requireBasePath(basePath);
		Path parent = base.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		TraceCodec.write(capture.trace(), companion(base, ".tsv"));
		writeOptional(companion(base, ".catalog"), capture.catalog(), BlockStateCatalogCodec::write);
		writeOptional(companion(base, ".world"), capture.world(), WorldSnapshotCodec::write);
		if (capture.worldEvents().isEmpty()) {
			Files.deleteIfExists(companion(base, ".events"));
		} else {
			WorldEventTrace.write(capture.worldEvents(), companion(base, ".events"));
		}
		writeOptional(companion(base, ".state-events"), capture.stateEvents(), StateEventTrace::write);
	}

	/**
	 * The companion file of {@code basePath} with {@code extension}, which must start with a dot.
	 * Refuses a base path whose file name is blank or contains a dot.
	 */
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
		for (WorldEventTrace.BlockCellEvent event : events.blockEvents()) {
			requireTraceTick(traceTicks, event.tick(), "world event");
			requireInside(snapshot, event.x(), event.y(), event.z(), "world event");
			requireUniqueBlockState(snapshot, event.blockStateId());
			if (event.fluidStateId() != WorldEventTrace.BlockCellEvent.KEEP_EXISTING_FLUID) {
				requireUniqueFluidState(snapshot, event.fluidStateId());
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

	private static void requireUniqueBlockState(final WorldSnapshot snapshot, final int blockStateId) {
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
	}

	private static void requireUniqueFluidState(final WorldSnapshot snapshot, final int fluidStateId) {
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
