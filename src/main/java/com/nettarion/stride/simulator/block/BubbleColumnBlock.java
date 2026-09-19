package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.tick.EntityFluidInteraction;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * A bubble column: {@code BubbleColumnBlock.entityInside} through
 * {@code Entity.onAboveBubbleColumn} and {@code onInsideBubbleColumn},
 * dragging the player down or pushing it up, harder when nothing sits above
 * the cell.
 *
 * <p>The body applies immediately at the visit and only when the
 * destination box overlaps the cell ({@code isPrecise}); it is the one body
 * {@code Player.aiStep} skips for a flying player, so that gate is here. The
 * cell above is asked for a collision shape and a fluid, as vanilla asks for
 * the above state's context-free collision shape and fluid state.
 */
public final class BubbleColumnBlock extends BlockBehaviour {
	/** {@code drag = true}: the column pulls the player down. */
	public static final BubbleColumnBlock DRAG_DOWN = new BubbleColumnBlock(true);
	/** {@code drag = false}: the column pushes the player up. */
	public static final BubbleColumnBlock PUSH_UP = new BubbleColumnBlock(false);

	private final boolean drag;

	private BubbleColumnBlock(final boolean drag) {
		this.drag = drag;
	}

	@Override
	int families() {
		return BUBBLE;
	}

	@Override
	boolean hasEntityInside() {
		return true;
	}

	@Override
	void entityInside(final PlayerState state, final int x, final int y, final int z, final boolean isPrecise,
	    final WorldView world, final Scratch scratch) {
		if (state.flying || !isPrecise) {
			return;
		}
		boolean nothingAbove = !collisionShapeAbove(world, x, y + 1, z)
		    && EntityFluidInteraction.kindAt(world, x, y + 1, z, scratch) == FluidSample.Kind.EMPTY;
		if (nothingAbove) {
			if (this.drag) {
				state.deltaMovementY = Math.max(-0.9, state.deltaMovementY - 0.03);
			} else {
				state.deltaMovementY = Math.min(1.8, state.deltaMovementY + 0.1);
			}
		} else {
			if (this.drag) {
				state.deltaMovementY = Math.max(-0.3, state.deltaMovementY - 0.03);
			} else {
				state.deltaMovementY = Math.min(0.7, state.deltaMovementY + 0.06);
			}
			state.fallDistance = 0.0;
		}
	}

	/**
	 * {@code stateAbove.getCollisionShape(level, pos).isEmpty()}, negated: the
	 * context-free overload, {@code CollisionContext.empty()}, whose answer
	 * for a context-sensitive block is a function of the state alone and not
	 * of the player the capture recorded the cell's boxes against.
	 * Scaffolding's {@code isAbove} takes the empty context's default of true
	 * and the empty context never descends, so it is the stable shape and a
	 * collision; powder snow's entity branch needs an entity the empty
	 * context lacks, so it is empty. Every other cell's captured shape is its
	 * context-free shape.
	 */
	private static boolean collisionShapeAbove(final WorldView world, final int x, final int y, final int z) {
		return switch (world.collisionBehaviorAt(x, y, z)) {
			case SCAFFOLDING_SUPPORTED, SCAFFOLDING_UNSTABLE_BOTTOM -> true;
			case POWDER_SNOW_NO_BOOTS -> false;
			default -> world.hasCollisionShapeAt(x, y, z);
		};
	}

	@Override
	public String toString() {
		return this.drag ? "bubble column dragging down" : "bubble column pushing up";
	}
}
