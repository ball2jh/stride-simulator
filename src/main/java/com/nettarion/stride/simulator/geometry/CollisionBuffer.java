package com.nettarion.stride.simulator.geometry;

import com.nettarion.stride.simulator.AABB;
import java.util.Arrays;

/**
 * Reusable primitive storage for collision shapes, grouped by source shape, with a selection over
 * those groups.
 *
 * <p>Shape gathering, not the collision arithmetic itself, was measured as the larger cost of a
 * tick. Keeping the six coordinates in primitive arrays avoids one {@link AABB} allocation per shape
 * and one list allocation per query. A buffer belongs to one stepping thread and is cleared by each
 * {@code WorldView} query; it is not thread-safe.
 *
 * <p>Each {@link #add} or {@link #addAt} starts one source collision-shape group; a
 * {@code WorldView} implementation fills the buffer it is handed with these. Additional boxes from
 * the same source shape use {@link #addPart}; the collision dead zone is applied between groups,
 * never between their boxes. Canonical full blocks ({@link #addCanonicalFullCube}) have a distinct
 * broadphase identity retained for span refiltering. Groups are stored as offsets into the box
 * arrays, so a group's extent is two loads rather than a scan, and each group names the cell that
 * owns it.
 *
 * <p>Readers walk the selected groups, in ascending group order: ordinary collection selects every
 * group as it is added, and a retained span is refiltered for a query by reselecting the groups the
 * query box reaches without copying a coordinate. A selected group's boxes are contiguous, so the
 * resolver reads them straight from the arrays. Coordinates are in blocks.
 */
public final class CollisionBuffer {
	private double[] minX;

	private double[] minY;

	private double[] minZ;

	private double[] maxX;

	private double[] maxY;

	private double[] maxZ;

	/**
	 * Per box, whether it opens its group. The resolver's inner loop ends a group on this flag
	 * instead of on a counted bound: that loop has a data-dependent exit, which keeps C2 from
	 * predicating it, and the predicated form measured a third slower on a 64-box group.
	 */
	private boolean[] boxStartsGroup;

	private int size;

	/** {@code groupStart[g]} is group g's first box; {@code groupStart[groupCount]} is {@link #size}. */
	private int[] groupStart;

	private boolean[] groupCanonicalFull;

	private int[] groupCellX;

	private int[] groupCellY;

	private int[] groupCellZ;

	private int groupCount;

	/** The selected groups, ascending. */
	private int[] selectedGroups;

	private int selectedCount;

	/** An empty buffer with room for 32 boxes before it grows. */
	public CollisionBuffer() {
		this(32);
	}

	/** An empty buffer with room for {@code initialCapacity} boxes (at least one) before it grows. */
	public CollisionBuffer(final int initialCapacity) {
		int capacity = Math.max(1, initialCapacity);
		this.minX = new double[capacity];
		this.minY = new double[capacity];
		this.minZ = new double[capacity];
		this.maxX = new double[capacity];
		this.maxY = new double[capacity];
		this.maxZ = new double[capacity];
		this.boxStartsGroup = new boolean[capacity];
		this.groupStart = new int[capacity + 1];
		this.groupCanonicalFull = new boolean[capacity];
		this.groupCellX = new int[capacity];
		this.groupCellY = new int[capacity];
		this.groupCellZ = new int[capacity];
		this.selectedGroups = new int[capacity];
	}

	/** Forgets every box, group and selection; capacity is kept. */
	public void clear() {
		this.size = 0;
		this.groupCount = 0;
		this.groupStart[0] = 0;
		this.selectedCount = 0;
	}

	/**
	 * Starts a source shape group with this box. The owning cell is the box's midpoint cell, which is
	 * the owner for every cell-bounded shape; a provider that knows the cell uses {@link #addAt}.
	 * This is the entry a {@code WorldView.collectCollisionBoxes} implementation calls per shape.
	 */
	public void add(final double minX, final double minY, final double minZ, final double maxX, final double maxY,
	    final double maxZ) {
		beginGroup(
		    Mth.floor((minX + maxX) * 0.5), Mth.floor((minY + maxY) * 0.5), Mth.floor((minZ + maxZ) * 0.5), false);
		addBox(minX, minY, minZ, maxX, maxY, maxZ);
	}

	/** Starts a source shape group owned by the cell {@code (x, y, z)} with this box. */
	public void addAt(final int x, final int y, final int z, final double minX, final double minY, final double minZ,
	    final double maxX, final double maxY, final double maxZ) {
		beginGroup(x, y, z, false);
		addBox(minX, minY, minZ, maxX, maxY, maxZ);
	}

	/**
	 * Appends another box belonging to the most recently added source shape.
	 *
	 * @throws IllegalStateException when no group has been started or the current group is a
	 *         canonical full cube, which has exactly one box
	 */
	public void addPart(final double minX, final double minY, final double minZ, final double maxX, final double maxY,
	    final double maxZ) {
		if (this.groupCount == 0) {
			throw new IllegalStateException("a collision group must start with add");
		}
		if (this.groupCanonicalFull[this.groupCount - 1]) {
			throw new IllegalStateException("a canonical full group has exactly one box");
		}
		addBox(minX, minY, minZ, maxX, maxY, maxZ);
	}

