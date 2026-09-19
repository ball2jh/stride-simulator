package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldView;
import java.util.Arrays;

/**
 * Which cells the box swept between two positions reached, in vanilla's
 * order and with vanilla's step indices: {@code Entity.checkInsideBlocks}
 * over {@code BlockGetter.forEachBlockIntersectedBetween}, the geometry
 * {@link BlockEffects} runs its cell visitor through.
 *
 * <p>Each sub-movement visits the box at its origin, then the cells a corner
 * of the destination box crosses on its way back along the travel vector
 * (the DDA of {@code addCollisionsAlongTravel}), then the box at its
 * destination, each pass in {@code Direction.axisStepOrder}. The step index
 * advances with the DDA and groups the effects the visitor collects. Two
 * visited sets are kept as vanilla keeps them: one over the tick and one
 * over the sub-movement.
 *
 * <p>Reads position, pose, and {@link Scratch}'s movement segment; writes
 * its own retained workspace. {@link BlockEffects.Contact} checks contact and
 * applies behaviours; this owner controls limits, duplicate suppression, and
 * step advancement between those operations. One instance belongs to one
 * stepping thread and resets its visited sets for each traversal.
 */
public final class InsideBlockTraversal {
	private final BlockEffects.Contact contact = new BlockEffects.Contact();

	/*
	 * Endpoints of the sub-movement currently being traversed. checkInsideBlocks
	 * passes its own `from` and `to` to collidedWithShapeMovingFrom, which is not
	 * the tick's movement for a per-axis split, and not the destination box the
	 * cell was reached from either.
	 */

	private double sweepFromX;
	private double sweepFromY;
	private double sweepFromZ;
	private double sweepToX;
	private double sweepToY;
	private double sweepToZ;

	/*
	 * Inside-block traversal state. Two visited sets, exactly as vanilla keeps
	 * them: Entity.visitedBlocks spans the whole tick and gates the effects,
	 * while forEachBlockIntersectedBetween's own set spans one sub-movement and
	 * gates only its destination pass.
	 */

	private int[] tickVisitedCells = new int[3 * 32];
	private int tickVisitedCellCount;
	private int[] sweepVisitedCells = new int[3 * 32];
	private int sweepVisitedCellCount;
	/** The last step index the visitor accepted, as vanilla's AtomicInteger. */
	private int insideLastIteration;
	/** Whether this server traversal should query fluid contacts. */
	private boolean includeFluids;

	public InsideBlockTraversal() {}

	/**
	 * {@code Direction.axisStepOrder}, as axis indices: 0 is X, 1 is Y, 2 is Z.
	 * Y always leads; X and Z follow in the order of the larger component.
	 */
	private static final int[] AXIS_STEP_ORDER = {1, 2, 0, 1, 0, 2};

	/**
	 * {@code Entity.checkInsideBlocks}: the tick's recorded movement split into
	 * up to three axis-aligned sub-movements, each swept by {@link #sweep} over
	 * one shared visited set and one shared step collector, then the
	 * stationary sweep at the destination when the iteration budget ran out.
	 * The cell visitor is {@link #visit}.
	 *
	 * @param axisDependent whether {@code move()} recorded the segment, so the
	 *     sub-movements are ordered by the requested delta; a synthesised
	 *     movement is one sub-movement
	 * @param includeFluids whether the admitted server span can contain fluid contacts
	 */
	void traverse(final PlayerState state, final double fromX, final double fromY, final double fromZ, final double toX,
	    final double toY, final double toZ, final boolean axisDependent, final boolean includeFluids,
	    final WorldView world, final Scratch scratch) {
		begin();
		traverseRetainingVisited(
		    state, fromX, fromY, fromZ, toX, toY, toZ, axisDependent, includeFluids, world, scratch);
	}

	void begin() {
		this.tickVisitedCellCount = 0;
	}

