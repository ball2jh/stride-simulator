package com.nettarion.stride.simulator.world;

import com.nettarion.stride.simulator.Refusal;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.block.BlockBehaviour;
import com.nettarion.stride.simulator.block.LayeredCauldronBlock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Source-extracted block records for one Minecraft version, checked before palette compilation.
 *
 * <p>The supplying adapter owns extraction from the pinned registry and loaded tags. This
 * table verifies every portable block fact, including ordered collision boxes; it does not
 * certify fluid flow, neighbours, datapacks or a live server's permissions. Context-dependent
 * shapes need an explicitly extracted matching variant. No catalog lookup runs during movement.
 */
public final class BlockStateCatalog {
	/** A pinned independent extractor used at the import/observation boundary. */
	public record
	    Source(String minecraftVersion, java.util.function.Function<WorldSnapshot, BlockStateCatalog> extract) {
		public Source {
			Objects.requireNonNull(minecraftVersion);
			Objects.requireNonNull(extract);
		}
		/** Extracts independent records for a snapshot and verifies them against this source's pinned version. */
		public BlockStateCatalog catalog(final WorldSnapshot snapshot) {
			BlockStateCatalog result = Objects.requireNonNull(this.extract.apply(snapshot));
			result.verify(snapshot, this.minecraftVersion);
			return result;
		}
	}

	private final String minecraftVersion;
	private final Map<Integer, List<WorldSnapshot.BlockEntry>> entries;
	private final Map<Integer, Cauldron> cauldrons;
	private final Map<Integer, InsideShape> insideShapes;
	/** Source entity-inside shape, including the canonical full-cube identity shortcut. */
	public record InsideShape(List<WorldSnapshot.ShapeBox> boxes, boolean canonicalFull) {
		public InsideShape {
			boxes = List.copyOf(boxes);
		}
	}

	/** Source facts for a layered cauldron's contents shape and extinguishing successor. */
	public record Cauldron(int blockStateId, int successorStateId, List<WorldSnapshot.ShapeBox> insideBoxes) {
		public Cauldron {
			insideBoxes = List.copyOf(insideBoxes);
		}
	}

	/** Build from source-extracted records; callers must not derive these from the input being verified. */
	public BlockStateCatalog(final String minecraftVersion, final Collection<WorldSnapshot.BlockEntry> entries,
	    final Collection<Cauldron> cauldrons) {
		this(minecraftVersion, entries, cauldrons, Map.of());
	}

	/** Build with source inside shapes, preserving inert visits that advance collector steps. */
	public BlockStateCatalog(final String minecraftVersion, final Collection<WorldSnapshot.BlockEntry> entries,
	    final Collection<Cauldron> cauldrons, final Map<Integer, InsideShape> insideShapes) {
		this.insideShapes = Map.copyOf(insideShapes);
		if (!cauldrons.isEmpty() && this.insideShapes.isEmpty())
			throw new IllegalArgumentException(
			    "cauldron mutations require source inside shapes for collector ordering");
		this.minecraftVersion = Objects.requireNonNull(minecraftVersion);
		Map<Integer, List<WorldSnapshot.BlockEntry>> variants = new HashMap<>();
		for (var entry : entries)
			variants.computeIfAbsent(entry.blockStateId(), ignored -> new ArrayList<>()).add(entry);
		variants.replaceAll((id, values) -> List.copyOf(values));
		this.entries = Map.copyOf(variants);
		Map<Integer, Cauldron> transitions = new HashMap<>();
		for (var cauldron : cauldrons) {
			if (!this.entries.containsKey(cauldron.blockStateId())
			    || !this.entries.containsKey(cauldron.successorStateId()))
				throw new IllegalArgumentException("cauldron transition lacks catalog entries");
			if (transitions.put(cauldron.blockStateId(), cauldron) != null)
				throw new IllegalArgumentException("duplicate cauldron state");
		}
		this.cauldrons = Map.copyOf(transitions);
	}

	/** Immutable extracted records for portable source evidence. */
	public List<WorldSnapshot.BlockEntry> entries() {
		return this.entries.entrySet()
		    .stream()
		    .sorted(Map.Entry.comparingByKey())
		    .flatMap(e -> e.getValue().stream())
		    .toList();
	}
	/** Returns immutable cauldron transition records ordered by block-state identifier. */
	public List<Cauldron> cauldrons() {
		return this.cauldrons.values()
		    .stream()
		    .sorted(java.util.Comparator.comparingInt(Cauldron::blockStateId))
		    .toList();
	}
	/** Returns the immutable map of source inside shapes keyed by block-state identifier. */
	public Map<Integer, InsideShape> insideShapes() {
		return this.insideShapes;
	}

	/** Returns the exact Minecraft version these independently extracted facts describe. */
	public String minecraftVersion() {
		return this.minecraftVersion;
	}

	/** Reject mismatched versions and any independently supplied fact that differs from the catalog. */
	public void verify(final WorldSnapshot snapshot, final String expectedVersion) {
		if (!this.minecraftVersion.equals(expectedVersion))
			throw new IllegalArgumentException("block catalog version mismatch");
		for (var entry : snapshot.palette()) {
			if (!this.entries.getOrDefault(entry.blockStateId(), List.of()).contains(entry))
				throw UnimplementedMechanicException.deferred(Refusal.INADMISSIBLE_BLOCK_FACTS,
				    ()
				        -> "block facts differ from " + this.minecraftVersion + " source catalog for state "
				        + entry.blockStateId() + " (" + entry.name() + ")");
		}
	}

	WorldSnapshot withSuccessors(final WorldSnapshot snapshot, final String expectedVersion) {
		verify(snapshot, expectedVersion);
		var palette = new ArrayList<>(snapshot.palette());
		for (int i = 0; i < palette.size(); i++) {
			Cauldron cauldron = this.cauldrons.get(palette.get(i).blockStateId());
			if (cauldron == null) continue;
			var successor = this.entries.get(cauldron.successorStateId()).getFirst();
			if (palette.stream().noneMatch(entry -> entry.blockStateId() == successor.blockStateId()))
				palette.add(successor);
		}
		// Both snapshots are immutable, so the extended palette shares the sections.
		return snapshot.withPalette(palette);
	}

	void installBehaviours(final SnapshotView view, final WorldSnapshot snapshot, final BlockBehaviour[] behaviours) {
		if (!this.insideShapes.isEmpty()) {
			view.sourceInsideShapes = new double[behaviours.length][];
			view.sourceInsideFull = new boolean[behaviours.length];
			for (int i = 0; i < behaviours.length; i++) {
				InsideShape shape = this.insideShapes.get(snapshot.palette().get(i).blockStateId());
				if (shape == null) continue;
				view.sourceInsideShapes[i] = LayeredCauldronBlock.compileShape(shape.boxes());
				view.sourceInsideFull[i] = shape.canonicalFull();
			}
		}
		for (int i = 0; i < behaviours.length; i++) {
			Cauldron cauldron = this.cauldrons.get(snapshot.palette().get(i).blockStateId());
			if (cauldron == null) continue;
			int successor = -1;
			for (int j = 0; j < behaviours.length; j++)
				if (snapshot.palette().get(j).blockStateId() == cauldron.successorStateId()) {
					successor = j;
					break;
				}
			behaviours[i] = new LayeredCauldronBlock(successor, cauldron.insideBoxes());
		}
	}
}
