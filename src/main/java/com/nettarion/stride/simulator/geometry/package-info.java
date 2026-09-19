/**
 * Scalar geometry: vanilla's math, and the collision buffers the tick resolves a displacement
 * against.
 *
 * <p>{@link com.nettarion.stride.simulator.geometry.Mth} is vanilla's math class in scalar form;
 * {@link com.nettarion.stride.simulator.AABB} in the root package is its box.
 * {@link com.nettarion.stride.simulator.geometry.CollisionBuffer} holds boxes grouped by source
 * shape. {@link com.nettarion.stride.simulator.geometry.CollisionCollector} retains one span of
 * cells per {@code Scratch} ({@link com.nettarion.stride.simulator.geometry.CollisionQuerySpan},
 * {@link com.nettarion.stride.simulator.geometry.RetainedSpan}) and answers a query inside it by
 * reselecting the groups the box reaches, so no box is copied per query.
 * {@link com.nettarion.stride.simulator.geometry.ShapeCollision} resolves an axis-ordered movement
 * against a collected shape set; its three axis orientations are generated during compilation from
 * the single rule in {@code src/main/templates/ShapeCollision.java.template}. Edit the template.
 *
 * <p>Dependencies: the collector reads a {@code WorldView} for cells and shapes and keeps its
 * buffers in the tick's {@code Scratch}; nothing here reads a {@code PlayerState}. The tick hands
 * in the box and the player's collision context (feet, descent, fall distance, boots) as scalars.
 * Grouped buffers and reselection are measured implementation choices, not a claim about the
 * game's shape API.
 */
package com.nettarion.stride.simulator.geometry;