	void traverseRetainingVisited(final PlayerState state, final double fromX, final double fromY, final double fromZ,
	    final double toX, final double toY, final double toZ, final boolean axisDependent, final boolean includeFluids,
	    final WorldView world, final Scratch scratch) {
		this.sweepVisitedCellCount = 0;
		this.insideLastIteration = 0;
		this.includeFluids = includeFluids;
		double moveX = toX - fromX;
		double moveY = toY - fromY;
		double moveZ = toZ - fromZ;
		int iterationBudget = 16;
		if (axisDependent && moveX * moveX + moveY * moveY + moveZ * moveZ > 0.0) {
			double stepFromX = fromX;
			double stepFromY = fromY;
			double stepFromZ = fromZ;
			// Ordered by the *requested* movement, stepped by the resolved one.
			boolean yzx = Math.abs(scratch.segmentRequestedX) < Math.abs(scratch.segmentRequestedZ);
			for (int order = 0; order < 3; order++) {
				int axis = AXIS_STEP_ORDER[(yzx ? 0 : 3) + order];
				double axisMove = axis == 0 ? moveX : axis == 1 ? moveY : moveZ;
				if (axisMove == 0.0) {
					continue;
				}
				double stepToX = axis == 0 ? stepFromX + axisMove : stepFromX;
				double stepToY = axis == 1 ? stepFromY + axisMove : stepFromY;
				double stepToZ = axis == 2 ? stepFromZ + axisMove : stepFromZ;
				iterationBudget -= sweep(
				    state, stepFromX, stepFromY, stepFromZ, stepToX, stepToY, stepToZ, iterationBudget, world, scratch);
				stepFromX = stepToX;
				stepFromY = stepToY;
				stepFromZ = stepToZ;
			}
		} else {
			iterationBudget -= sweep(state, fromX, fromY, fromZ, toX, toY, toZ, 16, world, scratch);
		}
		if (iterationBudget <= 0) {
			sweep(state, toX, toY, toZ, toX, toY, toZ, 1, world, scratch);
		}
	}

	/**
	 * {@code BlockGetter.forEachBlockIntersectedBetween} for one sub-movement.
	 * Returns what {@code Entity.checkInsideBlocks} returns: one past the last
	 * step index the visitor accepted.
	 */
	private int sweep(final PlayerState state, final double fromX, final double fromY, final double fromZ,
	    final double toX, final double toY, final double toZ, final int maxIterations, final WorldView world,
	    final Scratch scratch) {
		this.sweepVisitedCellCount = 0;
		if (!(world instanceof SnapshotView snapshot && snapshot.hasSourceInsideShapes()
		        && scratch.authority.isServer()))
			scratch.insideEffects.beginSweep();
		this.sweepFromX = fromX;
		this.sweepFromY = fromY;
		this.sweepFromZ = fromZ;
		this.sweepToX = toX;
		this.sweepToY = toY;
		this.sweepToZ = toZ;
		// makeBoundingBox(to).deflate(1.0E-5F) — the box at this sub-movement's
		// destination, which is not the tick's final box for a split movement.
		double halfWidth = state.pose.width / 2.0F;
		double minX = toX - halfWidth + 1.0E-5F;
		double minY = toY + 1.0E-5F;
		double minZ = toZ - halfWidth + 1.0E-5F;
		double maxX = toX + halfWidth - 1.0E-5F;
		double maxY = toY + state.pose.height - 1.0E-5F;
		double maxZ = toZ + halfWidth - 1.0E-5F;
		double travelX = toX - fromX;
		double travelY = toY - fromY;
		double travelZ = toZ - fromZ;
		double travelLengthSqr = travelX * travelX + travelY * travelY + travelZ * travelZ;
		this.insideLastIteration = 0;
		if (travelLengthSqr < 1.0E-5F * 1.0E-5F) {
			// BlockPos.betweenClosed order: X fastest, then Y, then Z.
			int x0 = Mth.floor(minX);
			int y0 = Mth.floor(minY);
			int z0 = Mth.floor(minZ);
			int x1 = Mth.floor(maxX);
			int y1 = Mth.floor(maxY);
			int z1 = Mth.floor(maxZ);
			for (int z = z0; z <= z1; z++) {
				for (int y = y0; y <= y1; y++) {
					for (int x = x0; x <= x1; x++) {
						if (!visit(state, x, y, z, 0, minX, minY, minZ, maxX, maxY, maxZ, false, maxIterations, world,
						        scratch)) {
							return this.insideLastIteration + 1;
						}
					}
				}
			}
			return this.insideLastIteration + 1;
		}
		boolean movedFar = travelLengthSqr > 0.9999900000002526 * 0.9999900000002526;
		// The box at the movement origin: aabbAtTarget.move(travel.scale(-1.0)).
		if (!visitBoxInDirection(state, Mth.floor(minX - travelX), Mth.floor(minY - travelY), Mth.floor(minZ - travelZ),
		        Mth.floor(maxX - travelX), Mth.floor(maxY - travelY), Mth.floor(maxZ - travelZ), travelX, travelY,
		        travelZ, 0, false, minX, minY, minZ, maxX, maxY, maxZ, movedFar, maxIterations, world, scratch)) {
			return this.insideLastIteration + 1;
		}
		int iterations = addCollisionsAlongTravel(state, travelX, travelY, travelZ, minX, minY, minZ, maxX, maxY, maxZ,
		    movedFar, maxIterations, world, scratch);
		if (iterations < 0) {
			return this.insideLastIteration + 1;
		}
		visitBoxInDirection(state, Mth.floor(minX), Mth.floor(minY), Mth.floor(minZ), Mth.floor(maxX), Mth.floor(maxY),
		    Mth.floor(maxZ), travelX, travelY, travelZ, iterations + 1, true, minX, minY, minZ, maxX, maxY, maxZ,
		    movedFar, maxIterations, world, scratch);
		return this.insideLastIteration + 1;
	}

