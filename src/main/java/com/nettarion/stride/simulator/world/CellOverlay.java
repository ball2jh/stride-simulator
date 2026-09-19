package com.nettarion.stride.simulator.world;

import java.util.Arrays;

/**
 * The sparse cell-to-palette-index replacements one {@link SnapshotView} holds
 * over its snapshot: a cell number and the index it now selects, for the block
 * plane or the fluid plane.
 *
 * <p>Entries are found by a linear scan while there are few of them, which is
 * every ordinary replay, and by an open-addressed hash index once there are
 * more than {@link #HASH_THRESHOLD}. Not thread-safe: the view that owns it
 * is worker-confined once it can be edited.
 */
final class CellOverlay {
	/** Linear lookup wins for the tiny edit sets of normal replay. */
	private static final int HASH_THRESHOLD = 16;

	private static final int INITIAL_CAPACITY = 4;

	private int[] cells = new int[INITIAL_CAPACITY];

	private int[] values = new int[INITIAL_CAPACITY];

	/** Open-addressed slots holding {@code cells} positions plus one, or null while the scan is linear. */
	private int[] index;

	private int count;

	CellOverlay() {}

	private CellOverlay(final CellOverlay source) {
		this.cells = Arrays.copyOf(source.cells, Math.max(INITIAL_CAPACITY, source.count));
		this.values = Arrays.copyOf(source.values, Math.max(INITIAL_CAPACITY, source.count));
		this.index = source.index == null ? null : Arrays.copyOf(source.index, source.index.length);
		this.count = source.count;
	}

	/** An independent copy for a fork: later edits to either side are invisible to the other. */
	CellOverlay copy() {
		return new CellOverlay(this);
	}

	/** How many cells are replaced. */
	int size() {
		return this.count;
	}

	/** The cell number replaced at a position below {@link #size()}. */
	int cellAt(final int position) {
		return this.cells[position];
	}

	/** The palette index selected at a position below {@link #size()}. */
	int valueAt(final int position) {
		return this.values[position];
	}

	/** The position of a cell's replacement, or {@code -1} when the cell is not replaced. */
	int find(final int cell) {
		if (this.index != null) {
			return findIndexed(cell, this.cells, this.index);
		}
		for (int i = 0; i < this.count; i++) {
			if (this.cells[i] == cell) {
				return i;
			}
		}
		return -1;
	}

	/** Changes the index selected at an existing position. */
	void setAt(final int position, final int value) {
		this.values[position] = value;
	}

	/** Records a replacement for a cell that has none. */
	void append(final int cell, final int value) {
		ensureCapacity(this.count + 1);
		this.cells[this.count] = cell;
		this.values[this.count] = value;
		this.count++;
		indexAppended(cell, this.count - 1);
	}

	/** Drops the replacement at a position, shifting later ones down. */
	void removeAt(final int position) {
		System.arraycopy(this.cells, position + 1, this.cells, position, this.count - position - 1);
		System.arraycopy(this.values, position + 1, this.values, position, this.count - position - 1);
		this.count--;
		rebuildIndex();
	}

	private static int findIndexed(final int cell, final int[] cells, final int[] index) {
		int mask = index.length - 1;
		int slot = hash(cell) & mask;
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

	private void indexAppended(final int cell, final int at) {
		if (this.count <= HASH_THRESHOLD) {
			return;
		}
		if (this.index == null || this.count * 2 > this.index.length) {
			rebuildIndex();
			return;
		}
		insertIndex(cell, at, this.index);
	}

	private void rebuildIndex() {
		this.index = buildIndex(this.cells, this.count);
	}

	private static int[] buildIndex(final int[] cells, final int count) {
		if (count <= HASH_THRESHOLD) {
			return null;
		}
		int capacity = 1;
		while (capacity < count * 2) {
			capacity <<= 1;
		}
		int[] index = new int[capacity];
		for (int at = 0; at < count; at++) {
			insertIndex(cells[at], at, index);
		}
		return index;
	}

	private static void insertIndex(final int cell, final int at, final int[] index) {
		int mask = index.length - 1;
		int slot = hash(cell) & mask;
		while (index[slot] != 0) {
			slot = (slot + 1) & mask;
		}
		index[slot] = at + 1;
	}

	private static int hash(final int cell) {
		int hash = cell * 0x9E3779B9;
		return hash ^ (hash >>> 16);
	}

	private void ensureCapacity(final int required) {
		if (required <= this.cells.length) {
			return;
		}
		int capacity = Math.max(required, this.cells.length * 2);
		this.cells = Arrays.copyOf(this.cells, capacity);
		this.values = Arrays.copyOf(this.values, capacity);
	}
}
