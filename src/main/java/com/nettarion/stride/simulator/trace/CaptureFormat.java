package com.nettarion.stride.simulator.trace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The format each file of one capture was recorded at, so a reader can refuse a
 * recording older than the one a live client writes today.
 *
 * <p>The readers migrate older formats deliberately: a research replay of an
 * archived recording needs them to, and every one of them is a transcription
 * that still decodes correctly. That is also what lets a corpus go stale
 * invisibly. A capture four versions behind loses whole columns and the
 * remaining ones still match, so a differential gate replaying it stays green
 * while the fields added since were never compared once — it proves agreement
 * with a client that no longer exists, which is the one thing such a gate must
 * not be able to do quietly.
 *
 * <p>This type is the difference between "readable" and "current". It reads the
 * version off each artifact without decoding it and {@link #staleness} turns a
 * capture behind the recorder into one sentence naming which file, the version
 * it carries, the version a recording writes now, and how to re-record it. What
 * to do with that sentence belongs to the caller; the differential gate fails
 * the capture by name.
 *
 * <p>The state-event sidecar is absent from this comparison because it cannot
 * be stale: {@code StateEventTrace} accepts its current version and no other,
 * so an old one already fails to read at all.
 */
public record CaptureFormat(int trace, int world, int worldEvents) {
	/** Absent from a capture, and never compared against the current version. */
	public static final int MISSING = -1;

	/** What a recording made by the live client writes today. */
	public static CaptureFormat current() {
		return new CaptureFormat(
		    Trace.FORMAT_VERSION, WorldSnapshotCodec.FORMAT_VERSION, WorldEventTrace.FORMAT_VERSION);
	}

	/** Reads the version off each file of the capture at this base path. */
	public static CaptureFormat of(final Path basePath) throws IOException {
		Path world = Capture.companion(basePath, ".world");
		Path worldEvents = Capture.companion(basePath, ".events");
		return new CaptureFormat(TraceCodec.formatVersion(Capture.companion(basePath, ".tsv")),
		    Files.exists(world) ? WorldSnapshotCodec.formatVersion(world) : MISSING,
		    Files.exists(worldEvents) ? WorldEventTrace.formatVersion(worldEvents) : MISSING);
	}

	/**
	 * One sentence naming every artifact recorded before the current format, or
	 * null when the capture is what a recording writes today.
	 */
	public String staleness(final String scenario) {
		CaptureFormat now = current();
		List<String> behind = new ArrayList<>(3);
		describe(behind, "trace", this.trace, now.trace());
		describe(behind, "world", this.world, now.world());
		describe(behind, "world events", this.worldEvents, now.worldEvents());
		if (behind.isEmpty()) {
			return null;
		}
		return "capture '" + scenario + "' was recorded at " + String.join(", ", behind)
		    + "; it is read by migration, not by agreement, so replaying it proves"
		    + " nothing about the fields added since. Re-record the scenario using"
		    + " a compatible external capture producer.";
	}

	private static void describe(final List<String> behind, final String what, final int recorded, final int now) {
		if (recorded != MISSING && recorded < now) {
			behind.add(what + " format " + recorded + " against " + now);
		}
	}
}