	/**
	 * {@code BlockGetter.addCollisionsAlongTravel}: the DDA that runs between the
	 * origin and destination passes. Returns its iteration count, or -1 when the
	 * visitor stopped the traversal.
	 */
	private int addCollisionsAlongTravel(final PlayerState state, final double deltaMovementX,
	    final double deltaMovementY, final double deltaMovementZ, final double minX, final double minY,
	    final double minZ, final double maxX, final double maxY, final double maxZ, final boolean movedFar,
	    final int maxIterations, final WorldView world, final Scratch scratch) {
		double boxSizeX = maxX - minX;
		double boxSizeY = maxY - minY;
		double boxSizeZ = maxZ - minZ;
		// getFurthestCorner. The axis permutation is the pinned implementation's
		// own, not a transcription slip: for the smallest-|x| case it returns
		// (-xSign, -zSign, ySign), and every corner choice still yields a corner
		// of the same box, so it is reproduced rather than repaired.
		double xDot = Math.abs(deltaMovementX);
		double yDot = Math.abs(deltaMovementY);
		double zDot = Math.abs(deltaMovementZ);
		int xSign = deltaMovementX >= 0.0 ? 1 : -1;
		int ySign = deltaMovementY >= 0.0 ? 1 : -1;
		int zSign = deltaMovementZ >= 0.0 ? 1 : -1;
		int cornerDirX;
		int cornerDirY;
		int cornerDirZ;
		if (xDot <= yDot && xDot <= zDot) {
			cornerDirX = -xSign;
			cornerDirY = -zSign;
			cornerDirZ = ySign;
		} else if (yDot <= zDot) {
			cornerDirX = zSign;
			cornerDirY = -ySign;
			cornerDirZ = -xSign;
		} else {
			cornerDirX = -ySign;
			cornerDirY = xSign;
			cornerDirZ = -zSign;
		}
		// AABB.getCenter is a lerp, not a midpoint sum.
		double toCornerX = minX + 0.5 * (maxX - minX) + boxSizeX * 0.5 * cornerDirX;
		double toCornerY = minY + 0.5 * (maxY - minY) + boxSizeY * 0.5 * cornerDirY;
		double toCornerZ = minZ + 0.5 * (maxZ - minZ) + boxSizeZ * 0.5 * cornerDirZ;
		double fromCornerX = toCornerX - deltaMovementX;
		double fromCornerY = toCornerY - deltaMovementY;
		double fromCornerZ = toCornerZ - deltaMovementZ;
		int cellX = Mth.floor(fromCornerX);
		int cellY = Mth.floor(fromCornerY);
		int cellZ = Mth.floor(fromCornerZ);
		int stepX = deltaMovementX == 0.0 ? 0 : deltaMovementX > 0.0 ? 1 : -1;
		int stepY = deltaMovementY == 0.0 ? 0 : deltaMovementY > 0.0 ? 1 : -1;
		int stepZ = deltaMovementZ == 0.0 ? 0 : deltaMovementZ > 0.0 ? 1 : -1;
		double tDeltaX = stepX == 0 ? Double.MAX_VALUE : stepX / deltaMovementX;
		double tDeltaY = stepY == 0 ? Double.MAX_VALUE : stepY / deltaMovementY;
		double tDeltaZ = stepZ == 0 ? Double.MAX_VALUE : stepZ / deltaMovementZ;
		double tX = tDeltaX * (stepX > 0 ? 1.0 - Mth.frac(fromCornerX) : Mth.frac(fromCornerX));
		double tY = tDeltaY * (stepY > 0 ? 1.0 - Mth.frac(fromCornerY) : Mth.frac(fromCornerY));
		double tZ = tDeltaZ * (stepZ > 0 ? 1.0 - Mth.frac(fromCornerZ) : Mth.frac(fromCornerZ));
		int iterations = 0;
		while (tX <= 1.0 || tY <= 1.0 || tZ <= 1.0) {
			if (tX < tY) {
				if (tX < tZ) {
					cellX += stepX;
					tX += tDeltaX;
				} else {
					cellZ += stepZ;
					tZ += tDeltaZ;
				}
			} else if (tY < tZ) {
				cellY += stepY;
				tY += tDeltaY;
			} else {
				cellZ += stepZ;
				tZ += tDeltaZ;
			}
			if (!AABB.clipCell(cellX, cellY, cellZ, fromCornerX, fromCornerY, fromCornerZ, toCornerX, toCornerY,
			        toCornerZ, scratch)) {
				continue;
			}
			iterations++;
			// The lower clamp is float arithmetic and the upper is double, which
			// is not interchangeable at these magnitudes.
			double cornerHitX = Mth.clamp(scratch.clipHitX, cellX + 1.0E-5F, cellX + 1.0 - 1.0E-5F);
			double cornerHitY = Mth.clamp(scratch.clipHitY, cellY + 1.0E-5F, cellY + 1.0 - 1.0E-5F);
			double cornerHitZ = Mth.clamp(scratch.clipHitZ, cellZ + 1.0E-5F, cellZ + 1.0 - 1.0E-5F);
			if (!visitBoxInDirection(state, cellX, cellY, cellZ, Mth.floor(cornerHitX - boxSizeX * cornerDirX),
			        Mth.floor(cornerHitY - boxSizeY * cornerDirY), Mth.floor(cornerHitZ - boxSizeZ * cornerDirZ),
			        deltaMovementX, deltaMovementY, deltaMovementZ, iterations, true, minX, minY, minZ, maxX, maxY,
			        maxZ, movedFar, maxIterations, world, scratch)) {
				return -1;
			}
		}
		return iterations;
	}

