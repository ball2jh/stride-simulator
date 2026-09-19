package com.nettarion.stride.simulator.world;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One immutable 16-cube of a {@link WorldSnapshot}: its block and fluid
 * cells and the coarse facts a view answers span questions from, shared by
 * every snapshot that holds the same cells on the same palette.
 *
 * <p>This is the unit vanilla's world is made of ({@code LevelChunkSection})
 * and the unit a producer captures, retains by revision and re-composes. A
 * section is a pure function of its cells and the entries they select, so a
 * composition or a crop that keeps a part on the same palette keeps the
 * object; nothing is copied and nothing is rescanned. That is what makes a
 * new snapshot cost what changed rather than what is held.
 *
 * <p>A uniform section holds no plane: every cell is {@link #uniform}. Air
 * over a valley, stone under a floor, is one shared object per palette
 * index. A dry section holds no fluid plane. Cells lie x fastest, then z,
 * then y: {@code (y * 16 + z) * 16 + x}.
 *
 * <p>Every section carries a {@link #revision}: a number no other section
 * in this process has, taken when its cells were first scanned. A
 * composition that keeps a section keeps its revision, so a pose-fit cache
 * keyed on the revisions of the sections it touched survives a later
 * snapshot that did not change them, and a re-indexed section keeps its
 * revision too, since its cells select the same entries. {@link #MISSING} is
 * the one section that is not a section: a slot a composition's parts do not
 * cover. Its facts say anything may be there, so a coarse question refines
 * into it, and every cell read into it refuses; a world holding one is not
 * padding, it is a hole the simulator will not guess across.
 */
final class Section {
	/** Cells along one edge of a section. */
	static final int EDGE = 16;

	/** Cells in a section. */
	static final int CELLS = EDGE * EDGE * EDGE;

	/** {@link #EDGE} as a power of two: a world coordinate shifted by this is a section coordinate. */
	static final int SHIFT = 4;

	/** A world coordinate masked by this is a local coordinate within its section. */
	static final int MASK = EDGE - 1;

	private static final AtomicLong REVISIONS = new AtomicLong(1L);

	/** The section a composition holds where no part covers; see the class description. */
	static final Section MISSING = new Section(null, -1, null, CELLS, (short) -1, true,
	    filled(new short[64], (short) -1), filled(new boolean[64], true), (short) -1, 0L);

	private final int[] cells;

	private final int uniform;

	private final int[] fluidCells;

	/** Cells whose entry can collide in some entity context. */
	final int collisionCount;

	/** The union of the cells' behavior bits. */
	final short properties;

	/** Whether any cell's shape leaves its own cell. */
	final boolean large;

	/** Per fine 4-cube: the union of its cells' behavior bits. */
	final short[] fineProperties;

	/** Per fine 4-cube: whether any cell's shape leaves its own cell. */
	final boolean[] fineLarge;

	/** The bits every fine cube carries. */
	final short uniformProperties;

	/** This section's number among every section this process has scanned; zero for {@link #MISSING}. */
	final long revision;

	private Section(final int[] cells, final int uniform, final int[] fluidCells, final int collisionCount,
	    final short properties, final boolean large, final short[] fineProperties, final boolean[] fineLarge,
	    final short uniformProperties, final long revision) {
		this.cells = cells;
		this.uniform = uniform;
		this.fluidCells = fluidCells;
		this.collisionCount = collisionCount;
		this.properties = properties;
		this.large = large;
		this.fineProperties = fineProperties;
		this.fineLarge = fineLarge;
		this.uniformProperties = uniformProperties;
		this.revision = revision;
	}

	/**
	 * A section over planes the caller wrote and will not touch again:
	 * {@code cells} holds {@link #CELLS} indices into {@code palette};
	 * {@code fluidCells} the same into {@code fluidPalette}, or null for a dry
	 * section. Every index is validated. A plane whose cells all agree is
	 * dropped for the uniform form.
	 */
	static Section of(final int[] cells, final int[] fluidCells, final List<BlockEntry> palette,
	    final List<FluidEntry> fluidPalette) {
		if (cells.length != CELLS) {
			throw new IllegalArgumentException("a section holds " + CELLS + " cells, received " + cells.length);
		}
		int size = palette.size();
		int first = cells[0];
		boolean uniform = true;
		for (int index = 0; index < CELLS; index++) {
			int cell = cells[index];
			if (cell < 0 || cell >= size) {
				throw new IllegalArgumentException(
				    "section cell " + index + " has palette index " + cell + ", palette size is " + size);
			}
			uniform &= cell == first;
		}
		int[] fluids = fluidCells;
		if (fluids != null) {
			if (fluids.length != CELLS) {
				throw new IllegalArgumentException(
				    "a section's fluid plane holds " + CELLS + " cells, received " + fluids.length);
			}
			int fluidSize = fluidPalette.size();
			boolean anyFluid = false;
			for (int index = 0; index < CELLS; index++) {
				int fluid = fluids[index];
				if (fluid < 0 || fluid >= fluidSize) {
					throw new IllegalArgumentException("section fluid cell " + index + " has palette index " + fluid
					    + ", palette size is " + fluidSize);
				}
				anyFluid |= fluid != 0;
			}
			if (!anyFluid) {
				fluids = null;
			}
		}
		return summarize(uniform ? null : cells, first, fluids, palette, fluidPalette);
	}

	/** The section every cell of which is {@code paletteIndex}, dry. */
	static Section uniform(
	    final int paletteIndex, final List<BlockEntry> palette, final List<FluidEntry> fluidPalette) {
		if (paletteIndex < 0 || paletteIndex >= palette.size()) {
			throw new IllegalArgumentException(
			    "uniform section palette index " + paletteIndex + ", palette size is " + palette.size());
		}
		return summarize(null, paletteIndex, null, palette, fluidPalette);
	}

	/** The local cell number of a local coordinate. */
	static int index(final int localX, final int localY, final int localZ) {
		return (localY << (2 * SHIFT)) + (localZ << SHIFT) + localX;
	}

	/** Whether this is the {@link #MISSING} section: a slot no part covered, which every cell read refuses. */
	boolean isMissing() {
		return this.revision == 0L;
	}

	/** This section's revision; see the class description. */
	long revision() {
		return this.revision;
	}

	/**
	 * This section's cells re-indexed through {@code blockRemap} and
	 * {@code fluidRemap} (identity when null), for a composition whose union
	 * palette orders the entries differently. The facts are over entries, not
	 * indices, so they carry over unchanged.
	 */
	Section remapped(final int[] blockRemap, final int[] fluidRemap) {
		if (this.revision == 0L || blockRemap == null && (fluidRemap == null || this.fluidCells == null)) {
			return this;
		}
		int[] cells = null;
		int uniform = this.uniform;
		if (blockRemap != null) {
			if (this.cells == null) {
				uniform = blockRemap[this.uniform];
			} else {
				cells = new int[CELLS];
				for (int index = 0; index < CELLS; index++) {
					cells[index] = blockRemap[this.cells[index]];
				}
			}
		} else {
			cells = this.cells;
		}
		int[] fluids = this.fluidCells;
		if (fluidRemap != null && fluids != null) {
			fluids = new int[CELLS];
			for (int index = 0; index < CELLS; index++) {
				fluids[index] = fluidRemap[this.fluidCells[index]];
			}
		}
		return new Section(cells, uniform, fluids, this.collisionCount, this.properties, this.large,
		    this.fineProperties, this.fineLarge, this.uniformProperties, this.revision);
	}

	/** The palette index at a local cell; {@code -1} throughout the {@link #MISSING} section. */
	int cell(final int localX, final int localY, final int localZ) {
		return this.cells == null ? this.uniform : this.cells[index(localX, localY, localZ)];
	}

	/** The palette index at a local cell number, see {@link #index}; {@code -1} throughout {@link #MISSING}. */
	int cell(final int local) {
		return this.cells == null ? this.uniform : this.cells[local];
	}

	/** The fluid palette index at a local cell number; zero throughout a dry section. */
	int fluidCell(final int local) {
		return this.fluidCells == null ? 0 : this.fluidCells[local];
	}

	/** Whether every cell holds one palette index, {@link #uniformIndex}; false for {@link #MISSING}. */
	boolean isUniform() {
		return this.cells == null && this.revision != 0L;
	}

	/** The one index of a uniform section; meaningless otherwise. */
	int uniformIndex() {
		return this.uniform;
	}

	/** Whether any cell selects a fluid. */
	boolean hasFluids() {
		return this.fluidCells != null;
	}

	/** The dense plane, or null when uniform; never written. */
	int[] rawCells() {
		return this.cells;
	}

	/** The dense fluid plane, or null when dry; never written. */
	int[] rawFluidCells() {
		return this.fluidCells;
	}

	private static short[] filled(final short[] array, final short value) {
		Arrays.fill(array, value);
		return array;
	}

	private static boolean[] filled(final boolean[] array, final boolean value) {
		Arrays.fill(array, value);
		return array;
	}

	/**
	 * The section over these planes with its facts scanned once: per section
	 * the count of cells that can collide, the union of behavior bits and
	 * whether any shape leaves its cell; the same bits and flag per fine
	 * 4-cube; and the bits every fine cube carries. One pass over 4096 cells,
	 * folding each fine run into locals before touching the grids.
	 */
	private static Section summarize(final int[] cells, final int uniform, final int[] fluidCells,
	    final List<BlockEntry> palette, final List<FluidEntry> fluidPalette) {
		final int fine = SectionFactGrids.FINE;
		final int finesPerEdge = EDGE / fine;
		short[] fineProperties = new short[finesPerEdge * finesPerEdge * finesPerEdge];
		boolean[] fineLarge = new boolean[fineProperties.length];
		if (cells == null && fluidCells == null) {
			BlockEntry entry = palette.get(uniform);
			int count = SectionFactGrids.collisionPotential(entry) ? CELLS : 0;
			short bits = (short) SectionFactGrids.propertyBits(entry);
			boolean large = SectionFactGrids.largeShape(entry);
			Arrays.fill(fineProperties, bits);
			Arrays.fill(fineLarge, large);
			return new Section(
			    null, uniform, null, count, bits, large, fineProperties, fineLarge, bits, REVISIONS.getAndIncrement());
		}
		boolean[] potential = new boolean[palette.size()];
		boolean[] largeShape = new boolean[palette.size()];
		short[] bits = new short[palette.size()];
		for (int i = 0; i < palette.size(); i++) {
			BlockEntry entry = palette.get(i);
			potential[i] = SectionFactGrids.collisionPotential(entry);
			largeShape[i] = SectionFactGrids.largeShape(entry);
			bits[i] = (short) SectionFactGrids.propertyBits(entry);
		}
		boolean[] fluidBearing = new boolean[fluidPalette.size()];
		for (int i = 0; i < fluidPalette.size(); i++) {
			fluidBearing[i] = fluidPalette.get(i).kind() != FluidKind.EMPTY;
		}
		int count = 0;
		int sectionBits = 0;
		boolean sectionLarge = false;
		for (int y = 0; y < EDGE; y++) {
			for (int z = 0; z < EDGE; z++) {
				int cell = index(0, y, z);
				int fineRow = ((y / fine) * finesPerEdge + z / fine) * finesPerEdge;
				for (int x = 0; x < EDGE; x += fine) {
					int runBits = 0;
					boolean runLarge = false;
					for (int dx = 0; dx < fine; dx++, cell++) {
						int index = cells == null ? uniform : cells[cell];
						if (potential[index]) {
							count++;
						}
						runLarge |= largeShape[index];
						int cellBits = bits[index];
						if (fluidCells != null && fluidBearing[fluidCells[cell]]) {
							cellBits |= WorldView.PROPERTY_FLUID;
						}
						runBits |= cellBits;
					}
					int at = fineRow + x / fine;
					fineProperties[at] |= (short) runBits;
					if (runLarge) {
						fineLarge[at] = true;
					}
					sectionBits |= runBits;
					sectionLarge |= runLarge;
				}
			}
		}
		short uniformBits = (short) WorldView.PROPERTIES_ALL;
		for (short value : fineProperties) {
			uniformBits &= value;
		}
		return new Section(cells, uniform, fluidCells, count, (short) sectionBits, sectionLarge, fineProperties,
		    fineLarge, uniformBits, REVISIONS.getAndIncrement());
	}
}
