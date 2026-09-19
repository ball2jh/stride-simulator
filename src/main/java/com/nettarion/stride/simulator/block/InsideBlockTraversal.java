package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.WorldView;
import java.util.Arrays;

/**
 * Visits the cells the player's box swept between two positions, in vanilla's order and with vanilla's step
 * indices.
 *
 * <p>This is {@code Entity.checkInsideBlocks} over {@code BlockGetter.forEachBlockIntersectedBetween}, the
 * geometry {@link BlockEffects} runs its cell visitor through. Each sub-movement visits the box at its origin,
 * then the cells a corner of the destination box crosses on its way back along the travel vector (the DDA of
 * {@code addCollisionsAlongTravel}), then the box at its destination, each pass in
 * {@code Direction.axisStepOrder}. The step index advances with the DDA and groups the effects the visitor
 * collects. Two visited sets are kept as vanilla keeps them: one over the tick and one over the sub-movement.
 *
 * <p>Reads position and pose; writes only its own retained workspace. {@code BlockEffects.Contact} detects
 * contacts and applies behaviors; this class owns the budget, duplicate suppression and step advancement between
 * those operations. Not thread-safe: one instance belongs to the tick's {@code Scratch}, and the per-traversal
 * fields below are valid only while a traversal is running.
 */
public final class InsideBlockTraversal {
	/** {@code Entity.checkInsideBlocks}'s iteration budget across one tick's sub-movements. */
	private static final int MAX_ITERATIONS = 16;

	/** {@code makeBoundingBox(to).deflate(1.0E-5F)}: how far the destination box is shrunk, as vanilla's float. */
	private static final float BOX_DEFLATION = 1.0E-5F;

	/** {@code Mth.square(1.0E-5F)}: a sub-movement shorter than this is traversed as stationary. */
	private static final double MIN_TRAVEL_SQR = 1.0E-5F * 1.0E-5F;

	/** The literal vanilla compares the sub-movement's length against to decide {@code movedFar}. */
	private static final double MOVED_FAR_DISTANCE = 0.9999900000002526;

	private static final double MOVED_FAR_DISTANCE_SQR = MOVED_FAR_DISTANCE * MOVED_FAR_DISTANCE;

	/**
	 * {@code Direction.axisStepOrder}, as axis indices: 0 is X, 1 is Y, 2 is Z. Y always leads; X and Z follow
	 * in the order of the larger requested component.
	 */
	private static final int[] AXIS_STEP_ORDER = {1, 2, 0, 1, 0, 2};

	/** The corner clip point of the current sweep segment, written by {@link AABB#clipCell}. */
	private final double[] clipHit = new double[3];

	private final BlockEffects.Contact contact = new BlockEffects.Contact();

	/** {@code Entity.visitedBlocks}: spans the whole tick and gates the effects. */
	private final CellSet tickVisited = new CellSet();

	/** {@code forEachBlockIntersectedBetween}'s own set: spans one sub-movement and gates its destination pass. */
	private final CellSet sweepVisited = new CellSet();

	/*
	 * Per-traversal constants, set by traverseRetainingVisited and read by every visit below it, exactly as
	 * vanilla's lambdas capture them.
	 */

	private PlayerState state;

	private WorldView world;

	private Scratch scratch;

	/** Whether this server traversal should query fluid contacts. */
	private boolean includeFluids;

	/**
	 * The step index of the last cell offered within the budget, as vanilla's {@code insideLastIteration}. Set
	 * before contact detection, so a cell without a contact still advances it.
	 */
	private int lastAcceptedStep;

	/*
	 * Per-sweep constants, set by sweep() for one sub-movement. checkInsideBlocks passes its own `from` and `to`
	 * to collidedWithShapeMovingFrom, which is not the tick's movement for a per-axis split, and not the
	 * destination box the cell was reached from either.
	 */

	private double sweepFromX;

	private double sweepFromY;

	private double sweepFromZ;

	private double sweepToX;

	private double sweepToY;

	private double sweepToZ;

	private double travelX;

	private double travelY;

	private double travelZ;

	/** {@code makeBoundingBox(to).deflate(1.0E-5F)}: the box at this sub-movement's destination. */
	private double boxMinX;

	private double boxMinY;

	private double boxMinZ;

	private double boxMaxX;

	private double boxMaxY;

	private double boxMaxZ;

	/** Whether this sub-movement is longer than {@link #MOVED_FAR_DISTANCE}, which makes every visit precise. */
	private boolean movedFar;

	/** The remaining iteration budget when this sub-movement started. */
	private int maxIterations;

	/** A traversal with empty visited sets; {@code Scratch} constructs one per workspace. */
	public InsideBlockTraversal() {}

