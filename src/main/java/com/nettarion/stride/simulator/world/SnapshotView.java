package com.nettarion.stride.simulator.world;

import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.block.BlockBehavior;
import com.nettarion.stride.simulator.block.InsideBlockTraversal;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.geometry.CollisionCollector;
import com.nettarion.stride.simulator.geometry.CollisionQuerySpan;
import com.nettarion.stride.simulator.geometry.Mth;

import java.util.Arrays;
import java.util.Objects;

/**
 * A {@link WorldView} compiled from a {@link WorldSnapshot}: real per-state
 * shapes, coefficients and behaviors, answered from the snapshot's sections.
 *
 * <p>Build one with {@link #compile(WorldSnapshot)}, which returns a frozen
 * view safe to share between threads. A caller that must apply block changes
 * calls {@link #fork()} and edits the returned worker-confined child through
 * {@link #replaceCell}; forks share the compiled palette tables and the
 * snapshot's sections, and copy a derived grid only when they first write it.
 *
 * <p>Leaving the region is not air. Space outside the bounds is what the
 * snapshot's {@link OutsidePolicy} says, and a missing section inside them is
 * a hole that every read refuses; neither is ever answered with a plausible
 * guess about terrain nobody captured.
 *
 * <p>Thread safety: a frozen view is immutable and safe for concurrent reads.
 * A mutable view is worker-confined: do not call {@link #replaceCell}
 * concurrently with any read, and do not share it between workers.
 */
public final class SnapshotView implements WorldView {
	private static final double[] SCAFFOLDING_STABLE_SHAPE = {0.0, 0.875, 0.0, 1.0, 1.0, 1.0, 0.0, 0.0, 0.0, 0.125, 1.0,
	    0.125, 0.875, 0.0, 0.0, 1.0, 1.0, 0.125, 0.0, 0.0, 0.875, 0.125, 1.0, 1.0, 0.875, 0.0, 0.875, 1.0, 1.0, 1.0};

	private static final double[] SCAFFOLDING_UNSTABLE_BOTTOM_SHAPE = {0.0, 0.0, 0.0, 1.0, 0.125, 1.0};

	private static final double[] POWDER_SNOW_FALLING_SHAPE = {0.0, 0.0, 0.0, 1.0, (double) 0.9F, 1.0};

	private static final double[] FULL_BLOCK_SHAPE = {0.0, 0.0, 0.0, 1.0, 1.0, 1.0};

	/** {@code PowderSnowBlock.getCollisionShape}: the fall distance past which the falling shape applies. */
	private static final double POWDER_SNOW_FALLING_DISTANCE = 2.5;

	/**
	 * Internal property bit for cells whose captured collision geometry the
	 * server can mutate. Not part of the {@link SpanQueries} contract: only this
	 * view's own fast paths consult it, to stay off spans that must refuse
	 * instead of answering from captured geometry.
	 */
	private static final int PROPERTY_SERVER_MUTABLE = SectionFactGrids.PROPERTY_SERVER_MUTABLE;

	/** A cell number is a section slot shifted past a local cell number. */
	private static final int CELL_SHIFT = 3 * Section.SHIFT;

	private static final int CELL_MASK = Section.CELLS - 1;

	/** Edge of a fine property cube, as a power of two. */
	private static final int FINE_SHIFT = SectionFactGrids.FINE_SHIFT;

	/*
	 * Fine property masks refine a positive section-level answer for small spans.
	 * The coarse mask always runs first. Skipping refinement can only retain an
	 * unnecessary query; it cannot hide a required one.
	 */

	/**
	 * Most fine cubes worth refining across. Beyond this the coarse answer stands:
	 * the refinement is a load per cube, and a span this large is a fast-moving
	 * swept box whose properties are usually present anyway.
	 */
	private static final int FINE_CUBE_LIMIT = 8;

	/**
	 * The same bound for the large-shape refinement, which is allowed more cubes
	 * because what it saves is the whole Cursor3D ring rather than one lookup.
	 */
	private static final int FINE_LARGE_SHAPE_LIMIT = 48;

	private static final long REVISION_SEED = 0x9E3779B97F4A7C15L;

	/** The portable facts this view was compiled from, shared by every fork. */
	private final WorldSnapshot snapshot;

	/** The per-entry facts of the snapshot's palettes, shared by every fork. */
	private final PaletteTables tables;

	/** The snapshot's sections in its grid order; a cell is a section and a local number. */
	private final Section[] sections;

	private final SectionGrid grid;

	private final int originX;

	private final int originY;

	private final int originZ;

	private final int endX;

	private final int endY;

	private final int endZ;

	private final int sectionOriginX;

	private final int sectionOriginY;

	private final int sectionOriginZ;

	private final int sectionsX;

	private final int sectionsY;

	private final int sectionsZ;

	private final int fineOriginX;

	private final int fineOriginY;

	private final int fineOriginZ;

	private final int fineSizeX;

	private final int fineSizeY;

	private final int fineSizeZ;

	/**
	 * Each section's {@code Section.revision} laid side by side in grid order,
	 * so a span's revision is read from one array rather than through the
	 * section objects; shared by every fork, since a fork holds the same
	 * sections.
	 */
	private final long[] sectionRevision;

	/**
	 * Per-section AND of fine-cube bits. A set bit proves refinement cannot clear
	 * that property. This is only a performance hint; either error direction falls
	 * back to the conservative section answer.
	 */
	private final short[] sectionUniformProperties;

	/** Whether any section is missing; see {@link #hasChunkAt}. */
	private final boolean hasMissingSections;

	private final OutsidePolicy outside;

	private final boolean immutable;

	private final long identity = WorldIdentity.next();

	/** Effective potentially-colliding owner-cell count per local 16-cube section. */
	private int[] collisionSectionCounts;

	/** Property bits present in each local 16-cube section. */
	private short[] sectionProperties;

	/** {@link #sectionProperties} on the fine grid. */
	private short[] fineProperties;

	/**
	 * Whether a placed cell in each section owns a shape extending beyond its
	 * cell. Mutation is set-only: a stale positive costs the general path, while a
	 * false negative could omit collision geometry.
	 */
	private boolean[] sectionLargeShape;

	/** The same conservative large-shape fact on the fine grid. */
	private boolean[] fineLargeShape;

	/**
	 * The answer for a span that is not wholly inside the region. Outside space is
	 * unknown, and the accessors already own that semantic: {@code fluidAt} and
	 * {@code onClimbableAt} throw there, the coefficient lookups return the default.
	 * Answering such a span with the whole-view mask keeps them reached exactly
	 * when they were reached before, so a snapshot with no fluids anywhere still
	 * skips the fluid scan at the region edge instead of newly refusing it.
	 */
	private short wholeViewProperties;

	/**
	 * Collision version per local 16-cube section, so a distant edit cannot
	 * invalidate a local pose-fit cache. Holds the value {@link #collisionVersion}
	 * had when this section was last written; sections never written stay at the
	 * value they were born with, which is why an untouched span keeps answering
	 * the same number however much of the rest of the world changes.
	 */
	private long[] sectionVersion;

	/** Cells whose block palette index this view replaced. */
	private CellOverlay overlay;

	/** Cells whose fluid palette index this view replaced. */
	private CellOverlay fluidOverlay;

	/** Forks share each derived-grid family until an edit first writes that family. */
	private boolean collisionGridsShared;

	private boolean propertyGridsShared;

	private boolean largeShapeGridsShared;

	private boolean hasFluidCells;

	private long collisionVersion;

	/** A mutable view over a snapshot; {@link #compile(WorldSnapshot)} freezes one and {@link #fork()} edits one. */
	private SnapshotView(final WorldSnapshot snapshot) {
		this(Objects.requireNonNull(snapshot, "snapshot"), PaletteTables.compile(snapshot), false);
	}

	private SnapshotView(final WorldSnapshot snapshot, final PaletteTables tables, final boolean immutable) {
		this.immutable = immutable;
		this.snapshot = snapshot;
		this.tables = tables;
		this.originX = snapshot.originX();
		this.originY = snapshot.originY();
		this.originZ = snapshot.originZ();
		this.endX = this.originX + snapshot.sizeX();
		this.endY = this.originY + snapshot.sizeY();
		this.endZ = this.originZ + snapshot.sizeZ();
		this.sections = snapshot.sections();
		this.grid = snapshot.grid();
		this.hasMissingSections = snapshot.hasMissingSections();
		this.sectionRevision = new long[this.sections.length];
		for (int slot = 0; slot < this.sections.length; slot++) {
			this.sectionRevision[slot] = this.sections[slot].revision;
		}
		this.sectionOriginX = this.grid.sectionOriginX();
		this.sectionOriginY = this.grid.sectionOriginY();
		this.sectionOriginZ = this.grid.sectionOriginZ();
		this.fineOriginX = this.sectionOriginX << (Section.SHIFT - FINE_SHIFT);
		this.fineOriginY = this.sectionOriginY << (Section.SHIFT - FINE_SHIFT);
		this.fineOriginZ = this.sectionOriginZ << (Section.SHIFT - FINE_SHIFT);
		this.outside = snapshot.outside();
		// The derived grids are the snapshot's own fact grids, shared read-only
		// and copied by the first edit, as a fork shares them. A snapshot
		// composed or cropped on section boundaries carries them from its parts;
		// only one assembled some other way is scanned, once.
		SectionFactGrids grids = snapshot.factGrids();
		this.sectionsX = grids.sectionsX;
		this.sectionsY = grids.sectionsY;
		this.sectionsZ = grids.sectionsZ;
		this.collisionSectionCounts = grids.counts;
		this.sectionProperties = grids.properties;
		this.sectionLargeShape = grids.large;
		this.fineSizeX = grids.finesX;
		this.fineSizeY = grids.finesY;
		this.fineSizeZ = grids.finesZ;
		this.fineProperties = grids.fineProperties;
		this.fineLargeShape = grids.fineLarge;
		this.sectionUniformProperties = grids.uniform;
		this.collisionGridsShared = true;
		this.propertyGridsShared = true;
		this.largeShapeGridsShared = true;
		this.sectionVersion = new long[this.collisionSectionCounts.length];
		this.overlay = new CellOverlay();
		this.fluidOverlay = new CellOverlay();
		this.hasFluidCells = tables.hasFluidCells;
		this.wholeViewProperties = tables.wholeViewProperties;
	}

	/** A fork or a frozen successor: the same backing, its own copy of the overlays and grid-sharing flags. */
	private SnapshotView(final SnapshotView source, final boolean immutable) {
		this.immutable = immutable;
		this.snapshot = source.snapshot;
		this.tables = source.tables;
		this.sections = source.sections;
		this.grid = source.grid;
		this.originX = source.originX;
		this.originY = source.originY;
		this.originZ = source.originZ;
		this.endX = source.endX;
		this.endY = source.endY;
		this.endZ = source.endZ;
		this.hasMissingSections = source.hasMissingSections;
		this.sectionRevision = source.sectionRevision;
		this.sectionOriginX = source.sectionOriginX;
		this.sectionOriginY = source.sectionOriginY;
		this.sectionOriginZ = source.sectionOriginZ;
		this.sectionsX = source.sectionsX;
		this.sectionsY = source.sectionsY;
		this.sectionsZ = source.sectionsZ;
		this.fineOriginX = source.fineOriginX;
		this.fineOriginY = source.fineOriginY;
		this.fineOriginZ = source.fineOriginZ;
		this.fineSizeX = source.fineSizeX;
		this.fineSizeY = source.fineSizeY;
		this.fineSizeZ = source.fineSizeZ;
		this.collisionSectionCounts = source.collisionSectionCounts;
		this.sectionProperties = source.sectionProperties;
		this.sectionLargeShape = source.sectionLargeShape;
		this.fineLargeShape = source.fineLargeShape;
		this.fineProperties = source.fineProperties;
		this.sectionUniformProperties = source.sectionUniformProperties;
		this.wholeViewProperties = source.wholeViewProperties;
		this.sectionVersion = source.sectionVersion;
		this.outside = source.outside;
		this.overlay = source.overlay.copy();
		this.fluidOverlay = source.fluidOverlay.copy();
		if (!source.immutable) {
			source.collisionGridsShared = true;
			source.propertyGridsShared = true;
			source.largeShapeGridsShared = true;
		}
		this.collisionGridsShared = true;
		this.propertyGridsShared = true;
		this.largeShapeGridsShared = true;
		this.hasFluidCells = source.hasFluidCells;
		this.collisionVersion = source.collisionVersion;
	}