	/**
	 * {@code BlockPos.betweenCornersInDirection} over one cell box. Returns false
	 * when the visitor stopped the traversal.
	 */
	private boolean visitBoxInDirection(final PlayerState state, final int firstX, final int firstY, final int firstZ,
	    final int secondX, final int secondY, final int secondZ, final double dirX, final double dirY,
	    final double dirZ, final int step, final boolean freshOnly, final double minX, final double minY,
	    final double minZ, final double maxX, final double maxY, final double maxZ, final boolean movedFar,
	    final int maxIterations, final WorldView world, final Scratch scratch) {
		int minCornerX = Math.min(firstX, secondX);
		int minCornerY = Math.min(firstY, secondY);
		int minCornerZ = Math.min(firstZ, secondZ);
		int maxCornerX = Math.max(firstX, secondX);
		int maxCornerY = Math.max(firstY, secondY);
		int maxCornerZ = Math.max(firstZ, secondZ);
		int startX = dirX >= 0.0 ? minCornerX : maxCornerX;
		int startY = dirY >= 0.0 ? minCornerY : maxCornerY;
		int startZ = dirZ >= 0.0 ? minCornerZ : maxCornerZ;
		int stepX = dirX >= 0.0 ? 1 : -1;
		int stepY = dirY >= 0.0 ? 1 : -1;
		int stepZ = dirZ >= 0.0 ? 1 : -1;
		int countX = maxCornerX - minCornerX;
		int countY = maxCornerY - minCornerY;
		int countZ = maxCornerZ - minCornerZ;
		// Direction.axisStepOrder: Y,Z,X when |X| < |Z|, otherwise Y,X,Z, with the
		// first axis varying slowest.
		if (Math.abs(dirX) < Math.abs(dirZ)) {
			for (int iy = 0; iy <= countY; iy++) {
				int y = startY + stepY * iy;
				for (int iz = 0; iz <= countZ; iz++) {
					int z = startZ + stepZ * iz;
					for (int ix = 0; ix <= countX; ix++) {
						int x = startX + stepX * ix;
						boolean fresh = this.markSweepVisited(x, y, z);
						if (freshOnly && !fresh) {
							continue;
						}
						if (!visit(state, x, y, z, step, minX, minY, minZ, maxX, maxY, maxZ, movedFar, maxIterations,
						        world, scratch)) {
							return false;
						}
					}
				}
			}
		} else {
			for (int iy = 0; iy <= countY; iy++) {
				int y = startY + stepY * iy;
				for (int ix = 0; ix <= countX; ix++) {
					int x = startX + stepX * ix;
					for (int iz = 0; iz <= countZ; iz++) {
						int z = startZ + stepZ * iz;
						boolean fresh = this.markSweepVisited(x, y, z);
						if (freshOnly && !fresh) {
							continue;
						}
						if (!visit(state, x, y, z, step, minX, minY, minZ, maxX, maxY, maxZ, movedFar, maxIterations,
						        world, scratch)) {
							return false;
						}
					}
				}
			}
		}
		return true;
	}

