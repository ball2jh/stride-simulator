package com.nettarion.stride.simulator.world;

import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import com.nettarion.stride.simulator.block.BlockBehavior;

/**
 * Block records extracted from one Minecraft version independently of any
 * capture, checked against a snapshot's palette before it is compiled.
 *
 * <p>The caller owns extraction from vanilla's registry and loaded tags. The
 * catalog verifies every portable block fact, including ordered collision
 * boxes; it does not verify fluid flow, neighbors, data packs or a live
 * server's permissions. A context-dependent shape needs an explicitly
 * extracted matching variant. No catalog lookup runs during movement.
 * Immutable and safe to share.
 */
public final class BlockStateCatalog {
	/**
	 * An extractor for one Minecraft version, run when a capture is imported.
	 *
	 * <p>{@code extract} receives the snapshot so it can limit extraction to the
	 * block states the palette names rather than the whole registry. It must
	 * take the facts from vanilla, never from the palette entries themselves,
	 * since those are what the result is checked against.
	 *
	 * @param minecraftVersion the version the extractor describes
	 * @param extract the extractor; its result is verified against {@code minecraftVersion}
	 */
	public record Source(String minecraftVersion, Function<WorldSnapshot, BlockStateCatalog> extract) {
		/** Requires both components. */
		public Source {
			Objects.requireNonNull(minecraftVersion, "minecraftVersion");
			Objects.requireNonNull(extract, "extract");
		}

		/**
		 * Extracts the catalog for a snapshot and verifies it against the snapshot and this version.
		 *
		 * @throws IllegalArgumentException when the extracted catalog describes another version
		 * @throws UnimplementedMechanicException when a palette entry's facts differ from the catalog's
		 */
		public BlockStateCatalog catalog(final WorldSnapshot snapshot) {
			BlockStateCatalog result = Objects.requireNonNull(this.extract.apply(snapshot), "extracted catalog");
			result.verify(snapshot, this.minecraftVersion);
			return result;
		}
	}

	/**
	 * The entity-inside shape vanilla uses for one block state.
	 *
	 * @param boxes the shape in block-local coordinates
	 * @param canonicalFull whether the shape is vanilla's canonical full cube by identity
	 */
	public record InsideShape(List<ShapeBox> boxes, boolean canonicalFull) {
		/** Copies the box list. */
		public InsideShape {
			boxes = List.copyOf(boxes);
		}
	}

	/**
	 * A layered cauldron's contents shape and the state it becomes when the
	 * player's fire is extinguished in it.
	 *
	 * @param blockStateId the cauldron state's registry id
	 * @param successorStateId the registry id of the state one level lower
	 * @param insideBoxes the contents shape the player must reach, in block-local coordinates
	 */
	public record Cauldron(int blockStateId, int successorStateId, List<ShapeBox> insideBoxes) {
		/** Copies the box list. */
		public Cauldron {
			insideBoxes = List.copyOf(insideBoxes);
		}
	}

	private final String minecraftVersion;

	private final Map<Integer, List<BlockEntry>> entries;

	private final Map<Integer, Cauldron> cauldrons;

	private final Map<Integer, InsideShape> insideShapes;

	/**
	 * A catalog of extracted records without cauldron transitions. Callers must
	 * not derive the records from the snapshot being verified.
	 */
	public BlockStateCatalog(final String minecraftVersion, final Collection<BlockEntry> entries) {
		this(minecraftVersion, entries, List.of(), Map.of());
	}

	/**
	 * A catalog of extracted records with cauldron transitions and the
	 * entity-inside shapes they need: a cauldron level change is admitted only
	 * where the traversal can tell which cells the player's move reached.
	 *
	 * @throws IllegalArgumentException when cauldrons are given without inside shapes, a cauldron names a
	 *     state the entries do not hold, or two cauldrons share a state
	 */
	public BlockStateCatalog(final String minecraftVersion, final Collection<BlockEntry> entries,
	    final Collection<Cauldron> cauldrons, final Map<Integer, InsideShape> insideShapes) {
		this.insideShapes = Map.copyOf(insideShapes);
		if (!cauldrons.isEmpty() && this.insideShapes.isEmpty()) {
			throw new IllegalArgumentException("cauldron transitions require the extracted entity-inside shapes");
		}
		this.minecraftVersion = Objects.requireNonNull(minecraftVersion, "minecraftVersion");
		Map<Integer, List<BlockEntry>> variants = new HashMap<>();
		for (BlockEntry entry : entries) {
			variants.computeIfAbsent(entry.blockStateId(), ignored -> new ArrayList<>()).add(entry);
		}
		variants.replaceAll((id, values) -> List.copyOf(values));
		this.entries = Map.copyOf(variants);
		Map<Integer, Cauldron> transitions = new HashMap<>();
		for (Cauldron cauldron : cauldrons) {
			if (!this.entries.containsKey(cauldron.blockStateId())
			    || !this.entries.containsKey(cauldron.successorStateId())) {
				throw new IllegalArgumentException("cauldron transition names a state the catalog does not hold");
			}
			if (transitions.put(cauldron.blockStateId(), cauldron) != null) {
				throw new IllegalArgumentException("duplicate cauldron state " + cauldron.blockStateId());
			}
		}
		this.cauldrons = Map.copyOf(transitions);
	}