	/**
	 * {@code Entity.collidedWithShapeMovingFrom}, valid only during a visit callback: whether the box swept from
	 * the current sub-movement's origin reached {@code shape} at the cell.
	 *
	 * <p>{@code shape} is {@link BlockBehavior#flattenShape}'s layout, in blocks relative to the cell.
	 * {@code AABB.collidedAlongVector} inflates each box by the player box's own half-extents and asks whether the
	 * box's center, swept along the sub-movement, starts inside, ends inside, or crosses a face. Public because
	 * {@code SnapshotView.sourceInsideReached} answers with it for a cell whose shape the world extracted.
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

	/** Forgets the tick's visited cells before the first traversal of a tick. */
	void begin() {
		this.tickVisited.clear();
	}

	/**
	 * {@code Entity.checkInsideBlocks}: the tick's recorded movement split into up to three axis-aligned
	 * sub-movements, each swept by {@link #sweep} over one shared visited set and one shared step collector, then
	 * the stationary sweep at the destination when the iteration budget ran out. The cell visitor is
	 * {@link #visit}. The tick's visited set is retained from the previous call; {@link #begin()} clears it.
	 *
	 * @param axisDependent whether {@code move()} recorded the segment, so the sub-movements are ordered by the
	 *     requested delta; a synthesized movement is one sub-movement
	 * @param requestedX the X component of the requested movement, read only when {@code axisDependent}
	 * @param requestedZ the Z component of the requested movement, read only when {@code axisDependent}
	 * @param includeFluids whether the admitted server cover can contain fluid contacts
	 */
	void traverseRetainingVisited(final PlayerState state, final double fromX, final double fromY, final double fromZ,
	    final double toX, final double toY, final double toZ, final boolean axisDependent, final double requestedX,
	    final double requestedZ, final boolean includeFluids, final WorldView world, final Scratch scratch) {
		this.state = state;
		this.world = world;
		this.scratch = scratch;
		this.includeFluids = includeFluids;
		this.sweepVisited.clear();
		this.lastAcceptedStep = 0;
		double moveX = toX - fromX;
		double moveY = toY - fromY;
		double moveZ = toZ - fromZ;
		int iterationBudget = MAX_ITERATIONS;
		if (axisDependent && moveX * moveX + moveY * moveY + moveZ * moveZ > 0.0) {
			double stepFromX = fromX;
			double stepFromY = fromY;
			double stepFromZ = fromZ;
			// Ordered by the requested movement, stepped by the resolved one.
			boolean yzx = Math.abs(requestedX) < Math.abs(requestedZ);
			for (int order = 0; order < 3; order++) {
				int axis = AXIS_STEP_ORDER[(yzx ? 0 : 3) + order];
				double axisMove = axis == 0 ? moveX : axis == 1 ? moveY : moveZ;
				if (axisMove == 0.0) {
					continue;
				}
				double stepToX = axis == 0 ? stepFromX + axisMove : stepFromX;
				double stepToY = axis == 1 ? stepFromY + axisMove : stepFromY;
				double stepToZ = axis == 2 ? stepFromZ + axisMove : stepFromZ;
				iterationBudget -= sweep(stepFromX, stepFromY, stepFromZ, stepToX, stepToY, stepToZ, iterationBudget);
				stepFromX = stepToX;
				stepFromY = stepToY;
				stepFromZ = stepToZ;
			}
		} else {
			iterationBudget -= sweep(fromX, fromY, fromZ, toX, toY, toZ, MAX_ITERATIONS);
		}
		if (iterationBudget <= 0) {
			sweep(toX, toY, toZ, toX, toY, toZ, 1);
		}
	}

