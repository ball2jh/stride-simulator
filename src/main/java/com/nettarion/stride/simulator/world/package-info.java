/**
 * The world as the simulator reads it: immutable captured facts compiled into the query views
 * the tick calls.
 *
 * <p>{@link com.nettarion.stride.simulator.world.WorldView} is the read interface every phase
 * calls. {@link com.nettarion.stride.simulator.world.WorldSnapshot} is the data: a region of
 * block states with shapes and coefficients, stored as a grid of immutable 16-cube
 * {@link com.nettarion.stride.simulator.world.Section}s as vanilla's level is, each carrying
 * its own coarse facts. A composition or crop on section boundaries shares its sources'
 * sections, so a producer that captures sections through one
 * {@link com.nettarion.stride.simulator.world.SharedPalette} republishes a corridor at the cost
 * of the sections that changed; a uniform section holds no plane, so air is free.
 * {@link com.nettarion.stride.simulator.world.SnapshotView} compiles a snapshot into palette
 * tables and lays the sections' facts side by side as
 * {@link com.nettarion.stride.simulator.world.SectionSummary} grids, and forks a per-thread
 * overlay for edits.
 * {@link com.nettarion.stride.simulator.world.BlockStateCatalog} can verify portable block
 * facts against independently extracted source records during verified compilation; a tuple it cannot
 * identify as one admitted block behavior refuses. {@link com.nettarion.stride.simulator.FluidSample}
 * and {@link com.nettarion.stride.simulator.world.SupportCell} are caller-owned query results.
 * {@link com.nettarion.stride.simulator.world.FlatFloorView} is the test helper of the same shape.
 *
 * <p>Unknown space is distinct from air and refuses the transitions that read it: space beyond
 * the bounds, and a {@link com.nettarion.stride.simulator.world.Section#MISSING} section a
 * composition's parts did not cover, which is a hole and never padding. Every section carries a
 * process-unique revision, so a proof keyed on {@code WorldView.spanRevision} over the sections
 * it touched survives a republication that kept them. Mutation
 * requires explicit interaction permission and an admitted neighbor closure. This package owns
 * no tick and no block physics; it answers the questions the tick and the block behaviors ask.
 */
package com.nettarion.stride.simulator.world;
import com.nettarion.stride.simulator.FluidSample;
