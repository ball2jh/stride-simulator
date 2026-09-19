package com.nettarion.stride.simulator.world;

import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.Refusal;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.block.BlockBehaviour;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.geometry.CollisionCollector;
import com.nettarion.stride.simulator.geometry.CollisionQuerySpan;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.tick.Scratch;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A {@link WorldView} over a captured region, with real per-state shapes.
 *
 * <p>Unlike {@link FlatFloorView} this carries arbitrary geometry — slabs,
 * stairs, walls — because the shapes come from the snapshot's palette rather
 * than being assumed to be full cubes.
 *
 * <p>Leaving the region is not air. Unknown space is explicit, and the snapshot
 * declares its behavior; the terminating case throws
 * rather than letting a rollout wander out of the captured world and return a
 * plausible answer about terrain nobody observed.
 *
 * <p>An unmodified instance is safe for concurrent reads. Sparse replacements
 * are deliberately worker-confined mutable state: do not call
 * {@link #replaceCell} concurrently with reads, and do not share a mutable
 * instance between rollout workers. {@link #fork} shares the immutable compiled
 * palette and base cells while giving the child independent replacement arrays.
 */
public final class SnapshotView implements WorldView {
	private static final double[] SCAFFOLDING_STABLE_SHAPE = {0.0, 0.875, 0.0, 1.0, 1.0, 1.0, 0.0, 0.0, 0.0, 0.125, 1.0,
	    0.125, 0.875, 0.0, 0.0, 1.0, 1.0, 0.125, 0.0, 0.0, 0.875, 0.125, 1.0, 1.0, 0.875, 0.0, 0.875, 1.0, 1.0, 1.0};
	private static final double[] SCAFFOLDING_UNSTABLE_BOTTOM_SHAPE = {0.0, 0.0, 0.0, 1.0, 0.125, 1.0};
	private static final double[] POWDER_SNOW_FALLING_SHAPE = {0.0, 0.0, 0.0, 1.0, (double) 0.9F, 1.0};
	private static final double[] FULL_BLOCK_SHAPE = {0.0, 0.0, 0.0, 1.0, 1.0, 1.0};

	/**
	 * Shape classes, so the common cells never touch {@link #shapes} at all.
	 *
	 * <p>Reading a cell's geometry through {@code double[][]} is three dependent
	 * loads — cell to palette index, index to array reference, reference to
	 * contents — and the overwhelming majority of cells are either empty or a
	 * plain unit cube whose six coordinates are already known from the loop
	 * counters. Classifying the palette once turns those cells into a byte
	 * compare. {@code 0.0 + x} is exactly {@code x} and {@code 1.0 + x} is
	 * exactly {@code x + 1.0}, so the emitted boxes are bit-identical.
	 */
	private static final byte SUFFOCATION_UNKNOWN = 0;
	private static final byte SUFFOCATION_NO = 1;
	private static final byte SUFFOCATION_YES = 2;

	private static final byte SHAPE_EMPTY = 0;
	private static final byte SHAPE_FULL_CUBE = 1;
	private static final byte SHAPE_GENERAL = 2;

	/**
	 * Internal {@link #paletteProperties} bit for cells whose captured collision
	 * geometry the server can mutate. Not part of the {@link WorldView} property
	 * contract — only this view's own fast paths consult it, to stay off spans
	 * that must refuse instead of answering from captured geometry.
	 */
	private static final int PROPERTY_SERVER_MUTABLE = SectionSummary.PROPERTY_SERVER_MUTABLE;
	/** A cell number is a section slot shifted past a local cell number. */
	private static final int CELL_SHIFT = 3 * Section.SHIFT;
	private static final int CELL_MASK = Section.CELLS - 1;

	private final byte[] shapeClass;

	/** Palette index → ordered min/max sextuples, compiled once for the hot path. */
	private final double[][] shapes;
	private final boolean[] largeShape;
	private final float[] friction;
	private final float[] speedFactor;
	private final float[] jumpFactor;
	private final boolean[] suppressesSupportingSpeedFactor;
	private final BubbleColumnMode[] bubbleColumnMode;
	private final Climbability[] climbability;
	private final boolean[] fallDistanceResettingBlock;
	private final byte[] suffocation;
	private final InsideEffect[] insideEffect;
	private final float[] bounceRestitution;
	private final boolean[] suppressesBounce;
	/** Each palette entry's {@link BlockBehaviour}, resolved once here. */
	private final BlockBehaviour[] behaviour;
	// Optional source-proven inside shapes; compiled before the view is frozen.
	double[][] sourceInsideShapes;
	boolean[] sourceInsideFull;
	/** Returns whether independently extracted entity-inside shapes are available. */
	public boolean hasSourceInsideShapes() {
		return this.sourceInsideShapes != null;
	}
	/** Tests the source inside shape for one visited cell, refusing undeclared or context-dependent shapes. */
	public boolean sourceInsideReached(
	    final PlayerState state, final int x, final int y, final int z, final Scratch scratch) {
		int index = paletteIndexAt(x, y, z);
		if (index < 0)
			throw new UnimplementedMechanicException(
			    Refusal.OUTSIDE_REGION, "inside-block traversal leaves source-verified region");
		if (this.air[index]) return false;
		if (this.sourceInsideShapes[index] == null)
			throw new UnimplementedMechanicException(
			    Refusal.UNDECLARED_BLOCK_STATE, "source inside shape is entity-dependent or unknown");
		return this.sourceInsideFull[index]
		    || scratch.insideTraversal.collidedWithShapeMovingFrom(state, x, y, z, this.sourceInsideShapes[index]);
	}
	private final boolean[] retainsSupportPos;
	private final CollisionBehavior[] collisionBehavior;
	/** Palette index → whether the entry is an air block state by identity. */
	private final boolean[] air;
	/** Palette entry can contribute a collision for at least one entity context. */
	private final boolean[] collisionPotential;
	private final boolean hasContextSensitiveCollision;
	/** Any palette entry whose captured collision geometry the server can mutate. */
	private final boolean hasServerMutableCollision;
	private final boolean hasPowderSnow;
	private final boolean hasBubbleColumns;
	private final boolean hasClimbables;
	private final boolean hasNonDefaultFriction;
	private final boolean hasNonDefaultSpeedFactor;
	private final boolean hasNonDefaultJumpFactor;
	private final FluidSample.Kind[] fluidKind;
	private final double[] fluidHeight;
	private final double[] fluidFlowX;
	private final double[] fluidFlowY;
	private final double[] fluidFlowZ;
	private final boolean[] fluidSource;
	private final int originX;
	private final int originY;
	private final int originZ;
	private final int endX;
	private final int endY;
	private final int endZ;
	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	/** The snapshot's sections in its grid order; a cell is a section and a local number. */
	private final Section[] sections;
	private final int sectionOriginX;
	private final int sectionOriginY;
	private final int sectionOriginZ;
	private final int fineOriginX;
	private final int fineOriginY;
	private final int fineOriginZ;
	private final int collisionSectionSizeX;
	private final int collisionSectionSizeY;
	private final int collisionSectionSizeZ;
	/** Effective potentially-colliding owner-cell count per local 16-cube section. */
	private int[] collisionSectionCounts;
	/** Palette index → the {@link WorldView} property bits that entry carries. */
	private final short[] paletteProperties;
	/** Property bits present in each local 16-cube section. */
	private short[] sectionProperties;

	/*
	 * Fine property masks refine a positive section-level answer for small spans.
	 * The coarse mask always runs first. Skipping refinement can only retain an
	 * unnecessary query; it cannot hide a required one.
	 */

	/** Edge of a fine property cube, as a power of two. */
	private static final int FINE_SHIFT = SectionSummary.FINE_SHIFT;

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

	private final int fineSizeX;
	private final int fineSizeY;
	private final int fineSizeZ;
	/** {@link #sectionProperties} on the fine grid. */
	private short[] fineProperties;
	/**
	 * Per-section AND of fine-cube bits. A set bit proves refinement cannot clear
	 * that property. This is only a performance hint; either error direction falls
	 * back to the conservative section answer.
	 */
	private final short[] sectionUniformProperties;
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
	 * unknown, and the accessors already own that semantic — {@code fluidAt} and
	 * {@code climbableAt} throw there, the coefficient lookups return the default.
	 * Answering such a span with the whole-view mask keeps them reached exactly
	 * when they were reached before, so a snapshot with no fluids anywhere still
	 * skips the fluid scan at the region edge instead of newly refusing it.
	 */
	private short wholeViewProperties;
	/**
	 * Collision version per local 16-cube section, so a distant edit cannot
	 * invalidate a local proof. Holds the value {@link #collisionVersion} had when
	 * this section was last written; sections never written stay at the value they
	 * were born with, which is why an untouched span keeps answering the same
	 * number however much of the rest of the world changes.
	 */
	private long[] sectionVersion;
	/**
	 * Each section's {@link Section#revision} laid side by side in grid order,
	 * so a span's revision is read from one array rather than through the
	 * section objects; shared by every fork, since a fork holds the same
	 * sections.
	 */
	private final long[] sectionRevision;
	private final boolean hasAnyLargeShape;
	private final boolean hasAnySuffocation;
	/** Whether any section is {@link Section#MISSING}; see {@link #hasChunkAt}. */
	private final boolean hasMissingSections;
	private final WorldSnapshot.OutsideRegion outside;
	/** Linear lookup wins for the tiny edit sets used by normal replay. */
	private static final int OVERRIDE_HASH_THRESHOLD = 16;
	private int[] overrideCells = new int[4];
	private int[] overridePalette = new int[4];
	/** Open-addressed slots containing {@code overrideCells} indexes plus one. */
	private int[] overrideIndex;
	private int overrideCount;
	private int[] overrideFluidCells = new int[4];
	private int[] overrideFluidPalette = new int[4];
	/** Open-addressed slots containing {@code overrideFluidCells} indexes plus one. */
	private int[] overrideFluidIndex;
	private int overrideFluidCount;
	/** Forks share each derived-grid family until an edit first writes that family. */
	private boolean collisionGridsShared;
	private boolean propertyGridsShared;
	private boolean largeShapeGridsShared;
	private boolean hasFluidCells;
	private long collisionVersion;
	private final boolean immutable;
	private final long identity = WorldIdentity.next();
	private final WorldSnapshot snapshot;

	/**
	 * Compile a snapshot once into a frozen view that is safe to share between
	 * threads. A worker that must apply block changes calls {@link #fork()} on
	 * it and mutates only the returned worker-confined child.
	 */
	public static SnapshotView compile(final WorldSnapshot snapshot) {
		return new SnapshotView(snapshot).frozenFork();
	}

	/** Verify portable block facts and compile the source-proven cauldron successors once. */
	public static SnapshotView compile(
	    final WorldSnapshot snapshot, final BlockStateCatalog catalog, final String minecraftVersion) {
		SnapshotView result = new SnapshotView(catalog.withSuccessors(snapshot, minecraftVersion));
		catalog.installBehaviours(result, result.snapshot, result.behaviour);
		return result.frozenFork();
	}

	/** Compiles a synthetic snapshot without independent catalog verification; use a catalog-backed factory for observations. */
	public SnapshotView(final WorldSnapshot snapshot) {
		this.immutable = false;
		this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
		this.originX = snapshot.originX();
		this.originY = snapshot.originY();
		this.originZ = snapshot.originZ();
		this.endX = this.originX + snapshot.sizeX();
		this.endY = this.originY + snapshot.sizeY();
		this.endZ = this.originZ + snapshot.sizeZ();
		this.sizeX = snapshot.sizeX();
		this.sizeY = snapshot.sizeY();
		this.sizeZ = snapshot.sizeZ();
		this.sections = snapshot.sections();
		this.hasMissingSections = snapshot.hasMissingSections();
		this.sectionRevision = new long[this.sections.length];
		for (int slot = 0; slot < this.sections.length; slot++) {
			this.sectionRevision[slot] = this.sections[slot].revision;
		}
		this.sectionOriginX = snapshot.grid().sectionOriginX();
		this.sectionOriginY = snapshot.grid().sectionOriginY();
		this.sectionOriginZ = snapshot.grid().sectionOriginZ();
		this.fineOriginX = this.sectionOriginX << (4 - FINE_SHIFT);
		this.fineOriginY = this.sectionOriginY << (4 - FINE_SHIFT);
		this.fineOriginZ = this.sectionOriginZ << (4 - FINE_SHIFT);
		this.outside = snapshot.outside();
		int size = snapshot.palette().size();
		this.shapes = new double[size][];
		this.shapeClass = new byte[size];
		this.largeShape = new boolean[size];
		this.friction = new float[size];
		this.speedFactor = new float[size];
		this.jumpFactor = new float[size];
		this.suppressesSupportingSpeedFactor = new boolean[size];
		this.bubbleColumnMode = new BubbleColumnMode[size];
		this.climbability = new Climbability[size];
		this.fallDistanceResettingBlock = new boolean[size];
		this.suffocation = new byte[size];
		this.insideEffect = new InsideEffect[size];
		this.bounceRestitution = new float[size];
		this.suppressesBounce = new boolean[size];
		this.behaviour = new BlockBehaviour[size];
		this.retainsSupportPos = new boolean[size];
		this.collisionBehavior = new CollisionBehavior[size];
		this.collisionPotential = new boolean[size];
		this.paletteProperties = new short[size];
		this.air = new boolean[size];
		int allProperties = 0;
		boolean anyLarge = false;
		boolean anySuffocation = false;
		boolean anyBubbleColumn = false;
		boolean anyContextSensitiveCollision = false;
		boolean anyServerMutableCollision = false;
		boolean anyPowderSnow = false;
		boolean anyClimbable = false;
		boolean anyFriction = false;
		boolean anySpeedFactor = false;
		boolean anyJumpFactor = false;

		for (int i = 0; i < size; i++) {
			WorldSnapshot.BlockEntry entry = snapshot.palette().get(i);
			if (entry.movingPiston()) {
				throw new IllegalArgumentException("moving-piston collision is outside the supported kernel slice");
			}
			List<WorldSnapshot.ShapeBox> boxes = entry.boxes();
			double[] converted = new double[boxes.size() * 6];
			for (int b = 0; b < boxes.size(); b++) {
				WorldSnapshot.ShapeBox box = boxes.get(b);
				validate(box, i, b);
				int at = b * 6;
				converted[at] = box.minX();
				converted[at + 1] = box.minY();
				converted[at + 2] = box.minZ();
				converted[at + 3] = box.maxX();
				converted[at + 4] = box.maxY();
				converted[at + 5] = box.maxZ();
			}
			boolean large = SectionSummary.largeShape(entry);
			this.shapes[i] = converted;
			this.air[i] = isAirIdentity(entry.name());
			this.shapeClass[i] = classify(converted, entry.collisionShapeIdentity());
			this.largeShape[i] = large;
			anyLarge |= large;
			this.friction[i] = entry.friction();
			this.speedFactor[i] = entry.speedFactor();
			this.jumpFactor[i] = entry.jumpFactor();
			this.suppressesSupportingSpeedFactor[i] = entry.suppressesSupportingSpeedFactor();
			this.bubbleColumnMode[i] = entry.bubbleColumnMode();
			anyBubbleColumn |= this.bubbleColumnMode[i] != BubbleColumnMode.NONE;
			this.climbability[i] = entry.climbability();
			this.fallDistanceResettingBlock[i] = entry.fallDistanceResetting();
			anyClimbable |= entry.climbability() != Climbability.NONE;
			this.suffocation[i] = switch (entry.suffocation()) {
				case UNKNOWN -> SUFFOCATION_UNKNOWN;
				case NO -> SUFFOCATION_NO;
				case YES -> SUFFOCATION_YES;
			};
			// An entry with no geometry cannot reach the query box whatever its
			// predicate says, so it never has to be known.
			anySuffocation |= this.suffocation[i] != SUFFOCATION_NO && converted.length != 0;
			this.insideEffect[i] = entry.insideEffect();
			this.bounceRestitution[i] = entry.bounceRestitution();
			this.suppressesBounce[i] = entry.suppressesBounce();
			this.behaviour[i] = BlockBehaviour.of(entry);
			this.retainsSupportPos[i] = entry.retainsSupportPos();
			anyFriction |= entry.friction() != 0.6F;
			anySpeedFactor |= entry.speedFactor() != 1.0F || entry.suppressesSupportingSpeedFactor();
			anyJumpFactor |= entry.jumpFactor() != 1.0F;
			this.collisionBehavior[i] = entry.collisionBehavior();
			// Scaffolding and powder snow resolve their shape from live player
			// context. A server-mutable cell consults no context — it refuses
			// whenever a query box overlaps its cell — so it must not disable
			// the context-free reuse paths for the rest of the world.
			anyContextSensitiveCollision |= this.collisionBehavior[i] != CollisionBehavior.ORDINARY
			    && this.collisionBehavior[i] != CollisionBehavior.SERVER_MUTABLE_SUPPORT;
			anyServerMutableCollision |= this.collisionBehavior[i] == CollisionBehavior.SERVER_MUTABLE_SUPPORT;
			anyPowderSnow |= this.collisionBehavior[i] == CollisionBehavior.POWDER_SNOW_NO_BOOTS;
			this.collisionPotential[i] = SectionSummary.collisionPotential(entry);
			// One bit per behaviour a cell of this entry could make the movement
			// path ask about, defined once with the section summary so a section's
			// mask is the same predicate over fewer cells.
			int properties = SectionSummary.propertyBits(entry);
			this.paletteProperties[i] = (short) properties;
			allProperties |= properties;
		}
		// The derived grids are the snapshot's own summary, shared read-only and
		// copied by the first edit, as a fork shares them. A snapshot composed or
		// cropped on section boundaries carries them from its parts; only one
		// assembled some other way is scanned, once.
		SectionSummary summary = snapshot.summary();
		this.collisionSectionSizeX = summary.sectionsX;
		this.collisionSectionSizeY = summary.sectionsY;
		this.collisionSectionSizeZ = summary.sectionsZ;
		this.collisionSectionCounts = summary.counts;
		this.sectionProperties = summary.properties;
		this.sectionLargeShape = summary.large;
		this.fineSizeX = summary.finesX;
		this.fineSizeY = summary.finesY;
		this.fineSizeZ = summary.finesZ;
		this.fineProperties = summary.fineProperties;
		this.fineLargeShape = summary.fineLarge;
		this.sectionUniformProperties = summary.uniform;
		this.collisionGridsShared = true;
		this.propertyGridsShared = true;
		this.largeShapeGridsShared = true;
		this.sectionVersion = new long[this.collisionSectionCounts.length];
		int fluidSize = snapshot.fluidPalette().size();
		this.fluidKind = new FluidSample.Kind[fluidSize];
		this.fluidHeight = new double[fluidSize];
		this.fluidFlowX = new double[fluidSize];
		this.fluidFlowY = new double[fluidSize];
		this.fluidFlowZ = new double[fluidSize];
		this.fluidSource = new boolean[fluidSize];
		this.hasFluidCells = snapshot.hasFluids();
		for (int i = 0; i < fluidSize; i++) {
			WorldSnapshot.FluidEntry entry = snapshot.fluidPalette().get(i);
			this.fluidKind[i] = switch (entry.kind()) {
				case EMPTY -> FluidSample.Kind.EMPTY;
				case WATER -> FluidSample.Kind.WATER;
				case LAVA -> FluidSample.Kind.LAVA;
			};
			this.fluidHeight[i] = entry.height();
			this.fluidFlowX[i] = entry.flowX();
			this.fluidFlowY[i] = entry.flowY();
			this.fluidFlowZ[i] = entry.flowZ();
			this.fluidSource[i] = entry.source();
		}
		// A fluid plane exists only once a cell selects a non-empty palette index,
		// so the whole-view bit follows the plane rather than the resolved kinds.
		if (this.hasFluidCells) {
			allProperties |= PROPERTY_FLUID;
		}
		if (this.hasMissingSections) {
			// A hole may hold anything, and the whole-view flags exist to skip
			// the questions a palette cannot raise. With a hole every question
			// stays live, so a read that reaches the hole refuses instead of a
			// skipped scan guessing that nothing is there.
			allProperties = -1;
			anyLarge = true;
			anySuffocation = true;
			anyBubbleColumn = true;
			anyServerMutableCollision = true;
			anyPowderSnow = true;
			anyClimbable = true;
			anyFriction = true;
			anySpeedFactor = true;
			anyJumpFactor = true;
			this.hasFluidCells = true;
		}
		this.wholeViewProperties = (short) allProperties;
		this.hasAnyLargeShape = anyLarge;
		this.hasAnySuffocation = anySuffocation;
		this.hasBubbleColumns = anyBubbleColumn;
		this.hasContextSensitiveCollision = anyContextSensitiveCollision;
		this.hasServerMutableCollision = anyServerMutableCollision;
		this.hasPowderSnow = anyPowderSnow;
		this.hasClimbables = anyClimbable;
		this.hasNonDefaultFriction = anyFriction;
		this.hasNonDefaultSpeedFactor = anySpeedFactor;
		this.hasNonDefaultJumpFactor = anyJumpFactor;
	}

	private SnapshotView(final SnapshotView source, final boolean immutable) {
		this.immutable = immutable;
		this.snapshot = source.snapshot;
		this.suffocation = source.suffocation;
		this.hasAnySuffocation = source.hasAnySuffocation;
		this.shapes = source.shapes;
		this.shapeClass = source.shapeClass;
		this.largeShape = source.largeShape;
		this.friction = source.friction;
		this.speedFactor = source.speedFactor;
		this.jumpFactor = source.jumpFactor;
		this.suppressesSupportingSpeedFactor = source.suppressesSupportingSpeedFactor;
		this.bubbleColumnMode = source.bubbleColumnMode;
		this.climbability = source.climbability;
		this.fallDistanceResettingBlock = source.fallDistanceResettingBlock;
		this.insideEffect = source.insideEffect;
		this.bounceRestitution = source.bounceRestitution;
		this.suppressesBounce = source.suppressesBounce;
		this.behaviour = source.behaviour;
		this.sourceInsideShapes = source.sourceInsideShapes;
		this.sourceInsideFull = source.sourceInsideFull;
		this.retainsSupportPos = source.retainsSupportPos;
		this.collisionBehavior = source.collisionBehavior;
		this.collisionPotential = source.collisionPotential;
		this.hasContextSensitiveCollision = source.hasContextSensitiveCollision;
		this.hasServerMutableCollision = source.hasServerMutableCollision;
		this.hasPowderSnow = source.hasPowderSnow;
		this.hasBubbleColumns = source.hasBubbleColumns;
		this.hasClimbables = source.hasClimbables;
		this.hasNonDefaultFriction = source.hasNonDefaultFriction;
		this.hasNonDefaultSpeedFactor = source.hasNonDefaultSpeedFactor;
		this.hasNonDefaultJumpFactor = source.hasNonDefaultJumpFactor;
		this.fluidKind = source.fluidKind;
		this.fluidHeight = source.fluidHeight;
		this.fluidFlowX = source.fluidFlowX;
		this.fluidFlowY = source.fluidFlowY;
		this.fluidFlowZ = source.fluidFlowZ;
		this.fluidSource = source.fluidSource;
		this.originX = source.originX;
		this.originY = source.originY;
		this.originZ = source.originZ;
		this.endX = source.endX;
		this.endY = source.endY;
		this.endZ = source.endZ;
		this.sizeX = source.sizeX;
		this.sizeY = source.sizeY;
		this.sizeZ = source.sizeZ;
		this.sections = source.sections;
		this.hasMissingSections = source.hasMissingSections;
		this.sectionRevision = source.sectionRevision;
		this.sectionOriginX = source.sectionOriginX;
		this.sectionOriginY = source.sectionOriginY;
		this.sectionOriginZ = source.sectionOriginZ;
		this.fineOriginX = source.fineOriginX;
		this.fineOriginY = source.fineOriginY;
		this.fineOriginZ = source.fineOriginZ;
		this.collisionSectionSizeX = source.collisionSectionSizeX;
		this.collisionSectionSizeY = source.collisionSectionSizeY;
		this.collisionSectionSizeZ = source.collisionSectionSizeZ;
		this.collisionSectionCounts = source.collisionSectionCounts;
		this.air = source.air;
		this.paletteProperties = source.paletteProperties;
		this.sectionProperties = source.sectionProperties;
		this.sectionLargeShape = source.sectionLargeShape;
		this.fineLargeShape = source.fineLargeShape;
		this.fineSizeX = source.fineSizeX;
		this.fineSizeY = source.fineSizeY;
		this.fineSizeZ = source.fineSizeZ;
		this.fineProperties = source.fineProperties;
		this.sectionUniformProperties = source.sectionUniformProperties;
		this.wholeViewProperties = source.wholeViewProperties;
		this.sectionVersion = source.sectionVersion;
		this.hasAnyLargeShape = source.hasAnyLargeShape;
		this.outside = source.outside;
		this.overrideCells = Arrays.copyOf(source.overrideCells, Math.max(4, source.overrideCount));
		this.overridePalette = Arrays.copyOf(source.overridePalette, Math.max(4, source.overrideCount));
		this.overrideIndex =
		    source.overrideIndex == null ? null : Arrays.copyOf(source.overrideIndex, source.overrideIndex.length);
		this.overrideCount = source.overrideCount;
		this.overrideFluidCells = Arrays.copyOf(source.overrideFluidCells, Math.max(4, source.overrideFluidCount));
		this.overrideFluidPalette = Arrays.copyOf(source.overrideFluidPalette, Math.max(4, source.overrideFluidCount));
		this.overrideFluidIndex = source.overrideFluidIndex == null
		    ? null
		    : Arrays.copyOf(source.overrideFluidIndex, source.overrideFluidIndex.length);
		this.overrideFluidCount = source.overrideFluidCount;
		if (!source.immutable) source.collisionGridsShared = true;
		this.collisionGridsShared = true;
		if (!source.immutable) source.propertyGridsShared = true;
		this.propertyGridsShared = true;
		if (!source.immutable) source.largeShapeGridsShared = true;
		this.largeShapeGridsShared = true;
		this.hasFluidCells = source.hasFluidCells;
		this.collisionVersion = source.collisionVersion;
	}

	/**
	 * Fork a cheap independent sparse successor world.
	 *
	 * <p>The compiled snapshot backing and derived query grids are shared read-only;
	 * existing overrides are copied. The first effective replacement in either
	 * world detaches its grids before publishing child-local changes.
	 * Call only while this instance is not being mutated concurrently.
	 */
	public SnapshotView fork() {
		return new SnapshotView(this, false);
	}

	/**
	 * A cheap read-only successor sharing this snapshot's frozen backing.
	 *
	 * <p>The returned object's movement answers cannot change, even if this source
	 * later detaches and publishes replacements. Use {@link #fork} when the child
	 * itself must accept replacements.
	 */
	public SnapshotView frozenFork() {
		return this.immutable ? this : new SnapshotView(this, true);
	}

	/**
	 * Replace one captured cell with an entry from this snapshot's frozen palette.
	 *
	 * <p>This publishes geometry and surface coefficients immediately, so a
	 * consumer implements source order by calling it before the movement step
	 * for that tick. It performs no placement validation, redstone, neighbour
	 * updates or other game logic. Effective changes increment
	 * {@link #collisionVersion}; replacing with the current entry is a no-op.
	 * This method and all reads of this instance are worker-confined once any
	 * mutation is possible.
	 */
	public void replaceCell(final int x, final int y, final int z, final int paletteIndex) {
		requireMutable();
		if (paletteIndex < 0 || paletteIndex >= this.shapes.length) {
			throw new IndexOutOfBoundsException("palette index " + paletteIndex + " outside 0.." + this.shapes.length);
		}
		int cell = baseCellIndex(x, y, z);
		if (cell < 0) {
			throw new IndexOutOfBoundsException("replacement cell outside snapshot: " + x + "," + y + "," + z);
		}
		int override = findOverride(cell);
		int current = override < 0 ? baseCell(cell) : this.overridePalette[override];
		if (current == paletteIndex) {
			return;
		}
		detachCollisionGrids();
		detachPropertyGrids();
		if (this.collisionPotential[current] != this.collisionPotential[paletteIndex]) {
			int section = collisionSectionIndex(x, y, z);
			this.collisionSectionCounts[section] += this.collisionPotential[paletteIndex] ? 1 : -1;
		}
		// Set only. Clearing would need a count per property per section, and the
		// error it would remove is the harmless one: a bit left set after the cell
		// that justified it is gone costs a lookup that answers the default, while
		// a bit wrongly clear silently drops a real behaviour.
		this.sectionProperties[collisionSectionIndex(x, y, z)] |= this.paletteProperties[paletteIndex];
		if (this.largeShape[paletteIndex]) {
			detachLargeShapeGrids();
			this.sectionLargeShape[collisionSectionIndex(x, y, z)] = true;
			this.fineLargeShape[fineIndex(x, y, z)] = true;
		}
		this.fineProperties[fineIndex(x, y, z)] |= this.paletteProperties[paletteIndex];
		int base = baseCell(cell);
		if (paletteIndex == base) {
			System.arraycopy(
			    this.overrideCells, override + 1, this.overrideCells, override, this.overrideCount - override - 1);
			System.arraycopy(
			    this.overridePalette, override + 1, this.overridePalette, override, this.overrideCount - override - 1);
			this.overrideCount--;
			rebuildOverrideIndex();
		} else if (override >= 0) {
			this.overridePalette[override] = paletteIndex;
		} else {
			ensureOverrideCapacity(this.overrideCount + 1);
			this.overrideCells[this.overrideCount] = cell;
			this.overridePalette[this.overrideCount] = paletteIndex;
			this.overrideCount++;
			indexAppendedOverride(cell, this.overrideCount - 1);
		}
		this.collisionVersion++;
		this.sectionVersion[collisionSectionIndex(x, y, z)] = this.collisionVersion;
	}

	/** Refuse cauldron notifications that could activate an unmodelled neighbour or vibration listener. */
	public void requireCauldronUpdateClosure(final int x, final int y, final int z) {
		for (var entry : this.snapshot.palette()) {
			if (entry.name().equals("minecraft:sculk_sensor")
			    || entry.name().equals("minecraft:calibrated_sculk_sensor")
			    || entry.name().equals("minecraft:sculk_shrieker"))
				throw new UnimplementedMechanicException(
				    Refusal.UNMODELLED_WORLD_WRITE, "cauldron block-change vibration reaches an unmodelled listener");
		}
		for (int axis = 0; axis < 3; axis++)
			for (int sign : new int[] {-1, 1})
				for (int distance = 1; distance <= 2; distance++) {
					int index = paletteIndexAt(x + (axis == 0 ? sign * distance : 0),
					    y + (axis == 1 ? sign * distance : 0), z + (axis == 2 ? sign * distance : 0));
					if (index < 0)
						throw new UnimplementedMechanicException(
						    Refusal.OUTSIDE_REGION, "cauldron neighbour updates leave the captured region");
					String name = this.snapshot.palette().get(index).name();
					if (!isAirIdentity(name) && !name.equals("minecraft:stone") && !name.equals("minecraft:cauldron")
					    && !name.equals("minecraft:water_cauldron") && !name.equals("minecraft:powder_snow_cauldron")
					    && !name.equals("minecraft:lava_cauldron"))
						throw UnimplementedMechanicException.deferred(Refusal.UNMODELLED_WORLD_WRITE,
						    () -> "cauldron neighbour-update closure is unmodelled for " + name);
				}
	}

	/** Publish the block and resolved fluid successor for one cell at tick head. */
	public void replaceCellState(
	    final int x, final int y, final int z, final int paletteIndex, final int fluidPaletteIndex) {
		if (fluidPaletteIndex < 0 || fluidPaletteIndex >= this.fluidKind.length) {
			throw new IndexOutOfBoundsException(
			    "fluid palette index " + fluidPaletteIndex + " outside 0.." + this.fluidKind.length);
		}
		if (paletteIndex < 0 || paletteIndex >= this.shapes.length) {
			throw new IndexOutOfBoundsException("palette index " + paletteIndex + " outside 0.." + this.shapes.length);
		}
		if (baseCellIndex(x, y, z) < 0) {
			throw new IndexOutOfBoundsException("replacement cell outside snapshot: " + x + "," + y + "," + z);
		}
		replaceCell(x, y, z, paletteIndex);
		replaceFluidCellUnchecked(x, y, z, fluidPaletteIndex);
	}

	/** Publish one fully resolved fluid successor independently of its block cell. */
	public void replaceFluidCell(final int x, final int y, final int z, final int fluidPaletteIndex) {
		requireMutable();
		if (fluidPaletteIndex < 0 || fluidPaletteIndex >= this.fluidKind.length) {
			throw new IndexOutOfBoundsException(
			    "fluid palette index " + fluidPaletteIndex + " outside 0.." + this.fluidKind.length);
		}
		if (baseCellIndex(x, y, z) < 0) {
			throw new IndexOutOfBoundsException("replacement cell outside snapshot: " + x + "," + y + "," + z);
		}
		replaceFluidCellUnchecked(x, y, z, fluidPaletteIndex);
	}

	private void replaceFluidCellUnchecked(final int x, final int y, final int z, final int fluidPaletteIndex) {
		int cell = baseCellIndex(x, y, z);
		int override = findFluidOverride(cell);
		int base = baseFluidCell(cell);
		int current = override < 0 ? base : this.overrideFluidPalette[override];
		if (current == fluidPaletteIndex) {
			return;
		}
		detachPropertyGrids();
		if (fluidPaletteIndex == base) {
			System.arraycopy(this.overrideFluidCells, override + 1, this.overrideFluidCells, override,
			    this.overrideFluidCount - override - 1);
			System.arraycopy(this.overrideFluidPalette, override + 1, this.overrideFluidPalette, override,
			    this.overrideFluidCount - override - 1);
			this.overrideFluidCount--;
			rebuildFluidOverrideIndex();
		} else if (override >= 0) {
			this.overrideFluidPalette[override] = fluidPaletteIndex;
		} else {
			ensureFluidOverrideCapacity(this.overrideFluidCount + 1);
			this.overrideFluidCells[this.overrideFluidCount] = cell;
			this.overrideFluidPalette[this.overrideFluidCount] = fluidPaletteIndex;
			this.overrideFluidCount++;
			indexAppendedFluidOverride(cell, this.overrideFluidCount - 1);
		}
		if (fluidPaletteIndex != 0) {
			this.hasFluidCells = true;
			int section = collisionSectionIndex(x, y, z);
			this.sectionProperties[section] |= PROPERTY_FLUID;
			this.fineProperties[fineIndex(x, y, z)] |= PROPERTY_FLUID;
			this.wholeViewProperties |= PROPERTY_FLUID;
		}
	}

	private void requireMutable() {
		if (this.immutable) {
			throw new IllegalStateException("cannot replace cells in a frozen snapshot world");
		}
	}

	@Override
	public boolean movementFactsComplete() {
		return true;
	}

	@Override
	public long collisionVersion() {
		return this.collisionVersion;
	}

	/** Same compiled fact plane and effective sparse replacements; different compilations conservatively differ. */
	public static boolean sameWorld(final SnapshotView a, final SnapshotView b) {
		if (a.snapshot != b.snapshot || a.behaviour != b.behaviour || a.sourceInsideShapes != b.sourceInsideShapes
		    || a.overrideCount != b.overrideCount || a.overrideFluidCount != b.overrideFluidCount)
			return false;
		for (int i = 0; i < a.overrideCount; i++) {
			if (b.effectivePaletteIndex(a.overrideCells[i]) != a.overridePalette[i]) return false;
		}
		for (int i = 0; i < a.overrideFluidCount; i++) {
			if (b.effectiveFluidPaletteIndex(a.overrideFluidCells[i]) != a.overrideFluidPalette[i]) return false;
		}
		return true;
	}

	/** Effective block and fluid cells, including branch-owned replacements. */
	public WorldSnapshot materializedSnapshot() {
		int[] blocks = new int[this.sizeX * this.sizeY * this.sizeZ];
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

	/** The portable facts this view was compiled from, shared by every fork. */
	public WorldSnapshot snapshot() {
		return this.snapshot;
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
			    Refusal.OUTSIDE_REGION, "fluid query left the captured region at ", x, y, z, "");
		}
		int index = effectiveFluidPaletteIndex(cell);
		target.set(this.fluidKind[index], this.fluidHeight[index], this.fluidFlowX[index], this.fluidFlowY[index],
		    this.fluidFlowZ[index], this.fluidSource[index]);
	}

	@Override
	public BubbleColumnMode bubbleColumnModeAt(final int x, final int y, final int z) {
		int index = paletteIndexAt(x, y, z);
		if (index < 0) {
			throw UnimplementedMechanicException.at(
			    Refusal.OUTSIDE_REGION, "bubble-column query left the captured region at ", x, y, z, "");
		}
		return this.bubbleColumnMode[index];
	}

	@Override
	public boolean hasBubbleColumns() {
		return this.hasBubbleColumns;
	}

	@Override
	public boolean hasCollisionShapeAt(final int x, final int y, final int z) {
		int index = paletteIndexAt(x, y, z);
		if (index < 0) {
			throw UnimplementedMechanicException.at(
			    Refusal.OUTSIDE_REGION, "collision-shape query left the captured region at ", x, y, z, "");
		}
		return this.shapes[index].length != 0;
	}

	@Override
	public boolean onClimbableAt(final int x, final int y, final int z, final boolean fallFlying) {
		int index = paletteIndexAt(x, y, z);
		if (index < 0) {
			throw UnimplementedMechanicException.at(
			    Refusal.OUTSIDE_REGION, "climbable query left the captured region at ", x, y, z, "");
		}
		Climbability role = this.climbability[index];
		if (role == Climbability.NONE || role == Climbability.GLIDE_THROUGH && fallFlying) {
			return false;
		}
		if (role.ordinal() < Climbability.OPEN_TRAPDOOR_NORTH.ordinal()) {
			return true;
		}
		int below = paletteIndexAt(x, y - 1, z);
		if (below < 0) {
			throw UnimplementedMechanicException.at(
			    Refusal.OUTSIDE_REGION, "trapdoor climbable query left the captured region below ", x, y, z, "");
		}
		return switch (role) {
			case OPEN_TRAPDOOR_NORTH -> this.climbability[below] == Climbability.LADDER_NORTH;
			case OPEN_TRAPDOOR_EAST -> this.climbability[below] == Climbability.LADDER_EAST;
			case OPEN_TRAPDOOR_SOUTH -> this.climbability[below] == Climbability.LADDER_SOUTH;
			case OPEN_TRAPDOOR_WEST -> this.climbability[below] == Climbability.LADDER_WEST;
			default -> false;
		};
	}

	@Override
	public CollisionBehavior collisionBehaviorAt(final int x, final int y, final int z) {
		int index = paletteIndexAt(x, y, z);
		if (index < 0) {
			throw UnimplementedMechanicException.at(
			    Refusal.OUTSIDE_REGION, "collision-behavior query left the captured region at ", x, y, z, "");
		}
		return this.collisionBehavior[index];
	}

	@Override
	public float bounceRestitutionAt(final int x, final int y, final int z) {
		int index = paletteIndexAt(x, y, z);
		if (index < 0) {
			throw UnimplementedMechanicException.at(
			    Refusal.OUTSIDE_REGION, "bounce query left the captured region at ", x, y, z, "");
		}
		return this.bounceRestitution[index];
	}

	@Override
	public boolean suppressesBounceAt(final int x, final int y, final int z) {
		int index = paletteIndexAt(x, y, z);
		if (index < 0) {
			throw UnimplementedMechanicException.at(
			    Refusal.OUTSIDE_REGION, "bounce-suppression query left the captured region at ", x, y, z, "");
		}
		return this.suppressesBounce[index];
	}

	@Override
	public boolean retainsSupportPosAt(final int x, final int y, final int z) {
		int index = paletteIndexAt(x, y, z);
		if (index < 0) {
			throw UnimplementedMechanicException.at(
			    Refusal.OUTSIDE_REGION, "support-pos query left the captured region at ", x, y, z, "");
		}
		return this.retainsSupportPos[index];
	}

	/**
	 * {@code CollisionGetter.findSupportingBlock} over the cells whose ordinary
	 * collision shape reaches the box.
	 *
	 * <p>Iterated in the same order and with the same ring as
	 * {@link #collectCollisionBoxes}, because it is the same {@code BlockCollisions}
	 * walk with the boxes discarded and only the position kept. The winner does not
	 * depend on that order — {@code distToCenterSqr} plus the {@code compareTo}
	 * tie-break is a total order on cells — but the *refusals* do, and a cell that
	 * leaves the region has to fail closed at the point the collision scan would
	 * have reached it.
	 */
	@Override
	public void findSupportingBlock(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double atX, final double atY, final double atZ,
	    final SupportCell out) {
		findSupportingBlock(minX, minY, minZ, maxX, maxY, maxZ, atX, atY, atZ, atY, false, 0.0, false, out);
	}

	@Override
	public void findSupportingBlock(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double atX, final double atY, final double atZ,
	    final double entityBottom, final boolean descending, final double fallDistance, final SupportCell out) {
		findSupportingBlock(
		    minX, minY, minZ, maxX, maxY, maxZ, atX, atY, atZ, entityBottom, descending, fallDistance, false, out);
	}

	@Override
	public void findSupportingBlock(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double atX, final double atY, final double atZ,
	    final double entityBottom, final boolean descending, final double fallDistance,
	    final boolean canWalkOnPowderSnow, final SupportCell out) {
		CollisionQuerySpan.requireSupported(minX, minY, minZ, maxX, maxY, maxZ);
		out.clear();
		double best = Double.MAX_VALUE;
		int x0 = Mth.floor(minX - 1.0E-7) - 1;
		int x1 = Mth.floor(maxX + 1.0E-7) + 1;
		int y0 = Mth.floor(minY - 1.0E-7) - 1;
		int y1 = Mth.floor(maxY + 1.0E-7) + 1;
		int z0 = Mth.floor(minZ - 1.0E-7) - 1;
		int z1 = Mth.floor(maxZ + 1.0E-7) + 1;
		boolean ring = this.hasAnyLargeShape;
		int scanX0 = ring ? x0 : x0 + 1;
		int scanX1 = ring ? x1 : x1 - 1;
		int scanY0 = ring ? y0 : y0 + 1;
		int scanY1 = ring ? y1 : y1 - 1;
		int scanZ0 = ring ? z0 : z0 + 1;
		int scanZ1 = ring ? z1 : z1 - 1;
		for (int z = scanZ0; z <= scanZ1; z++) {
			for (int y = scanY0; y <= scanY1; y++) {
				for (int x = scanX0; x <= scanX1; x++) {
					int cursorType = 0;
					if (x == x0 || x == x1) cursorType++;
					if (y == y0 || y == y1) cursorType++;
					if (z == z0 || z == z1) cursorType++;
					if (cursorType >= 2) {
						continue;
					}
					if (x < this.originX || x >= this.endX || y < this.originY || y >= this.endY || z < this.originZ
					    || z >= this.endZ) {
						if (cursorType == 0
						    && intersects(minX, minY, minZ, maxX, maxY, maxZ, x, y, z, x + 1.0, y + 1.0, z + 1.0)) {
							throw UnimplementedMechanicException.at(Refusal.OUTSIDE_REGION,
							    "supporting-block query left the captured region at ", x, y, z, "");
						}
						continue;
					}
					int index = paletteIndexAt(x, y, z);
					if (cursorType == 1 && !this.largeShape[index]) {
						continue;
					}
					CollisionBehavior behavior = this.collisionBehavior[index];
					double[] shape = switch (behavior) {
						case ORDINARY -> this.shapes[index];
						case SCAFFOLDING_SUPPORTED ->
							!descending&& entityBottom > y + 1.0 - (double) Mth.EPSILON ? SCAFFOLDING_STABLE_SHAPE
							                                                            : null;
						case SCAFFOLDING_UNSTABLE_BOTTOM -> unstableScaffoldingShape(y, entityBottom, descending);
						case POWDER_SNOW_NO_BOOTS ->
							fallDistance > 2.5 ? POWDER_SNOW_FALLING_SHAPE
							    : canWalkOnPowderSnow&& entityBottom > y + 1.0 - (double) Mth.EPSILON && !descending
							    ? FULL_BLOCK_SHAPE
							    : null;
						// The captured shape is exactly what a server-mutable cell
						// may invalidate, so it cannot decide its own refusal. Both
						// collision paths refuse on whole-cell overlap, the same
						// predicate this method already uses for a cell whose facts
						// it does not hold.
						case SERVER_MUTABLE_SUPPORT -> {
							if (intersects(minX, minY, minZ, maxX, maxY, maxZ, x, y, z, x + 1.0, y + 1.0, z + 1.0)) {
								throw UnimplementedMechanicException.at(Refusal.UNDECLARED_SERVER_MUTABLE,
								    "support query reached server-mutable collision at ", x, y, z, "");
							}
							yield null;
						}
					};
					byte kind = behavior == CollisionBehavior.ORDINARY ? this.shapeClass[index]
					    : shape == FULL_BLOCK_SHAPE                    ? SHAPE_FULL_CUBE
					                                                   : SHAPE_GENERAL;
					if (shape == null || !reaches(shape, kind, x, y, z, minX, minY, minZ, maxX, maxY, maxZ)) {
						continue;
					}
					double dx = x + 0.5 - atX;
					double dy = y + 0.5 - atY;
					double dz = z + 0.5 - atZ;
					double distance = dx * dx + dy * dy + dz * dz;
					if (distance < best || distance == best && (!out.present || out.compareTo(x, y, z) < 0)) {
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
		if (fromX == toX && fromY == toY && fromZ == toZ) {
			return false;
		}
		double adjustedToX = toX + -1.0E-7 * (fromX - toX);
		double adjustedToY = toY + -1.0E-7 * (fromY - toY);
		double adjustedToZ = toZ + -1.0E-7 * (fromZ - toZ);
		double adjustedFromX = fromX + -1.0E-7 * (toX - fromX);
		double adjustedFromY = fromY + -1.0E-7 * (toY - fromY);
		double adjustedFromZ = fromZ + -1.0E-7 * (toZ - fromZ);
		int cellX = Mth.floor(adjustedFromX);
		int cellY = Mth.floor(adjustedFromY);
		int cellZ = Mth.floor(adjustedFromZ);
		// A water cell whose hit depends on the client's session-cached fluid
		// shape is carried along the traversal: a certain hit further on still
		// answers yes, and only a segment with no certain hit refuses.
		boolean sessionDependent = false;
		int hit = resetCellHit(cellX, cellY, cellZ, fromX, fromY, fromZ, toX, toY, toZ);
		if (hit == RESET_HIT) {
			return true;
		}
		sessionDependent |= hit == RESET_SESSION_DEPENDENT;
		double dx = adjustedToX - adjustedFromX;
		double dy = adjustedToY - adjustedFromY;
		double dz = adjustedToZ - adjustedFromZ;
		int signX = Integer.compare((int) Math.signum(dx), 0);
		int signY = Integer.compare((int) Math.signum(dy), 0);
		int signZ = Integer.compare((int) Math.signum(dz), 0);
		double deltaMovementX = signX == 0 ? Double.MAX_VALUE : signX / dx;
		double deltaMovementY = signY == 0 ? Double.MAX_VALUE : signY / dy;
		double deltaMovementZ = signZ == 0 ? Double.MAX_VALUE : signZ / dz;
		double nextX = deltaMovementX * (signX > 0 ? 1.0 - fraction(adjustedFromX) : fraction(adjustedFromX));
		double nextY = deltaMovementY * (signY > 0 ? 1.0 - fraction(adjustedFromY) : fraction(adjustedFromY));
		double nextZ = deltaMovementZ * (signZ > 0 ? 1.0 - fraction(adjustedFromZ) : fraction(adjustedFromZ));
		while (nextX <= 1.0 || nextY <= 1.0 || nextZ <= 1.0) {
			if (nextX < nextY) {
				if (nextX < nextZ) {
					cellX += signX;
					nextX += deltaMovementX;
				} else {
					cellZ += signZ;
					nextZ += deltaMovementZ;
				}
			} else if (nextY < nextZ) {
				cellY += signY;
				nextY += deltaMovementY;
			} else {
				cellZ += signZ;
				nextZ += deltaMovementZ;
			}
			hit = resetCellHit(cellX, cellY, cellZ, fromX, fromY, fromZ, toX, toY, toZ);
			if (hit == RESET_HIT) {
				return true;
			}
			sessionDependent |= hit == RESET_SESSION_DEPENDENT;
		}
		if (sessionDependent) {
			throw UnimplementedMechanicException.deferred(Refusal.UNDECLARED_WORLD_FACT,
			    ()
			        -> "the fall-distance-resetting clip from " + fromX + "," + fromY + "," + fromZ + " to " + toX + ","
			        + toY + "," + toZ + " meets water only above the height the client's session-cached"
			        + " fluid shape may hold, which is not a world fact");
		}
		return false;
	}

	private static final int RESET_MISS = 0;
	private static final int RESET_HIT = 1;
	/** The water shape's cached height decides the hit, and the capture cannot know it. */
	private static final int RESET_SESSION_DEPENDENT = 2;

	/**
	 * One cell of {@code Level.clip} for {@code ClipContext.Block.FALLDAMAGE_RESETTING}
	 * and {@code ClipContext.Fluid.WATER}: the full cube of a tagged block, then
	 * the water shape.
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
	 */
	private int resetCellHit(final int x, final int y, final int z, final double fromX, final double fromY,
	    final double fromZ, final double toX, final double toY, final double toZ) {
		int cell = baseCellIndex(x, y, z);
		if (cell < 0) {
			throw UnimplementedMechanicException.at(
			    Refusal.OUTSIDE_REGION, "fall-distance-resetting query left the captured region at ", x, y, z, "");
		}
		int palette = effectivePaletteIndex(cell);
		boolean resettingBlock = this.fallDistanceResettingBlock[palette]
		    || this.insideEffect[palette] == InsideEffect.COBWEB
		    || this.insideEffect[palette] == InsideEffect.SWEET_BERRY_BUSH;
		if (resettingBlock && clipBox(x, y, z, x + 1.0, y + 1.0, z + 1.0, fromX, fromY, fromZ, toX, toY, toZ)) {
			return RESET_HIT;
		}
		int fluid = effectiveFluidPaletteIndex(cell);
		if (this.fluidKind[fluid] != FluidSample.Kind.WATER) {
			return RESET_MISS;
		}
		// Neither cached height exceeds the full cube, so a full-cube miss is a miss.
		if (!clipBox(x, y, z, x + 1.0, y + 1.0, z + 1.0, fromX, fromY, fromZ, toX, toY, toZ)) {
			return RESET_MISS;
		}
		// The lower of the two heights the cache can hold for this state. A
		// captured height below one is the state's own height, and the cache
		// holds it or 1.0. A captured height of one means the same fluid lies
		// above, and the cache holds 1.0 or the own height, which the capture
		// does not carry: eight ninths for a source, at least one ninth for a
		// flowing state.
		double captured = this.fluidHeight[fluid];
		double lowest = captured < 1.0 ? captured : this.fluidSource[fluid] ? 8 / 9.0F : 1 / 9.0F;
		return clipBox(x, y, z, x + 1.0, y + lowest, z + 1.0, fromX, fromY, fromZ, toX, toY, toZ)
		    ? RESET_HIT
		    : RESET_SESSION_DEPENDENT;
	}

	private static double fraction(final double value) {
		return value - Math.floor(value);
	}

	/** {@code ScaffoldingBlock.getCollisionShape} for a bottom unstable state. */
	private static double[] unstableScaffoldingShape(final int y, final double entityBottom, final boolean descending) {
		if (!descending && entityBottom > y + 1.0 - (double) Mth.EPSILON) {
			return SCAFFOLDING_STABLE_SHAPE;
		}
		return entityBottom > y - (double) Mth.EPSILON ? SCAFFOLDING_UNSTABLE_BOTTOM_SHAPE : null;
	}

	private static boolean clipBox(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double fromX, final double fromY, final double fromZ,
	    final double toX, final double toY, final double toZ) {
		double dx = toX - fromX;
		double dy = toY - fromY;
		double dz = toZ - fromZ;
		// VoxelShape.clip first probes 0.1% along the ray. If that point is
		// inside, it returns an immediate inside hit rather than waiting for a
		// boundary crossing. This matters when a fast entity starts in water or
		// in a tagged reset block.
		double probeX = fromX + dx * 0.001;
		double probeY = fromY + dy * 0.001;
		double probeZ = fromZ + dz * 0.001;
		if (probeX >= minX && probeX < maxX && probeY >= minY && probeY < maxY && probeZ >= minZ && probeZ < maxZ) {
			return true;
		}
		return dx > 1.0E-7 && clipPoint(dx, dy, dz, minX, minY, maxY, minZ, maxZ, fromX, fromY, fromZ)
		    || dx < -1.0E-7 && clipPoint(dx, dy, dz, maxX, minY, maxY, minZ, maxZ, fromX, fromY, fromZ)
		    || dy > 1.0E-7 && clipPoint(dy, dz, dx, minY, minZ, maxZ, minX, maxX, fromY, fromZ, fromX)
		    || dy < -1.0E-7 && clipPoint(dy, dz, dx, maxY, minZ, maxZ, minX, maxX, fromY, fromZ, fromX)
		    || dz > 1.0E-7 && clipPoint(dz, dx, dy, minZ, minX, maxX, minY, maxY, fromZ, fromX, fromY)
		    || dz < -1.0E-7 && clipPoint(dz, dx, dy, maxZ, minX, maxX, minY, maxY, fromZ, fromX, fromY);
	}

	private static boolean clipPoint(final double da, final double db, final double dc, final double point,
	    final double minB, final double maxB, final double minC, final double maxC, final double fromA,
	    final double fromB, final double fromC) {
		double scale = (point - fromA) / da;
		double pointB = fromB + scale * db;
		double pointC = fromC + scale * dc;
		return 0.0 < scale && scale < 1.0 && minB - 1.0E-7 < pointB && pointB < maxB + 1.0E-7 && minC - 1.0E-7 < pointC
		    && pointC < maxC + 1.0E-7;
	}

	@Override
	public BlockBehaviour behaviourAt(final int x, final int y, final int z) {
		int index = paletteIndexAt(x, y, z);
		if (index < 0) {
			throw UnimplementedMechanicException.at(
			    Refusal.OUTSIDE_REGION, "behaviour query left the captured region at ", x, y, z, "");
		}
		return this.behaviour[index];
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
		if (!this.hasAnySuffocation) {
			return false;
		}
		for (int x = Mth.floor(minX); x <= Mth.floor(maxX); x++) {
			for (int y = Mth.floor(minY); y <= Mth.floor(maxY); y++) {
				for (int z = Mth.floor(minZ); z <= Mth.floor(maxZ); z++) {
					int cell = baseCellIndex(x, y, z);
					if (cell < 0) {
						throw UnimplementedMechanicException.at(
						    Refusal.OUTSIDE_REGION, "suffocation query left the captured region at ", x, y, z, "");
					}
					int palette = effectivePaletteIndex(cell);
					if (this.suffocation[palette] == SUFFOCATION_NO) {
						continue;
					}
					double[] boxes = this.shapes[palette];
					boolean meets = false;
					for (int b = 0; b < boxes.length; b += 6) {
						if (Math.min(x + boxes[b + 3], maxX) - Math.max(x + boxes[b], minX) > 1.0E-7
						    && Math.min(y + boxes[b + 4], maxY) - Math.max(y + boxes[b + 1], minY) > 1.0E-7
						    && Math.min(z + boxes[b + 5], maxZ) - Math.max(z + boxes[b + 2], minZ) > 1.0E-7) {
							meets = true;
							break;
						}
					}
					if (!meets) {
						continue;
					}
					if (this.suffocation[palette] == SUFFOCATION_UNKNOWN) {
						throw UnimplementedMechanicException.at(Refusal.UNDECLARED_BLOCK_STATE,
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
	 * written before this fact existed still answer every ordinary tick — the
	 * player's own box is in air — while still failing closed the moment an
	 * unclassified solid actually reaches the query.
	 */
	@Override
	public boolean suffocatesAt(
	    final int cellX, final int cellZ, final double boundingBoxMinY, final double boundingBoxMaxY) {
		if (!this.hasAnySuffocation) {
			return false;
		}
		// The column path is exact wherever no shape can reach into the column, and
		// that is a question about this column's neighbourhood rather than about the
		// world: validation gated it on the palette-wide flag, which one fence anywhere
		// turns off for every query . The span tested is the one the general
		// form would scan, the column expanded by the Cursor3D ring.
		if (!largeShapeIn(cellX - 1, Mth.floor(boundingBoxMinY + 1.0E-7) - 1, cellZ - 1, cellX + 1,
		        Mth.floor(boundingBoxMaxY - 1.0E-7) + 1, cellZ + 1)) {
			return suffocatesInColumn(cellX, cellZ, boundingBoxMinY, boundingBoxMaxY);
		}
		double minX = cellX + 1.0E-7;
		double maxX = cellX + 1.0 - 1.0E-7;
		double minY = boundingBoxMinY + 1.0E-7;
		double maxY = boundingBoxMaxY - 1.0E-7;
		double minZ = cellZ + 1.0E-7;
		double maxZ = cellZ + 1.0 - 1.0E-7;
		int x0 = Mth.floor(minX - 1.0E-7) - 1;
		int x1 = Mth.floor(maxX + 1.0E-7) + 1;
		int y0 = Mth.floor(minY - 1.0E-7) - 1;
		int y1 = Mth.floor(maxY + 1.0E-7) + 1;
		int z0 = Mth.floor(minZ - 1.0E-7) - 1;
		int z1 = Mth.floor(maxZ + 1.0E-7) + 1;
		boolean ring = this.hasAnyLargeShape;
		int scanX0 = ring ? x0 : x0 + 1;
		int scanX1 = ring ? x1 : x1 - 1;
		int scanY0 = ring ? y0 : y0 + 1;
		int scanY1 = ring ? y1 : y1 - 1;
		int scanZ0 = ring ? z0 : z0 + 1;
		int scanZ1 = ring ? z1 : z1 - 1;
		for (int z = scanZ0; z <= scanZ1; z++) {
			for (int y = scanY0; y <= scanY1; y++) {
				for (int x = scanX0; x <= scanX1; x++) {
					int cursorType = 0;
					if (x == x0 || x == x1) cursorType++;
					if (y == y0 || y == y1) cursorType++;
					if (z == z0 || z == z1) cursorType++;
					if (cursorType >= 2) {
						continue;
					}
					if (x < this.originX || x >= this.endX || y < this.originY || y >= this.endY || z < this.originZ
					    || z >= this.endZ) {
						if (cursorType == 0) {
							outsideSuffocates(x, y, z, minX, minY, minZ, maxX, maxY, maxZ);
						}
						continue;
					}
					int index = paletteIndexAt(x, y, z);
					if (cursorType == 1 && !this.largeShape[index]) {
						continue;
					}
					byte suffocates = this.suffocation[index];
					if (suffocates == SUFFOCATION_NO) {
						continue;
					}
					// Context-sensitive geometry is resolved for collision from the
					// player's own state, which this query does not carry. Neither
					// scaffolding nor powder snow suffocates at this pin — both fail
					// isCollisionShapeFullBlock against an empty context — so the
					// captured shape is only consulted for ORDINARY cells and
					// anything else is refused rather than approximated.
					double[] shape = this.shapes[index];
					if (this.collisionBehavior[index] != CollisionBehavior.ORDINARY) {
						if (reaches(shape, this.shapeClass[index], x, y, z, minX, minY, minZ, maxX, maxY, maxZ)) {
							throw UnimplementedMechanicException.at(Refusal.UNDECLARED_WORLD_FACT,
							    "suffocation query reached context-sensitive collision at ", x, y, z, "");
						}
						continue;
					}
					if (!reaches(shape, this.shapeClass[index], x, y, z, minX, minY, minZ, maxX, maxY, maxZ)) {
						continue;
					}
					if (suffocates == SUFFOCATION_UNKNOWN) {
						throw UnimplementedMechanicException.at(Refusal.UNDECLARED_BLOCK_STATE,
						    "suffocation is unknown for the block at ", x, y, z,
						    "; the snapshot predates the fact and the query reached it");
					}
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * {@link #suffocatesAt} for a world with no shape that leaves its own cell.
	 *
	 * <p>The query box is one cell wide in X and Z, deflated inside it, so the
	 * only cells whose geometry can reach it are the single column
	 * {@code (cellX, cellZ)}. The general form still walks two columns on each
	 * horizontal axis and discards three of every four with {@link #reaches},
	 * because it reproduces {@code Cursor3D}'s ring — and the ring exists for
	 * shapes that reach out of their cell, which by this branch's condition the
	 * world has none of. It also drops the per-cell {@code cursorType} arithmetic,
	 * which is constant zero here.
	 *
	 * <p>Exactly equivalent, not merely close. The cells this skips are the ones
	 * the general form reaches with {@code cursorType == 0} and then rejects:
	 * inside the region {@link #reaches} is false for all of them, since their
	 * shapes are cell-local and their cells do not intersect the box, and outside
	 * it {@link #outsideSuffocates} throws only on intersection, which the same
	 * argument rules out. Validation measures what it was costing: four of these run per
	 * tick, unconditionally, from {@code LocalPlayer.aiStep}.
	 */
	private boolean suffocatesInColumn(
	    final int cellX, final int cellZ, final double boundingBoxMinY, final double boundingBoxMaxY) {
		double minX = cellX + 1.0E-7;
		double maxX = cellX + 1.0 - 1.0E-7;
		double minY = boundingBoxMinY + 1.0E-7;
		double maxY = boundingBoxMaxY - 1.0E-7;
		double minZ = cellZ + 1.0E-7;
		double maxZ = cellZ + 1.0 - 1.0E-7;
		int y0 = Mth.floor(minY);
		int y1 = Mth.floor(maxY);
		if (cellX < this.originX || cellX >= this.endX || cellZ < this.originZ || cellZ >= this.endZ) {
			throw UnimplementedMechanicException.deferred(
			    Refusal.OUTSIDE_REGION, () -> "suffocation query left the captured region at " + cellX + ",*," + cellZ);
		}
		for (int y = y0; y <= y1; y++) {
			if (y < this.originY || y >= this.endY) {
				throw UnimplementedMechanicException.at(
				    Refusal.OUTSIDE_REGION, "suffocation query left the captured region at ", cellX, y, cellZ, "");
			}
			int index = paletteIndexAt(cellX, y, cellZ);
			byte suffocates = this.suffocation[index];
			if (suffocates == SUFFOCATION_NO) {
				continue;
			}
			double[] shape = this.shapes[index];
			if (this.collisionBehavior[index] != CollisionBehavior.ORDINARY) {
				if (reaches(shape, this.shapeClass[index], cellX, y, cellZ, minX, minY, minZ, maxX, maxY, maxZ)) {
					throw UnimplementedMechanicException.at(Refusal.UNDECLARED_WORLD_FACT,
					    "suffocation query reached context-sensitive collision at ", cellX, y, cellZ, "");
				}
				continue;
			}
			if (!reaches(shape, this.shapeClass[index], cellX, y, cellZ, minX, minY, minZ, maxX, maxY, maxZ)) {
				continue;
			}
			if (suffocates == SUFFOCATION_UNKNOWN) {
				throw UnimplementedMechanicException.at(Refusal.UNDECLARED_BLOCK_STATE,
				    "suffocation is unknown for the block at ", cellX, y, cellZ,
				    "; the snapshot predates the fact and the query reached it");
			}
			return true;
		}
		return false;
	}

	/** Whether this source shape passes its canonical-full/general admission. */
	private static boolean reaches(final double[] shape, final byte kind, final int x, final int y, final int z,
	    final double minX, final double minY, final double minZ, final double maxX, final double maxY,
	    final double maxZ) {
		if (kind == SHAPE_EMPTY) {
			return false;
		}
		if (kind == SHAPE_FULL_CUBE) {
			return intersects(minX, minY, minZ, maxX, maxY, maxZ, x, y, z, x + 1.0, y + 1.0, z + 1.0);
		}
		return CollisionCollector.generalGroupIntersects(shape, x, y, z, minX, minY, minZ, maxX, maxY, maxZ);
	}

	/**
	 * Outside the region is a full cube of unknown suffocation. Blocking space is
	 * still unclassified space, so reaching it with this query fails closed just
	 * as terminating space does.
	 */
	private void outsideSuffocates(final int x, final int y, final int z, final double minX, final double minY,
	    final double minZ, final double maxX, final double maxY, final double maxZ) {
		if (!intersects(minX, minY, minZ, maxX, maxY, maxZ, x, y, z, x + 1.0, y + 1.0, z + 1.0)) {
			return;
		}
		throw UnimplementedMechanicException.at(
		    Refusal.OUTSIDE_REGION, "suffocation query left the captured region at ", x, y, z, "");
	}

	@Override
	public boolean hasContextSensitiveCollision() {
		return this.hasContextSensitiveCollision;
	}

	@Override
	public boolean hasClimbables() {
		return this.hasClimbables;
	}

	@Override
	public boolean hasNonDefaultFriction() {
		return this.hasNonDefaultFriction;
	}

	@Override
	public boolean hasNonDefaultSpeedFactor() {
		return this.hasNonDefaultSpeedFactor;
	}

	@Override
	public boolean hasNonDefaultJumpFactor() {
		return this.hasNonDefaultJumpFactor;
	}

	@Override
	public boolean hasPowderSnow() {
		return this.hasPowderSnow;
	}

	@Override
	public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final CollisionBuffer target) {
		if (this.hasContextSensitiveCollision) {
			throw new UnimplementedMechanicException(
			    Refusal.UNDECLARED_WORLD_FACT, "context-free collision query cannot resolve contextual collision");
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

	@Override
	public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double entityBottom, final boolean descending,
	    final double fallDistance, final boolean canWalkOnPowderSnow, final CollisionBuffer target) {
		CollisionQuerySpan.requireSupported(minX, minY, minZ, maxX, maxY, maxZ);
		target.clear();
		int x0 = Mth.floor(minX - 1.0E-7) - 1;
		int x1 = Mth.floor(maxX + 1.0E-7) + 1;
		int y0 = Mth.floor(minY - 1.0E-7) - 1;
		int y1 = Mth.floor(maxY + 1.0E-7) + 1;
		int z0 = Mth.floor(minZ - 1.0E-7) - 1;
		int z1 = Mth.floor(maxZ + 1.0E-7) + 1;
		// Whether the Cursor3D ring has to be walked at all. Asked of this query's
		// own scan span rather than of the palette, so one fence in the region no
		// longer costs the ring on every query everywhere in it .
		boolean ring = largeShapeIn(x0, y0, z0, x1, y1, z1);
		int scanX0 = ring ? x0 : x0 + 1;
		int scanX1 = ring ? x1 : x1 - 1;
		int scanY0 = ring ? y0 : y0 + 1;
		int scanY1 = ring ? y1 : y1 - 1;
		int scanZ0 = ring ? z0 : z0 + 1;
		int scanZ1 = ring ? z1 : z1 - 1;
		boolean scanInside = scanX0 >= this.originX && scanX1 < this.endX && scanY0 >= this.originY
		    && scanY1 < this.endY && scanZ0 >= this.originZ && scanZ1 < this.endZ;
		if (scanInside && !hasCollisionPotential(scanX0, scanY0, scanZ0, scanX1, scanY1, scanZ1)) {
			return;
		}
		// The interior scan reads captured shapes without consulting collision
		// behaviour, so it may only run where no cell refuses. Context-sensitive
		// cells are excluded for the whole view; a server-mutable cell refuses
		// without context, so it withdraws only the queries that reach it.
		if (scanInside && !ring && !this.hasContextSensitiveCollision
		    && (!this.hasServerMutableCollision
		        || propertiesIn(PROPERTY_SERVER_MUTABLE, scanX0, scanY0, scanZ0, scanX1, scanY1, scanZ1) == 0)) {
			collectOrdinaryInterior(
			    scanX0, scanY0, scanZ0, scanX1, scanY1, scanZ1, minX, minY, minZ, maxX, maxY, maxZ, target);
			return;
		}

		// Cursor3D order in vanilla: X fastest, then Y, then Z.
		for (int z = scanZ0; z <= scanZ1; z++) {
			for (int y = scanY0; y <= scanY1; y++) {
				for (int x = scanX0; x <= scanX1; x++) {
					int cursorType = 0;
					if (x == x0 || x == x1) cursorType++;
					if (y == y0 || y == y1) cursorType++;
					if (z == z0 || z == z1) cursorType++;
					if (cursorType >= 2) {
						continue;
					}
					if (x < this.originX || x >= this.endX || y < this.originY || y >= this.endY || z < this.originZ
					    || z >= this.endZ) {
						// Sealed space behaves as an ordinary full block. It is not a
						// large shape, so Cursor3D's
						// face and edge rings filter it before querying the world.
						if (cursorType == 0) {
							outside(x, y, z, target, minX, minY, minZ, maxX, maxY, maxZ);
						}
						continue;
					}
					int index = paletteIndexAt(x, y, z);
					if (cursorType == 1 && !this.largeShape[index]) {
						continue;
					}
					CollisionBehavior behavior = this.collisionBehavior[index];
					double[] shape = switch (behavior) {
						case ORDINARY -> this.shapes[index];
						case SCAFFOLDING_SUPPORTED ->
							!descending&& entityBottom > y + 1.0 - (double) Mth.EPSILON ? SCAFFOLDING_STABLE_SHAPE
							                                                            : null;
						case SCAFFOLDING_UNSTABLE_BOTTOM -> unstableScaffoldingShape(y, entityBottom, descending);
						case POWDER_SNOW_NO_BOOTS ->
							fallDistance > 2.5 ? POWDER_SNOW_FALLING_SHAPE
							    : canWalkOnPowderSnow&& entityBottom > y + 1.0 - (double) Mth.EPSILON && !descending
							    ? FULL_BLOCK_SHAPE
							    : null;
						case SERVER_MUTABLE_SUPPORT -> {
							if (intersects(minX, minY, minZ, maxX, maxY, maxZ, x, y, z, x + 1.0, y + 1.0, z + 1.0)) {
								throw UnimplementedMechanicException.at(Refusal.UNDECLARED_SERVER_MUTABLE,
								    "movement reached server-mutable collision at ", x, y, z, "");
							}
							yield null;
						}
					};
					if (shape == null) {
						continue;
					}
					byte kind = behavior == CollisionBehavior.ORDINARY ? this.shapeClass[index]
					    : shape == FULL_BLOCK_SHAPE                    ? SHAPE_FULL_CUBE
					                                                   : SHAPE_GENERAL;
					appendGroupIfIntersects(shape, kind, x, y, z, minX, minY, minZ, maxX, maxY, maxZ, target);
				}
			}
		}
	}

	/**
	 * Exact hot path for an in-region Cursor3D interior containing only ordinary,
	 * cell-bounded shapes. X remains fastest, then Y, then Z, matching vanilla.
	 */
	private void collectOrdinaryInterior(final int x0, final int y0, final int z0, final int x1, final int y1,
	    final int z1, final double minX, final double minY, final double minZ, final double maxX, final double maxY,
	    final double maxZ, final CollisionBuffer target) {
		boolean hasOverrides = this.overrideCount != 0;
		// A row of cells along x lies in one section until a section boundary,
		// so the row is walked as runs, each over one section's contiguous
		// plane. A uniform empty run is skipped whole. The row's section slot
		// and local base are fixed for the row; only the x term varies.
		for (int z = z0; z <= z1; z++) {
			int rowSlotZ = ((z >> 4) - this.sectionOriginZ) * this.collisionSectionSizeX;
			int rowLocalZ = (z & Section.MASK) << Section.SHIFT;
			for (int y = y0; y <= y1; y++) {
				int rowSlot = ((y >> 4) - this.sectionOriginY) * this.collisionSectionSizeZ * this.collisionSectionSizeX
				    + rowSlotZ - this.sectionOriginX;
				int rowLocal = ((y & Section.MASK) << (2 * Section.SHIFT)) | rowLocalZ;
				int x = x0;
				while (x <= x1) {
					int runEnd = Math.min(x1, x | Section.MASK);
					int slot = rowSlot + (x >> 4);
					Section section = this.sections[slot];
					int local = rowLocal | (x & Section.MASK);
					int[] plane = section.rawCells();
					if (plane == null) {
						int uniform = section.uniformIndex();
						if (uniform < 0) {
							throw missingSection((slot << CELL_SHIFT) | local);
						}
						if (!hasOverrides && this.shapeClass[uniform] == SHAPE_EMPTY) {
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
		for (; x <= runEnd; x++, cell++, local++) {
			int index = hasOverrides ? effectivePaletteIndex(cell)
			    : plane == null      ? section.uniformIndex()
			                         : plane[local];
			byte kind = this.shapeClass[index];
			if (kind == SHAPE_EMPTY) {
				continue;
			}
			if (kind == SHAPE_FULL_CUBE) {
				if (intersects(minX, minY, minZ, maxX, maxY, maxZ, x, y, z, x + 1.0, y + 1.0, z + 1.0)) {
					target.addCanonicalFullCube(x, y, z);
				}
				continue;
			}
			appendGroupIfIntersects(this.shapes[index], kind, x, y, z, minX, minY, minZ, maxX, maxY, maxZ, target);
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
	 * fence can still reuse every span that is nowhere near it. Capture-backed
	 * planner and kernel rows own the cost of making this answer coarser.
	 *
	 * <p>A server-mutable cell refuses rather than answering, and depends on no
	 * player context, so it does not make the whole world context-sensitive. It
	 * withdraws only the spans that touch it, in {@link #collectSpanBoxes}.
	 */
	@Override
	public boolean supportsSpanReuse() {
		return !this.hasContextSensitiveCollision;
	}

	@Override
	public CollisionShapeProtocol collisionShapeProtocol() {
		return CollisionShapeProtocol.SOURCE_GROUPS_V1;
	}

	@Override
	public boolean spanHasCollisionPotential(
	    final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		if (x0 < this.originX || x1 >= this.endX || y0 < this.originY || y1 >= this.endY || z0 < this.originZ
		    || z1 >= this.endZ) {
			return true;
		}
		return hasCollisionPotential(x0, y0, z0, x1, y1, z1);
	}

	@Override
	public boolean collectSpanBoxes(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1,
	    final CollisionBuffer target) {
		if (!supportsSpanReuse() || x0 < this.originX || x1 >= this.endX || y0 < this.originY || y1 >= this.endY
		    || z0 < this.originZ || z1 >= this.endZ) {
			// Outside the region is not air, and the ordinary path owns that
			// semantic. Refusing here keeps it in one place.
			return false;
		}
		// Cell-boundedness, per span. A retained span is refiltered for sub-queries,
		// so a shape owned one cell outside it could reach into one of those and be
		// missed — hence the ring, the same neighbourhood the scan itself walks.
		if (largeShapeIn(x0 - 1, y0 - 1, z0 - 1, x1 + 1, y1 + 1, z1 + 1)) {
			return false;
		}
		// A span collects captured shapes once and refilters them for later
		// sub-queries, so it cannot ask a server-mutable cell's refusal question,
		// which depends on the querying box. Declining the span sends those
		// queries down the ordinary path, which refuses exactly when a box
		// overlaps the cell. The ring matches the neighbourhood that path scans.
		if (this.hasServerMutableCollision
		    && propertiesIn(PROPERTY_SERVER_MUTABLE, x0 - 1, y0 - 1, z0 - 1, x1 + 1, y1 + 1, z1 + 1) != 0) {
			return false;
		}
		target.clear();
		if (!hasCollisionPotential(x0, y0, z0, x1, y1, z1)) {
			return true;
		}
		boolean hasOverrides = this.overrideCount != 0;
		// X fastest, then Y, then Z, matching Cursor3D and collectOrdinaryInterior.
		// A sub-span of this span is an interval in each coordinate, so restricting
		// this order to it is that sub-span's own order. Rows are walked as runs
		// per section, as there.
		for (int z = z0; z <= z1; z++) {
			int rowSlotZ = ((z >> 4) - this.sectionOriginZ) * this.collisionSectionSizeX;
			int rowLocalZ = (z & Section.MASK) << Section.SHIFT;
			for (int y = y0; y <= y1; y++) {
				int rowSlot = ((y >> 4) - this.sectionOriginY) * this.collisionSectionSizeZ * this.collisionSectionSizeX
				    + rowSlotZ - this.sectionOriginX;
				int rowLocal = ((y & Section.MASK) << (2 * Section.SHIFT)) | rowLocalZ;
				int x = x0;
				while (x <= x1) {
					int runEnd = Math.min(x1, x | Section.MASK);
					int slot = rowSlot + (x >> 4);
					Section section = this.sections[slot];
					int local = rowLocal | (x & Section.MASK);
					int[] plane = section.rawCells();
					if (plane == null) {
						int uniform = section.uniformIndex();
						if (uniform < 0) {
							throw missingSection((slot << CELL_SHIFT) | local);
						}
						if (!hasOverrides && this.shapeClass[uniform] == SHAPE_EMPTY) {
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

	/** One row's run inside one section for {@link #collectSpanBoxes}; outlined as {@link #collectOrdinaryRun} is. */
	private void collectSpanRun(final Section section, final int[] plane, int cell, int local, int x, final int runEnd,
	    final int y, final int z, final boolean hasOverrides, final CollisionBuffer target) {
		for (; x <= runEnd; x++, cell++, local++) {
			int index = hasOverrides ? effectivePaletteIndex(cell)
			    : plane == null      ? section.uniformIndex()
			                         : plane[local];
			byte kind = this.shapeClass[index];
			if (kind == SHAPE_EMPTY) {
				continue;
			}
			if (kind == SHAPE_FULL_CUBE) {
				target.addCanonicalFullCube(x, y, z);
				continue;
			}
			double[] shape = this.shapes[index];
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

	/**
	 * A conservative property union: fine masks for bounded spans, section masks
	 * for larger spans, or the whole-view mask outside the region.
	 *
	 * <p>Structured like {@link #hasCollisionPotential}, and for the same reason:
	 * a movement query spans two to four cells, so it almost always lies in one
	 * 16-cube and the loop setup would be most of the cost.
	 */
	@Override
	public int propertiesAnywhere(final int wanted) {
		return this.wholeViewProperties & wanted;
	}

	@Override
	public int propertiesIn(
	    final int wanted, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		// No section can carry what the whole snapshot does not, so this rejects
		// every fluid question asked of a dry world for one field load — which is
		// what the flag it replaces cost, and the reason a small fixture does not
		// pay for the section machinery it cannot use.
		int possible = this.wholeViewProperties & wanted;
		if (possible == 0) {
			return 0;
		}
		if (x0 < this.originX || x1 >= this.endX || y0 < this.originY || y1 >= this.endY || z0 < this.originZ
		    || z1 >= this.endZ) {
			return possible;
		}
		// A span inside one fine cube is answered by that cube alone, and more
		// precisely than the coarse mask could: its bits are a subset. Reaching the
		// coarse level first would be two extra loads to obtain a weaker answer. Query
		// counting owns the distribution; common coefficient and fluid questions ask
		// about one cell, which always lies in one cube.
		int fineX0 = (x0 >> FINE_SHIFT) - this.fineOriginX;
		int fineY0 = (y0 >> FINE_SHIFT) - this.fineOriginY;
		int fineZ0 = (z0 >> FINE_SHIFT) - this.fineOriginZ;
		int fineX1 = (x1 >> FINE_SHIFT) - this.fineOriginX;
		int fineY1 = (y1 >> FINE_SHIFT) - this.fineOriginY;
		int fineZ1 = (z1 >> FINE_SHIFT) - this.fineOriginZ;
		if (fineX0 == fineX1 && fineY0 == fineY1 && fineZ0 == fineZ1) {
			return this.fineProperties[(fineY0 * this.fineSizeZ + fineZ0) * this.fineSizeX + fineX0] & possible;
		}
		// Fine masks are subsets of their section masks. For a bounded fine scan,
		// their union is already the refined answer; a coarse read cannot alter it.
		if ((fineX1 - fineX0 + 1) * (fineY1 - fineY0 + 1) * (fineZ1 - fineZ0 + 1) <= FINE_CUBE_LIMIT) {
			return propertiesFromFine(possible, fineX0, fineY0, fineZ0, fineX1, fineY1, fineZ1);
		}
		return propertiesFromSections(possible, x0, y0, z0, x1, y1, z1);
	}

	/**
	 * {@link #propertiesIn} for a span exceeding the bounded fine-grid scan.
	 *
	 * <p>Outlined to keep the entry point inlinable; see
	 * {@link #propertiesAcrossSections} for what that is worth and how to check it.
	 */
	private int propertiesFromSections(
	    final int possible, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		int sectionX0 = (x0 >> 4) - this.sectionOriginX;
		int sectionY0 = (y0 >> 4) - this.sectionOriginY;
		int sectionZ0 = (z0 >> 4) - this.sectionOriginZ;
		if (sectionX0 != (x1 >> 4) - this.sectionOriginX || sectionY0 != (y1 >> 4) - this.sectionOriginY
		    || sectionZ0 != (z1 >> 4) - this.sectionOriginZ) {
			return propertiesAcrossSections(possible, x0, y0, z0, x1, y1, z1);
		}
		int section = (sectionY0 * this.collisionSectionSizeZ + sectionZ0) * this.collisionSectionSizeX + sectionX0;
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
	 * this method. Keep the hot entry point small, check bytecode and compilation
	 * output after changing it, then confirm with the fixed A/B matrix.
	 */
	private int propertiesAcrossSections(
	    final int possible, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		int sectionX0 = (x0 >> 4) - this.sectionOriginX;
		int sectionY0 = (y0 >> 4) - this.sectionOriginY;
		int sectionZ0 = (z0 >> 4) - this.sectionOriginZ;
		int sectionX1 = (x1 >> 4) - this.sectionOriginX;
		int sectionY1 = (y1 >> 4) - this.sectionOriginY;
		int sectionZ1 = (z1 >> 4) - this.sectionOriginZ;
		int properties = 0;
	outer:
		for (int sectionZ = sectionZ0; sectionZ <= sectionZ1; sectionZ++) {
			for (int sectionY = sectionY0; sectionY <= sectionY1; sectionY++) {
				int section =
				    (sectionY * this.collisionSectionSizeZ + sectionZ) * this.collisionSectionSizeX + sectionX0;
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
	 * loads can outweigh what refinement saves. The Elytra and working-set rows
	 * own this cutoff.
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
			return this.fineProperties[(fineY0 * this.fineSizeZ + fineZ0) * this.fineSizeX + fineX0] & coarse;
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
				int fine = (fineY * this.fineSizeZ + fineZ) * this.fineSizeX + fineX0;
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

	/**
	 * The revisions of the sections the span touches, folded in grid order,
	 * and this view's identity folded in as well when any of them has been
	 * edited here. Two views that hold the same section objects over the
	 * span, both unedited there, answer the same number, whichever
	 * publication each was compiled from; a span reaching outside the bounds
	 * or into a missing section has no revision. The fold is a 64-bit mix of
	 * process-unique revisions: a stale proof becomes a miss, and a hit that
	 * was not one needs two distinct revision tuples over one span to mix to
	 * one value.
	 */
	@Override
	public long spanRevision(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		if (x0 < this.originX || x1 >= this.endX || y0 < this.originY || y1 >= this.endY || z0 < this.originZ
		    || z1 >= this.endZ) {
			return 0L;
		}
		int sectionX0 = (x0 >> 4) - this.sectionOriginX;
		int sectionY0 = (y0 >> 4) - this.sectionOriginY;
		int sectionZ0 = (z0 >> 4) - this.sectionOriginZ;
		int sectionX1 = (x1 >> 4) - this.sectionOriginX;
		int sectionY1 = (y1 >> 4) - this.sectionOriginY;
		int sectionZ1 = (z1 >> 4) - this.sectionOriginZ;
		long[] revisions = this.sectionRevision;
		long fold;
		boolean edited;
		if (sectionX0 == sectionX1 && sectionY0 == sectionY1 && sectionZ0 == sectionZ1) {
			// One section, the common body span: its revision is already a
			// process-unique name for its cells, so nothing is folded.
			int section = (sectionY0 * this.collisionSectionSizeZ + sectionZ0) * this.collisionSectionSizeX + sectionX0;
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
					int section =
					    (sectionY * this.collisionSectionSizeZ + sectionZ) * this.collisionSectionSizeX + sectionX0;
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

	private static final long REVISION_SEED = 0x9E3779B97F4A7C15L;

	/** One step of the revision fold: a SplitMix64 finalizer over the running value and the next revision. */
	private static long mix(final long fold, final long value) {
		long z = (fold ^ value) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return (z ^ (z >>> 31)) + REVISION_SEED;
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
		if (x0 < this.originX || x1 >= this.endX || y0 < this.originY || y1 >= this.endY || z0 < this.originZ
		    || z1 >= this.endZ) {
			return this.collisionVersion;
		}
		int sectionX0 = (x0 >> 4) - this.sectionOriginX;
		int sectionY0 = (y0 >> 4) - this.sectionOriginY;
		int sectionZ0 = (z0 >> 4) - this.sectionOriginZ;
		int sectionX1 = (x1 >> 4) - this.sectionOriginX;
		int sectionY1 = (y1 >> 4) - this.sectionOriginY;
		int sectionZ1 = (z1 >> 4) - this.sectionOriginZ;
		if (sectionX0 == sectionX1 && sectionY0 == sectionY1 && sectionZ0 == sectionZ1) {
			return this.sectionVersion[(sectionY0 * this.collisionSectionSizeZ + sectionZ0) * this.collisionSectionSizeX
			    + sectionX0];
		}
		long version = 0L;
		for (int sectionZ = sectionZ0; sectionZ <= sectionZ1; sectionZ++) {
			for (int sectionY = sectionY0; sectionY <= sectionY1; sectionY++) {
				int section =
				    (sectionY * this.collisionSectionSizeZ + sectionZ) * this.collisionSectionSizeX + sectionX0;
				for (int sectionX = sectionX0; sectionX <= sectionX1; sectionX++, section++) {
					if (this.sectionVersion[section] > version) {
						version = this.sectionVersion[section];
					}
				}
			}
		}
		return version;
	}

	/**
	 * Whether any cell of this closed span owns a shape that leaves its own cell.
	 *
	 * <p>Conservative in one direction only: true may be returned for a span that
	 * holds none, which costs the general path; false must mean there is none, since
	 * a caller takes it as licence to skip the ring. A span not wholly inside the
	 * region answers true, because outside cells are unknown and the general path is
	 * the one that owns that semantic.
	 *
	 * <p>Callers pass the span they would *scan*, ring included, not the query box:
	 * the question is whether a shape can reach in, and reaching in is what the ring
	 * exists to catch.
	 */
	private boolean largeShapeIn(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		// The palette-wide answer first, so a world with no such shape anywhere pays
		// one field load and nothing else — validation's argument for keeping the coarse
		// answer as a pre-filter rather than deleting it.
		if (!this.hasAnyLargeShape) {
			return false;
		}
		if (x0 < this.originX || x1 >= this.endX || y0 < this.originY || y1 >= this.endY || z0 < this.originZ
		    || z1 >= this.endZ) {
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
		int sectionX0 = (x0 >> 4) - this.sectionOriginX;
		int sectionY0 = (y0 >> 4) - this.sectionOriginY;
		int sectionZ0 = (z0 >> 4) - this.sectionOriginZ;
		int sectionX1 = (x1 >> 4) - this.sectionOriginX;
		int sectionY1 = (y1 >> 4) - this.sectionOriginY;
		int sectionZ1 = (z1 >> 4) - this.sectionOriginZ;
		if (sectionX0 == sectionX1 && sectionY0 == sectionY1 && sectionZ0 == sectionZ1) {
			return this
			    .sectionLargeShape[(sectionY0 * this.collisionSectionSizeZ + sectionZ0) * this.collisionSectionSizeX
			        + sectionX0];
		}
		for (int sectionZ = sectionZ0; sectionZ <= sectionZ1; sectionZ++) {
			for (int sectionY = sectionY0; sectionY <= sectionY1; sectionY++) {
				int section =
				    (sectionY * this.collisionSectionSizeZ + sectionZ) * this.collisionSectionSizeX + sectionX0;
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
				int fine = (fineY * this.fineSizeZ + fineZ) * this.fineSizeX + fineX0;
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
		int sectionX0 = (x0 >> 4) - this.sectionOriginX;
		int sectionY0 = (y0 >> 4) - this.sectionOriginY;
		int sectionZ0 = (z0 >> 4) - this.sectionOriginZ;
		int sectionX1 = (x1 >> 4) - this.sectionOriginX;
		int sectionY1 = (y1 >> 4) - this.sectionOriginY;
		int sectionZ1 = (z1 >> 4) - this.sectionOriginZ;
		// A movement query spans two to four cells, so it almost always lies in
		// one 16-cube. Sparing that case the loop setup matters because in open
		// air this test is the entire cost of the query.
		if (sectionX0 == sectionX1 && sectionY0 == sectionY1 && sectionZ0 == sectionZ1) {
			return this.collisionSectionCounts[(sectionY0 * this.collisionSectionSizeZ + sectionZ0)
			               * this.collisionSectionSizeX
			           + sectionX0]
			    != 0;
		}
		for (int sectionZ = sectionZ0; sectionZ <= sectionZ1; sectionZ++) {
			for (int sectionY = sectionY0; sectionY <= sectionY1; sectionY++) {
				int section =
				    (sectionY * this.collisionSectionSizeZ + sectionZ) * this.collisionSectionSizeX + sectionX0;
				for (int sectionX = sectionX0; sectionX <= sectionX1; sectionX++, section++) {
					if (this.collisionSectionCounts[section] != 0) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static void appendGroupIfIntersects(final double[] shape, final byte kind, final int x, final int y,
	    final int z, final double minX, final double minY, final double minZ, final double maxX, final double maxY,
	    final double maxZ, final CollisionBuffer target) {
		if (kind == SHAPE_EMPTY) {
			return;
		}
		if (kind == SHAPE_FULL_CUBE) {
			if (intersects(minX, minY, minZ, maxX, maxY, maxZ, x, y, z, x + 1.0, y + 1.0, z + 1.0)) {
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

	private static byte classify(final double[] shape, final WorldSnapshot.CollisionShapeIdentity identity) {
		if (shape.length == 0) {
			return SHAPE_EMPTY;
		}
		return switch (identity) {
			case CANONICAL_FULL -> SHAPE_FULL_CUBE;
			case GENERAL -> SHAPE_GENERAL;
			case LEGACY_GEOMETRY -> isLegacyFullCube(shape) ? SHAPE_FULL_CUBE : SHAPE_GENERAL;
		};
	}

	private static boolean isLegacyFullCube(final double[] shape) {
		return shape.length == 6 && shape[0] == 0.0 && shape[1] == 0.0 && shape[2] == 0.0 && shape[3] == 1.0
		    && shape[4] == 1.0 && shape[5] == 1.0;
	}

	private static void validate(final WorldSnapshot.ShapeBox box, final int paletteIndex, final int shapeIndex) {
		if (!Double.isFinite(box.minX()) || !Double.isFinite(box.minY()) || !Double.isFinite(box.minZ())
		    || !Double.isFinite(box.maxX()) || !Double.isFinite(box.maxY()) || !Double.isFinite(box.maxZ())
		    || box.minX() > box.maxX() || box.minY() > box.maxY() || box.minZ() > box.maxZ()) {
			throw new IllegalArgumentException(
			    "invalid collision shape at palette " + paletteIndex + ", box " + shapeIndex + ": " + box);
		}
	}

	private static boolean intersects(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double shapeMinX, final double shapeMinY, final double shapeMinZ,
	    final double shapeMaxX, final double shapeMaxY, final double shapeMaxZ) {
		return shapeMaxX > minX && shapeMinX < maxX && shapeMaxY > minY && shapeMinY < maxY && shapeMaxZ > minZ
		    && shapeMinZ < maxZ;
	}

	/**
	 * Fails closed by default. A rollout that reaches the edge of the captured
	 * region has left the world we actually observed, and any answer about what
	 * is out there would be invented.
	 */
	private void outside(final int x, final int y, final int z, final CollisionBuffer target, final double minX,
	    final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
		switch (this.outside) {
			case SEALED -> {
				if (intersects(minX, minY, minZ, maxX, maxY, maxZ, x, y, z, x + 1.0, y + 1.0, z + 1.0)) {
					target.addCanonicalFullCube(x, y, z);
				}
			}
			case ROLLOUT_TERMINATING -> {
				if (intersects(minX, minY, minZ, maxX, maxY, maxZ, x, y, z, x + 1.0, y + 1.0, z + 1.0)) {
					throw UnimplementedMechanicException.at(Refusal.OUTSIDE_REGION,
					    "collision query left the captured region at ", x, y, z,
					    "; the snapshot declares outside space rollout-terminating");
				}
			}
		}
	}

	@Override
	public float friction(final int x, final int y, final int z) {
		int index = paletteIndexAt(x, y, z);
		return index < 0 ? 0.6F : this.friction[index];
	}

	@Override
	public float speedFactor(final int x, final int y, final int z) {
		int index = paletteIndexAt(x, y, z);
		return index < 0 ? 1.0F : this.speedFactor[index];
	}

	@Override
	public boolean suppressesSupportingSpeedFactor(final int x, final int y, final int z) {
		int index = paletteIndexAt(x, y, z);
		return index >= 0 && this.suppressesSupportingSpeedFactor[index];
	}

	@Override
	public float jumpFactor(final int x, final int y, final int z) {
		int index = paletteIndexAt(x, y, z);
		return index < 0 ? 1.0F : this.jumpFactor[index];
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
		if (x < this.originX || x >= this.endX || this.originY >= this.endY || z < this.originZ || z >= this.endZ) {
			return false;
		}
		if (!this.hasMissingSections) {
			return true;
		}
		int column = ((z >> 4) - this.sectionOriginZ) * this.collisionSectionSizeX + ((x >> 4) - this.sectionOriginX);
		int stride = this.collisionSectionSizeX * this.collisionSectionSizeZ;
		for (int sectionY = 0; sectionY < this.collisionSectionSizeY; sectionY++) {
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

	/** The three vanilla air block states, by registry name. */
	private static boolean isAirIdentity(final String name) {
		return name.equals("minecraft:air") || name.equals("minecraft:cave_air") || name.equals("minecraft:void_air");
	}

	@Override
	public Air airIn(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		boolean unknown = false;
		for (int y = y0; y <= y1; y++) {
			for (int z = z0; z <= z1; z++) {
				for (int x = x0; x <= x1; x++) {
					int cell = baseCellIndex(x, y, z);
					if (cell < 0) {
						// Sealed space is solid, so it decides; terminating space
						// claims nothing about the cell.
						if (this.outside == WorldSnapshot.OutsideRegion.SEALED) {
							return Air.NOT_AIR;
						}
						unknown = true;
						continue;
					}
					if (!this.air[effectivePaletteIndex(cell)]) {
						return Air.NOT_AIR;
					}
				}
			}
		}
		return unknown ? Air.UNKNOWN : Air.ALL_AIR;
	}

	/** Effective palette index after sparse replacements, or {@code -1} outside. */
	public int paletteIndexAt(final int x, final int y, final int z) {
		int cell = baseCellIndex(x, y, z);
		if (cell < 0) {
			return -1;
		}
		return effectivePaletteIndex(cell);
	}

	private int effectivePaletteIndex(final int cell) {
		if (this.overrideCount == 0) {
			return baseCell(cell);
		}
		int override = findOverride(cell);
		return override < 0 ? baseCell(cell) : this.overridePalette[override];
	}

	private int effectiveFluidPaletteIndex(final int cell) {
		if (this.overrideFluidCount == 0) {
			return baseFluidCell(cell);
		}
		int override = findFluidOverride(cell);
		return override < 0 ? baseFluidCell(cell) : this.overrideFluidPalette[override];
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
		int sectionX = slot % this.collisionSectionSizeX;
		int sectionZ = slot / this.collisionSectionSizeX % this.collisionSectionSizeZ;
		int sectionY = slot / (this.collisionSectionSizeX * this.collisionSectionSizeZ);
		int x = ((this.sectionOriginX + sectionX) << Section.SHIFT) + (local & Section.MASK);
		int y = ((this.sectionOriginY + sectionY) << Section.SHIFT) + (local >>> (2 * Section.SHIFT));
		int z = ((this.sectionOriginZ + sectionZ) << Section.SHIFT) + ((local >>> Section.SHIFT) & Section.MASK);
		return UnimplementedMechanicException.deferred(Refusal.OUTSIDE_REGION,
		    () -> "query reached a section the world does not hold at " + x + "," + y + "," + z);
	}

	private int collisionSectionIndex(final int x, final int y, final int z) {
		return (((y >> 4) - this.sectionOriginY) * this.collisionSectionSizeZ + ((z >> 4) - this.sectionOriginZ))
		    * this.collisionSectionSizeX
		    + ((x >> 4) - this.sectionOriginX);
	}

	private int fineIndex(final int x, final int y, final int z) {
		return (((y >> FINE_SHIFT) - this.fineOriginY) * this.fineSizeZ + ((z >> FINE_SHIFT) - this.fineOriginZ))
		    * this.fineSizeX
		    + ((x >> FINE_SHIFT) - this.fineOriginX);
	}

	/** A cell's number: its section's grid slot above its local number in the section. */
	private int baseCellIndex(final int x, final int y, final int z) {
		if (x < this.originX || x >= this.endX || y < this.originY || y >= this.endY || z < this.originZ
		    || z >= this.endZ) {
			return -1;
		}
		return (collisionSectionIndex(x, y, z) << CELL_SHIFT)
		    | Section.index(x & Section.MASK, y & Section.MASK, z & Section.MASK);
	}

	private int findOverride(final int cell) {
		if (this.overrideIndex != null) {
			return findIndexedOverride(cell, this.overrideCells, this.overrideIndex);
		}
		for (int i = 0; i < this.overrideCount; i++) {
			if (this.overrideCells[i] == cell) {
				return i;
			}
		}
		return -1;
	}

	private int findFluidOverride(final int cell) {
		if (this.overrideFluidIndex != null) {
			return findIndexedOverride(cell, this.overrideFluidCells, this.overrideFluidIndex);
		}
		for (int i = 0; i < this.overrideFluidCount; i++) {
			if (this.overrideFluidCells[i] == cell) {
				return i;
			}
		}
		return -1;
	}

	private static int findIndexedOverride(final int cell, final int[] cells, final int[] index) {
		int mask = index.length - 1;
		int slot = overrideHash(cell) & mask;
		while (true) {
			int entry = index[slot];
			if (entry == 0) {
				return -1;
			}
			int at = entry - 1;
			if (cells[at] == cell) {
				return at;
			}
			slot = (slot + 1) & mask;
		}
	}

	private void indexAppendedOverride(final int cell, final int at) {
		if (this.overrideCount <= OVERRIDE_HASH_THRESHOLD) {
			return;
		}
		if (this.overrideIndex == null || this.overrideCount * 2 > this.overrideIndex.length) {
			rebuildOverrideIndex();
			return;
		}
		insertOverrideIndex(cell, at, this.overrideIndex);
	}

	private void indexAppendedFluidOverride(final int cell, final int at) {
		if (this.overrideFluidCount <= OVERRIDE_HASH_THRESHOLD) {
			return;
		}
		if (this.overrideFluidIndex == null || this.overrideFluidCount * 2 > this.overrideFluidIndex.length) {
			rebuildFluidOverrideIndex();
			return;
		}
		insertOverrideIndex(cell, at, this.overrideFluidIndex);
	}

	private void rebuildOverrideIndex() {
		this.overrideIndex = buildOverrideIndex(this.overrideCells, this.overrideCount);
	}

	private void rebuildFluidOverrideIndex() {
		this.overrideFluidIndex = buildOverrideIndex(this.overrideFluidCells, this.overrideFluidCount);
	}

	private static int[] buildOverrideIndex(final int[] cells, final int count) {
		if (count <= OVERRIDE_HASH_THRESHOLD) {
			return null;
		}
		int capacity = 1;
		while (capacity < count * 2) {
			capacity <<= 1;
		}
		int[] index = new int[capacity];
		for (int at = 0; at < count; at++) {
			insertOverrideIndex(cells[at], at, index);
		}
		return index;
	}

	private static void insertOverrideIndex(final int cell, final int at, final int[] index) {
		int mask = index.length - 1;
		int slot = overrideHash(cell) & mask;
		while (index[slot] != 0) {
			slot = (slot + 1) & mask;
		}
		index[slot] = at + 1;
	}

	private static int overrideHash(final int cell) {
		int hash = cell * 0x9E3779B9;
		return hash ^ (hash >>> 16);
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

	private void ensureOverrideCapacity(final int required) {
		if (required <= this.overrideCells.length) {
			return;
		}
		int capacity = Math.max(required, this.overrideCells.length * 2);
		this.overrideCells = Arrays.copyOf(this.overrideCells, capacity);
		this.overridePalette = Arrays.copyOf(this.overridePalette, capacity);
	}

	private void ensureFluidOverrideCapacity(final int required) {
		if (required <= this.overrideFluidCells.length) {
			return;
		}
		int capacity = Math.max(required, this.overrideFluidCells.length * 2);
		this.overrideFluidCells = Arrays.copyOf(this.overrideFluidCells, capacity);
		this.overrideFluidPalette = Arrays.copyOf(this.overrideFluidPalette, capacity);
	}
}
