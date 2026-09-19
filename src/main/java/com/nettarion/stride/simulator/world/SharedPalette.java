package com.nettarion.stride.simulator.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One growing palette that every section a producer captures indexes into,
 * so composing those sections into a corridor copies rows whole instead of
 * remapping every cell.
 *
 * <p>{@link WorldSnapshot#compose} unions its parts' palettes and remaps each
 * part's cells into the union. That is the whole cost of publishing a corridor
 * when the parts are the pilot's retained sections, most of which it held at
 * the previous publication: millions of cells re-indexed to say what they
 * already said. A producer that interns its entries here instead gives every
 * section the same index for the same state, and the union of parts that
 * share a palette is that palette, so their rows copy with one array copy
 * each and no cell is touched.
 *
 * <p>Append-only and never shrinks, so an index handed out stays valid for
 * every section that holds it; the parts a corridor composes therefore never
 * disagree about what an index means. Indexing more distinct states than a
 * session ever meets is a few thousand entries. Not thread-safe; the producer
 * that captures sections owns it.
 */
public final class SharedPalette {
	private final List<BlockEntry> blocks = new ArrayList<>();
	private final Map<BlockEntry, Integer> blockIndices = new HashMap<>();
	private final List<FluidEntry> fluids = new ArrayList<>();
	private final Map<FluidEntry, Integer> fluidIndices = new HashMap<>();
	private List<BlockEntry> blockView = List.of();
	private List<FluidEntry> fluidView = List.of();

	/** Creates an empty palette interner for sharing consistent block and fluid identities across snapshots. */
	public SharedPalette() {
		this.fluidIndices.put(FluidEntry.EMPTY, 0);
		this.fluids.add(FluidEntry.EMPTY);
	}

	/** The index of {@code entry}, interned on first sight. */
	public int index(final BlockEntry entry) {
		Integer index = this.blockIndices.get(Objects.requireNonNull(entry, "entry"));
		if (index == null) {
			index = this.blocks.size();
			this.blocks.add(entry);
			this.blockIndices.put(entry, index);
			this.blockView = null;
		}
		return index;
	}

	/** The index of {@code entry}, interned on first sight; the empty entry is always zero. */
	public int fluidIndex(final FluidEntry entry) {
		Integer index = this.fluidIndices.get(Objects.requireNonNull(entry, "entry"));
		if (index == null) {
			index = this.fluids.size();
			this.fluids.add(entry);
			this.fluidIndices.put(entry, index);
			this.fluidView = null;
		}
		return index;
	}

	/**
	 * The palette as it stands, an immutable list every section captured so
	 * far indexes into. A later intern does not change the entries already
	 * listed, so a section holding an earlier list still reads its own cells
	 * correctly from a later one.
	 */
	public List<BlockEntry> blocks() {
		List<BlockEntry> view = this.blockView;
		if (view == null) {
			view = Collections.unmodifiableList(new ArrayList<>(this.blocks));
			this.blockView = view;
		}
		return view;
	}

	/** The fluid palette as it stands; see {@link #blocks()}. */
	public List<FluidEntry> fluids() {
		List<FluidEntry> view = this.fluidView;
		if (view == null) {
			view = Collections.unmodifiableList(new ArrayList<>(this.fluids));
			this.fluidView = view;
		}
		return view;
	}

	/** Distinct block states interned so far. */
	public int size() {
		return this.blocks.size();
	}
}