	/** Entity.visitedBlocks.add: true when this cell had not been visited this tick. */
	private boolean markTickVisited(final int x, final int y, final int z) {
		for (int i = 0; i < this.tickVisitedCellCount * 3; i += 3) {
			if (this.tickVisitedCells[i] == x && this.tickVisitedCells[i + 1] == y
			    && this.tickVisitedCells[i + 2] == z) {
				return false;
			}
		}
		this.tickVisitedCells = append(this.tickVisitedCells, this.tickVisitedCellCount, x, y, z);
		this.tickVisitedCellCount++;
		return true;
	}

	/** The sub-movement-local set forEachBlockIntersectedBetween keeps. */
	private boolean markSweepVisited(final int x, final int y, final int z) {
		for (int i = 0; i < this.sweepVisitedCellCount * 3; i += 3) {
			if (this.sweepVisitedCells[i] == x && this.sweepVisitedCells[i + 1] == y
			    && this.sweepVisitedCells[i + 2] == z) {
				return false;
			}
		}
		this.sweepVisitedCells = append(this.sweepVisitedCells, this.sweepVisitedCellCount, x, y, z);
		this.sweepVisitedCellCount++;
		return true;
	}

	private static int[] append(final int[] cells, final int count, final int x, final int y, final int z) {
		int[] target = cells;
		if (count * 3 == target.length) {
			target = Arrays.copyOf(target, target.length * 2);
		}
		target[count * 3] = x;
		target[count * 3 + 1] = y;
		target[count * 3 + 2] = z;
		return target;
	}

