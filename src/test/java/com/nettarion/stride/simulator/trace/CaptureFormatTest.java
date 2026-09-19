package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.world.WorldSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.BlockEntry;

class CaptureFormatTest {
	@TempDir Path temporaryDirectory;

	@Test
	void afreshRecordingIsCurrent() throws IOException {
		Path base = write();

		assertEquals(CaptureFormat.current(), CaptureFormat.of(base));
		assertNull(CaptureFormat.of(base).staleness("round-trip"));
	}

	/**
	 * The corpus failure this exists for: a capture behind the recorder still
	 * reads, because the readers migrate on purpose, so nothing else notices.
	 */
	@Test
	void aTraceBehindTheRecorderIsRefusedByName() throws IOException {
		Path base = write();
		Path trace = Capture.companion(base, ".tsv");
		downgrade(trace, "#stride-trace", Trace.FORMAT_VERSION - 1);

		String staleness = CaptureFormat.of(base).staleness("round-trip");

		assertNotNull(staleness, "a trace one version behind must be refused");
		assertTrue(
		    staleness.contains("trace format " + (Trace.FORMAT_VERSION - 1) + " against " + Trace.FORMAT_VERSION),
		    staleness);
		assertTrue(staleness.contains("a compatible external capture producer"), staleness);
	}

	@Test
	void aWorldBehindTheRecorderIsRefusedByName() throws IOException {
		Path base = write();
		downgrade(Capture.companion(base, ".world"), "#stride-world", WorldSnapshotCodec.FORMAT_VERSION - 1);

		String staleness = CaptureFormat.of(base).staleness("round-trip");

		assertNotNull(staleness, "a world one version behind must be refused");
		assertTrue(staleness.contains("world format " + (WorldSnapshotCodec.FORMAT_VERSION - 1) + " against "
		               + WorldSnapshotCodec.FORMAT_VERSION),
		    staleness);
	}

	@Test
	void aCaptureWithoutOptionalCompanionsIsNotStaleForLackingThem() throws IOException {
		Path base = Capture.basePath(this.temporaryDirectory, TraceProducer.LIVE_GAME, "trace-only");
		Capture.writeUnverified(base, Capture.of(trace("trace-only"), null, null, null));

		CaptureFormat format = CaptureFormat.of(base);

		assertEquals(CaptureFormat.MISSING, format.world());
		assertEquals(CaptureFormat.MISSING, format.worldEvents());
		assertNull(format.staleness("trace-only"));
	}

	private Path write() throws IOException {
		Path base = Capture.basePath(this.temporaryDirectory, TraceProducer.LIVE_GAME, "round-trip");
		Capture.writeUnverified(base,
		    Capture.of(trace("round-trip"), world(),
		        new WorldEventTrace(List.of(new WorldEventTrace.CellStateEvent(0, 0, 0, 0, 0))), null));
		return base;
	}

	/** Rewrites only the magic line, so the file still reads by migration. */
	private static void downgrade(final Path path, final String magic, final int version) throws IOException {
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		lines.set(0, magic + "\t" + version);
		Files.write(path, lines, StandardCharsets.UTF_8);
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
		return new WorldSnapshot(0, 0, 0, 1, 1, 1, OutsidePolicy.REFUSING,
		    List.of(new BlockEntry(0, "minecraft:air", 0.6F, 1.0F, 1.0F, List.of())), new int[] {0});
	}
}