	/** Adds vanilla's canonical singleton full-block shape at one cell. */
	public void addCanonicalFullCube(final int x, final int y, final int z) {
		beginGroup(x, y, z, true);
		addBox(x, y, z, x + 1.0, y + 1.0, z + 1.0);
	}

	private void beginGroup(final int x, final int y, final int z, final boolean canonicalFull) {
		if (this.groupCount == this.groupCanonicalFull.length) {
			growGroups();
		}
		int group = this.groupCount++;
		this.groupStart[group] = this.size;
		this.groupStart[group + 1] = this.size;
		this.groupCanonicalFull[group] = canonicalFull;
		this.groupCellX[group] = x;
		this.groupCellY[group] = y;
		this.groupCellZ[group] = z;
		// Ordinary collection is its own selection.
		this.selectedGroups[this.selectedCount++] = group;
	}

	private void addBox(final double minX, final double minY, final double minZ, final double maxX, final double maxY,
	    final double maxZ) {
		if (this.size == this.minX.length) {
			growBoxes();
		}
		int at = this.size++;
		this.boxStartsGroup[at] = this.groupStart[this.groupCount - 1] == at;
		this.minX[at] = minX;
		this.minY[at] = minY;
		this.minZ[at] = minZ;
		this.maxX[at] = maxX;
		this.maxY[at] = maxY;
		this.maxZ[at] = maxZ;
		this.groupStart[this.groupCount] = this.size;
	}

	/** Every box held, selected or not. */
	public int size() {
		return this.size;
	}

	/** Whether no group is selected: the query reached nothing. */
	public boolean isEmpty() {
		return this.selectedCount == 0;
	}

	/** The minimum X face of the box at {@code index}, in blocks. */
	public double minX(final int index) {
		return this.minX[index];
	}

	/** The minimum Y face of the box at {@code index}, in blocks. */
	public double minY(final int index) {
		return this.minY[index];
	}

	/** The minimum Z face of the box at {@code index}, in blocks. */
	public double minZ(final int index) {
		return this.minZ[index];
	}

	/** The maximum X face of the box at {@code index}, in blocks. */
	public double maxX(final int index) {
		return this.maxX[index];
	}

	/** The maximum Y face of the box at {@code index}, in blocks. */
	public double maxY(final int index) {
		return this.maxY[index];
	}

	/** The maximum Z face of the box at {@code index}, in blocks. */
	public double maxZ(final int index) {
		return this.maxZ[index];
	}

	/** Every group held, selected or not. */
	int groupCount() {
		return this.groupCount;
	}

	/** The index of the group's first box. */
	public int groupStart(final int group) {
		return this.groupStart[group];
	}

	/** One past the group's last box. */
	public int groupEnd(final int group) {
		return this.groupStart[group + 1];
	}

	boolean groupCanonicalFull(final int group) {
		return this.groupCanonicalFull[group];
	}

	int groupCellX(final int group) {
		return this.groupCellX[group];
	}

	int groupCellY(final int group) {
		return this.groupCellY[group];
	}

	int groupCellZ(final int group) {
		return this.groupCellZ[group];
	}

	/** How many groups the last query selected. */
	public int selectedGroupCount() {
		return this.selectedCount;
	}

	/** The {@code k}th selected group; selections ascend. */
	public int selectedGroup(final int k) {
		return this.selectedGroups[k];
	}

	/** The first box of the {@code k}th selected group. */
	int selectedGroupStart(final int k) {
		return this.groupStart[this.selectedGroups[k]];
	}

	/** Forget the selection, keeping every group, before reselecting for a query. */
	void clearSelection() {
		this.selectedCount = 0;
	}

	/** Select a group; callers select in ascending order. */
	void select(final int group) {
		this.selectedGroups[this.selectedCount++] = group;
	}

	/** Whether this box is the first of its group. */
	boolean startsGroup(final int index) {
		return this.boxStartsGroup[index];
	}

	private void growBoxes() {
		int capacity = this.minX.length * 2;
		this.minX = Arrays.copyOf(this.minX, capacity);
		this.minY = Arrays.copyOf(this.minY, capacity);
		this.minZ = Arrays.copyOf(this.minZ, capacity);
		this.maxX = Arrays.copyOf(this.maxX, capacity);
		this.maxY = Arrays.copyOf(this.maxY, capacity);
		this.maxZ = Arrays.copyOf(this.maxZ, capacity);
		this.boxStartsGroup = Arrays.copyOf(this.boxStartsGroup, capacity);
	}

	private void growGroups() {
		int capacity = this.groupCanonicalFull.length * 2;
		this.groupStart = Arrays.copyOf(this.groupStart, capacity + 1);
		this.groupCanonicalFull = Arrays.copyOf(this.groupCanonicalFull, capacity);
		this.groupCellX = Arrays.copyOf(this.groupCellX, capacity);
		this.groupCellY = Arrays.copyOf(this.groupCellY, capacity);
		this.groupCellZ = Arrays.copyOf(this.groupCellZ, capacity);
		this.selectedGroups = Arrays.copyOf(this.selectedGroups, capacity);
	}
}
