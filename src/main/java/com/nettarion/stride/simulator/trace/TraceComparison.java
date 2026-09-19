package com.nettarion.stride.simulator.trace;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Raw-bit trace equality, implemented once.
 *
 * <p>Raw-bit equality on the audited state vector, for every tick, with no
 * re-anchoring. There is deliberately no tolerance parameter: a tolerance would
 * hide exactly the near-miss transitions the consumers exist to find, so if a
 * mode cannot reach bit-exactness the mode is scoped out rather than the
 * relation weakened.
 *
 * <p>Only the first diverging tick is reported. Without re-anchoring every
 * later tick is computed from an already-wrong state, so listing them would
 * bury the one fact that localizes the bug.
 */
public sealed interface TraceComparison {
	/** A comparison in which every compared tick agreed bit for bit. */
	record Agreement(int ticks) implements TraceComparison {}

	/** The first differing tick and its per-field differences. */
	record Divergence(int tick, List<FieldDivergence> fields) implements TraceComparison {}

	/** The traces cannot be meaningfully compared at all. Never a physics result. */
	record Incomparable(String reason) implements TraceComparison {}

	/** One differing state field, rendered from the reference and candidate raw values. */
	record FieldDivergence(StateField field, String reference, String candidate) {}

	static TraceComparison compare(final Trace reference, final Trace candidate) {
		return compare(reference, candidate, EnumSet.noneOf(StateField.class));
	}

	/**
	 * @param excluded fields declared inert or unreachable
	 *                 requires this list to be named, versioned and reviewed —
	 *                 it is a parameter rather than a default so that no
	 *                 exclusion can be inherited silently.
	 */
	static TraceComparison compare(final Trace reference, final Trace candidate, final Set<StateField> excluded) {
		EnumSet<StateField> effectiveExcluded = EnumSet.noneOf(StateField.class);
		effectiveExcluded.addAll(excluded);
		if (!reference.header().scenario().equals(candidate.header().scenario())) {
			return new Incomparable(
			    "different scenarios: " + reference.header().scenario() + " and " + candidate.header().scenario());
		}
		if (!reference.header().minecraftVersion().equals(candidate.header().minecraftVersion())) {
			return new Incomparable("different Minecraft versions: " + reference.header().minecraftVersion() + " and "
			    + candidate.header().minecraftVersion());
		}
		if (reference.entries().size() != candidate.entries().size()) {
			return new Incomparable(
			    "different tick counts: " + reference.entries().size() + " and " + candidate.entries().size());
		}

		List<FieldDivergence> initial =
		    diverging(reference.initialState(), candidate.initialState(), effectiveExcluded);
		if (!initial.isEmpty()) {
			// The implementations did not start from the same state, so any later
			// agreement or disagreement says nothing about the transition.
			return new Incomparable("initial states differ: " + describe(initial));
		}

		for (int i = 0; i < reference.entries().size(); i++) {
			Trace.TraceEntry referenceEntry = reference.entries().get(i);
			Trace.TraceEntry candidateEntry = candidate.entries().get(i);
			if (referenceEntry.tick() != candidateEntry.tick()) {
				return new Incomparable("tick numbering differs at index " + i + ": " + referenceEntry.tick() + " and "
				    + candidateEntry.tick());
			}
			if (!referenceEntry.action().equals(candidateEntry.action())) {
				return new Incomparable("different actions applied at tick " + referenceEntry.tick());
			}
			List<FieldDivergence> fields = diverging(referenceEntry.state(), candidateEntry.state(), effectiveExcluded);
			if (!fields.isEmpty()) {
				return new Divergence(referenceEntry.tick(), fields);
			}
		}
		return new Agreement(reference.entries().size());
	}

	private static List<FieldDivergence> diverging(
	    final StateVector reference, final StateVector candidate, final Set<StateField> excluded) {
		List<FieldDivergence> divergences = new ArrayList<>();
		for (StateField field : StateField.ALL) {
			if (excluded.contains(field)) {
				continue;
			}
			if (reference.rawBits(field) != candidate.rawBits(field)) {
				divergences.add(new FieldDivergence(field, render(field, reference), render(field, candidate)));
			}
		}
		return divergences;
	}

	/**
	 * Renders a field for a human. Bits first, because bits are what diverged;
	 * the decoded value second, because that is what makes the bug obvious.
	 */
	private static String render(final StateField field, final StateVector state) {
		long bits = state.rawBits(field);
		return switch (field.kind()) {
			case DOUBLE -> String.format("0x%016x (%s)", bits, Double.longBitsToDouble(bits));
			case FLOAT -> String.format("0x%08x (%s)", bits, Float.intBitsToFloat((int) bits));
			case BYTE_FLAGS -> String.format("0x%02x", bits);
			case BOOLEAN -> bits != 0L ? "true" : "false";
			case INT -> Integer.toString((int) bits);
			case ENUM -> bits + ":" + state.label(field);
		};
	}

	private static String describe(final List<FieldDivergence> fields) {
		StringBuilder description = new StringBuilder();
		for (FieldDivergence field : fields) {
			description.append("\n  ")
			    .append(field.field())
			    .append(": reference ")
			    .append(field.reference())
			    .append(", candidate ")
			    .append(field.candidate());
		}
		return description.toString();
	}
}
