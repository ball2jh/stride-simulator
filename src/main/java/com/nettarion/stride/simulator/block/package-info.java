/**
 * Block behaviours keyed by palette entry, and the traversal that applies them inside one tick.
 *
 * <p>{@link com.nettarion.stride.simulator.block.BlockBehaviour} dispatches each admitted tuple to
 * one upstream owner: a singleton behaviour per block class with its captured coefficients and
 * the {@code entityInside}, {@code stepOn}, {@code fallOn}, speed and jump hooks the source gives
 * it. A tuple with no admitted behaviour compiles to
 * {@link com.nettarion.stride.simulator.block.UnmodelledBlock} or
 * {@link com.nettarion.stride.simulator.block.UnknownStateBlock} and refuses at its first required
 * transition; the simulator never combines unrelated callbacks.
 * {@link com.nettarion.stride.simulator.block.BlockEffects} is the phase entry
 * ({@code applyEffectsFromBlocks}); {@link com.nettarion.stride.simulator.block.InsideBlockTraversal}
 * walks the cells a movement intersects in source order over one visited set, and
 * {@link com.nettarion.stride.simulator.block.InsideBlockEffectCollector} queues server effects
 * until traversal finishes, so immediate callbacks precede collected damage.
 *
 * <p>Sounds, particles and statistics are omitted only where they cannot alter admitted movement
 * or required randomness. Ambiguous grouping refuses. Adding a block is adding one behaviour here
 * with one source-derived test and one oracle pairing; no branch on a block name belongs in the tick.
 */
package com.nettarion.stride.simulator.block;