	/**
	 * Compiles a snapshot once into a frozen view that is safe to share between
	 * threads. A worker that must apply block changes calls {@link #fork()} on
	 * it and mutates only the returned worker-confined child.
	 *
	 * @throws IllegalArgumentException when the palette holds a moving piston, which is not modeled
	 */
	public static SnapshotView compile(final WorldSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot");
		return new SnapshotView(snapshot, PaletteTables.compile(snapshot), true);
	}

	/**
	 * Verifies the snapshot's block facts against an independently extracted
	 * catalog, then compiles a frozen view whose cauldron entries carry their
	 * catalog-declared successors and entity-inside shapes.
	 *
	 * <p>{@code minecraftVersion} is the version the caller expects, normally
	 * the capture's own; the catalog must describe exactly that version, so a
	 * catalog for another version refuses before any palette entry is compared.
	 *
	 * @throws IllegalArgumentException when the catalog's version is not {@code minecraftVersion}
	 * @throws UnimplementedMechanicException when a palette entry's facts differ from the catalog's
	 */
	public static SnapshotView compile(
	    final WorldSnapshot snapshot, final BlockStateCatalog catalog, final String minecraftVersion) {
		WorldSnapshot extended = catalog.withSuccessors(snapshot, minecraftVersion);
		PaletteTables tables = PaletteTables.compile(extended);
		catalog.installBehaviors(tables, extended);
		return new SnapshotView(extended, tables, true);
	}

