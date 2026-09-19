package com.nettarion.stride.simulator.world;

import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A bounded region of blocks, with the collision shapes and surface
 * coefficients of every distinct state it contains.
 *
 * <p>The palette carries resolved shapes and movement facts rather than making
 * the kernel derive them from Minecraft registries. The live adapter captures
 * those facts, the trace package persists them, and the tick consumes the
 * portable record without a Minecraft or filesystem dependency.
 *
 * <p>Coordinates and coefficients are stored as raw bits, for the same reason
 * traces are: a shape boundary that is off by one ulp changes a collision.
 *
 * <p>Storage is a grid of immutable 16-cube {@link Section}s, as vanilla's
 * level is, each carrying its own coarse facts. {@link #compose} and
 * {@link #crop} on section boundaries share the sections of their sources
 * outright: a corridor re-published after a chunk crossing holds the same
 * objects for every section it already held, copies nothing and rescans
 * nothing, so a publication costs the sections that changed. A uniform
 * section holds no plane, so air over a valley is one shared object. Bounds
 * need not fall on section boundaries; the cells of an edge section outside
 * the bounds are padding no read ever reaches. A section-aligned composition
 * whose parts leave a section uncovered holds {@link Section#MISSING} there:
 * not padding and not air, a hole every cell read refuses across, as the
 * simulator refuses rather than guesses. {@link #hasMissingSections} says
 * whether a snapshot holds one; a materialized plane of such a snapshot
 * refuses, since a dense plane has no cell that means unknown.
 *
 * <p>Immutable. Two snapshots are equal only when they are the same object; a
 * compiled view compares them so.
 */
public final class WorldSnapshot {
	private final int originX;
	private final int originY;
	private final int originZ;
	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final OutsidePolicy outside;
	private final List<BlockEntry> palette;
	private final List<FluidEntry> fluidPalette;
	private final SectionGrid grid;
	private final Section[] sections;
	private final boolean anyFluid;
	private final boolean anyMissing;
	/** The flat grids a view answers span questions from, concatenated from the sections on first use. */
	private volatile SectionSummary summary;

	/**
	 * Joins disjoint immutable parts into one snapshot.
	 *
	 * <p>Parts and target on section boundaries compose by reference: each
	 * target section is the part's section that covers it, re-indexed only
	 * when the part's palette is not a prefix of the union's, as a part from
	 * a {@link SharedPalette} always is. Parts placed otherwise are joined
	 * cell by cell, which a test fixture may do and a publication never does.
	 */
	public static WorldSnapshot compose(final int originX, final int originY, final int originZ, final int sizeX,
	    final int sizeY, final int sizeZ, final OutsidePolicy outside, final List<WorldSnapshot> parts) {
		Objects.requireNonNull(outside, "outside");
		List<WorldSnapshot> immutableParts = List.copyOf(parts);
		int cellCount = SnapshotBounds.requireCellCount(originX, originY, originZ, sizeX, sizeY, sizeZ);
		Map<BlockEntry, Integer> blockIndices = new LinkedHashMap<>();
		List<BlockEntry> blockPalette = new ArrayList<>();
		Map<FluidEntry, Integer> fluidIndices = new LinkedHashMap<>();
		List<FluidEntry> fluidPalette = new ArrayList<>();
		fluidIndices.put(FluidEntry.EMPTY, 0);
		fluidPalette.add(FluidEntry.EMPTY);
		boolean sectioned = SectionGrid.aligned(originX, originY, originZ, sizeX, sizeY, sizeZ);
		for (WorldSnapshot part : immutableParts) {
			validatePart(originX, originY, originZ, sizeX, sizeY, sizeZ, part);
			sectioned &=
			    SectionGrid.aligned(part.originX, part.originY, part.originZ, part.sizeX, part.sizeY, part.sizeZ);
		}
		if (sectioned) {
			SectionGrid grid = SectionGrid.over(originX, originY, originZ, sizeX, sizeY, sizeZ);
			Section[] sections = new Section[grid.count()];
			for (WorldSnapshot part : immutableParts) {
				int[] blockRemap = extendsPrefix(blockPalette, blockIndices, part.palette)
				    ? null
				    : remap(part.palette, blockIndices, blockPalette);
				int[] fluidRemap = extendsPrefix(fluidPalette, fluidIndices, part.fluidPalette)
				    ? null
				    : remap(part.fluidPalette, fluidIndices, fluidPalette);
				SectionGrid partGrid = part.grid;
				for (int sy = 0; sy < partGrid.sectionsY(); sy++) {
					for (int sz = 0; sz < partGrid.sectionsZ(); sz++) {
						for (int sx = 0; sx < partGrid.sectionsX(); sx++) {
							int slot = grid.index(partGrid.sectionOriginX() + sx - grid.sectionOriginX(),
							    partGrid.sectionOriginY() + sy - grid.sectionOriginY(),
							    partGrid.sectionOriginZ() + sz - grid.sectionOriginZ());
							if (sections[slot] != null) {
								throw new IllegalArgumentException("snapshot parts overlap at section "
								    + ((partGrid.sectionOriginX() + sx) << Section.SHIFT) + ","
								    + ((partGrid.sectionOriginY() + sy) << Section.SHIFT) + ","
								    + ((partGrid.sectionOriginZ() + sz) << Section.SHIFT));
							}
							sections[slot] = part.sections[partGrid.index(sx, sy, sz)].remapped(blockRemap, fluidRemap);
						}
					}
				}
			}
			for (int slot = 0; slot < sections.length; slot++) {
				if (sections[slot] == null) {
					sections[slot] = Section.MISSING;
				}
			}
			return new WorldSnapshot(originX, originY, originZ, sizeX, sizeY, sizeZ, outside, List.copyOf(blockPalette),
			    List.copyOf(fluidPalette), grid, sections);
		}
		int[] cells = new int[cellCount];
		Arrays.fill(cells, -1);
		int[] fluidCells = null;
		for (WorldSnapshot part : immutableParts) {
			int[] blockRemap = extendsPrefix(blockPalette, blockIndices, part.palette)
			    ? null
			    : remap(part.palette, blockIndices, blockPalette);
			int[] fluidRemap = extendsPrefix(fluidPalette, fluidIndices, part.fluidPalette)
			    ? null
			    : remap(part.fluidPalette, fluidIndices, fluidPalette);
			int[] partCells = part.cells();
			int[] partFluids = part.anyFluid ? part.fluidCells() : null;
			if (partFluids != null && fluidCells == null) {
				fluidCells = new int[cellCount];
			}
			for (int localY = 0; localY < part.sizeY; localY++) {
				int targetY = part.originY + localY - originY;
				for (int localZ = 0; localZ < part.sizeZ; localZ++) {
					int targetZ = part.originZ + localZ - originZ;
					int partRow = (localY * part.sizeZ + localZ) * part.sizeX;
					int targetRow = (targetY * sizeZ + targetZ) * sizeX + (part.originX - originX);
					for (int localX = 0; localX < part.sizeX; localX++) {
						if (cells[targetRow + localX] != -1) {
							throw new IllegalArgumentException("snapshot parts overlap at " + (part.originX + localX)
							    + "," + (targetY + originY) + "," + (targetZ + originZ));
						}
						int block = partCells[partRow + localX];
						cells[targetRow + localX] = blockRemap == null ? block : blockRemap[block];
						if (partFluids != null) {
							int fluid = partFluids[partRow + localX];
							fluidCells[targetRow + localX] = fluidRemap == null ? fluid : fluidRemap[fluid];
						}
					}
				}
			}
		}
		for (int index = 0; index < cells.length; index++) {
			if (cells[index] == -1) {
				throw new IllegalArgumentException("snapshot parts do not cover target cell " + index);
			}
		}
		return fromDense(originX, originY, originZ, sizeX, sizeY, sizeZ, outside, List.copyOf(blockPalette), cells,
		    List.copyOf(fluidPalette), fluidCells == null ? new int[0] : fluidCells, true);
	}

	/**
	 * The window of this snapshot inside the given closed-open bounds, with the
	 * same palettes and outside semantics. A window on section boundaries
	 * shares this snapshot's sections; any other is cut cell by cell.
	 *
	 * @throws IllegalArgumentException when the window is not wholly inside
	 */
	public WorldSnapshot crop(final int windowX, final int windowY, final int windowZ, final int windowSizeX,
	    final int windowSizeY, final int windowSizeZ) {
		int cellCount =
		    SnapshotBounds.requireCellCount(windowX, windowY, windowZ, windowSizeX, windowSizeY, windowSizeZ);
		if (windowX < this.originX || windowY < this.originY || windowZ < this.originZ
		    || (long) windowX + windowSizeX > (long) this.originX + this.sizeX
		    || (long) windowY + windowSizeY > (long) this.originY + this.sizeY
		    || (long) windowZ + windowSizeZ > (long) this.originZ + this.sizeZ) {
			throw new IllegalArgumentException("crop window lies outside the snapshot");
		}
		if (SectionGrid.aligned(windowX, windowY, windowZ, windowSizeX, windowSizeY, windowSizeZ)) {
			// Every section of an aligned window inside the bounds lies wholly
			// inside them, so it holds no padding and is the window's own.
			SectionGrid window = SectionGrid.over(windowX, windowY, windowZ, windowSizeX, windowSizeY, windowSizeZ);
			Section[] shared = new Section[window.count()];
			for (int sy = 0; sy < window.sectionsY(); sy++) {
				for (int sz = 0; sz < window.sectionsZ(); sz++) {
					for (int sx = 0; sx < window.sectionsX(); sx++) {
						shared[window.index(sx, sy, sz)] =
						    this.sections[this.grid.index(window.sectionOriginX() + sx - this.grid.sectionOriginX(),
						        window.sectionOriginY() + sy - this.grid.sectionOriginY(),
						        window.sectionOriginZ() + sz - this.grid.sectionOriginZ())];
					}
				}
			}
			return new WorldSnapshot(windowX, windowY, windowZ, windowSizeX, windowSizeY, windowSizeZ, this.outside,
			    this.palette, this.fluidPalette, window, shared);
		}
		int[] cells = new int[cellCount];
		int[] fluids = this.anyFluid ? new int[cellCount] : new int[0];
		for (int y = 0; y < windowSizeY; y++) {
			for (int z = 0; z < windowSizeZ; z++) {
				int target = (y * windowSizeZ + z) * windowSizeX;
				copyRowTo(windowX, windowY + y, windowZ + z, windowSizeX, cells, target);
				if (fluids.length != 0) {
					copyFluidRowTo(windowX, windowY + y, windowZ + z, windowSizeX, fluids, target);
				}
			}
		}
		return fromDense(windowX, windowY, windowZ, windowSizeX, windowSizeY, windowSizeZ, this.outside, this.palette,
		    cells, this.fluidPalette, fluids, true);
	}

	/** This snapshot's sections under a palette that extends this one's, which every index already selects into. */
	WorldSnapshot withPalette(final List<BlockEntry> extended) {
		List<BlockEntry> blocks = List.copyOf(extended);
		if (blocks.size() < this.palette.size() || !blocks.subList(0, this.palette.size()).equals(this.palette)) {
			throw new IllegalArgumentException("an extended palette keeps every entry at its index");
		}
		return new WorldSnapshot(this.originX, this.originY, this.originZ, this.sizeX, this.sizeY, this.sizeZ,
		    this.outside, blocks, this.fluidPalette, this.grid, this.sections);
	}

	/** The flat grids of this snapshot's section facts, concatenated once. */
	SectionSummary summary() {
		SectionSummary current = this.summary;
		if (current == null) {
			// Two threads may both build; the results are equal, and either is kept.
			current = SectionSummary.of(this);
			this.summary = current;
		}
		return current;
	}

	/** The section grid this snapshot's cells lie in; the view indexes by it. */
	SectionGrid grid() {
		return this.grid;
	}

	/** The sections in {@link SectionGrid#index} order; never written. */
	Section[] sections() {
		return this.sections;
	}

	private static void validatePart(final int originX, final int originY, final int originZ, final int sizeX,
	    final int sizeY, final int sizeZ, final WorldSnapshot part) {
		Objects.requireNonNull(part, "part");
		long endX = (long) part.originX + part.sizeX;
		long endY = (long) part.originY + part.sizeY;
		long endZ = (long) part.originZ + part.sizeZ;
		if (part.sizeX <= 0 || part.sizeY <= 0 || part.sizeZ <= 0 || part.originX < originX
		    || endX > (long) originX + sizeX || part.originY < originY || endY > (long) originY + sizeY
		    || part.originZ < originZ || endZ > (long) originZ + sizeZ) {
			throw new IllegalArgumentException("snapshot part lies outside target bounds");
		}
	}

	/**
	 * Whether {@code source} agrees with the union so far on every index it
	 * holds, growing the union by the entries past its end; then the part's
	 * sections are its own. A part from a {@link SharedPalette} always does,
	 * and so does the first part of any composition. Comparing entries is a
	 * reference test first: sections a producer captured through one shared
	 * palette hold the same instances, and a deep record equality is reached
	 * only when a part was built elsewhere.
	 */
	private static <T> boolean extendsPrefix(final List<T> union, final Map<T, Integer> indices, final List<T> source) {
		int shared = Math.min(union.size(), source.size());
		for (int index = 0; index < shared; index++) {
			T ours = union.get(index);
			T theirs = source.get(index);
			if (ours != theirs && !ours.equals(theirs)) {
				return false;
			}
		}
		for (int index = shared; index < source.size(); index++) {
			T entry = source.get(index);
			// An entry past the union's end that the union already holds under
			// another index would need a remap after all.
			if (indices.containsKey(entry)) {
				return false;
			}
		}
		for (int index = shared; index < source.size(); index++) {
			T entry = source.get(index);
			indices.put(entry, union.size());
			union.add(entry);
		}
		return true;
	}

	private static <T> int[] remap(final List<T> source, final Map<T, Integer> indices, final List<T> palette) {
		int[] remap = new int[source.size()];
		for (int index = 0; index < source.size(); index++) {
			T entry = source.get(index);
			Integer target = indices.get(entry);
			if (target == null) {
				target = palette.size();
				indices.put(entry, target);
				palette.add(entry);
			}
			remap[index] = target;
		}
		return remap;
	}

	/**
	 * Fills one dimensioned region cell by cell, in world coordinates.
	 *
	 * <p>Every caller that authors a snapshot otherwise repeats the same three
	 * steps: allocate {@code sizeX * sizeY * sizeZ} cells, write them through an
	 * open-coded {@code (y * sizeZ + z) * sizeX + x} index, and assemble a
	 * palette. The open-coded index silently corrupts a neighboring cell when
	 * a coordinate leaves the region, so the builder refuses that write instead.
	 *
	 * <p>Cells start at palette index 0 and fluid cells at the canonical empty
	 * fluid, so a fixture writes only what differs from an empty region.
	 */
	public static Builder builder(final OutsidePolicy outside, final int originX, final int originY, final int originZ,
	    final int sizeX, final int sizeY, final int sizeZ) {
		return new Builder(outside, originX, originY, originZ, sizeX, sizeY, sizeZ);
	}

	/** Accumulates the dense planes and palettes of one {@link WorldSnapshot}. */
	public static final class Builder {
		private final OutsidePolicy outside;
		private final int originX;
		private final int originY;
		private final int originZ;
		private final int sizeX;
		private final int sizeY;
		private final int sizeZ;
		private final int[] cells;
		private int[] fluidCells;
		private final List<BlockEntry> palette = new ArrayList<>();
		private List<FluidEntry> fluidPalette = List.of(FluidEntry.EMPTY);

		private Builder(final OutsidePolicy outside, final int originX, final int originY, final int originZ,
		    final int sizeX, final int sizeY, final int sizeZ) {
			this.outside = Objects.requireNonNull(outside, "outside");
			int cellCount = SnapshotBounds.requireCellCount(originX, originY, originZ, sizeX, sizeY, sizeZ);
			this.originX = originX;
			this.originY = originY;
			this.originZ = originZ;
			this.sizeX = sizeX;
			this.sizeY = sizeY;
			this.sizeZ = sizeZ;
			this.cells = new int[cellCount];
		}

		/** Declares the block palette, in the index order the cells refer to. */
		public Builder palette(final List<BlockEntry> entries) {
			this.palette.clear();
			for (BlockEntry entry : entries) {
				this.palette.add(Objects.requireNonNull(entry, "palette entry"));
			}
			return this;
		}

		/** Declares the block palette, in the index order the cells refer to. */
		public Builder palette(final BlockEntry... entries) {
			return palette(Arrays.asList(entries));
		}

		/**
		 * Declares the fluid palette, in the index order the fluid cells refer
		 * to. Index 0 must be {@link FluidEntry#EMPTY}.
		 */
		public Builder fluidPalette(final List<FluidEntry> entries) {
			this.fluidPalette = List.copyOf(entries);
			return this;
		}

		/** Declares the fluid palette, in the index order the fluid cells refer to. */
		public Builder fluidPalette(final FluidEntry... entries) {
			return fluidPalette(Arrays.asList(entries));
		}

		/** Selects a block palette index at one world position. */
		public Builder set(final int x, final int y, final int z, final int paletteIndex) {
			this.cells[index(x, y, z)] = paletteIndex;
			return this;
		}

		/** Selects a fluid palette index at one world position. */
		public Builder setFluid(final int x, final int y, final int z, final int fluidIndex) {
			int at = index(x, y, z);
			if (this.fluidCells == null) {
				this.fluidCells = new int[this.cells.length];
			}
			this.fluidCells[at] = fluidIndex;
			return this;
		}

		private int index(final int x, final int y, final int z) {
			int localX = x - this.originX;
			int localY = y - this.originY;
			int localZ = z - this.originZ;
			if (localX < 0 || localX >= this.sizeX || localY < 0 || localY >= this.sizeY || localZ < 0
			    || localZ >= this.sizeZ) {
				throw new IllegalArgumentException(
				    "snapshot cell " + x + "," + y + "," + z + " lies outside the region");
			}
			return (localY * this.sizeZ + localZ) * this.sizeX + localX;
		}

		/** Publishes the builder's declared region as an immutable section-based snapshot. */
		public WorldSnapshot build() {
			return new WorldSnapshot(this.originX, this.originY, this.originZ, this.sizeX, this.sizeY, this.sizeZ,
			    this.outside, List.copyOf(this.palette), this.cells, this.fluidPalette,
			    this.fluidCells == null ? new int[0] : this.fluidCells);
		}
	}

	/** Creates a snapshot without fluid cells. */
	public WorldSnapshot(final int originX, final int originY, final int originZ, final int sizeX, final int sizeY,
	    final int sizeZ, final OutsidePolicy outside, final List<BlockEntry> palette, final int[] cells) {
		this(originX, originY, originZ, sizeX, sizeY, sizeZ, outside, palette, cells, List.of(FluidEntry.EMPTY),
		    new int[0]);
	}

	/**
	 * A snapshot over the caller's planes and palettes, which are copied into
	 * sections and validated cell by cell against the palettes.
	 */
	public WorldSnapshot(final int originX, final int originY, final int originZ, final int sizeX, final int sizeY,
	    final int sizeZ, final OutsidePolicy outside, final List<BlockEntry> palette, final int[] cells,
	    final List<FluidEntry> fluidPalette, final int[] fluidCells) {
		this(fromDense(originX, originY, originZ, sizeX, sizeY, sizeZ, outside, List.copyOf(palette), cells,
		    List.copyOf(fluidPalette), fluidCells, false));
	}

	/** Every field of another snapshot; the public constructors delegate their sectioning here. */
	private WorldSnapshot(final WorldSnapshot built) {
		this.originX = built.originX;
		this.originY = built.originY;
		this.originZ = built.originZ;
		this.sizeX = built.sizeX;
		this.sizeY = built.sizeY;
		this.sizeZ = built.sizeZ;
		this.outside = built.outside;
		this.palette = built.palette;
		this.fluidPalette = built.fluidPalette;
		this.grid = built.grid;
		this.sections = built.sections;
		this.anyFluid = built.anyFluid;
		this.anyMissing = built.anyMissing;
	}

	/**
	 * A snapshot that takes ownership of planes the caller has just written and
	 * will not touch again: validated cell by cell like the constructor, and a
	 * plane that is exactly one section on its boundaries is adopted without a
	 * copy. This is for producers that fill a fresh array per section or per
	 * read; a caller that keeps writing to the array breaks the snapshot.
	 */
	public static WorldSnapshot owning(final int originX, final int originY, final int originZ, final int sizeX,
	    final int sizeY, final int sizeZ, final OutsidePolicy outside, final List<BlockEntry> palette,
	    final int[] cells, final List<FluidEntry> fluidPalette, final int[] fluidCells) {
		return fromDense(originX, originY, originZ, sizeX, sizeY, sizeZ, outside, List.copyOf(palette), cells,
		    List.copyOf(fluidPalette), fluidCells, true);
	}

	/** {@link #owning} without a fluid plane. */
	public static WorldSnapshot owning(final int originX, final int originY, final int originZ, final int sizeX,
	    final int sizeY, final int sizeZ, final OutsidePolicy outside, final List<BlockEntry> palette,
	    final int[] cells) {
		return owning(originX, originY, originZ, sizeX, sizeY, sizeZ, outside, palette, cells,
		    List.of(FluidEntry.EMPTY), new int[0]);
	}

	/**
	 * Cuts dense planes into sections. A section wholly inside the bounds is
	 * gathered by row copies; the cells of an edge section outside the bounds
	 * are padding at index zero. {@code fluidCells} is empty for a dry plane.
	 */
	private static WorldSnapshot fromDense(final int originX, final int originY, final int originZ, final int sizeX,
	    final int sizeY, final int sizeZ, final OutsidePolicy outside, final List<BlockEntry> palette,
	    final int[] cells, final List<FluidEntry> fluidPalette, final int[] fluidCells, final boolean adopt) {
		int expectedCells = SnapshotBounds.requireCellCount(originX, originY, originZ, sizeX, sizeY, sizeZ);
		if (expectedCells != cells.length) {
			throw new IllegalArgumentException(
			    "snapshot dimensions require " + expectedCells + " cells, received " + cells.length);
		}
		if (fluidCells.length != 0 && fluidCells.length != expectedCells) {
			throw new IllegalArgumentException(
			    "fluid plane must be absent or contain " + expectedCells + " cells, received " + fluidCells.length);
		}
		SectionGrid grid = SectionGrid.over(originX, originY, originZ, sizeX, sizeY, sizeZ);
		Section[] sections = new Section[grid.count()];
		boolean oneSection = grid.count() == 1 && SectionGrid.aligned(originX, originY, originZ, sizeX, sizeY, sizeZ);
		if (oneSection) {
			int[] blocks = adopt ? cells : cells.clone();
			int[] fluids = fluidCells.length == 0 ? null : adopt ? fluidCells : fluidCells.clone();
			sections[0] = Section.of(blocks, fluids, palette, fluidPalette);
		} else {
			for (int sy = 0; sy < grid.sectionsY(); sy++) {
				int worldY = (grid.sectionOriginY() + sy) << Section.SHIFT;
				for (int sz = 0; sz < grid.sectionsZ(); sz++) {
					int worldZ = (grid.sectionOriginZ() + sz) << Section.SHIFT;
					for (int sx = 0; sx < grid.sectionsX(); sx++) {
						int worldX = (grid.sectionOriginX() + sx) << Section.SHIFT;
						int[] blocks = new int[Section.CELLS];
						int[] fluids = fluidCells.length == 0 ? null : new int[Section.CELLS];
						// Long arithmetic: a bound may sit within a section of the int limit.
						int fromX = (int) Math.max((long) worldX, originX);
						int toX = (int) Math.min((long) worldX + Section.EDGE, (long) originX + sizeX);
						for (int ly = 0; ly < Section.EDGE; ly++) {
							long y = (long) worldY + ly;
							if (y < originY || y >= (long) originY + sizeY) continue;
							for (int lz = 0; lz < Section.EDGE; lz++) {
								long z = (long) worldZ + lz;
								if (z < originZ || z >= (long) originZ + sizeZ) continue;
								int source =
								    (int) (((y - originY) * sizeZ + (z - originZ)) * sizeX + (fromX - originX));
								int target = Section.index(fromX - worldX, ly, lz);
								System.arraycopy(cells, source, blocks, target, toX - fromX);
								if (fluids != null) {
									System.arraycopy(fluidCells, source, fluids, target, toX - fromX);
								}
							}
						}
						sections[grid.index(sx, sy, sz)] = Section.of(blocks, fluids, palette, fluidPalette);
					}
				}
			}
		}
		return new WorldSnapshot(
		    originX, originY, originZ, sizeX, sizeY, sizeZ, outside, palette, fluidPalette, grid, sections);
	}

	/** Adopts sections this class chose; the palettes are already immutable lists. */
	private WorldSnapshot(final int originX, final int originY, final int originZ, final int sizeX, final int sizeY,
	    final int sizeZ, final OutsidePolicy outside, final List<BlockEntry> palette,
	    final List<FluidEntry> fluidPalette, final SectionGrid grid, final Section[] sections) {
		this.originX = originX;
		this.originY = originY;
		this.originZ = originZ;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.outside = Objects.requireNonNull(outside, "outside");
		this.palette = palette;
		this.fluidPalette = fluidPalette;
		if (fluidPalette.isEmpty() || !FluidEntry.EMPTY.equals(fluidPalette.getFirst())) {
			throw new IllegalArgumentException("fluid palette index 0 must be canonical EMPTY");
		}
		this.grid = grid;
		this.sections = sections;
		boolean fluid = false;
		boolean missing = false;
		for (Section section : sections) {
			fluid |= section.hasFluids();
			missing |= section.isMissing();
		}
		this.anyFluid = fluid;
		this.anyMissing = missing;
	}

	/** Whether any section is {@link Section#MISSING}: a hole no part of a composition covered. */
	public boolean hasMissingSections() {
		return this.anyMissing;
	}

	/** Whether the section holding a world position inside the bounds is missing. */
	public boolean isMissingAt(final int x, final int y, final int z) {
		return contains(x, y, z) && sectionAt(x, y, z).isMissing();
	}

	private void requireNoMissingSections(final String what) {
		if (this.anyMissing) {
			throw UnimplementedMechanicException.deferred(RefusalCause.OUTSIDE_REGION,
			    () -> what + " of a snapshot with a missing section: a dense plane has no cell that means unknown");
		}
	}

	/** Returns the inclusive minimum X cell coordinate of the declared region. */
	public int originX() {
		return this.originX;
	}

	/** Returns the inclusive minimum Y cell coordinate of the declared region. */
	public int originY() {
		return this.originY;
	}

	/** Returns the inclusive minimum Z cell coordinate of the declared region. */
	public int originZ() {
		return this.originZ;
	}

	/** Returns the extent of the declared region along X, in cells. */
	public int sizeX() {
		return this.sizeX;
	}

	/** Returns the extent of the declared region along Y, in cells. */
	public int sizeY() {
		return this.sizeY;
	}

	/** Returns the extent of the declared region along Z, in cells. */
	public int sizeZ() {
		return this.sizeZ;
	}

	/** Returns the declared policy for queries outside the captured region. */
	public OutsidePolicy outside() {
		return this.outside;
	}

	/** This snapshot with the requested outside semantics, sharing its immutable sections and palettes. */
	public WorldSnapshot withOutside(final OutsidePolicy policy) {
		Objects.requireNonNull(policy, "outside");
		if (policy == this.outside) return this;
		return new WorldSnapshot(this.originX, this.originY, this.originZ, this.sizeX, this.sizeY, this.sizeZ, policy,
		    this.palette, this.fluidPalette, this.grid, this.sections);
	}

	/** The distinct block states, by the index the cells select. */
	public List<BlockEntry> palette() {
		return this.palette;
	}

	/** The distinct fluid states, by the index the fluid cells select; index 0 is empty. */
	public List<FluidEntry> fluidPalette() {
		return this.fluidPalette;
	}

	/**
	 * The block cells as one dense plane over the bounds, y outermost then z then x; a fresh copy.
	 *
	 * @throws UnimplementedMechanicException when a section is missing
	 */
	public int[] cells() {
		requireNoMissingSections("the dense cells");
		int[] out = new int[this.sizeX * this.sizeY * this.sizeZ];
		for (int y = 0; y < this.sizeY; y++) {
			for (int z = 0; z < this.sizeZ; z++) {
				copyRowTo(this.originX, this.originY + y, this.originZ + z, this.sizeX, out,
				    (y * this.sizeZ + z) * this.sizeX);
			}
		}
		return out;
	}

	/** The fluid cells as one dense plane like {@link #cells()}, or empty when no cell selects a fluid. */
	public int[] fluidCells() {
		requireNoMissingSections("the dense fluid cells");
		if (!this.anyFluid) {
			return new int[0];
		}
		int[] out = new int[this.sizeX * this.sizeY * this.sizeZ];
		for (int y = 0; y < this.sizeY; y++) {
			for (int z = 0; z < this.sizeZ; z++) {
				copyFluidRowTo(this.originX, this.originY + y, this.originZ + z, this.sizeX, out,
				    (y * this.sizeZ + z) * this.sizeX);
			}
		}
		return out;
	}

	/** Copy {@code count} block cells along x from a world position into {@code target} at {@code at}. */
	private void copyRowTo(int x, final int y, final int z, int count, final int[] target, int at) {
		int sectionY = (y >> Section.SHIFT) - this.grid.sectionOriginY();
		int sectionZ = (z >> Section.SHIFT) - this.grid.sectionOriginZ();
		int localY = y & Section.MASK;
		int localZ = z & Section.MASK;
		while (count > 0) {
			Section section =
			    this.sections[this.grid.index((x >> Section.SHIFT) - this.grid.sectionOriginX(), sectionY, sectionZ)];
			int localX = x & Section.MASK;
			int run = Math.min(count, Section.EDGE - localX);
			if (section.isMissing()) {
				throw new UnimplementedMechanicException(
				    RefusalCause.OUTSIDE_REGION, "a dense copy reached a missing section at " + x + "," + y + "," + z);
			}
			int[] cells = section.rawCells();
			if (cells == null) {
				Arrays.fill(target, at, at + run, section.uniformIndex());
			} else {
				System.arraycopy(cells, Section.index(localX, localY, localZ), target, at, run);
			}
			x += run;
			at += run;
			count -= run;
		}
	}

	/** {@link #copyRowTo} for the fluid plane; a dry section writes zeros. */
	private void copyFluidRowTo(int x, final int y, final int z, int count, final int[] target, int at) {
		int sectionY = (y >> Section.SHIFT) - this.grid.sectionOriginY();
		int sectionZ = (z >> Section.SHIFT) - this.grid.sectionOriginZ();
		int localY = y & Section.MASK;
		int localZ = z & Section.MASK;
		while (count > 0) {
			Section section =
			    this.sections[this.grid.index((x >> Section.SHIFT) - this.grid.sectionOriginX(), sectionY, sectionZ)];
			int localX = x & Section.MASK;
			int run = Math.min(count, Section.EDGE - localX);
			if (section.isMissing()) {
				throw new UnimplementedMechanicException(
				    RefusalCause.OUTSIDE_REGION, "a dense fluid copy reached a missing section at " + x + "," + y + "," + z);
			}
			int[] fluids = section.rawFluidCells();
			if (fluids == null) {
				Arrays.fill(target, at, at + run, 0);
			} else {
				System.arraycopy(fluids, Section.index(localX, localY, localZ), target, at, run);
			}
			x += run;
			at += run;
			count -= run;
		}
	}

	/** Returns whether the declared region contains any nonempty fluid cells. */
	public boolean hasFluids() {
		return this.anyFluid;
	}

	/** Tests the declared rectangular bounds; a coordinate inside them may still belong to a missing section. */
	public boolean contains(final int x, final int y, final int z) {
		return x >= this.originX && x < this.originX + this.sizeX && y >= this.originY && y < this.originY + this.sizeY
		    && z >= this.originZ && z < this.originZ + this.sizeZ;
	}

	/** Palette index at a world position, or -1 outside the region or in a missing section. */
	public int paletteIndexAt(final int x, final int y, final int z) {
		if (!contains(x, y, z)) {
			return -1;
		}
		return sectionAt(x, y, z).cell(x & Section.MASK, y & Section.MASK, z & Section.MASK);
	}

	/** Returns the declared block palette entry, or {@code null} outside the region or in a missing section. */
	public BlockEntry entryAt(final int x, final int y, final int z) {
		int index = paletteIndexAt(x, y, z);
		return index < 0 ? null : this.palette.get(index);
	}

	/** Fluid palette index at a world position, or -1 outside the region or in a missing section; 0 everywhere in a dry snapshot. */
	public int fluidPaletteIndexAt(final int x, final int y, final int z) {
		if (!contains(x, y, z)) {
			return -1;
		}
		Section section = sectionAt(x, y, z);
		return section.isMissing()
		    ? -1
		    : section.fluidCell(Section.index(x & Section.MASK, y & Section.MASK, z & Section.MASK));
	}

	/** Returns the declared fluid entry, or {@code null} outside the region or in a missing section. */
	public FluidEntry fluidEntryAt(final int x, final int y, final int z) {
		int index = fluidPaletteIndexAt(x, y, z);
		return index < 0 ? null : this.fluidPalette.get(index);
	}

	/** The section holding a world position inside the bounds. */
	private Section sectionAt(final int x, final int y, final int z) {
		return this.sections[this.grid.index((x >> Section.SHIFT) - this.grid.sectionOriginX(),
		    (y >> Section.SHIFT) - this.grid.sectionOriginY(), (z >> Section.SHIFT) - this.grid.sectionOriginZ())];
	}
}