	/** The extracted records, ordered by block-state id; an id with several variants lists each. */
	public List<BlockEntry> entries() {
		return this.entries.entrySet()
		    .stream()
		    .sorted(Map.Entry.comparingByKey())
		    .flatMap(e -> e.getValue().stream())
		    .toList();
	}

	/** The cauldron transitions, ordered by block-state id. */
	public List<Cauldron> cauldrons() {
		return this.cauldrons.values().stream().sorted(Comparator.comparingInt(Cauldron::blockStateId)).toList();
	}

	/** The entity-inside shapes by block-state id; immutable. */
	public Map<Integer, InsideShape> insideShapes() {
		return this.insideShapes;
	}

	/** The Minecraft version these records describe. */
	public String minecraftVersion() {
		return this.minecraftVersion;
	}

	/**
	 * Refuses a snapshot whose palette holds an entry this catalog does not.
	 *
	 * @throws IllegalArgumentException when {@code expectedVersion} is not this catalog's version
	 * @throws UnimplementedMechanicException with {@link RefusalCause#INADMISSIBLE_BLOCK_FACTS} when a
	 *     palette entry equals no extracted record for its state id
	 */
	public void verify(final WorldSnapshot snapshot, final String expectedVersion) {
		if (!this.minecraftVersion.equals(expectedVersion)) {
			throw new IllegalArgumentException(
			    "block catalog describes " + this.minecraftVersion + ", expected " + expectedVersion);
		}
		for (BlockEntry entry : snapshot.palette()) {
			if (!this.entries.getOrDefault(entry.blockStateId(), List.of()).contains(entry)) {
				throw UnimplementedMechanicException.deferred(RefusalCause.INADMISSIBLE_BLOCK_FACTS,
				    ()
				        -> "block facts differ from the " + this.minecraftVersion + " catalog for state "
				        + entry.blockStateId() + " (" + entry.name() + ")");
			}
		}
	}

	/**
	 * The snapshot with every cauldron successor its palette lacks appended,
	 * after {@link #verify}; the snapshot itself when nothing is appended. A
	 * successor already in the palette under any
	 * variant is kept; one absent from it is taken from the catalog, which must
	 * then hold exactly one variant, since the palette gives no way to choose.
	 *
	 * @throws UnimplementedMechanicException with {@link RefusalCause#INADMISSIBLE_BLOCK_FACTS} when an
	 *     absent successor has several extracted variants
	 */
	WorldSnapshot withSuccessors(final WorldSnapshot snapshot, final String expectedVersion) {
		verify(snapshot, expectedVersion);
		List<BlockEntry> palette = new ArrayList<>(snapshot.palette());
		for (int i = 0; i < palette.size(); i++) {
			Cauldron cauldron = this.cauldrons.get(palette.get(i).blockStateId());
			if (cauldron == null) {
				continue;
			}
			int successorId = cauldron.successorStateId();
			if (palette.stream().anyMatch(entry -> entry.blockStateId() == successorId)) {
				continue;
			}
			List<BlockEntry> variants = this.entries.get(successorId);
			if (variants.size() != 1) {
				throw UnimplementedMechanicException.deferred(RefusalCause.INADMISSIBLE_BLOCK_FACTS,
				    ()
				        -> "cauldron successor state " + successorId + " has " + variants.size()
				        + " extracted variants and the palette names none of them");
			}
			palette.add(variants.getFirst());
		}
		if (palette.size() == snapshot.palette().size()) {
			return snapshot;
		}
		// Both snapshots are immutable, so the extended palette shares the sections.
		return snapshot.withPalette(palette);
	}

	/**
	 * Installs the entity-inside shapes and replaces each cauldron entry's
	 * behavior with a a layered-cauldron behavior naming its successor's
	 * palette index, before the tables are shared.
	 */
	void installBehaviors(final PaletteTables tables, final WorldSnapshot snapshot) {
		List<BlockEntry> palette = snapshot.palette();
		if (!this.insideShapes.isEmpty()) {
			tables.sourceInsideShapes = new double[palette.size()][];
			tables.sourceInsideFull = new boolean[palette.size()];
			for (int i = 0; i < palette.size(); i++) {
				InsideShape shape = this.insideShapes.get(palette.get(i).blockStateId());
				if (shape == null) {
					continue;
				}
				tables.sourceInsideShapes[i] = BlockBehavior.flattenShape(shape.boxes());
				tables.sourceInsideFull[i] = shape.canonicalFull();
			}
		}
		for (int i = 0; i < palette.size(); i++) {
			Cauldron cauldron = this.cauldrons.get(palette.get(i).blockStateId());
			if (cauldron == null) {
				continue;
			}
			int successor = -1;
			for (int j = 0; j < palette.size(); j++) {
				if (palette.get(j).blockStateId() == cauldron.successorStateId()) {
					successor = j;
					break;
				}
			}
			tables.behavior[i] = BlockBehavior.layeredCauldron(successor, cauldron.insideBoxes());
		}
	}
}