	/**
	 * Whether two views answer every movement question alike: the same compiled
	 * backing and the same effective replacements. Two compilations of one
	 * snapshot conservatively differ.
	 */
	public static boolean sameWorld(final SnapshotView a, final SnapshotView b) {
		if (a.snapshot != b.snapshot || a.tables != b.tables || a.overlay.size() != b.overlay.size()
		    || a.fluidOverlay.size() != b.fluidOverlay.size()) {
			return false;
		}
		for (int i = 0; i < a.overlay.size(); i++) {
			if (b.effectivePaletteIndex(a.overlay.cellAt(i)) != a.overlay.valueAt(i)) {
				return false;
			}
		}
		for (int i = 0; i < a.fluidOverlay.size(); i++) {
			if (b.effectiveFluidPaletteIndex(a.fluidOverlay.cellAt(i)) != a.fluidOverlay.valueAt(i)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * A cheap independent mutable successor.
	 *
	 * <p>The compiled backing and derived query grids are shared read-only;
	 * existing replacements are copied. The first effective replacement in either
	 * view detaches its grids before making child-local changes visible. Call
	 * only while this instance is not being mutated concurrently.
	 */
	public SnapshotView fork() {
		return new SnapshotView(this, false);
	}

	/**
	 * A read-only view over this one's current cells: this instance when it is
	 * already frozen, else a cheap successor whose answers cannot change even
	 * if this source later accepts replacements. Use {@link #fork} when the
	 * child itself must accept replacements.
	 */
	public SnapshotView frozen() {
		return this.immutable ? this : new SnapshotView(this, true);
	}

	/** The portable facts this view was compiled from, shared by every fork. */
	public WorldSnapshot snapshot() {
		return this.snapshot;
	}

	/**
	 * The effective block and fluid cells as a snapshot: this view's own snapshot
	 * when nothing was replaced, else a fresh one over the replaced cells.
	 *
	 * @throws UnimplementedMechanicException when a cell was replaced and a section is missing,
	 *     since a dense plane has no cell that means unknown
	 */
	public WorldSnapshot materializedSnapshot() {
		if (this.overlay.size() == 0 && this.fluidOverlay.size() == 0) {
			return this.snapshot;
		}
		int[] blocks = new int[this.snapshot.sizeX() * this.snapshot.sizeY() * this.snapshot.sizeZ()];
		int[] fluids = new int[blocks.length];
		int at = 0;
		for (int y = this.originY; y < this.endY; y++) {
			for (int z = this.originZ; z < this.endZ; z++) {
				for (int x = this.originX; x < this.endX; x++, at++) {
					int cell = baseCellIndex(x, y, z);
					blocks[at] = effectivePaletteIndex(cell);
					fluids[at] = effectiveFluidPaletteIndex(cell);
				}
			}
		}
		return WorldSnapshot.owning(this.snapshot.originX(), this.snapshot.originY(), this.snapshot.originZ(),
		    this.snapshot.sizeX(), this.snapshot.sizeY(), this.snapshot.sizeZ(), this.snapshot.outside(),
		    this.snapshot.palette(), blocks, this.snapshot.fluidPalette(), fluids);
	}

	/**
	 * Replaces one cell's block with another entry of this snapshot's palette.
	 *
	 * <p>Geometry and coefficients change immediately, so a caller reproducing
	 * vanilla's order calls this before the movement step of the tick the change
	 * belongs to. No placement validation, neighbor update or other game logic
	 * runs. An effective change increments {@link #collisionVersion}; replacing
	 * with the current entry is a no-op. Worker-confined: not safe with any
	 * concurrent read of this instance.
	 *
	 * @throws IllegalStateException when this view is frozen
	 * @throws IndexOutOfBoundsException when the index or the cell is outside the snapshot
	 */
	public void replaceCell(final int x, final int y, final int z, final int paletteIndex) {
		requireMutable();
		if (paletteIndex < 0 || paletteIndex >= this.tables.shapes.length) {
			throw new IndexOutOfBoundsException(
			    "palette index " + paletteIndex + " outside 0.." + this.tables.shapes.length);
		}
		int cell = baseCellIndex(x, y, z);
		if (cell < 0) {
			throw new IndexOutOfBoundsException("replacement cell outside snapshot: " + x + "," + y + "," + z);
		}
		int override = this.overlay.find(cell);
		int current = override < 0 ? baseCell(cell) : this.overlay.valueAt(override);
		if (current == paletteIndex) {
			return;
		}
		detachCollisionGrids();
		detachPropertyGrids();
		if (this.tables.collisionPotential[current] != this.tables.collisionPotential[paletteIndex]) {
			int section = sectionSlot(x, y, z);
			this.collisionSectionCounts[section] += this.tables.collisionPotential[paletteIndex] ? 1 : -1;
		}
		// Set only. Clearing would need a count per property per section, and the
		// error it would remove is the harmless one: a bit left set after the cell
		// that justified it is gone costs a lookup that answers the default, while
		// a bit wrongly clear silently drops a real behavior.
		this.sectionProperties[sectionSlot(x, y, z)] |= this.tables.properties[paletteIndex];
		if (this.tables.largeShape[paletteIndex]) {
			detachLargeShapeGrids();
			this.sectionLargeShape[sectionSlot(x, y, z)] = true;
			this.fineLargeShape[fineSlot(x, y, z)] = true;
		}
		this.fineProperties[fineSlot(x, y, z)] |= this.tables.properties[paletteIndex];
		int base = baseCell(cell);
		if (paletteIndex == base) {
			this.overlay.removeAt(override);
		} else if (override >= 0) {
			this.overlay.setAt(override, paletteIndex);
		} else {
			this.overlay.append(cell, paletteIndex);
		}
		this.collisionVersion++;
		this.sectionVersion[sectionSlot(x, y, z)] = this.collisionVersion;
	}

	/**
	 * Replaces one cell's block and fluid together, as vanilla's block change
	 * sets both, or neither when either index is invalid.
	 *
	 * @throws IllegalStateException when this view is frozen
	 * @throws IndexOutOfBoundsException when an index or the cell is outside the snapshot
	 */
	public void replaceCellState(
	    final int x, final int y, final int z, final int paletteIndex, final int fluidPaletteIndex) {
		if (fluidPaletteIndex < 0 || fluidPaletteIndex >= this.tables.fluidKind.length) {
			throw new IndexOutOfBoundsException(
			    "fluid palette index " + fluidPaletteIndex + " outside 0.." + this.tables.fluidKind.length);
		}
		if (paletteIndex < 0 || paletteIndex >= this.tables.shapes.length) {
			throw new IndexOutOfBoundsException(
			    "palette index " + paletteIndex + " outside 0.." + this.tables.shapes.length);
		}
		if (baseCellIndex(x, y, z) < 0) {
			throw new IndexOutOfBoundsException("replacement cell outside snapshot: " + x + "," + y + "," + z);
		}
		replaceCell(x, y, z, paletteIndex);
		replaceFluidCellUnchecked(x, y, z, fluidPaletteIndex);
	}

	/**
	 * Replaces one cell's fluid independently of its block.
	 *
	 * @throws IllegalStateException when this view is frozen
	 * @throws IndexOutOfBoundsException when the index or the cell is outside the snapshot
	 */
	public void replaceFluidCell(final int x, final int y, final int z, final int fluidPaletteIndex) {
		requireMutable();
		if (fluidPaletteIndex < 0 || fluidPaletteIndex >= this.tables.fluidKind.length) {
			throw new IndexOutOfBoundsException(
			    "fluid palette index " + fluidPaletteIndex + " outside 0.." + this.tables.fluidKind.length);
		}
		if (baseCellIndex(x, y, z) < 0) {
			throw new IndexOutOfBoundsException("replacement cell outside snapshot: " + x + "," + y + "," + z);
		}
		replaceFluidCellUnchecked(x, y, z, fluidPaletteIndex);
	}

	/**
	 * Refuses a cauldron level change at a cell unless everything the change
	 * can reach is inert; see {@code CauldronUpdateNeighborhood}.
	 *
	 * @throws UnimplementedMechanicException when a neighbor update or vibration could reach a block the
	 *     simulator does not model, or a neighbor lies outside the view
	 */
	public void requireCauldronUpdateClosure(final int x, final int y, final int z) {
		CauldronUpdateNeighborhood.requireInert(this, x, y, z);
	}

	/** Whether a catalog installed independently extracted entity-inside shapes. */
	public boolean hasSourceInsideShapes() {
		return this.tables.hasSourceInsideShapes();
	}

	/**
	 * Whether the player's move reached the catalog-extracted entity-inside
	 * shape of one visited cell.
	 *
	 * @throws UnimplementedMechanicException when the cell lies outside the view, or its inside shape is
	 *     entity-dependent or was not extracted
	 */
	public boolean sourceInsideReached(
	    final PlayerState state, final int x, final int y, final int z, final InsideBlockTraversal traversal) {
		int index = paletteIndexAt(x, y, z);
		if (index < 0) {
			throw new UnimplementedMechanicException(
			    RefusalCause.OUTSIDE_REGION, "inside-block traversal leaves the catalog-verified region");
		}
		if (this.tables.air[index]) {
			return false;
		}
		double[] shape = this.tables.sourceInsideShapes[index];
		if (shape == null) {
			throw new UnimplementedMechanicException(
			    RefusalCause.UNDECLARED_BLOCK_STATE, "catalog inside shape is entity-dependent or unknown");
		}
		return this.tables.sourceInsideFull[index] || traversal.collidedWithShapeMovingFrom(state, x, y, z, shape);
	}

	/** Effective palette index after sparse replacements, or {@code -1} outside the bounds. */
	public int paletteIndexAt(final int x, final int y, final int z) {
		int cell = baseCellIndex(x, y, z);
		if (cell < 0) {
			return -1;
		}
		return effectivePaletteIndex(cell);
	}

	@Override
	public boolean movementFactsComplete() {
		return true;
	}

	@Override
	public long collisionVersion() {
		return this.collisionVersion;
	}

	@Override
	public boolean movementFactsImmutable() {
		return this.immutable;
	}

	@Override
	public long identity() {
		return this.identity;
	}

	@Override
	public boolean hasFluids() {
		return this.hasFluidCells;
	}

	@Override
	public void fluidAt(final int x, final int y, final int z, final FluidSample target) {
		int cell = baseCellIndex(x, y, z);
		if (cell < 0) {
			throw UnimplementedMechanicException.at(
			    RefusalCause.OUTSIDE_REGION, "fluid query left the captured region at ", x, y, z, "");
		}
		int index = effectiveFluidPaletteIndex(cell);
		target.set(this.tables.fluidKind[index], this.tables.fluidHeight[index], this.tables.fluidFlowX[index],
		    this.tables.fluidFlowY[index], this.tables.fluidFlowZ[index], this.tables.fluidSource[index]);
	}

	@Override
	public BubbleColumnMode bubbleColumnModeAt(final int x, final int y, final int z) {
		return this.tables.bubbleColumnMode[requireIndexAt(x, y, z, "bubble-column query")];
	}

	@Override
	public boolean hasBubbleColumns() {
		return this.tables.hasBubbleColumns;
	}

	@Override
	public boolean hasCollisionShapeAt(final int x, final int y, final int z) {
		return this.tables.shapes[requireIndexAt(x, y, z, "collision-shape query")].length != 0;
	}

	@Override
	public boolean onClimbableAt(final int x, final int y, final int z, final boolean fallFlying) {
		Climbability role = this.tables.climbability[requireIndexAt(x, y, z, "climbable query")];
		if (role == Climbability.NONE || role == Climbability.GLIDE_THROUGH && fallFlying) {
			return false;
		}
		if (role.ordinal() < Climbability.OPEN_TRAPDOOR_NORTH.ordinal()) {
			return true;
		}
		int below = paletteIndexAt(x, y - 1, z);
		if (below < 0) {
			throw UnimplementedMechanicException.at(
			    RefusalCause.OUTSIDE_REGION, "trapdoor climbable query left the captured region below ", x, y, z, "");
		}
		Climbability[] climbability = this.tables.climbability;
		return switch (role) {
			case OPEN_TRAPDOOR_NORTH -> climbability[below] == Climbability.LADDER_NORTH;
			case OPEN_TRAPDOOR_EAST -> climbability[below] == Climbability.LADDER_EAST;
			case OPEN_TRAPDOOR_SOUTH -> climbability[below] == Climbability.LADDER_SOUTH;
			case OPEN_TRAPDOOR_WEST -> climbability[below] == Climbability.LADDER_WEST;
			default -> false;
		};
	}

	@Override
	public CollisionBehavior collisionBehaviorAt(final int x, final int y, final int z) {
		return this.tables.collisionBehavior[requireIndexAt(x, y, z, "collision-behavior query")];
	}

	@Override
	public float bounceRestitutionAt(final int x, final int y, final int z) {
		return this.tables.bounceRestitution[requireIndexAt(x, y, z, "bounce query")];
	}

	@Override
	public boolean suppressesBounceAt(final int x, final int y, final int z) {
		return this.tables.suppressesBounce[requireIndexAt(x, y, z, "bounce-suppression query")];
	}

	@Override
	public boolean retainsSupportPosAt(final int x, final int y, final int z) {
		return this.tables.retainsSupportPos[requireIndexAt(x, y, z, "support-pos query")];
	}

	/**
	 * The context-free form of {@code CollisionGetter.findSupportingBlock}.
	 *
	 * @throws UnimplementedMechanicException when any palette entry resolves its shape from player
	 *     context, which this form does not carry, as {@link #collectCollisionBoxes} refuses
	 */
	@Override
	public void findSupportingBlock(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double atX, final double atY, final double atZ,
	    final SupportCell out) {
		if (this.tables.hasContextSensitiveCollision) {
			throw new UnimplementedMechanicException(RefusalCause.UNDECLARED_WORLD_FACT,
			    "context-free supporting-block query cannot resolve contextual collision");
		}
		findSupportingBlock(minX, minY, minZ, maxX, maxY, maxZ, atX, atY, atZ, atY, false, 0.0, false, out);
	}

	@Override
	public void findSupportingBlock(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double atX, final double atY, final double atZ,
	    final double entityBottom, final boolean descending, final double fallDistance, final SupportCell out) {
		findSupportingBlock(
		    minX, minY, minZ, maxX, maxY, maxZ, atX, atY, atZ, entityBottom, descending, fallDistance, false, out);
	}

	/**
	 * {@code CollisionGetter.findSupportingBlock} over the cells whose
	 * collision shape reaches the box.
	 *
	 * <p>Iterated in the same order and with the same ring as
	 * {@link #collectCollisionBoxes}, because it is the same {@code BlockCollisions}
	 * walk with the boxes discarded and only the position kept. The winner does not
	 * depend on that order, since {@code distToCenterSqr} plus the {@code compareTo}
	 * tie-break is a total order on cells, but the refusals do: a cell that leaves
	 * the region has to fail closed at the point the collision scan would have
	 * reached it.
	 */
	@Override
	public void findSupportingBlock(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double atX, final double atY, final double atZ,
	    final double entityBottom, final boolean descending, final double fallDistance,
	    final boolean canWalkOnPowderSnow, final SupportCell out) {
		CollisionQuerySpan.requireSupported(minX, minY, minZ, maxX, maxY, maxZ);
		out.clear();
		double best = Double.MAX_VALUE;
		int x0 = ringLow(minX);
		int x1 = ringHigh(maxX);
		int y0 = ringLow(minY);
		int y1 = ringHigh(maxY);
		int z0 = ringLow(minZ);
		int z1 = ringHigh(maxZ);
		boolean ring = this.tables.hasAnyLargeShape;
		int scanX0 = scanLow(x0, ring);
		int scanX1 = scanHigh(x1, ring);
		int scanY0 = scanLow(y0, ring);
		int scanY1 = scanHigh(y1, ring);
		int scanZ0 = scanLow(z0, ring);
		int scanZ1 = scanHigh(z1, ring);
		for (int z = scanZ0; z <= scanZ1; z++) {
			for (int y = scanY0; y <= scanY1; y++) {
				for (int x = scanX0; x <= scanX1; x++) {
					int cursorType = cursorType(x, y, z, x0, y0, z0, x1, y1, z1);
					if (cursorType >= 2) {
						continue;
					}
					if (!inside(x, y, z)) {
						if (cursorType == 0 && intersectsCell(minX, minY, minZ, maxX, maxY, maxZ, x, y, z)) {
							throw UnimplementedMechanicException.at(RefusalCause.OUTSIDE_REGION,
							    "supporting-block query left the captured region at ", x, y, z, "");
						}
						continue;
					}
					int index = paletteIndexAt(x, y, z);
					if (cursorType == 1 && !this.tables.largeShape[index]) {
						continue;
					}
					CollisionBehavior behavior = this.tables.collisionBehavior[index];
					// The captured shape is exactly what a server-mutable cell
					// may invalidate, so it cannot decide its own refusal. Both
					// collision paths refuse on whole-cell overlap, the same
					// predicate this method already uses for a cell whose facts
					// it does not hold.
					if (behavior == CollisionBehavior.SERVER_MUTABLE_SUPPORT) {
						if (intersectsCell(minX, minY, minZ, maxX, maxY, maxZ, x, y, z)) {
							throw UnimplementedMechanicException.at(RefusalCause.UNDECLARED_SERVER_MUTABLE,
							    "support query reached server-mutable collision at ", x, y, z, "");
						}
						continue;
					}
					double[] shape =
					    contextShape(behavior, index, y, entityBottom, descending, fallDistance, canWalkOnPowderSnow);
					if (shape == null
					    || !reaches(
					        shape, shapeKind(behavior, index, shape), x, y, z, minX, minY, minZ, maxX, maxY, maxZ)) {
						continue;
					}
					double dx = x + 0.5 - atX;
					double dy = y + 0.5 - atY;
					double dz = z + 0.5 - atZ;
					double distance = dx * dx + dy * dy + dz * dz;
					if (distance < best || distance == best && (!out.present || out.compareCell(x, y, z) < 0)) {
						best = distance;
						out.set(x, y, z);
					}
				}
			}
		}
	}

	@Override
	public boolean resetsFallDistanceAlong(final double fromX, final double fromY, final double fromZ, final double toX,
	    final double toY, final double toZ) {
		return FallResetClip.resetsAlong(this, fromX, fromY, fromZ, toX, toY, toZ);
	}

	@Override
	public BlockBehavior behaviorAt(final int x, final int y, final int z) {
		return this.tables.behavior[requireIndexAt(x, y, z, "behavior query")];
	}

	/**
	 * {@code Entity.isInWall} over the captured cells: a state whose
	 * suffocation predicate is yes and whose collision box meets the query
	 * box by more than the coordinate merge. An undeclared predicate on a
	 * colliding cell refuses.
	 */
	@Override
	public boolean suffocatingShapeMeets(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ) {
		if (!this.tables.hasAnySuffocation) {
			return false;
		}
		for (int x = Mth.floor(minX); x <= Mth.floor(maxX); x++) {
			for (int y = Mth.floor(minY); y <= Mth.floor(maxY); y++) {
				for (int z = Mth.floor(minZ); z <= Mth.floor(maxZ); z++) {
					int cell = baseCellIndex(x, y, z);
					if (cell < 0) {
						throw UnimplementedMechanicException.at(
						    RefusalCause.OUTSIDE_REGION, "suffocation query left the captured region at ", x, y, z, "");
					}
					int palette = effectivePaletteIndex(cell);
					if (this.tables.suffocation[palette] == PaletteTables.SUFFOCATION_NO) {
						continue;
					}
					double[] boxes = this.tables.shapes[palette];
					boolean meets = false;
					for (int b = 0; b < boxes.length; b += 6) {
						if (Math.min(x + boxes[b + 3], maxX) - Math.max(x + boxes[b], minX) > VANILLA_EPSILON
						    && Math.min(y + boxes[b + 4], maxY) - Math.max(y + boxes[b + 1], minY) > VANILLA_EPSILON
						    && Math.min(z + boxes[b + 5], maxZ) - Math.max(z + boxes[b + 2], minZ) > VANILLA_EPSILON) {
							meets = true;
							break;
						}
					}
					if (!meets) {
						continue;
					}
					if (this.tables.suffocation[palette] == PaletteTables.SUFFOCATION_UNKNOWN) {
						throw UnimplementedMechanicException.at(RefusalCause.UNDECLARED_BLOCK_STATE,
						    "suffocation is undeclared for the colliding cell at ", x, y, z, "");
					}
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * {@code CollisionGetter.collidesWithSuffocatingBlock} over
	 * {@code LocalPlayer.suffocatesAt}'s column box, structured exactly like
	 * {@link #collectCollisionBoxes} because it is the same iterator with one
	 * extra per-state filter and a first-hit exit.
	 *
	 * <p>A palette entry with no geometry is answered without consulting the
	 * predicate at all: {@code BlockCollisions} tests the moved shape against the
	 * box, and an empty shape reaches nothing. That is what lets a snapshot
	 * written before this fact existed still answer every ordinary tick, since
	 * the player's own box is in air, while still failing closed the moment an
	 * unclassified solid actually reaches the query.
	 */
	@Override
	public boolean suffocatesAt(
	    final int cellX, final int cellZ, final double boundingBoxMinY, final double boundingBoxMaxY) {
		if (!this.tables.hasAnySuffocation) {
			return false;
		}
		// The column path is exact wherever no shape can reach into the column,
		// and that is a question about this column's neighborhood rather than
		// about the world: gating it on the palette-wide flag lets one fence
		// anywhere turn it off for every query. The span tested is the one the
		// general form would scan, the column expanded by the Cursor3D ring.
		if (!largeShapeIn(cellX - 1, Mth.floor(boundingBoxMinY + VANILLA_EPSILON) - 1, cellZ - 1, cellX + 1,
		        Mth.floor(boundingBoxMaxY - VANILLA_EPSILON) + 1, cellZ + 1)) {
			return suffocatesInColumn(cellX, cellZ, boundingBoxMinY, boundingBoxMaxY);
		}
		double minX = cellX + VANILLA_EPSILON;
		double maxX = cellX + 1.0 - VANILLA_EPSILON;
		double minY = boundingBoxMinY + VANILLA_EPSILON;
		double maxY = boundingBoxMaxY - VANILLA_EPSILON;
		double minZ = cellZ + VANILLA_EPSILON;
		double maxZ = cellZ + 1.0 - VANILLA_EPSILON;
		int x0 = ringLow(minX);
		int x1 = ringHigh(maxX);
		int y0 = ringLow(minY);
		int y1 = ringHigh(maxY);
		int z0 = ringLow(minZ);
		int z1 = ringHigh(maxZ);
		boolean ring = this.tables.hasAnyLargeShape;
		int scanX0 = scanLow(x0, ring);
		int scanX1 = scanHigh(x1, ring);
		int scanY0 = scanLow(y0, ring);
		int scanY1 = scanHigh(y1, ring);
		int scanZ0 = scanLow(z0, ring);
		int scanZ1 = scanHigh(z1, ring);
		for (int z = scanZ0; z <= scanZ1; z++) {
			for (int y = scanY0; y <= scanY1; y++) {
				for (int x = scanX0; x <= scanX1; x++) {
					int cursorType = cursorType(x, y, z, x0, y0, z0, x1, y1, z1);
					if (cursorType >= 2) {
						continue;
					}
					if (!inside(x, y, z)) {
						if (cursorType == 0) {
							outsideSuffocates(x, y, z, minX, minY, minZ, maxX, maxY, maxZ);
						}
						continue;
					}
					int index = paletteIndexAt(x, y, z);
					if (cursorType == 1 && !this.tables.largeShape[index]) {
						continue;
					}
					if (suffocatingCell(index, x, y, z, minX, minY, minZ, maxX, maxY, maxZ)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	@Override
	public boolean hasContextSensitiveCollision() {
		return this.tables.hasContextSensitiveCollision;
	}

	@Override
	public boolean hasClimbables() {
		return this.tables.hasClimbables;
	}

	@Override
	public boolean hasNonDefaultFriction() {
		return this.tables.hasNonDefaultFriction;
	}

	@Override
	public boolean hasNonDefaultSpeedFactor() {
		return this.tables.hasNonDefaultSpeedFactor;
	}

	@Override
	public boolean hasNonDefaultJumpFactor() {
		return this.tables.hasNonDefaultJumpFactor;
	}

	@Override
	public boolean hasPowderSnow() {
		return this.tables.hasPowderSnow;
	}

	/**
	 * The context-free form of {@code BlockCollisions}.
	 *
	 * @throws UnimplementedMechanicException when any palette entry resolves its shape from player
	 *     context, which this form does not carry
	 */
	@Override
	public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final CollisionBuffer target) {
		if (this.tables.hasContextSensitiveCollision) {
			throw new UnimplementedMechanicException(
			    RefusalCause.UNDECLARED_WORLD_FACT, "context-free collision query cannot resolve contextual collision");
		}
		collectCollisionBoxes(minX, minY, minZ, maxX, maxY, maxZ, Double.NEGATIVE_INFINITY, false, 0.0, false, target);
	}

	@Override
	public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double entityBottom, final boolean descending,
	    final CollisionBuffer target) {
		collectCollisionBoxes(minX, minY, minZ, maxX, maxY, maxZ, entityBottom, descending, 0.0, false, target);
	}

	@Override
	public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double entityBottom, final boolean descending,
	    final double fallDistance, final CollisionBuffer target) {
		collectCollisionBoxes(
		    minX, minY, minZ, maxX, maxY, maxZ, entityBottom, descending, fallDistance, false, target);
	}

	/**
	 * {@code BlockCollisions} over the captured cells: every shape the query box
	 * reaches, in Cursor3D order, X fastest, then Y, then Z.
	 */
	@Override
	public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double entityBottom, final boolean descending,
	    final double fallDistance, final boolean canWalkOnPowderSnow, final CollisionBuffer target) {
		CollisionQuerySpan.requireSupported(minX, minY, minZ, maxX, maxY, maxZ);
		target.clear();
		int x0 = ringLow(minX);
		int x1 = ringHigh(maxX);
		int y0 = ringLow(minY);
		int y1 = ringHigh(maxY);
		int z0 = ringLow(minZ);
		int z1 = ringHigh(maxZ);
		// Whether the Cursor3D ring has to be walked at all. Asked of this query's
		// own scan span rather than of the palette, so one fence in the region
		// does not cost the ring on every query everywhere in it.
		boolean ring = largeShapeIn(x0, y0, z0, x1, y1, z1);
		int scanX0 = scanLow(x0, ring);
		int scanX1 = scanHigh(x1, ring);
		int scanY0 = scanLow(y0, ring);
		int scanY1 = scanHigh(y1, ring);
		int scanZ0 = scanLow(z0, ring);
		int scanZ1 = scanHigh(z1, ring);
		boolean scanInside = scanX0 >= this.originX && scanX1 < this.endX && scanY0 >= this.originY
		    && scanY1 < this.endY && scanZ0 >= this.originZ && scanZ1 < this.endZ;
		if (scanInside && !hasCollisionPotential(scanX0, scanY0, scanZ0, scanX1, scanY1, scanZ1)) {
			return;
		}
		// The interior scan reads captured shapes without consulting collision
		// behavior, so it may only run where no cell refuses. Context-sensitive
		// cells are excluded for the whole view; a server-mutable cell refuses
		// without context, so it withdraws only the queries that reach it.
		if (scanInside && !ring && !this.tables.hasContextSensitiveCollision
		    && (!this.tables.hasServerMutableCollision
		        || propertiesIn(PROPERTY_SERVER_MUTABLE, scanX0, scanY0, scanZ0, scanX1, scanY1, scanZ1) == 0)) {
			collectOrdinaryInterior(
			    scanX0, scanY0, scanZ0, scanX1, scanY1, scanZ1, minX, minY, minZ, maxX, maxY, maxZ, target);
			return;
		}
		// Cursor3D order in vanilla: X fastest, then Y, then Z.
		for (int z = scanZ0; z <= scanZ1; z++) {
			for (int y = scanY0; y <= scanY1; y++) {
				for (int x = scanX0; x <= scanX1; x++) {
					int cursorType = cursorType(x, y, z, x0, y0, z0, x1, y1, z1);
					if (cursorType >= 2) {
						continue;
					}
					if (!inside(x, y, z)) {
						// Sealed space behaves as an ordinary full block. It is not
						// a large shape, so Cursor3D's face and edge rings filter it
						// before querying the world.
						if (cursorType == 0) {
							outside(x, y, z, target, minX, minY, minZ, maxX, maxY, maxZ);
						}
						continue;
					}
					int index = paletteIndexAt(x, y, z);
					if (cursorType == 1 && !this.tables.largeShape[index]) {
						continue;
					}
					CollisionBehavior behavior = this.tables.collisionBehavior[index];
					if (behavior == CollisionBehavior.SERVER_MUTABLE_SUPPORT) {
						if (intersectsCell(minX, minY, minZ, maxX, maxY, maxZ, x, y, z)) {
							throw UnimplementedMechanicException.at(RefusalCause.UNDECLARED_SERVER_MUTABLE,
							    "movement reached server-mutable collision at ", x, y, z, "");
						}
						continue;
					}
					double[] shape =
					    contextShape(behavior, index, y, entityBottom, descending, fallDistance, canWalkOnPowderSnow);
					if (shape == null) {
						continue;
					}
					appendGroupIfIntersects(
					    shape, shapeKind(behavior, index, shape), x, y, z, minX, minY, minZ, maxX, maxY, maxZ, target);
				}
			}
		}
	}

	/**
	 * Span reuse needs exactly what {@link #collectOrdinaryInterior} needs: every
	 * shape cell-bounded, so no cell outside a sub-span can contribute a box to
	 * it, and no dependence on player context, so a retained span stays valid for
	 * queries that carry different context than the one that built it.
	 *
	 * <p>Cell-boundedness is checked per span in {@link #collectSpanBoxes} and not
	 * here. This answers whether reuse is ever possible, and a world holding one
	 * fence can still reuse every span that is nowhere near it.
	 *
	 * <p>A server-mutable cell refuses rather than answering, and depends on no
	 * player context, so it does not make the whole world context-sensitive. It
	 * withdraws only the spans that touch it, in {@link #collectSpanBoxes}.
	 */
	@Override
	public boolean supportsSpanReuse() {
		return !this.tables.hasContextSensitiveCollision;
	}

	@Override
	public CollisionShapeProtocol collisionShapeProtocol() {
		return CollisionShapeProtocol.SOURCE_GROUPS_V1;
	}

	@Override
	public boolean spanHasCollisionPotential(
	    final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		if (!spanInside(x0, y0, z0, x1, y1, z1)) {
			return true;
		}
		return hasCollisionPotential(x0, y0, z0, x1, y1, z1);
	}

	@Override
	public boolean collectSpanBoxes(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1,
	    final CollisionBuffer target) {
		if (!supportsSpanReuse() || !spanInside(x0, y0, z0, x1, y1, z1)) {
			// Outside the region is not air, and the ordinary path owns that
			// semantic. Refusing here keeps it in one place.
			return false;
		}
		// Cell-boundedness, per span. A retained span is refiltered for sub-queries,
		// so a shape owned one cell outside it could reach into one of those and be
		// missed; hence the ring, the same neighborhood the scan itself walks.
		if (largeShapeIn(x0 - 1, y0 - 1, z0 - 1, x1 + 1, y1 + 1, z1 + 1)) {
			return false;
		}
		// A span collects captured shapes once and refilters them for later
		// sub-queries, so it cannot ask a server-mutable cell's refusal question,
		// which depends on the querying box. Declining the span sends those
		// queries down the ordinary path, which refuses exactly when a box
		// overlaps the cell. The ring matches the neighborhood that path scans.
		if (this.tables.hasServerMutableCollision
		    && propertiesIn(PROPERTY_SERVER_MUTABLE, x0 - 1, y0 - 1, z0 - 1, x1 + 1, y1 + 1, z1 + 1) != 0) {
			return false;
		}
		target.clear();
		if (!hasCollisionPotential(x0, y0, z0, x1, y1, z1)) {
			return true;
		}
		boolean hasOverrides = this.overlay.size() != 0;
		// X fastest, then Y, then Z, matching Cursor3D and collectOrdinaryInterior.
		// A sub-span of this span is an interval in each coordinate, so restricting
		// this order to it is that sub-span's own order. Rows are walked as runs
		// per section, as there.
		for (int z = z0; z <= z1; z++) {
			int rowSlotZ = ((z >> Section.SHIFT) - this.sectionOriginZ) * this.sectionsX;
			int rowLocalZ = (z & Section.MASK) << Section.SHIFT;
			for (int y = y0; y <= y1; y++) {
				int rowSlot = ((y >> Section.SHIFT) - this.sectionOriginY) * this.sectionsZ * this.sectionsX + rowSlotZ
				    - this.sectionOriginX;
				int rowLocal = ((y & Section.MASK) << (2 * Section.SHIFT)) | rowLocalZ;
				int x = x0;
				while (x <= x1) {
					int runEnd = Math.min(x1, x | Section.MASK);
					int slot = rowSlot + (x >> Section.SHIFT);
					Section section = this.sections[slot];
					int local = rowLocal | (x & Section.MASK);
					int[] plane = section.rawCells();
					if (plane == null) {
						int uniform = section.uniformIndex();
						if (uniform < 0) {
							throw missingSection((slot << CELL_SHIFT) | local);
						}
						if (!hasOverrides && this.tables.shapeClass[uniform] == PaletteTables.SHAPE_EMPTY) {
							x = runEnd + 1;
							continue;
						}
					}
					collectSpanRun(
					    section, plane, (slot << CELL_SHIFT) | local, local, x, runEnd, y, z, hasOverrides, target);
					x = runEnd + 1;
				}
			}
		}
		return true;
	}

	@Override
	public int propertiesAnywhere(final int wanted) {
		return this.wholeViewProperties & wanted;
	}

	/**
	 * A conservative property union: fine masks for bounded spans, section masks
	 * for larger spans, or the whole-view mask outside the region.
	 *
	 * <p>Structured like {@link #hasCollisionPotential}, and for the same reason:
	 * a movement query spans two to four cells, so it almost always lies in one
	 * 16-cube and the loop setup would be most of the cost.
	 */
	@Override
	public int propertiesIn(
	    final int wanted, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		// No section can carry what the whole snapshot does not, so this rejects
		// every fluid question asked of a dry world for one field load, which is
		// what the flag it replaces cost, and the reason a small fixture does not
		// pay for the section machinery it cannot use.
		int possible = this.wholeViewProperties & wanted;
		if (possible == 0) {
			return 0;
		}
		if (!spanInside(x0, y0, z0, x1, y1, z1)) {
			return possible;
		}
		// A span inside one fine cube is answered by that cube alone, and more
		// precisely than the coarse mask could: its bits are a subset. Reaching
		// the coarse level first would be two extra loads to obtain a weaker
		// answer. Common coefficient and fluid questions ask about one cell,
		// which always lies in one cube.
		int fineX0 = (x0 >> FINE_SHIFT) - this.fineOriginX;
		int fineY0 = (y0 >> FINE_SHIFT) - this.fineOriginY;
		int fineZ0 = (z0 >> FINE_SHIFT) - this.fineOriginZ;
		int fineX1 = (x1 >> FINE_SHIFT) - this.fineOriginX;
		int fineY1 = (y1 >> FINE_SHIFT) - this.fineOriginY;
		int fineZ1 = (z1 >> FINE_SHIFT) - this.fineOriginZ;
		if (fineX0 == fineX1 && fineY0 == fineY1 && fineZ0 == fineZ1) {
			return this.fineProperties[fineSlotOf(fineX0, fineY0, fineZ0)] & possible;
		}
		// Fine masks are subsets of their section masks. For a bounded fine scan,
		// their union is already the refined answer; a coarse read cannot alter it.
		if ((fineX1 - fineX0 + 1) * (fineY1 - fineY0 + 1) * (fineZ1 - fineZ0 + 1) <= FINE_CUBE_LIMIT) {
			return propertiesFromFine(possible, fineX0, fineY0, fineZ0, fineX1, fineY1, fineZ1);
		}
		return propertiesFromSections(possible, x0, y0, z0, x1, y1, z1);
	}

	/**
	 * The revisions of the sections the span touches, folded in grid order,
	 * and this view's identity folded in as well when any of them has been
	 * edited here. Two views that hold the same section objects over the
	 * span, both unedited there, answer the same number, whichever snapshot
	 * each was compiled from; a span reaching outside the bounds or into a
	 * missing section has no revision. The fold is a 64-bit mix of
	 * process-unique revisions: a stale cache entry becomes a miss, and a hit
	 * that was not one needs two distinct revision tuples over one span to mix
	 * to one value.
	 */
	@Override
	public long spanRevision(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		if (!spanInside(x0, y0, z0, x1, y1, z1)) {
			return 0L;
		}
		int sectionX0 = (x0 >> Section.SHIFT) - this.sectionOriginX;
		int sectionY0 = (y0 >> Section.SHIFT) - this.sectionOriginY;
		int sectionZ0 = (z0 >> Section.SHIFT) - this.sectionOriginZ;
		int sectionX1 = (x1 >> Section.SHIFT) - this.sectionOriginX;
		int sectionY1 = (y1 >> Section.SHIFT) - this.sectionOriginY;
		int sectionZ1 = (z1 >> Section.SHIFT) - this.sectionOriginZ;
		long[] revisions = this.sectionRevision;
		long fold;
		boolean edited;
		if (sectionX0 == sectionX1 && sectionY0 == sectionY1 && sectionZ0 == sectionZ1) {
			// One section, the common body span: its revision is already a
			// process-unique name for its cells, so nothing is folded.
			int section = this.grid.index(sectionX0, sectionY0, sectionZ0);
			fold = revisions[section];
			if (fold == 0L) {
				return 0L;
			}
			edited = this.collisionVersion != 0L && this.sectionVersion[section] != 0L;
		} else {
			fold = REVISION_SEED;
			edited = false;
			for (int sectionZ = sectionZ0; sectionZ <= sectionZ1; sectionZ++) {
				for (int sectionY = sectionY0; sectionY <= sectionY1; sectionY++) {
					int section = this.grid.index(sectionX0, sectionY, sectionZ);
					for (int sectionX = sectionX0; sectionX <= sectionX1; sectionX++, section++) {
						long revision = revisions[section];
						if (revision == 0L) {
							return 0L;
						}
						fold = mix(fold, revision);
						edited |= this.sectionVersion[section] != 0L;
					}
				}
			}
			edited &= this.collisionVersion != 0L;
		}
		if (edited) {
			fold = mix(mix(fold, this.identity), this.collisionVersionIn(x0, y0, z0, x1, y1, z1));
		}
		return fold == 0L ? 1L : fold;
	}

	/**
	 * Highest version stamped on any section the span touches.
	 *
	 * <p>A maximum rather than a sum or a counter: it is monotone, it changes
	 * exactly when a section in the span is written, and it is unaffected by
	 * writes anywhere else. A span leaving the region falls back to the global
	 * counter, since space outside the snapshot has no section to stamp.
	 */
	@Override
	public long collisionVersionIn(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		if (this.collisionVersion == 0L) {
			// Nothing has ever been written, so every section still answers 0 and
			// the loop below can only agree. Worth its own test because an
			// unmodified world is the overwhelmingly common case.
			return 0L;
		}
		if (!spanInside(x0, y0, z0, x1, y1, z1)) {
			return this.collisionVersion;
		}
		int sectionX0 = (x0 >> Section.SHIFT) - this.sectionOriginX;
		int sectionY0 = (y0 >> Section.SHIFT) - this.sectionOriginY;
		int sectionZ0 = (z0 >> Section.SHIFT) - this.sectionOriginZ;
		int sectionX1 = (x1 >> Section.SHIFT) - this.sectionOriginX;
		int sectionY1 = (y1 >> Section.SHIFT) - this.sectionOriginY;
		int sectionZ1 = (z1 >> Section.SHIFT) - this.sectionOriginZ;
		if (sectionX0 == sectionX1 && sectionY0 == sectionY1 && sectionZ0 == sectionZ1) {
			return this.sectionVersion[this.grid.index(sectionX0, sectionY0, sectionZ0)];
		}
		long version = 0L;
		for (int sectionZ = sectionZ0; sectionZ <= sectionZ1; sectionZ++) {
			for (int sectionY = sectionY0; sectionY <= sectionY1; sectionY++) {
				int section = this.grid.index(sectionX0, sectionY, sectionZ);
				for (int sectionX = sectionX0; sectionX <= sectionX1; sectionX++, section++) {
					if (this.sectionVersion[section] > version) {
						version = this.sectionVersion[section];
					}
				}
			}
		}
		return version;
	}

	@Override
	public float friction(final int x, final int y, final int z) {
		int index = paletteIndexAt(x, y, z);
		return index < 0 ? 0.6F : this.tables.friction[index];
	}

	@Override
	public float speedFactor(final int x, final int y, final int z) {
		int index = paletteIndexAt(x, y, z);
		return index < 0 ? 1.0F : this.tables.speedFactor[index];
	}

	@Override
	public boolean suppressesSupportingSpeedFactor(final int x, final int y, final int z) {
		int index = paletteIndexAt(x, y, z);
		return index >= 0 && this.tables.suppressesSupportingSpeedFactor[index];
	}

	@Override
	public float jumpFactor(final int x, final int y, final int z) {
		int index = paletteIndexAt(x, y, z);
		return index < 0 ? 1.0F : this.tables.jumpFactor[index];
	}

	/**
	 * Whether the world holds the column: it lies inside the bounds and at
	 * least one of its sections is held. A column of nothing but missing
	 * sections is a chunk the producer did not have, and vanilla's answer
	 * about it is the one this world refuses to guess; a held column with a
	 * missing section in it is a chunk vanilla had, and a read into the
	 * missing section refuses on its own.
	 */
	@Override
	public boolean hasChunkAt(final int x, final int z) {
		if (x < this.originX || x >= this.endX || z < this.originZ || z >= this.endZ) {
			return false;
		}
		if (!this.hasMissingSections) {
			return true;
		}
		int column =
		    this.grid.index((x >> Section.SHIFT) - this.sectionOriginX, 0, (z >> Section.SHIFT) - this.sectionOriginZ);
		int stride = this.sectionsX * this.sectionsZ;
		for (int sectionY = 0; sectionY < this.sectionsY; sectionY++) {
			if (!this.sections[sectionY * stride + column].isMissing()) {
				return true;
			}
		}
		return false;
	}

	@Override
	public int minY() {
		return this.originY;
	}

	/**
	 * Air by block identity over the closed range. Sealed space outside the
	 * bounds is solid and decides {@link Air#NOT_AIR}; refusing space outside
	 * the bounds and a missing section inside them claim nothing, so a range
	 * that reaches either without meeting a known non-air cell is
	 * {@link Air#UNKNOWN}.
	 */
	@Override
	public Air airIn(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		boolean unknown = false;
		for (int y = y0; y <= y1; y++) {
			for (int z = z0; z <= z1; z++) {
				for (int x = x0; x <= x1; x++) {
					int cell = baseCellIndex(x, y, z);
					if (cell < 0) {
						if (this.outside == OutsidePolicy.SEALED) {
							return Air.NOT_AIR;
						}
						unknown = true;
						continue;
					}
					// A replaced cell always lies in a held section, so a missing
					// section has no override to consult.
					if (this.sections[cell >>> CELL_SHIFT].isMissing()) {
						unknown = true;
						continue;
					}
					if (!this.tables.air[effectivePaletteIndex(cell)]) {
						return Air.NOT_AIR;
					}
				}
			}
		}
		return unknown ? Air.UNKNOWN : Air.ALL_AIR;
	}

	/**
	 * One cell of the fall-distance-resetting clip: the full cube of a tagged
	 * block, then the water shape.
	 *
	 * <p>The water shape is {@code FlowingFluid.getShape}, which caches one
	 * {@code Shapes.box(0, 0, 0, 1, height, 1)} per fluid state for the life
	 * of the client and computes {@code height} from whichever cell first asked
	 * for that state: {@code 1.0F} when that cell had the same fluid above it,
	 * else {@code amount / 9.0F}. Water has no amount of nine, so the per-call
	 * full-block branch never applies. The captured {@code height} is this
	 * cell's own {@code getHeight}, so the cached height is either it or the
	 * other of the two values, and a hit that only one of them produces is
	 * session history rather than a world fact.
	 *
	 * @return {@link FallResetClip#MISS}, {@link FallResetClip#HIT} or {@link FallResetClip#SESSION_DEPENDENT}
	 */
	int resetCellHit(final int x, final int y, final int z, final double fromX, final double fromY, final double fromZ,
	    final double toX, final double toY, final double toZ) {
		int cell = baseCellIndex(x, y, z);
		if (cell < 0) {
			throw UnimplementedMechanicException.at(
			    RefusalCause.OUTSIDE_REGION, "fall-distance-resetting query left the captured region at ", x, y, z, "");
		}
		int palette = effectivePaletteIndex(cell);
		boolean resettingBlock = this.tables.fallDistanceResettingBlock[palette]
		    || this.tables.insideEffect[palette] == InsideEffect.COBWEB
		    || this.tables.insideEffect[palette] == InsideEffect.SWEET_BERRY_BUSH;
		if (resettingBlock
		    && FallResetClip.clipBox(x, y, z, x + 1.0, y + 1.0, z + 1.0, fromX, fromY, fromZ, toX, toY, toZ)) {
			return FallResetClip.HIT;
		}
		int fluid = effectiveFluidPaletteIndex(cell);
		if (this.tables.fluidKind[fluid] != FluidSample.Kind.WATER) {
			return FallResetClip.MISS;
		}
		// Neither cached height exceeds the full cube, so a full-cube miss is a miss.
		if (!FallResetClip.clipBox(x, y, z, x + 1.0, y + 1.0, z + 1.0, fromX, fromY, fromZ, toX, toY, toZ)) {
			return FallResetClip.MISS;
		}
		// The lower of the two heights the cache can hold for this state. A
		// captured height below one is the state's own height, and the cache
		// holds it or 1.0. A captured height of one means the same fluid lies
		// above, and the cache holds 1.0 or the own height, which the capture
		// does not carry: eight ninths for a source, at least one ninth for a
		// flowing state.
		double captured = this.tables.fluidHeight[fluid];
		double lowest = captured < 1.0 ? captured : this.tables.fluidSource[fluid] ? 8 / 9.0F : 1 / 9.0F;
		return FallResetClip.clipBox(x, y, z, x + 1.0, y + lowest, z + 1.0, fromX, fromY, fromZ, toX, toY, toZ)
		    ? FallResetClip.HIT
		    : FallResetClip.SESSION_DEPENDENT;
	}

	/*
	 * The Cursor3D scan span. BlockCollisions widens the query box by the vanilla
	 * epsilon, floors it to cells, and walks one cell further on every side: that
	 * ring is where a shape owned by a neighboring cell can still reach the box.
	 * A world with no such shape skips the ring.
	 */

	/** The first cell of the scan span on one axis, one below the widened box's low face. */
	private static int ringLow(final double min) {
		return Mth.floor(min - VANILLA_EPSILON) - 1;
	}

	/** The last cell of the scan span on one axis, one above the widened box's high face. */
	private static int ringHigh(final double max) {
		return Mth.floor(max + VANILLA_EPSILON) + 1;
	}

	private static int scanLow(final int ringLow, final boolean ring) {
		return ring ? ringLow : ringLow + 1;
	}

	private static int scanHigh(final int ringHigh, final boolean ring) {
		return ring ? ringHigh : ringHigh - 1;
	}

	/**
	 * {@code Cursor3D}'s cursor type: how many of a cell's coordinates lie on the
	 * ring. Zero is the interior, one is a face of the ring, whose cells are
	 * consulted only for shapes that leave their cell, and two or more is an
	 * edge or corner, which the walk skips.
	 */
	private static int cursorType(final int x, final int y, final int z, final int x0, final int y0, final int z0,
	    final int x1, final int y1, final int z1) {
		int cursorType = 0;
		if (x == x0 || x == x1) {
			cursorType++;
		}
		if (y == y0 || y == y1) {
			cursorType++;
		}
		if (z == z0 || z == z1) {
			cursorType++;
		}
		return cursorType;
	}

	/**
	 * The shape a cell of {@code behavior} contributes under this player context,
	 * or null when it contributes none. {@code SERVER_MUTABLE_SUPPORT} is the
	 * caller's: it refuses rather than answering.
	 */
	private double[] contextShape(final CollisionBehavior behavior, final int index, final int y,
	    final double entityBottom, final boolean descending, final double fallDistance,
	    final boolean canWalkOnPowderSnow) {
		return switch (behavior) {
			case ORDINARY -> this.tables.shapes[index];
			case SCAFFOLDING_SUPPORTED ->
				!descending&& entityBottom > y + 1.0 - (double) Mth.EPSILON ? SCAFFOLDING_STABLE_SHAPE : null;
			case SCAFFOLDING_UNSTABLE_BOTTOM -> unstableScaffoldingShape(y, entityBottom, descending);
			case POWDER_SNOW_NO_BOOTS ->
				fallDistance > POWDER_SNOW_FALLING_DISTANCE ? POWDER_SNOW_FALLING_SHAPE
				    : canWalkOnPowderSnow&& entityBottom > y + 1.0 - (double) Mth.EPSILON && !descending
				    ? FULL_BLOCK_SHAPE
				    : null;
			case SERVER_MUTABLE_SUPPORT -> throw new IllegalStateException("server-mutable cells refuse before this");
		};
	}

	/** The shape class of a context-resolved shape; an ordinary cell's is its palette entry's. */
	private byte shapeKind(final CollisionBehavior behavior, final int index, final double[] shape) {
		return behavior == CollisionBehavior.ORDINARY ? this.tables.shapeClass[index]
		    : shape == FULL_BLOCK_SHAPE               ? PaletteTables.SHAPE_FULL_CUBE
		                                              : PaletteTables.SHAPE_GENERAL;
	}

	/** {@code ScaffoldingBlock.getCollisionShape} for a bottom unstable state. */
	private static double[] unstableScaffoldingShape(final int y, final double entityBottom, final boolean descending) {
		if (!descending && entityBottom > y + 1.0 - (double) Mth.EPSILON) {
			return SCAFFOLDING_STABLE_SHAPE;
		}
		return entityBottom > y - (double) Mth.EPSILON ? SCAFFOLDING_UNSTABLE_BOTTOM_SHAPE : null;
	}

	/**
	 * {@link #suffocatesAt} for a world with no shape that leaves its own cell.
	 *
	 * <p>The query box is one cell wide in X and Z, deflated inside it, so the
	 * only cells whose geometry can reach it are the single column
	 * {@code (cellX, cellZ)}. The general form still walks two columns on each
	 * horizontal axis and discards three of every four with {@link #reaches},
	 * because it reproduces {@code Cursor3D}'s ring, and the ring exists for
	 * shapes that reach out of their cell, which by this branch's condition the
	 * world has none of. It also drops the per-cell cursor-type arithmetic,
	 * which is constant zero here.
	 *
	 * <p>Exactly equivalent, not merely close. The cells this skips are the ones
	 * the general form reaches with cursor type zero and then rejects: inside
	 * the region {@link #reaches} is false for all of them, since their shapes
	 * are cell-local and their cells do not intersect the box, and outside it
	 * {@link #outsideSuffocates} throws only on intersection, which the same
	 * argument rules out. Four of these run per tick, unconditionally, from
	 * {@code LocalPlayer.aiStep}.
	 */
	private boolean suffocatesInColumn(
	    final int cellX, final int cellZ, final double boundingBoxMinY, final double boundingBoxMaxY) {
		double minX = cellX + VANILLA_EPSILON;
		double maxX = cellX + 1.0 - VANILLA_EPSILON;
		double minY = boundingBoxMinY + VANILLA_EPSILON;
		double maxY = boundingBoxMaxY - VANILLA_EPSILON;
		double minZ = cellZ + VANILLA_EPSILON;
		double maxZ = cellZ + 1.0 - VANILLA_EPSILON;
		int y0 = Mth.floor(minY);
		int y1 = Mth.floor(maxY);
		if (cellX < this.originX || cellX >= this.endX || cellZ < this.originZ || cellZ >= this.endZ) {
			throw UnimplementedMechanicException.deferred(RefusalCause.OUTSIDE_REGION,
			    () -> "suffocation query left the captured region at " + cellX + ",*," + cellZ);
		}
		for (int y = y0; y <= y1; y++) {
			if (y < this.originY || y >= this.endY) {
				throw UnimplementedMechanicException.at(
				    RefusalCause.OUTSIDE_REGION, "suffocation query left the captured region at ", cellX, y, cellZ, "");
			}
			int index = paletteIndexAt(cellX, y, cellZ);
			if (suffocatingCell(index, cellX, y, cellZ, minX, minY, minZ, maxX, maxY, maxZ)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * One cell of {@code collidesWithSuffocatingBlock}: true when the cell's
	 * shape reaches the box and its predicate is yes, false when it does not
	 * reach or does not suffocate.
	 *
	 * @throws UnimplementedMechanicException when the shape reaches the box and the predicate is
	 *     undeclared, or the cell resolves its shape from player context
	 */
	private boolean suffocatingCell(final int index, final int x, final int y, final int z, final double minX,
	    final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
		byte suffocates = this.tables.suffocation[index];
		if (suffocates == PaletteTables.SUFFOCATION_NO) {
			return false;
		}
		// Context-sensitive geometry is resolved for collision from the player's
		// own state, which this query does not carry. Neither scaffolding nor
		// powder snow suffocates in vanilla, since both fail
		// isCollisionShapeFullBlock against an empty context, so the captured
		// shape is only consulted for ORDINARY cells and anything else is
		// refused rather than approximated.
		double[] shape = this.tables.shapes[index];
		byte kind = this.tables.shapeClass[index];
		if (this.tables.collisionBehavior[index] != CollisionBehavior.ORDINARY) {
			if (reaches(shape, kind, x, y, z, minX, minY, minZ, maxX, maxY, maxZ)) {
				throw UnimplementedMechanicException.at(RefusalCause.UNDECLARED_WORLD_FACT,
				    "suffocation query reached context-sensitive collision at ", x, y, z, "");
			}
			return false;
		}
		if (!reaches(shape, kind, x, y, z, minX, minY, minZ, maxX, maxY, maxZ)) {
			return false;
		}
		if (suffocates == PaletteTables.SUFFOCATION_UNKNOWN) {
			throw UnimplementedMechanicException.at(RefusalCause.UNDECLARED_BLOCK_STATE,
			    "suffocation is unknown for the block at ", x, y, z,
			    "; the snapshot predates the fact and the query reached it");
		}
		return true;
	}

	/** Whether this shape reaches the query box under its canonical-full or general admission. */
	private static boolean reaches(final double[] shape, final byte kind, final int x, final int y, final int z,
	    final double minX, final double minY, final double minZ, final double maxX, final double maxY,
	    final double maxZ) {
		if (kind == PaletteTables.SHAPE_EMPTY) {
			return false;
		}
		if (kind == PaletteTables.SHAPE_FULL_CUBE) {
			return intersectsCell(minX, minY, minZ, maxX, maxY, maxZ, x, y, z);
		}
		return CollisionCollector.generalGroupIntersects(shape, x, y, z, minX, minY, minZ, maxX, maxY, maxZ);
	}

	/**
	 * Outside the region is a full cube of unknown suffocation. Sealed space is
	 * still unclassified space, so reaching it with this query fails closed just
	 * as refusing space does.
	 */
	private void outsideSuffocates(final int x, final int y, final int z, final double minX, final double minY,
	    final double minZ, final double maxX, final double maxY, final double maxZ) {
		if (!intersectsCell(minX, minY, minZ, maxX, maxY, maxZ, x, y, z)) {
			return;
		}
		throw UnimplementedMechanicException.at(
		    RefusalCause.OUTSIDE_REGION, "suffocation query left the captured region at ", x, y, z, "");
	}

	/**
	 * Exact hot path for an in-region Cursor3D interior containing only ordinary,
	 * cell-bounded shapes. X remains fastest, then Y, then Z, matching vanilla.
	 */
	private void collectOrdinaryInterior(final int x0, final int y0, final int z0, final int x1, final int y1,
	    final int z1, final double minX, final double minY, final double minZ, final double maxX, final double maxY,
	    final double maxZ, final CollisionBuffer target) {
		boolean hasOverrides = this.overlay.size() != 0;
		// A row of cells along x lies in one section until a section boundary,
		// so the row is walked as runs, each over one section's contiguous
		// plane. A uniform empty run is skipped whole. The row's section slot
		// and local base are fixed for the row; only the x term varies.
		for (int z = z0; z <= z1; z++) {
			int rowSlotZ = ((z >> Section.SHIFT) - this.sectionOriginZ) * this.sectionsX;
			int rowLocalZ = (z & Section.MASK) << Section.SHIFT;
			for (int y = y0; y <= y1; y++) {
				int rowSlot = ((y >> Section.SHIFT) - this.sectionOriginY) * this.sectionsZ * this.sectionsX + rowSlotZ
				    - this.sectionOriginX;
				int rowLocal = ((y & Section.MASK) << (2 * Section.SHIFT)) | rowLocalZ;
				int x = x0;
				while (x <= x1) {
					int runEnd = Math.min(x1, x | Section.MASK);
					int slot = rowSlot + (x >> Section.SHIFT);
					Section section = this.sections[slot];
					int local = rowLocal | (x & Section.MASK);
					int[] plane = section.rawCells();
					if (plane == null) {
						int uniform = section.uniformIndex();
						if (uniform < 0) {
							throw missingSection((slot << CELL_SHIFT) | local);
						}
						if (!hasOverrides && this.tables.shapeClass[uniform] == PaletteTables.SHAPE_EMPTY) {
							x = runEnd + 1;
							continue;
						}
					}
					collectOrdinaryRun(section, plane, (slot << CELL_SHIFT) | local, local, x, runEnd, y, z,
					    hasOverrides, minX, minY, minZ, maxX, maxY, maxZ, target);
					x = runEnd + 1;
				}
			}
		}
	}

	/**
	 * One row's run inside one section, {@code x} to {@code runEnd}. Outlined
	 * so the loop that walks rows stays small enough for its leaves to inline;
	 * the run body is the whole per-cell work.
	 */
	private void collectOrdinaryRun(final Section section, final int[] plane, int cell, int local, int x,
	    final int runEnd, final int y, final int z, final boolean hasOverrides, final double minX, final double minY,
	    final double minZ, final double maxX, final double maxY, final double maxZ, final CollisionBuffer target) {
		byte[] shapeClass = this.tables.shapeClass;
		for (; x <= runEnd; x++, cell++, local++) {
			int index = hasOverrides ? effectivePaletteIndex(cell)
			    : plane == null      ? section.uniformIndex()
			                         : plane[local];
			byte kind = shapeClass[index];
			if (kind == PaletteTables.SHAPE_EMPTY) {
				continue;
			}
			if (kind == PaletteTables.SHAPE_FULL_CUBE) {
				if (intersectsCell(minX, minY, minZ, maxX, maxY, maxZ, x, y, z)) {
					target.addCanonicalFullCube(x, y, z);
				}
				continue;
			}
			appendGroupIfIntersects(
			    this.tables.shapes[index], kind, x, y, z, minX, minY, minZ, maxX, maxY, maxZ, target);
		}
	}

	/** One row's run inside one section for {@link #collectSpanBoxes}; outlined as {@link #collectOrdinaryRun} is. */
	private void collectSpanRun(final Section section, final int[] plane, int cell, int local, int x, final int runEnd,
	    final int y, final int z, final boolean hasOverrides, final CollisionBuffer target) {
		byte[] shapeClass = this.tables.shapeClass;
		for (; x <= runEnd; x++, cell++, local++) {
			int index = hasOverrides ? effectivePaletteIndex(cell)
			    : plane == null      ? section.uniformIndex()
			                         : plane[local];
			byte kind = shapeClass[index];
			if (kind == PaletteTables.SHAPE_EMPTY) {
				continue;
			}
			if (kind == PaletteTables.SHAPE_FULL_CUBE) {
				target.addCanonicalFullCube(x, y, z);
				continue;
			}
			double[] shape = this.tables.shapes[index];
			for (int at = 0; at < shape.length; at += 6) {
				if (at == 0) {
					target.addAt(x, y, z, shape[at] + x, shape[at + 1] + y, shape[at + 2] + z, shape[at + 3] + x,
					    shape[at + 4] + y, shape[at + 5] + z);
				} else {
					target.addPart(shape[at] + x, shape[at + 1] + y, shape[at + 2] + z, shape[at + 3] + x,
					    shape[at + 4] + y, shape[at + 5] + z);
				}
			}
		}
	}

	private static void appendGroupIfIntersects(final double[] shape, final byte kind, final int x, final int y,
	    final int z, final double minX, final double minY, final double minZ, final double maxX, final double maxY,
	    final double maxZ, final CollisionBuffer target) {
		if (kind == PaletteTables.SHAPE_EMPTY) {
			return;
		}
		if (kind == PaletteTables.SHAPE_FULL_CUBE) {
			if (intersectsCell(minX, minY, minZ, maxX, maxY, maxZ, x, y, z)) {
				target.addCanonicalFullCube(x, y, z);
			}
			return;
		}
		if (!CollisionCollector.generalGroupIntersects(shape, x, y, z, minX, minY, minZ, maxX, maxY, maxZ)) {
			return;
		}
		for (int at = 0; at < shape.length; at += 6) {
			double shapeMinX = shape[at] + x;
			double shapeMinY = shape[at + 1] + y;
			double shapeMinZ = shape[at + 2] + z;
			double shapeMaxX = shape[at + 3] + x;
			double shapeMaxY = shape[at + 4] + y;
			double shapeMaxZ = shape[at + 5] + z;
			if (at == 0) {
				target.addAt(x, y, z, shapeMinX, shapeMinY, shapeMinZ, shapeMaxX, shapeMaxY, shapeMaxZ);
			} else {
				target.addPart(shapeMinX, shapeMinY, shapeMinZ, shapeMaxX, shapeMaxY, shapeMaxZ);
			}
		}
	}

	/** {@code AABB.intersects}, open on every face: a box that only touches a face does not intersect. */
	private static boolean intersects(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double shapeMinX, final double shapeMinY, final double shapeMinZ,
	    final double shapeMaxX, final double shapeMaxY, final double shapeMaxZ) {
		return shapeMaxX > minX && shapeMinX < maxX && shapeMaxY > minY && shapeMinY < maxY && shapeMaxZ > minZ
		    && shapeMinZ < maxZ;
	}

	/** {@link #intersects} against the unit cube of a cell. */
	private static boolean intersectsCell(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final int x, final int y, final int z) {
		return intersects(minX, minY, minZ, maxX, maxY, maxZ, x, y, z, x + 1.0, y + 1.0, z + 1.0);
	}

	/**
	 * Space outside the bounds, as the snapshot's {@link OutsidePolicy} declares
	 * it: a full block when sealed, a refusal when reached otherwise. A move
	 * that reaches the edge of the captured region has left the world that was
	 * captured, and any answer about what is out there would be invented.
	 */
	private void outside(final int x, final int y, final int z, final CollisionBuffer target, final double minX,
	    final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
		switch (this.outside) {
			case SEALED -> {
				if (intersectsCell(minX, minY, minZ, maxX, maxY, maxZ, x, y, z)) {
					target.addCanonicalFullCube(x, y, z);
				}
			}
			case REFUSING -> {
				if (intersectsCell(minX, minY, minZ, maxX, maxY, maxZ, x, y, z)) {
					throw UnimplementedMechanicException.at(RefusalCause.OUTSIDE_REGION,
					    "collision query left the captured region at ", x, y, z,
					    "; the snapshot declares outside space refusing");
				}
			}
		}
	}

	/** {@link #propertiesIn} for a span exceeding the bounded fine-grid scan; outlined to keep the entry point small. */
	private int propertiesFromSections(
	    final int possible, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		int sectionX0 = (x0 >> Section.SHIFT) - this.sectionOriginX;
		int sectionY0 = (y0 >> Section.SHIFT) - this.sectionOriginY;
		int sectionZ0 = (z0 >> Section.SHIFT) - this.sectionOriginZ;
		if (sectionX0 != (x1 >> Section.SHIFT) - this.sectionOriginX
		    || sectionY0 != (y1 >> Section.SHIFT) - this.sectionOriginY
		    || sectionZ0 != (z1 >> Section.SHIFT) - this.sectionOriginZ) {
			return propertiesAcrossSections(possible, x0, y0, z0, x1, y1, z1);
		}
		int section = this.grid.index(sectionX0, sectionY0, sectionZ0);
		int coarse = this.sectionProperties[section] & possible;
		if (coarse == 0
		    // Every bit still in play is in every fine cube of this section, so
		    // no refinement of any span inside it can clear one.
		    || coarse == (this.sectionUniformProperties[section] & coarse)) {
			return coarse;
		}
		return refine(coarse, x0, y0, z0, x1, y1, z1);
	}

	/**
	 * {@link #propertiesIn} for a span crossing more than one 16-cube.
	 *
	 * <p>Outlined because HotSpot inlining decisions changed when refinement grew
	 * this method. Keep the hot entry point small, and check compilation output
	 * after changing it.
	 */
	private int propertiesAcrossSections(
	    final int possible, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		int sectionX0 = (x0 >> Section.SHIFT) - this.sectionOriginX;
		int sectionY0 = (y0 >> Section.SHIFT) - this.sectionOriginY;
		int sectionZ0 = (z0 >> Section.SHIFT) - this.sectionOriginZ;
		int sectionX1 = (x1 >> Section.SHIFT) - this.sectionOriginX;
		int sectionY1 = (y1 >> Section.SHIFT) - this.sectionOriginY;
		int sectionZ1 = (z1 >> Section.SHIFT) - this.sectionOriginZ;
		int properties = 0;
	outer:
		for (int sectionZ = sectionZ0; sectionZ <= sectionZ1; sectionZ++) {
			for (int sectionY = sectionY0; sectionY <= sectionY1; sectionY++) {
				int section = this.grid.index(sectionX0, sectionY, sectionZ);
				for (int sectionX = sectionX0; sectionX <= sectionX1; sectionX++, section++) {
					properties |= this.sectionProperties[section];
					if ((properties & possible) == possible) {
						// Every bit still in play is already accounted for; the
						// remaining sections cannot change the answer.
						break outer;
					}
				}
			}
		}
		int coarse = properties & possible;
		if (coarse == 0) {
			return coarse;
		}
		return refine(coarse, x0, y0, z0, x1, y1, z1);
	}

	/**
	 * Narrow a coarse answer with the fine grid, or return it unchanged.
	 *
	 * <p>Only reached when the 16-cube mask failed to reject, which is where the
	 * cost it was meant to prevent is actually paid. Declines when the span covers
	 * more than {@link #FINE_CUBE_LIMIT} fine cubes, because past that the extra
	 * loads can outweigh what refinement saves.
	 */
	private int refine(
	    final int coarse, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		int fineX0 = (x0 >> FINE_SHIFT) - this.fineOriginX;
		int fineY0 = (y0 >> FINE_SHIFT) - this.fineOriginY;
		int fineZ0 = (z0 >> FINE_SHIFT) - this.fineOriginZ;
		int fineX1 = (x1 >> FINE_SHIFT) - this.fineOriginX;
		int fineY1 = (y1 >> FINE_SHIFT) - this.fineOriginY;
		int fineZ1 = (z1 >> FINE_SHIFT) - this.fineOriginZ;
		if (fineX0 == fineX1 && fineY0 == fineY1 && fineZ0 == fineZ1) {
			return this.fineProperties[fineSlotOf(fineX0, fineY0, fineZ0)] & coarse;
		}
		if ((fineX1 - fineX0 + 1) * (fineY1 - fineY0 + 1) * (fineZ1 - fineZ0 + 1) > FINE_CUBE_LIMIT) {
			return coarse;
		}
		return propertiesFromFine(coarse, fineX0, fineY0, fineZ0, fineX1, fineY1, fineZ1);
	}

	private int propertiesFromFine(final int coarse, final int fineX0, final int fineY0, final int fineZ0,
	    final int fineX1, final int fineY1, final int fineZ1) {
		int properties = 0;
		for (int fineZ = fineZ0; fineZ <= fineZ1; fineZ++) {
			for (int fineY = fineY0; fineY <= fineY1; fineY++) {
				int fine = fineSlotOf(fineX0, fineY, fineZ);
				for (int fineX = fineX0; fineX <= fineX1; fineX++, fine++) {
					properties |= this.fineProperties[fine];
					if ((properties & coarse) == coarse) {
						return coarse;
					}
				}
			}
		}
		return properties & coarse;
	}

	/** One step of the revision fold: a SplitMix64 finalizer over the running value and the next revision. */
	private static long mix(final long fold, final long value) {
		long z = (fold ^ value) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return (z ^ (z >>> 31)) + REVISION_SEED;
	}

	/**
	 * Whether any cell of this closed span owns a shape that leaves its own cell.
	 *
	 * <p>Conservative in one direction only: true may be returned for a span that
	 * holds none, which costs the general path; false must mean there is none, since
	 * a caller takes it as license to skip the ring. A span not wholly inside the
	 * region answers true, because outside cells are unknown and the general path is
	 * the one that owns that semantic.
	 *
	 * <p>Callers pass the span they would scan, ring included, not the query box:
	 * the question is whether a shape can reach in, and reaching in is what the ring
	 * exists to catch.
	 */
	private boolean largeShapeIn(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		// The palette-wide answer first, so a world with no such shape anywhere
		// pays one field load and nothing else.
		if (!this.tables.hasAnyLargeShape) {
			return false;
		}
		if (!spanInside(x0, y0, z0, x1, y1, z1)) {
			return true;
		}
		if (!coarseLargeShapeIn(x0, y0, z0, x1, y1, z1)) {
			return false;
		}
		return fineLargeShapeIn(x0, y0, z0, x1, y1, z1);
	}

	/** {@link #largeShapeIn} on the 16-cube grid: one load for the common answer. */
	private boolean coarseLargeShapeIn(
	    final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		int sectionX0 = (x0 >> Section.SHIFT) - this.sectionOriginX;
		int sectionY0 = (y0 >> Section.SHIFT) - this.sectionOriginY;
		int sectionZ0 = (z0 >> Section.SHIFT) - this.sectionOriginZ;
		int sectionX1 = (x1 >> Section.SHIFT) - this.sectionOriginX;
		int sectionY1 = (y1 >> Section.SHIFT) - this.sectionOriginY;
		int sectionZ1 = (z1 >> Section.SHIFT) - this.sectionOriginZ;
		if (sectionX0 == sectionX1 && sectionY0 == sectionY1 && sectionZ0 == sectionZ1) {
			return this.sectionLargeShape[this.grid.index(sectionX0, sectionY0, sectionZ0)];
		}
		for (int sectionZ = sectionZ0; sectionZ <= sectionZ1; sectionZ++) {
			for (int sectionY = sectionY0; sectionY <= sectionY1; sectionY++) {
				int section = this.grid.index(sectionX0, sectionY, sectionZ);
				for (int sectionX = sectionX0; sectionX <= sectionX1; sectionX++, section++) {
					if (this.sectionLargeShape[section]) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * {@link #largeShapeIn} on the fine grid, reached only when the coarse grid
	 * failed to reject. Answers true without looking once the span covers more cubes
	 * than the loads are worth, which is conservative in the safe direction.
	 */
	private boolean fineLargeShapeIn(
	    final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		int fineX0 = (x0 >> FINE_SHIFT) - this.fineOriginX;
		int fineY0 = (y0 >> FINE_SHIFT) - this.fineOriginY;
		int fineZ0 = (z0 >> FINE_SHIFT) - this.fineOriginZ;
		int fineX1 = (x1 >> FINE_SHIFT) - this.fineOriginX;
		int fineY1 = (y1 >> FINE_SHIFT) - this.fineOriginY;
		int fineZ1 = (z1 >> FINE_SHIFT) - this.fineOriginZ;
		if ((fineX1 - fineX0 + 1) * (fineY1 - fineY0 + 1) * (fineZ1 - fineZ0 + 1) > FINE_LARGE_SHAPE_LIMIT) {
			return true;
		}
		for (int fineZ = fineZ0; fineZ <= fineZ1; fineZ++) {
			for (int fineY = fineY0; fineY <= fineY1; fineY++) {
				int fine = fineSlotOf(fineX0, fineY, fineZ);
				for (int fineX = fineX0; fineX <= fineX1; fineX++, fine++) {
					if (this.fineLargeShape[fine]) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Conservative section rejection: false means no scanned owner can collide. */
	private boolean hasCollisionPotential(
	    final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		int sectionX0 = (x0 >> Section.SHIFT) - this.sectionOriginX;
		int sectionY0 = (y0 >> Section.SHIFT) - this.sectionOriginY;
		int sectionZ0 = (z0 >> Section.SHIFT) - this.sectionOriginZ;
		int sectionX1 = (x1 >> Section.SHIFT) - this.sectionOriginX;
		int sectionY1 = (y1 >> Section.SHIFT) - this.sectionOriginY;
		int sectionZ1 = (z1 >> Section.SHIFT) - this.sectionOriginZ;
		// A movement query spans two to four cells, so it almost always lies in
		// one 16-cube. Sparing that case the loop setup matters because in open
		// air this test is the entire cost of the query.
		if (sectionX0 == sectionX1 && sectionY0 == sectionY1 && sectionZ0 == sectionZ1) {
			return this.collisionSectionCounts[this.grid.index(sectionX0, sectionY0, sectionZ0)] != 0;
		}
		for (int sectionZ = sectionZ0; sectionZ <= sectionZ1; sectionZ++) {
			for (int sectionY = sectionY0; sectionY <= sectionY1; sectionY++) {
				int section = this.grid.index(sectionX0, sectionY, sectionZ);
				for (int sectionX = sectionX0; sectionX <= sectionX1; sectionX++, section++) {
					if (this.collisionSectionCounts[section] != 0) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private void replaceFluidCellUnchecked(final int x, final int y, final int z, final int fluidPaletteIndex) {
		int cell = baseCellIndex(x, y, z);
		int override = this.fluidOverlay.find(cell);
		int base = baseFluidCell(cell);
		int current = override < 0 ? base : this.fluidOverlay.valueAt(override);
		if (current == fluidPaletteIndex) {
			return;
		}
		detachPropertyGrids();
		if (fluidPaletteIndex == base) {
			this.fluidOverlay.removeAt(override);
		} else if (override >= 0) {
			this.fluidOverlay.setAt(override, fluidPaletteIndex);
		} else {
			this.fluidOverlay.append(cell, fluidPaletteIndex);
		}
		if (fluidPaletteIndex != 0) {
			this.hasFluidCells = true;
			this.sectionProperties[sectionSlot(x, y, z)] |= PROPERTY_FLUID;
			this.fineProperties[fineSlot(x, y, z)] |= PROPERTY_FLUID;
			this.wholeViewProperties |= PROPERTY_FLUID;
		}
	}

	private void requireMutable() {
		if (this.immutable) {
			throw new IllegalStateException("cannot replace cells in a frozen snapshot world");
		}
	}

	/** The effective palette index of a cell inside the bounds, refusing a cell outside them. */
	private int requireIndexAt(final int x, final int y, final int z, final String what) {
		int index = paletteIndexAt(x, y, z);
		if (index < 0) {
			throw UnimplementedMechanicException.at(
			    RefusalCause.OUTSIDE_REGION, what + " left the captured region at ", x, y, z, "");
		}
		return index;
	}

	private int effectivePaletteIndex(final int cell) {
		if (this.overlay.size() == 0) {
			return baseCell(cell);
		}
		int override = this.overlay.find(cell);
		return override < 0 ? baseCell(cell) : this.overlay.valueAt(override);
	}

	private int effectiveFluidPaletteIndex(final int cell) {
		if (this.fluidOverlay.size() == 0) {
			return baseFluidCell(cell);
		}
		int override = this.fluidOverlay.find(cell);
		return override < 0 ? baseFluidCell(cell) : this.fluidOverlay.valueAt(override);
	}

	/**
	 * The captured palette index of a cell number, before any replacement.
	 *
	 * @throws UnimplementedMechanicException when the cell lies in a missing
	 *     section, which holds no fact to answer with
	 */
	private int baseCell(final int cell) {
		int index = this.sections[cell >>> CELL_SHIFT].cell(cell & CELL_MASK);
		if (index < 0) {
			throw missingSection(cell);
		}
		return index;
	}

	/** The captured fluid palette index of a cell number, before any replacement; refuses as {@link #baseCell}. */
	private int baseFluidCell(final int cell) {
		Section section = this.sections[cell >>> CELL_SHIFT];
		if (section.isMissing()) {
			throw missingSection(cell);
		}
		return section.fluidCell(cell & CELL_MASK);
	}

	/** The refusal of a read into a missing section, naming the cell in world coordinates. */
	private UnimplementedMechanicException missingSection(final int cell) {
		int slot = cell >>> CELL_SHIFT;
		int local = cell & CELL_MASK;
		int sectionX = slot % this.sectionsX;
		int sectionZ = slot / this.sectionsX % this.sectionsZ;
		int sectionY = slot / (this.sectionsX * this.sectionsZ);
		int x = ((this.sectionOriginX + sectionX) << Section.SHIFT) + (local & Section.MASK);
		int y = ((this.sectionOriginY + sectionY) << Section.SHIFT) + (local >>> (2 * Section.SHIFT));
		int z = ((this.sectionOriginZ + sectionZ) << Section.SHIFT) + ((local >>> Section.SHIFT) & Section.MASK);
		return UnimplementedMechanicException.deferred(RefusalCause.UNDECLARED_WORLD_FACT,
		    () -> "query reached a section the world does not hold at " + x + "," + y + "," + z);
	}

	/** Whether a cell lies inside the bounds. */
	private boolean inside(final int x, final int y, final int z) {
		return x >= this.originX && x < this.endX && y >= this.originY && y < this.endY && z >= this.originZ
		    && z < this.endZ;
	}

	/** Whether a closed cell span lies wholly inside the bounds. */
	private boolean spanInside(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		return x0 >= this.originX && x1 < this.endX && y0 >= this.originY && y1 < this.endY && z0 >= this.originZ
		    && z1 < this.endZ;
	}

	/** The grid slot of the section holding a world position inside the bounds. */
	private int sectionSlot(final int x, final int y, final int z) {
		return this.grid.index((x >> Section.SHIFT) - this.sectionOriginX, (y >> Section.SHIFT) - this.sectionOriginY,
		    (z >> Section.SHIFT) - this.sectionOriginZ);
	}

	/** The fine-grid slot of the fine cube holding a world position inside the bounds. */
	private int fineSlot(final int x, final int y, final int z) {
		return fineSlotOf((x >> FINE_SHIFT) - this.fineOriginX, (y >> FINE_SHIFT) - this.fineOriginY,
		    (z >> FINE_SHIFT) - this.fineOriginZ);
	}

	/** The fine-grid slot of a fine cube by its offset from the grid's origin, y outermost, then z, then x. */
	private int fineSlotOf(final int fineX, final int fineY, final int fineZ) {
		return (fineY * this.fineSizeZ + fineZ) * this.fineSizeX + fineX;
	}

	/** A cell's number: its section's grid slot above its local number in the section. */
	private int baseCellIndex(final int x, final int y, final int z) {
		if (!inside(x, y, z)) {
			return -1;
		}
		return (sectionSlot(x, y, z) << CELL_SHIFT)
		    | Section.index(x & Section.MASK, y & Section.MASK, z & Section.MASK);
	}

	private void detachCollisionGrids() {
		if (!this.collisionGridsShared) {
			return;
		}
		this.collisionSectionCounts = Arrays.copyOf(this.collisionSectionCounts, this.collisionSectionCounts.length);
		this.sectionVersion = Arrays.copyOf(this.sectionVersion, this.sectionVersion.length);
		this.collisionGridsShared = false;
	}

	private void detachPropertyGrids() {
		if (!this.propertyGridsShared) {
			return;
		}
		this.sectionProperties = Arrays.copyOf(this.sectionProperties, this.sectionProperties.length);
		this.fineProperties = Arrays.copyOf(this.fineProperties, this.fineProperties.length);
		this.propertyGridsShared = false;
	}

	private void detachLargeShapeGrids() {
		if (!this.largeShapeGridsShared) {
			return;
		}
		this.sectionLargeShape = Arrays.copyOf(this.sectionLargeShape, this.sectionLargeShape.length);
		this.fineLargeShape = Arrays.copyOf(this.fineLargeShape, this.fineLargeShape.length);
		this.largeShapeGridsShared = false;
	}
}
