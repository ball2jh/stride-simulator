/**
 * Block behaviors keyed by palette entry, and the traversal that applies them inside one tick.
 *
 * <p>A behavior ({@link com.nettarion.stride.simulator.block.BlockBehavior}) is the set of vanilla movement hooks
 * one block overrides and the tick calls on a player who reaches it. A hook is the implementation a block runs
 * for {@code entityInside} (on the client and on the server's copy), {@code stepOn}, or {@code fallOn}, plus the
 * {@code getEntityInsideCollisionShape} that decides whether a sweep reached the block at all. Speed and jump
 * factors, friction, and bounce restitution are palette coefficients the tick reads directly; they are not hooks
 * and no behavior carries them. Refusal messages call a hook's implementation its body: "the contact body" is
 * the {@code entityInside} a block runs on the server's copy.
 *
 * <p>A behavior is admitted from a palette entry's facts by {@code BlockBehavior.of}: every fact names the block
 * that owns the hook, and the block's singleton answers only when it owns exactly the facts present. Facts naming
 * two different blocks, or an incomplete set for one block, refuse at compile time. A contact or landing fact the
 * capture marked unmodeled, or left unrecorded, resolves to a sentinel behavior that refuses on contact and never
 * at compile time, so a world holding such a block still steps until the player reaches it; the sentinel carries
 * which hook families it refuses, so an ordinary landing beside an unmodeled contact still lands ordinarily.
 *
 * <p>Two exceptions to the one-singleton-per-block rule: a layered water or powder-snow cauldron is one instance
 * per catalog entry, since its inside shape and successor state come from a
 * {@link com.nettarion.stride.simulator.world.BlockStateCatalog}; and a cell with no behavior at all falls back,
 * on the server's copy of a world that carries extracted inside shapes, to the world's own shape so the step index
 * advances as vanilla's would.
 *
 * <p>This package is exported for {@code BlockBehavior} and the results of
 * {@link com.nettarion.stride.simulator.world.WorldView#behaviorAt}. {@code InsideBlockTraversal},
 * {@code InsideBlockEffectCollector}, and {@code BlockEffects} are the tick's plumbing: one traversal and one
 * collector are owned by the tick's {@code Scratch}, and consumers never construct or call them.
 */
package com.nettarion.stride.simulator.block;
