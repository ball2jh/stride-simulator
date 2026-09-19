package com.nettarion.stride.simulator.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CaptureFormatTest {
	@TempDir Path temporaryDirectory;

	@Test
	void aFreshCaptureIsCurrent() throws IOException {
		Path base = write();

		assertEquals(CaptureFormat.current(), CaptureFormat.of(base));
		assertEquals(Optional.empty(), CaptureFormat.of(base).staleness("round-trip"));
	}

	@Test
	void aTraceBehindTheCurrentFormatIsNamed() throws IOException {
		Path base = write();
		downgrade(Capture.companion(base, ".tsv"), "#stride-trace", Trace.FORMAT_VERSION - 1);

		String staleness = CaptureFormat.of(base).staleness("round-trip").orElseThrow();

		assertTrue(
		    staleness.contains("trace format " + (Trace.FORMAT_VERSION - 1) + " against " + Trace.FORMAT_VERSION),
		    staleness);
		assertTrue(staleness.contains("re-recorded"), staleness);
	}

	/**
	 * The case this type exists for: a world one version behind still reads, because the reader
	 * migrates on purpose, so nothing else notices.
	 */
	@Test
	void aWorldBehindTheCurrentFormatIsNamed() throws IOException {
		Path base = write();
		downgrade(Capture.companion(base, ".world"), "#stride-world", WorldSnapshotCodec.FORMAT_VERSION - 1);

		String staleness = CaptureFormat.of(base).staleness("round-trip").orElseThrow();

		assertTrue(staleness.contains("world format " + (WorldSnapshotCodec.FORMAT_VERSION - 1) + " against "
		               + WorldSnapshotCodec.FORMAT_VERSION),
		    staleness);
	}

	@Test
	void worldEventsBehindTheCurrentFormatAreNamed() throws IOException {
		Path base = write();
		downgrade(Capture.companion(base, ".events"), "#stride-world-events", WorldEventTrace.FORMAT_VERSION - 1);

		String staleness = CaptureFormat.of(base).staleness("round-trip").orElseThrow();

		assertTrue(staleness.contains("world events format " + (WorldEventTrace.FORMAT_VERSION - 1) + " against "
		               + WorldEventTrace.FORMAT_VERSION),
		    staleness);
	}

	@Test
	void everyStaleFileIsNamedTogether() throws IOException {
		Path base = write();
		downgrade(Capture.companion(base, ".tsv"), "#stride-trace", Trace.FORMAT_VERSION - 1);
		downgrade(Capture.companion(base, ".world"), "#stride-world", WorldSnapshotCodec.FORMAT_VERSION - 1);
		downgrade(Capture.companion(base, ".events"), "#stride-world-events", WorldEventTrace.FORMAT_VERSION - 1);

		String staleness = CaptureFormat.of(base).staleness("round-trip").orElseThrow();

		assertTrue(staleness.contains("trace format"), staleness);
		assertTrue(staleness.contains("world format"), staleness);
		assertTrue(staleness.contains("world events format"), staleness);
	}

	@Test
	void aCaptureWithoutOptionalCompanionsIsNotStaleForLackingThem() throws IOException {
		Path base = Capture.basePath(this.temporaryDirectory, TraceProducer.LIVE_GAME, "trace-only");
		Capture.writeUnverified(base, Capture.of(TraceFixtures.oneTickTrace("trace-only"), null, null, null));

		CaptureFormat format = CaptureFormat.of(base);

		assertEquals(OptionalInt.empty(), format.world());
		assertEquals(OptionalInt.empty(), format.worldEvents());
		assertEquals(Optional.empty(), format.staleness("trace-only"));
	}

	private Path write() throws IOException {
		Path base = Capture.basePath(this.temporaryDirectory, TraceProducer.LIVE_GAME, "round-trip");
		Capture.writeUnverified(base,
		    Capture.of(TraceFixtures.oneTickTrace("round-trip"), TraceFixtures.oneCellWorld(),
		        new WorldEventTrace(List.of(new WorldEventTrace.BlockCellEvent(0, 0, 0, 0, 0))), null));
		return base;
	}

	/** Rewrites only the magic line, so the file still reads by migration where the reader migrates. */
	private static void downgrade(final Path path, final String magic, final int version) throws IOException {
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		lines.set(0, magic + "\t" + version);
		Files.write(path, lines, StandardCharsets.UTF_8);
	}
}
