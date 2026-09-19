package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.tick.ClientTick;
import com.nettarion.stride.simulator.world.FlatFloorView;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every refusal names its boundary and costs no stack trace.
 *
 * <p>A refusal is thrown on the search's hot path for every branch that
 * leaves the admitted slice, so it is a signal with a cause, not a fault
 * with a trace. The four refusal types all extend {@link RefusalException}, and
 * each throw site inside the simulator names a {@link RefusalCause}.
 */
class RefusalTest {
	@Test
	void aRefusalFromTheTickCarriesItsCauseAndNoTrace() {
		PlayerState state = new PlayerState();
		state.x = 0.5;
		state.y = 1.0;
		state.z = 0.5;
		state.setBox(
		    AABB.around(state.x, state.y, state.z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));
		state.autoJumpEnabled = true;

		UnimplementedMechanicException refusal = assertThrows(UnimplementedMechanicException.class,
		    () -> new ClientTick().tick(state, PlayerInput.idle(0.0F, 0.0F), FlatFloorView.ordinary(1, -64)));

		assertSame(RefusalCause.INADMISSIBLE_STATE, refusal.cause());
		assertEquals("auto-jump is enabled", refusal.getMessage(),
		    "the message is recorded evidence and does not change with the cause");
		assertFalse(RefusalException.TRACE, "the trace property is off in the test JVM");
		assertEquals(
		    0, refusal.getStackTrace().length, "a refusal records no trace unless stride.refusal.trace is set");
	}

	@Test
	void everyRefusalTypeCarriesItsCauseAndNoTrace() {
		RefusalException[] refusals = {
		    new UnimplementedMechanicException(RefusalCause.UNMODELED_BLOCK, "unmodeled"),
		    new PendingServerWriteException(RefusalCause.PENDING_ABILITY, "pending"),
		    new StaleServerWriteException(1L, 2L)};
		assertSame(RefusalCause.UNMODELED_BLOCK, refusals[0].cause());
		assertSame(RefusalCause.PENDING_ABILITY, refusals[1].cause());
		assertSame(RefusalCause.STALE_WRITE, refusals[2].cause());
		for (RefusalException refusal : refusals) {
			assertEquals(0, refusal.getStackTrace().length, refusal.getClass().getName());
		}
	}

	/**
	 * Every throw site inside the simulator names its cause. Scans the library's
	 * own source, as the phase write-set test scans its fields, so a new site
	 * cannot omit the cause without saying so here.
	 */
	@Test
	void everyThrowSiteInsideTheSimulatorNamesItsCause() throws IOException {
		Path root = Path.of("src/main/java/com/nettarion/stride/simulator");
		assertTrue(Files.isDirectory(root), "run from the simulator module: " + root.toAbsolutePath());
		Pattern site = Pattern.compile(
		    "(?:new |UnimplementedMechanicException\\.deferred)(UnimplementedMechanicException|PendingServerWriteException)?\\(\\s*([^)]{0,40})");
		StringBuilder unnamed = new StringBuilder();
		try (Stream<Path> files = Files.walk(root)) {
			for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
				String name = file.getFileName().toString();
				if (name.equals("UnimplementedMechanicException.java")
				    || name.equals("PendingServerWriteException.java")) {
					continue;
				}
				String source = Files.readString(file);
				Matcher matcher = site.matcher(source);
				while (matcher.find()) {
					String head = matcher.group(2);
					if (head.startsWith("RefusalCause.") || head.isEmpty() && name.equals("Simulator.java")) {
						continue;
					}
					unnamed.append(root.relativize(file)).append(": ").append(matcher.group()).append('\n');
				}
			}
		}
		assertEquals("", unnamed.toString(), "throw sites without a RefusalCause cause");
	}
}
