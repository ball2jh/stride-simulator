/**
 * The world as the simulator reads it: captured block facts compiled into the
 * query views the tick calls.
 *
 * <p>A snapshot ({@link com.nettarion.stride.simulator.world.WorldSnapshot}) is
 * the immutable data: a bounded region of cells selecting into palettes of
 * {@link com.nettarion.stride.simulator.world.BlockEntry} and
 * {@link com.nettarion.stride.simulator.world.FluidEntry}. A view
 * ({@link com.nettarion.stride.simulator.world.WorldView}, implemented by
 * {@link com.nettarion.stride.simulator.world.SnapshotView}) is the read
 * interface the tick queries, compiled from a snapshot. A catalog
 * ({@link com.nettarion.stride.simulator.world.BlockStateCatalog}) is a set of
 * block records extracted from Minecraft independently of any capture, against
 * which a snapshot's palette is verified before compilation. A palette is the
 * list of distinct block or fluid states a snapshot's cells index into, and
 * {@link com.nettarion.stride.simulator.world.SharedPalette} is one that a
 * producer grows across captures so its sections need no remapping. A section
 * is one immutable 16-cube of a snapshot's storage, as vanilla's level is
 * made of, shared by every snapshot composed or cropped from the same parts.
 *
 * <p>To build a world: {@code WorldSnapshot.builder(...)}, declare the
 * palettes and set the cells, {@code build()}, then
 * {@code SnapshotView.compile(snapshot)}; the frozen view is safe to share,
 * and {@code fork()} gives a worker its own editable copy. A producer with
 * captured planes uses {@code WorldSnapshot.owning} or {@code compose}.
 *
 * <p>Unknown space is never air. Space outside a snapshot's bounds is what its
 * {@link com.nettarion.stride.simulator.world.OutsidePolicy} declares, and a
 * section a composition's parts did not cover is a hole that every cell read
 * refuses across. {@link com.nettarion.stride.simulator.world.FlatFloorView}
 * is the synthetic reference view: a uniform floor with no snapshot behind it.
 * This package owns no tick and no block physics; it answers the questions the
 * tick and the block behaviors ask.
 */
package com.nettarion.stride.simulator.world;