	/**
	 * {@code BlockGetter.forEachBlockIntersectedBetween} for one sub-movement. Returns what
	 * {@code Entity.checkInsideBlocks} returns: one past the step index of the last cell offered within the budget.
	 */
	private int sweep(final double fromX, final double fromY, final double fromZ, final double toX, final double toY,
	    final double toZ, final int maxIterations) {
		this.sweepVisited.clear();
		if (!BlockEffects.usesSourceInsideShapes(this.world, this.scratch)) {
			this.scratch.insideEffects.beginSweep();
		}
		this.sweepFromX = fromX;
		this.sweepFromY = fromY;
		this.sweepFromZ = fromZ;
		this.sweepToX = toX;
		this.sweepToY = toY;
		this.sweepToZ = toZ;
		this.maxIterations = maxIterations;
		// makeBoundingBox(to).deflate(1.0E-5F): the box at this sub-movement's
		// destination, which is not the tick's final box for a split movement.
		double halfWidth = this.state.pose.width / 2.0F;
		this.boxMinX = toX - halfWidth + BOX_DEFLATION;
		this.boxMinY = toY + BOX_DEFLATION;
		this.boxMinZ = toZ - halfWidth + BOX_DEFLATION;
		this.boxMaxX = toX + halfWidth - BOX_DEFLATION;
		this.boxMaxY = toY + this.state.pose.height - BOX_DEFLATION;
		this.boxMaxZ = toZ + halfWidth - BOX_DEFLATION;
		this.travelX = toX - fromX;
		this.travelY = toY - fromY;
		this.travelZ = toZ - fromZ;
		double travelLengthSqr =
		    this.travelX * this.travelX + this.travelY * this.travelY + this.travelZ * this.travelZ;
		this.lastAcceptedStep = 0;
		if (travelLengthSqr < MIN_TRAVEL_SQR) {
			this.movedFar = false;
			// BlockPos.betweenClosed order: X fastest, then Y, then Z.
			int x0 = Mth.floor(this.boxMinX);
			int y0 = Mth.floor(this.boxMinY);
			int z0 = Mth.floor(this.boxMinZ);
			int x1 = Mth.floor(this.boxMaxX);
			int y1 = Mth.floor(this.boxMaxY);
			int z1 = Mth.floor(this.boxMaxZ);
			for (int z = z0; z <= z1; z++) {
				for (int y = y0; y <= y1; y++) {
					for (int x = x0; x <= x1; x++) {
						if (!visit(x, y, z, 0)) {
							return this.lastAcceptedStep + 1;
						}
					}
				}
			}
			return this.lastAcceptedStep + 1;
		}
		this.movedFar = travelLengthSqr > MOVED_FAR_DISTANCE_SQR;
		// The box at the movement origin: aabbAtTarget.move(travel.scale(-1.0)).
		if (!visitBoxInDirection(Mth.floor(this.boxMinX - this.travelX), Mth.floor(this.boxMinY - this.travelY),
		        Mth.floor(this.boxMinZ - this.travelZ), Mth.floor(this.boxMaxX - this.travelX),
		        Mth.floor(this.boxMaxY - this.travelY), Mth.floor(this.boxMaxZ - this.travelZ), 0, false)) {
			return this.lastAcceptedStep + 1;
		}
		int iterations = addCollisionsAlongTravel();
		if (iterations < 0) {
			return this.lastAcceptedStep + 1;
		}
		visitBoxInDirection(Mth.floor(this.boxMinX), Mth.floor(this.boxMinY), Mth.floor(this.boxMinZ),
		    Mth.floor(this.boxMaxX), Mth.floor(this.boxMaxY), Mth.floor(this.boxMaxZ), iterations + 1, true);
		return this.lastAcceptedStep + 1;
	}

