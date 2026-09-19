package com.nettarion.stride.simulator.world;

import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.block.BlockBehavior;
import java.util.List;

/**
 * The per-entry facts of one snapshot's palettes, compiled once into parallel
 * arrays indexed by palette index, and the whole-palette flags folded from them.
 *
 * <p>One instance backs a {@link SnapshotView} and every fork of it: nothing
 * here changes after compilation, except the entity-inside shapes a
 * {@link BlockStateCatalog} installs before the first view over it is handed
 * out. Reading a cell's facts through these arrays is what keeps the hot paths
 * off the {@link BlockEntry} records.
 */
final class PaletteTables {
	/** {@link #suffocation} for an entry whose predicate the capture did not record. */
	static final byte SUFFOCATION_UNKNOWN = 0;

	/** {@link #suffocation} for an entry that does not suffocate. */
	static final byte SUFFOCATION_NO = 1;

	/** {@link #suffocation} for an entry that suffocates. */
	static final byte SUFFOCATION_YES = 2;

	/*
	 * Shape classes, so the common cells never touch the shape arrays at all.
	 *
	 * Reading a cell's geometry through double[][] is three dependent loads:
	 * cell to palette index, index to array reference, reference to contents.
	 * The overwhelming majority of cells are either empty or a plain unit cube
	 * whose six coordinates are already known from the loop counters, so
	 * classifying the palette once turns those cells into a byte compare.
	 * 0.0 + x is exactly x and 1.0 + x is exactly x + 1.0, so the boxes a
	 * full cube emits are bit-identical either way.
	 */

	/** {@link #shapeClass} of an entry with no collision box. */
	static final byte SHAPE_EMPTY = 0;

	/** {@link #shapeClass} of an entry whose shape is vanilla's canonical full cube. */
	static final byte SHAPE_FULL_CUBE = 1;

	/** {@link #shapeClass} of every other entry. */
	static final byte SHAPE_GENERAL = 2;

	/** Palette index to ordered min/max sextuples, in block-local coordinates. */
	final double[][] shapes;

	final byte[] shapeClass;

	/** Whether any box of the entry leaves its cell. */
	final boolean[] largeShape;

	final float[] friction;

	final float[] speedFactor;

	final float[] jumpFactor;

	final boolean[] suppressesSupportingSpeedFactor;

	final WorldView.BubbleColumnMode[] bubbleColumnMode;

	final WorldView.Climbability[] climbability;

	final boolean[] fallDistanceResettingBlock;

	final byte[] suffocation;

	final WorldView.InsideEffect[] insideEffect;

	final float[] bounceRestitution;

	final boolean[] suppressesBounce;

	/** Each entry's {@link BlockBehavior}, resolved once; a catalog may replace cauldron entries' before freezing. */
	final BlockBehavior[] behavior;

	final boolean[] retainsSupportPos;

	final WorldView.CollisionBehavior[] collisionBehavior;

	/** Whether the entry is one of vanilla's three air block states, by name. */
	final boolean[] air;

	/** Whether the entry can contribute a collision in some entity context. */
	final boolean[] collisionPotential;

	/** The {@link SpanQueries} property bits the entry carries. */
	final short[] properties;

	final FluidSample.Kind[] fluidKind;

	final double[] fluidHeight;

	final double[] fluidFlowX;

	final double[] fluidFlowY;

	final double[] fluidFlowZ;

	final boolean[] fluidSource;

	/** Whether any entry resolves its shape from player context: scaffolding or powder snow. */
	final boolean hasContextSensitiveCollision;

	/** Whether any entry's captured collision geometry the server can mutate. */
	final boolean hasServerMutableCollision;

	final boolean hasPowderSnow;

	final boolean hasBubbleColumns;

	final boolean hasClimbables;

	final boolean hasNonDefaultFriction;

	final boolean hasNonDefaultSpeedFactor;

	final boolean hasNonDefaultJumpFactor;

	final boolean hasAnyLargeShape;

	/** Whether any entry with geometry has a suffocation predicate other than no. */
	final boolean hasAnySuffocation;

	/** The union of every entry's property bits, with the fluid bit when the snapshot has a fluid plane. */
	final short wholeViewProperties;

	/** Whether the snapshot selects a non-empty fluid anywhere, or holds a missing section. */
	final boolean hasFluidCells;

	/** Catalog-extracted entity-inside shapes by palette index, or null when no catalog installed them. */
	double[][] sourceInsideShapes;

	/** Whether the catalog-extracted entity-inside shape is vanilla's canonical full cube. */
	boolean[] sourceInsideFull;

