package com.nettarion.stride.simulator.trace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The format version each file of one capture carries, read without decoding the files.
 *
 * <p>The world and world-event readers migrate older formats, so a capture recorded before a
 * column was added still reads: the missing column takes a derived value and every other column
 * still compares. That is exactly how a capture goes stale invisibly, because the fields added
 * since are never compared at all. {@link #staleness} names every file behind the current
 * format so a consumer can refuse the capture by name instead of trusting a comparison that
 * omits fields.
 *
 * <p>The trace and state-event files are compared too, although their readers accept only the
 * current version, so a stale one already fails to read.
 *
 * @param trace the {@code .tsv} format version
 * @param world the {@code .world} format version, or empty when the file is absent
 * @param worldEvents the {@code .events} format version, or empty when the file is absent
 */
public record CaptureFormat(int trace, OptionalInt world, OptionalInt worldEvents) {
	/** The versions this library writes today. */
	public static CaptureFormat current() {
		return new CaptureFormat(Trace.FORMAT_VERSION, OptionalInt.of(WorldSnapshotCodec.FORMAT_VERSION),
		    OptionalInt.of(WorldEventTrace.FORMAT_VERSION));
	}

	/** Reads the version off each present file of the capture at {@code basePath}. */
	public static CaptureFormat of(final Path basePath) throws IOException {
		Path world = Capture.companion(basePath, ".world");
		Path worldEvents = Capture.companion(basePath, ".events");
		return new CaptureFormat(TraceCodec.formatVersion(Capture.companion(basePath, ".tsv")),
		    Files.exists(world) ? OptionalInt.of(WorldSnapshotCodec.formatVersion(world)) : OptionalInt.empty(),
		    Files.exists(worldEvents) ? OptionalInt.of(WorldEventTrace.formatVersion(worldEvents))
		                              : OptionalInt.empty());
	}

	/**
	 * One sentence naming every file of the capture recorded before the current format, or empty
	 * when the capture is what this library writes today. Absent files are never stale.
	 */
	public Optional<String> staleness(final String scenario) {
		CaptureFormat now = current();
		List<String> behind = new ArrayList<>(3);
		describe(behind, "trace", OptionalInt.of(this.trace), now.trace());
		describe(behind, "world", this.world, now.world().orElseThrow());
		describe(behind, "world events", this.worldEvents, now.worldEvents().orElseThrow());
		if (behind.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of("capture '" + scenario + "' was recorded at " + String.join(", ", behind)
		    + "; the fields added since are not compared when it is read by migration, so the capture"
		    + " must be re-recorded with a producer writing the current format");
	}

	private static void describe(
	    final List<String> behind, final String what, final OptionalInt recorded, final int now) {
		if (recorded.isPresent() && recorded.getAsInt() < now) {
			behind.add(what + " format " + recorded.getAsInt() + " against " + now);
		}
	}
}