	/**
	 * {@code BlockGetter.addCollisionsAlongTravel}: the DDA that runs between the origin and destination passes.
	 * Returns its iteration count, or -1 when the visitor stopped the traversal.
	 */
	private int addCollisionsAlongTravel() {
		double deltaMovementX = this.travelX;
		double deltaMovementY = this.travelY;
		double deltaMovementZ = this.travelZ;
		double boxSizeX = this.boxMaxX - this.boxMinX;
		double boxSizeY = this.boxMaxY - this.boxMinY;
		double boxSizeZ = this.boxMaxZ - this.boxMinZ;
		// getFurthestCorner. The axis permutation is vanilla's own, not a
		// transcription slip: for the smallest-|x| case it returns
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
		double toCornerX = this.boxMinX + 0.5 * (this.boxMaxX - this.boxMinX) + boxSizeX * 0.5 * cornerDirX;
		double toCornerY = this.boxMinY + 0.5 * (this.boxMaxY - this.boxMinY) + boxSizeY * 0.5 * cornerDirY;
		double toCornerZ = this.boxMinZ + 0.5 * (this.boxMaxZ - this.boxMinZ) + boxSizeZ * 0.5 * cornerDirZ;
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
			        toCornerZ, this.clipHit)) {
				continue;
			}
			iterations++;
			// The lower clamp is float arithmetic and the upper is double, which
			// is not interchangeable at these magnitudes.
			double cornerHitX = Mth.clamp(this.clipHit[0], cellX + 1.0E-5F, cellX + 1.0 - 1.0E-5F);
			double cornerHitY = Mth.clamp(this.clipHit[1], cellY + 1.0E-5F, cellY + 1.0 - 1.0E-5F);
			double cornerHitZ = Mth.clamp(this.clipHit[2], cellZ + 1.0E-5F, cellZ + 1.0 - 1.0E-5F);
			if (!visitBoxInDirection(cellX, cellY, cellZ, Mth.floor(cornerHitX - boxSizeX * cornerDirX),
			        Mth.floor(cornerHitY - boxSizeY * cornerDirY), Mth.floor(cornerHitZ - boxSizeZ * cornerDirZ),
			        iterations, true)) {
				return -1;
			}
		}
		return iterations;
	}

	/**
	 * {@code BlockPos.betweenCornersInDirection} over one cell box, stepping along the sweep's travel vector.
	 * Returns false when the visitor stopped the traversal.
	 *
	 * @param freshOnly whether to skip cells the sub-movement's own visited set already holds
	 */
	private boolean visitBoxInDirection(final int firstX, final int firstY, final int firstZ, final int secondX,
	    final int secondY, final int secondZ, final int step, final boolean freshOnly) {
		int minCornerX = Math.min(firstX, secondX);
		int minCornerY = Math.min(firstY, secondY);
		int minCornerZ = Math.min(firstZ, secondZ);
		int maxCornerX = Math.max(firstX, secondX);
		int maxCornerY = Math.max(firstY, secondY);
		int maxCornerZ = Math.max(firstZ, secondZ);
		int startX = this.travelX >= 0.0 ? minCornerX : maxCornerX;
		int startY = this.travelY >= 0.0 ? minCornerY : maxCornerY;
		int startZ = this.travelZ >= 0.0 ? minCornerZ : maxCornerZ;
		int stepX = this.travelX >= 0.0 ? 1 : -1;
		int stepY = this.travelY >= 0.0 ? 1 : -1;
		int stepZ = this.travelZ >= 0.0 ? 1 : -1;
		int countX = maxCornerX - minCornerX;
		int countY = maxCornerY - minCornerY;
		int countZ = maxCornerZ - minCornerZ;
		// Direction.axisStepOrder: Y,Z,X when |X| < |Z|, otherwise Y,X,Z, with the
		// first axis varying slowest. The middle axis is Z or X accordingly and
		// the innermost the other one.
		boolean zBeforeX = Math.abs(this.travelX) < Math.abs(this.travelZ);
		int middleStart = zBeforeX ? startZ : startX;
		int middleStep = zBeforeX ? stepZ : stepX;
		int middleCount = zBeforeX ? countZ : countX;
		int innerStart = zBeforeX ? startX : startZ;
		int innerStep = zBeforeX ? stepX : stepZ;
		int innerCount = zBeforeX ? countX : countZ;
		for (int iy = 0; iy <= countY; iy++) {
			int y = startY + stepY * iy;
			for (int im = 0; im <= middleCount; im++) {
				int middle = middleStart + middleStep * im;
				for (int ii = 0; ii <= innerCount; ii++) {
					int inner = innerStart + innerStep * ii;
					int x = zBeforeX ? inner : middle;
					int z = zBeforeX ? middle : inner;
					boolean fresh = this.sweepVisited.add(x, y, z);
					if (freshOnly && !fresh) {
						continue;
					}
					if (!visit(x, y, z, step)) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/**
	 * The cell visitor. The budget is checked before any world query. Contact detection precedes tick-wide
	 * duplicate suppression, so a rejected shape contact remains eligible in another sweep. Only a new accepted
	 * contact advances the effect collector, immediately before the block or fluid hook runs.
	 */
	private boolean visit(final int x, final int y, final int z, final int step) {
		if (step >= this.maxIterations) {
			if (!BlockEffects.usesSourceInsideShapes(this.world, this.scratch)) {
				this.scratch.insideEffects.abortSweep();
			}
			return false;
		}
		this.lastAcceptedStep = step;
		if (!this.contact.find(this.state, x, y, z, this.includeFluids, this.world, this.scratch)) {
			return true;
		}
		if (!this.tickVisited.add(x, y, z)) {
			return true;
		}
		this.scratch.insideEffects.advanceStep(step);
		this.contact.apply(this.state, x, y, z, this.boxMinX, this.boxMinY, this.boxMinZ, this.boxMaxX, this.boxMaxY,
		    this.boxMaxZ, this.movedFar, this.world, this.scratch);
		return true;
	}

	/**
	 * A set of cells as a flat {@code x, y, z} triple array with a linear scan.
	 *
	 * <p>Membership is O(n) and a traversal's adds are O(n squared), which is cheaper than hashing here: the
	 * iteration budget caps a tick at a few dozen cells, and the scan allocates nothing and boxes nothing.
	 */
	private static final class CellSet {
		private int[] cells = new int[3 * 32];

		private int count;

		/** Adds the cell; true when it was not already present, as {@code Set.add}. */
		boolean add(final int x, final int y, final int z) {
			for (int i = 0; i < this.count * 3; i += 3) {
				if (this.cells[i] == x && this.cells[i + 1] == y && this.cells[i + 2] == z) {
					return false;
				}
			}
			if (this.count * 3 == this.cells.length) {
				this.cells = Arrays.copyOf(this.cells, this.cells.length * 2);
			}
			this.cells[this.count * 3] = x;
			this.cells[this.count * 3 + 1] = y;
			this.cells[this.count * 3 + 2] = z;
			this.count++;
			return true;
		}

		void clear() {
			this.count = 0;
		}
	}
}
