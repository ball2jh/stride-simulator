package com.nettarion.stride.simulator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How each block class that can reach a player transition is classified.
 *
 * <p>The bundled inventory records the source project's classification of movement-hook
 * overrides. Consumers may use it when admitting captured regions. Regenerating or proving
 * completeness against the game requires external native tooling, which is not part of
 * this standalone library. Loading the table does not itself validate a world's block facts.
 *
 * <p>This is committed conformance evidence, not runtime movement data.
 */
public final class MovementClasses {
	/** The admission decision for a block class that overrides a movement hook. */
	public enum Classification { MODELLED, GEOMETRY_ONLY, NO_MOVEMENT_EFFECT, UNSUPPORTED }

	/**
	 * The third column: the body the block's {@code entityInside} or
	 * {@code stepOn} runs for a player on the server beyond the movement
	 * path, such as a hit, an effect, a teleport, or a freeze.
	 *
	 * <p>This is one hook family and nothing else. A landing body
	 * ({@code fallOn}) is the landing column's; a fluid's body belongs to the
	 * fluid entry the capture resolves beside the block; a world change the
	 * hook schedules is not a body on the player. The value is consumed as
	 * the block's contact fact, so a broader meaning here becomes a false
	 * refusal there.
	 */
	public enum ServerContact {
		/** No such body, or one that writes nothing on a player. */
		NONE,
		/** A body the simulator's server tick runs; the capture names the class. */
		MODELLED,
		/** A body the slice does not model; a visit refuses. */
		UNMODELLED
	}

	private static final String RESOURCE = "/stride/movement-classes.tsv";

	private final Map<String, Classification> byClass;
	private final Map<String, ServerContact> contacts;
	private final Map<String, String> reasons;

	private MovementClasses(final Map<String, Classification> byClass, final Map<String, ServerContact> contacts,
	    final Map<String, String> reasons) {
		this.byClass = Collections.unmodifiableMap(byClass);
		this.contacts = Collections.unmodifiableMap(contacts);
		this.reasons = Collections.unmodifiableMap(reasons);
	}

	/** Loads the bundled movement-hook inventory, failing if its resource is missing or malformed. */
	public static MovementClasses load() {
		Map<String, Classification> byClass = new LinkedHashMap<>();
		Map<String, ServerContact> contacts = new LinkedHashMap<>();
		Map<String, String> reasons = new LinkedHashMap<>();
		try (InputStream stream = MovementClasses.class.getResourceAsStream(RESOURCE)) {
			if (stream == null) {
				throw new IllegalStateException(
				    "missing " + RESOURCE + "; the movement classification is not on the classpath");
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank() || line.startsWith("#")) {
					continue;
				}
				String[] parts = line.split("\t", -1);
				if (parts.length < 4) {
					throw new IllegalArgumentException("a classification needs a class, a"
					    + " movement class, a server contact and a reason: " + line);
				}
				Classification classification;
				try {
					classification = Classification.valueOf(parts[1]);
				} catch (IllegalArgumentException unknown) {
					throw new IllegalArgumentException(
					    "unknown movement classification '" + parts[1] + "' for " + parts[0], unknown);
				}
				if (byClass.put(parts[0], classification) != null) {
					throw new IllegalArgumentException("duplicate classification: " + parts[0]);
				}
				// Parsed rather than defaulted, so a class added without deciding this
				// question fails the load instead of silently reading as harmless.
				switch (parts[2]) {
					case "NONE", "MODELLED", "UNMODELLED" -> contacts.put(parts[0], ServerContact.valueOf(parts[2]));
					default ->
						throw new IllegalArgumentException("'" + parts[2] + "' is not a server contact for " + parts[0]
						    + "; use NONE, MODELLED or UNMODELLED");
				}
				reasons.put(parts[0], parts[3]);
			}
		} catch (IOException failed) {
			throw new UncheckedIOException("cannot read " + RESOURCE, failed);
		}
		return new MovementClasses(byClass, contacts, reasons);
	}

	/**
	 * The server body this class's contact or step hook runs on a player. An
	 * unlisted class is {@link ServerContact#NONE}: the build gate proves
	 * every class with a contact hook is listed, so an unlisted one has no
	 * body to run.
	 */
	public ServerContact serverContact(final String implementingClass) {
		return this.contacts.getOrDefault(implementingClass, ServerContact.NONE);
	}

	/**
	 * Whether contact with this block class must fail closed.
	 *
	 * <p>An <em>unlisted</em> class is not unsupported: the overwhelming majority
	 * of blocks override no movement hook at all and are fully described by their
	 * captured geometry and coefficients. What guarantees that an unlisted class
	 * is genuinely inert is the build gate, not this method.
	 */
	public boolean isUnsupported(final String implementingClass) {
		return Classification.UNSUPPORTED == this.byClass.get(implementingClass);
	}

	/** Why it was classified that way, for a refusal that can be diagnosed. */
	public String reasonFor(final String implementingClass) {
		return this.reasons.getOrDefault(implementingClass, "unclassified");
	}

	/** Returns the immutable movement-hook classification keyed by upstream block class name. */
	public Map<String, Classification> byClass() {
		return this.byClass;
	}
}