	/**
	 * The traversal budget is checked before any world query. Contact detection
	 * precedes tick-wide duplicate suppression: a rejected shape contact remains
	 * eligible in another sweep. Only a new accepted contact advances the effect
	 * collector, immediately before the block or fluid body runs.
	 */
	private boolean visit(final PlayerState state, final int x, final int y, final int z, final int step,
	    final double minX, final double minY, final double minZ, final double maxX, final double maxY,
	    final double maxZ, final boolean movedFar, final int maxIterations, final WorldView world,
	    final Scratch scratch) {
		if (step >= maxIterations) {
			if (!(world instanceof SnapshotView snapshot && snapshot.hasSourceInsideShapes()
			        && scratch.authority.isServer()))
				scratch.insideEffects.abortSweep();
			return false;
		}
		this.insideLastIteration = step;
		if (!this.contact.find(state, x, y, z, this.includeFluids, world, scratch)) {
			return true;
		}
		if (!markTickVisited(x, y, z)) {
			return true;
		}
		scratch.insideEffects.advanceStep(step);
		this.contact.apply(state, x, y, z, minX, minY, minZ, maxX, maxY, maxZ, movedFar, world, scratch);
		return true;
	}

	/**
	 * {@code Entity.collidedWithShapeMovingFrom}: whether the box swept from
	 * this sub-movement's origin reached a shape at the cell.
	 *
	 * <p>{@code AABB.collidedAlongVector} inflates the shape by the player box's
	 * own half-extents and asks whether the box's centre, swept along the
	 * sub-movement, starts inside, ends inside, or crosses a face.
	 */
	public boolean collidedWithShapeMovingFrom(
	    final PlayerState state, final int x, final int y, final int z, final double[] shape) {
		// makeBoundingBox(from): the box at this sub-movement's origin, not the
		// deflated destination box the cell was reached through.
		double halfWidth = state.pose.width / 2.0F;
		double boundingBoxMinX = this.sweepFromX - halfWidth;
		double boundingBoxMaxX = this.sweepFromX + halfWidth;
		double boundingBoxMinY = this.sweepFromY;
		double boundingBoxMaxY = this.sweepFromY + state.pose.height;
		double boundingBoxMinZ = this.sweepFromZ - halfWidth;
		double boundingBoxMaxZ = this.sweepFromZ + halfWidth;
		// AABB.inflate by getXsize() * 0.5 - 1.0E-7, computed from the box the
		// same way vanilla computes it.
		double growX = (boundingBoxMaxX - boundingBoxMinX) * 0.5 - 1.0E-7;
		double growY = (boundingBoxMaxY - boundingBoxMinY) * 0.5 - 1.0E-7;
		double growZ = (boundingBoxMaxZ - boundingBoxMinZ) * 0.5 - 1.0E-7;
		// AABB.getCenter is a lerp, not a midpoint sum.
		double fromX = boundingBoxMinX + 0.5 * (boundingBoxMaxX - boundingBoxMinX);
		double fromY = boundingBoxMinY + 0.5 * (boundingBoxMaxY - boundingBoxMinY);
		double fromZ = boundingBoxMinZ + 0.5 * (boundingBoxMaxZ - boundingBoxMinZ);
		double toX = fromX + (this.sweepToX - this.sweepFromX);
		double toY = fromY + (this.sweepToY - this.sweepFromY);
		double toZ = fromZ + (this.sweepToZ - this.sweepFromZ);
		for (int at = 0; at < shape.length; at += 6) {
			double minX = x + shape[at] - growX;
			double minY = y + shape[at + 1] - growY;
			double minZ = z + shape[at + 2] - growZ;
			double maxX = x + shape[at + 3] + growX;
			double maxY = y + shape[at + 4] + growY;
			double maxZ = z + shape[at + 5] + growZ;
			if (AABB.contains(minX, minY, minZ, maxX, maxY, maxZ, toX, toY, toZ)
			    || AABB.contains(minX, minY, minZ, maxX, maxY, maxZ, fromX, fromY, fromZ)
			    || AABB.clip(minX, minY, minZ, maxX, maxY, maxZ, fromX, fromY, fromZ, toX, toY, toZ)) {
				return true;
			}
		}
		return false;
	}
}
