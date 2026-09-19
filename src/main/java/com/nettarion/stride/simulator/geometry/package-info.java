/**
 * Scalar geometry: boxes, pinned arithmetic, and the collision buffers the tick resolves
 * displacement against.
 *
 * <p>{@link com.nettarion.stride.simulator.AABB} and
 * {@link com.nettarion.stride.simulator.geometry.Mth} are the source's box and math utilities
 * in scalar form. {@link com.nettarion.stride.simulator.geometry.CollisionBuffer} holds grouped
 * shapes; {@link com.nettarion.stride.simulator.geometry.CollisionCollector} retains one span of
 * cells per workspace ({@link com.nettarion.stride.simulator.geometry.CollisionQuerySpan},
 * {@link com.nettarion.stride.simulator.geometry.RetainedSpan}) and answers a query inside it by
 * reselecting the groups the box reaches, so no box is copied per query.
 * {@link com.nettarion.stride.simulator.geometry.ShapeCollision} resolves an axis-ordered movement
 * against a collected shape set; its three axis orientations are generated during compilation from
 * the single rule in {@code src/main/templates/ShapeCollision.java.template}. Edit the template.
 *
 * <p>Nothing here knows the player or the world: a world view hands in the cells, and the tick
 * hands in the box. Grouped buffers and reselection are measured implementation choices, not a
 * claim about the game's shape API.
 */
package com.nettarion.stride.simulator.geometry;
import com.nettarion.stride.simulator.AABB;