	private PaletteTables(final WorldSnapshot snapshot) {
		int size = snapshot.palette().size();
		this.shapes = new double[size][];
		this.shapeClass = new byte[size];
		this.largeShape = new boolean[size];
		this.friction = new float[size];
		this.speedFactor = new float[size];
		this.jumpFactor = new float[size];
		this.suppressesSupportingSpeedFactor = new boolean[size];
		this.bubbleColumnMode = new WorldView.BubbleColumnMode[size];
		this.climbability = new WorldView.Climbability[size];
		this.fallDistanceResettingBlock = new boolean[size];
		this.suffocation = new byte[size];
		this.insideEffect = new WorldView.InsideEffect[size];
		this.bounceRestitution = new float[size];
		this.suppressesBounce = new boolean[size];
		this.behavior = new BlockBehavior[size];
		this.retainsSupportPos = new boolean[size];
		this.collisionBehavior = new WorldView.CollisionBehavior[size];
		this.collisionPotential = new boolean[size];
		this.properties = new short[size];
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
			BlockEntry entry = snapshot.palette().get(i);
			if (entry.movingPiston()) {
				throw new IllegalArgumentException("moving-piston collision is outside the admitted domain");
			}
			List<ShapeBox> boxes = entry.boxes();
			double[] converted = new double[boxes.size() * 6];
			for (int b = 0; b < boxes.size(); b++) {
				ShapeBox box = boxes.get(b);
				int at = b * 6;
				converted[at] = box.minX();
				converted[at + 1] = box.minY();
				converted[at + 2] = box.minZ();
				converted[at + 3] = box.maxX();
				converted[at + 4] = box.maxY();
				converted[at + 5] = box.maxZ();
			}
			boolean large = SectionFactGrids.largeShape(entry);
			this.shapes[i] = converted;
			this.air[i] = isAirIdentity(entry.name());
			this.shapeClass[i] = classify(converted, entry.shapeProvenance());
			this.largeShape[i] = large;
			anyLarge |= large;
			this.friction[i] = entry.friction();
			this.speedFactor[i] = entry.speedFactor();
			this.jumpFactor[i] = entry.jumpFactor();
			this.suppressesSupportingSpeedFactor[i] = entry.suppressesSupportingSpeedFactor();
			this.bubbleColumnMode[i] = entry.bubbleColumnMode();
			anyBubbleColumn |= this.bubbleColumnMode[i] != WorldView.BubbleColumnMode.NONE;
			this.climbability[i] = entry.climbability();
			this.fallDistanceResettingBlock[i] = entry.fallDistanceResetting();
			anyClimbable |= entry.climbability() != WorldView.Climbability.NONE;
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
			this.behavior[i] = BlockBehavior.of(entry);
			this.retainsSupportPos[i] = entry.retainsSupportPos();
			anyFriction |= entry.friction() != 0.6F;
			anySpeedFactor |= entry.speedFactor() != 1.0F || entry.suppressesSupportingSpeedFactor();
			anyJumpFactor |= entry.jumpFactor() != 1.0F;
			this.collisionBehavior[i] = entry.collisionBehavior();
			// Scaffolding and powder snow resolve their shape from live player
			// context. A server-mutable cell consults no context: it refuses
			// whenever a query box overlaps its cell, so it must not disable
			// the context-free reuse paths for the rest of the world.
			anyContextSensitiveCollision |= this.collisionBehavior[i] != WorldView.CollisionBehavior.ORDINARY
			    && this.collisionBehavior[i] != WorldView.CollisionBehavior.SERVER_MUTABLE_SUPPORT;
			anyServerMutableCollision |=
			    this.collisionBehavior[i] == WorldView.CollisionBehavior.SERVER_MUTABLE_SUPPORT;
			anyPowderSnow |= this.collisionBehavior[i] == WorldView.CollisionBehavior.POWDER_SNOW_NO_BOOTS;
			this.collisionPotential[i] = SectionFactGrids.collisionPotential(entry);
			// One bit per behavior a cell of this entry could make the movement
			// path ask about, defined once with the section fact grids so a
			// section's mask is the same predicate over fewer cells.
			int bits = SectionFactGrids.propertyBits(entry);
			this.properties[i] = (short) bits;
			allProperties |= bits;
		}
		int fluidSize = snapshot.fluidPalette().size();
		this.fluidKind = new FluidSample.Kind[fluidSize];
		this.fluidHeight = new double[fluidSize];
		this.fluidFlowX = new double[fluidSize];
		this.fluidFlowY = new double[fluidSize];
		this.fluidFlowZ = new double[fluidSize];
		this.fluidSource = new boolean[fluidSize];
		boolean fluidCells = snapshot.hasFluids();
		for (int i = 0; i < fluidSize; i++) {
			FluidEntry entry = snapshot.fluidPalette().get(i);
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
		if (fluidCells) {
			allProperties |= WorldView.PROPERTY_FLUID;
		}
		if (snapshot.hasMissingSections()) {
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
			fluidCells = true;
		}
		this.wholeViewProperties = (short) allProperties;
		this.hasFluidCells = fluidCells;
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

	/**
	 * Compiles a snapshot's palettes.
	 *
	 * @throws IllegalArgumentException when an entry is a moving piston, which the simulator does not model
	 */
	static PaletteTables compile(final WorldSnapshot snapshot) {
		return new PaletteTables(snapshot);
	}

	/** Whether catalog-extracted entity-inside shapes were installed. */
	boolean hasSourceInsideShapes() {
		return this.sourceInsideShapes != null;
	}

	/** The three vanilla air block states, by registry name. */
	static boolean isAirIdentity(final String name) {
		return name.equals("minecraft:air") || name.equals("minecraft:cave_air") || name.equals("minecraft:void_air");
	}

	private static byte classify(final double[] shape, final ShapeProvenance provenance) {
		if (shape.length == 0) {
			return SHAPE_EMPTY;
		}
		return switch (provenance) {
			case CANONICAL_FULL -> SHAPE_FULL_CUBE;
			case GENERAL -> SHAPE_GENERAL;
			case LEGACY_GEOMETRY -> isLegacyFullCube(shape) ? SHAPE_FULL_CUBE : SHAPE_GENERAL;
		};
	}

	/** The geometry classifier snapshot formats through 13 relied on, for entries without recorded provenance. */
	private static boolean isLegacyFullCube(final double[] shape) {
		return shape.length == 6 && shape[0] == 0.0 && shape[1] == 0.0 && shape[2] == 0.0 && shape[3] == 1.0
		    && shape[4] == 1.0 && shape[5] == 1.0;
	}
}
