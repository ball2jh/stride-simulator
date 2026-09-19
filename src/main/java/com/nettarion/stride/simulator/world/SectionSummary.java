package com.nettarion.stride.simulator.world;

/**
 * The flat grids a {@link SnapshotView} answers its coarse questions from:
 * per 16-cube section, how many cells can collide, which behavior bits any
 * cell carries and whether any shape leaves its cell; the same bits and flag
 * per fine cube; and per section the bits every fine cube inside it carries.
 *
 * <p>Every fact here is a {@link Section}'s own, scanned once when the
 * section was made and carried with it through every composition and crop.
 * This type only lays the sections' facts side by side in the grid order the
 * view indexes, five small copies per section, so a view over a corridor of
 * millions of cells compiles without reading one.
 *
 * <p>A view shares these arrays read-only and copies the family it edits
 * before the first write, as forks already do.
 */
final class SectionSummary {
	/** Edge of a fine cube, as a power of two. */
	static final int FINE_SHIFT = 2;
	static final int FINE = 1 << FINE_SHIFT;
	static final int SECTION = Section.EDGE;
	/** Fine cubes along one edge of a section. */
	static final int FINES_PER_SECTION = SECTION / FINE;
	/** The one behavior bit a view derives rather than reads from a palette entry. */
	static final int PROPERTY_SERVER_MUTABLE = 1 << 10;

	final int sectionsX;
	final int sectionsY;
	final int sectionsZ;
	final int finesX;
	final int finesY;
	final int finesZ;
	/** Per section: cells whose entry can collide in some entity context. */
	final int[] counts;
	/** Per section: the union of its cells' behavior bits. */
	final short[] properties;
	/** Per section: whether any cell's shape leaves its own cell. */
	final boolean[] large;
	final short[] fineProperties;
	final boolean[] fineLarge;
	/** Per section: the bits every one of its fine cubes carries. */
	final short[] uniform;

	private SectionSummary(final SectionGrid grid) {
		this.sectionsX = grid.sectionsX();
		this.sectionsY = grid.sectionsY();
		this.sectionsZ = grid.sectionsZ();
		this.finesX = this.sectionsX * FINES_PER_SECTION;
		this.finesY = this.sectionsY * FINES_PER_SECTION;
		this.finesZ = this.sectionsZ * FINES_PER_SECTION;
		int sections = grid.count();
		this.counts = new int[sections];
		this.properties = new short[sections];
		this.large = new boolean[sections];
		this.uniform = new short[sections];
		int fines = this.finesX * this.finesY * this.finesZ;
		this.fineProperties = new short[fines];
		this.fineLarge = new boolean[fines];
	}

	/** The grids of a snapshot's sections laid side by side. */
	static SectionSummary of(final WorldSnapshot snapshot) {
		SectionGrid grid = snapshot.grid();
		Section[] sections = snapshot.sections();
		SectionSummary summary = new SectionSummary(grid);
		for (int sy = 0; sy < grid.sectionsY(); sy++) {
			for (int sz = 0; sz < grid.sectionsZ(); sz++) {
				for (int sx = 0; sx < grid.sectionsX(); sx++) {
					int at = grid.index(sx, sy, sz);
					Section section = sections[at];
					summary.counts[at] = section.collisionCount;
					summary.properties[at] = section.properties;
					summary.large[at] = section.large;
					summary.uniform[at] = section.uniformProperties;
					for (int fy = 0; fy < FINES_PER_SECTION; fy++) {
						for (int fz = 0; fz < FINES_PER_SECTION; fz++) {
							int source = (fy * FINES_PER_SECTION + fz) * FINES_PER_SECTION;
							int target = summary.fine(
							    sx * FINES_PER_SECTION, sy * FINES_PER_SECTION + fy, sz * FINES_PER_SECTION + fz);
							System.arraycopy(
							    section.fineProperties, source, summary.fineProperties, target, FINES_PER_SECTION);
							System.arraycopy(section.fineLarge, source, summary.fineLarge, target, FINES_PER_SECTION);
						}
					}
				}
			}
		}
		return summary;
	}

	/** Whether any cell selects a fluid: the fluid bit of any section. */
	boolean anyFluid() {
		for (short bits : this.properties) {
			if ((bits & WorldView.PROPERTY_FLUID) != 0) {
				return true;
			}
		}
		return false;
	}

	int section(final int sectionX, final int sectionY, final int sectionZ) {
		return (sectionY * this.sectionsZ + sectionZ) * this.sectionsX + sectionX;
	}

	int fine(final int fineX, final int fineY, final int fineZ) {
		return (fineY * this.finesZ + fineZ) * this.finesX + fineX;
	}

	/*
	 * The per-entry facts a cell contributes. One definition, read by the view
	 * for its palette tables and by the section scan, so the two cannot drift.
	 */

	/** Whether a cell of this entry can contribute a collision in some entity context. */
	static boolean collisionPotential(final BlockEntry entry) {
		return !entry.boxes().isEmpty() || entry.collisionBehavior() != WorldView.CollisionBehavior.ORDINARY;
	}

	/** Whether any box of this entry leaves the unit cell. */
	static boolean largeShape(final BlockEntry entry) {
		for (ShapeBox box : entry.boxes()) {
			if (box.minX() < 0.0 || box.minY() < 0.0 || box.minZ() < 0.0 || box.maxX() > 1.0 || box.maxY() > 1.0
			    || box.maxZ() > 1.0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * One bit per behavior a cell of this entry could make the movement path
	 * ask about, as {@link WorldView#propertiesIn} reports them. The fluid bit is
	 * a cell's fluid entry's, not the block's, and is added by the scan.
	 */
	static int propertyBits(final BlockEntry entry) {
		int properties = 0;
		if (entry.bubbleColumnMode() != WorldView.BubbleColumnMode.NONE) {
			properties |= WorldView.PROPERTY_BUBBLE_COLUMN;
		}
		if (entry.climbability() != WorldView.Climbability.NONE) {
			properties |= WorldView.PROPERTY_CLIMBABLE;
		}
		if (entry.friction() != 0.6F) {
			properties |= WorldView.PROPERTY_FRICTION;
		}
		if (entry.speedFactor() != 1.0F || entry.suppressesSupportingSpeedFactor()) {
			properties |= WorldView.PROPERTY_SPEED_FACTOR;
		}
		if (entry.jumpFactor() != 1.0F) {
			properties |= WorldView.PROPERTY_JUMP_FACTOR;
		}
		if (entry.collisionBehavior() == WorldView.CollisionBehavior.POWDER_SNOW_NO_BOOTS) {
			properties |= WorldView.PROPERTY_POWDER_SNOW;
		}
		if (entry.collisionBehavior() == WorldView.CollisionBehavior.SERVER_MUTABLE_SUPPORT) {
			properties |= PROPERTY_SERVER_MUTABLE;
		}
		if (entry.insideEffect() != WorldView.InsideEffect.NONE) {
			properties |= WorldView.PROPERTY_INSIDE_EFFECT;
		}
		// Suppression rides with the bounce bit rather than having one of its
		// own: honey suppresses and never bounces, so a span holding only honey
		// still has to reach restituteMovementAfterCollisions to be told no.
		if (entry.bounceRestitution() != 0.0F || entry.suppressesBounce()) {
			properties |= WorldView.PROPERTY_BOUNCE;
		}
		if (entry.stepOn() != WorldView.StepOn.NONE) {
			properties |= WorldView.PROPERTY_STEP_ON;
		}
		if (entry.contact() != WorldView.Contact.NONE) {
			properties |= WorldView.PROPERTY_CONTACT;
		}
		return properties;
	}
}
