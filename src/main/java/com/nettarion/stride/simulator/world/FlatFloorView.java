package com.nettarion.stride.simulator.world;

import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.block.BlockBehavior;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.geometry.CollisionQuerySpan;
import com.nettarion.stride.simulator.geometry.Mth;

/**
 * A uniform full-cube floor everywhere below
 * {@code floorTopY}, air above, unbounded horizontally.
 *
 * <p>Provides the same declared geometry to scalar tests, planner fixtures, and
 * benchmarks without involving snapshot persistence.
 */
public final class FlatFloorView implements WorldView {
	private final int floorTopY;
	private final int minY;
	private final float friction;
	private final float speedFactor;
	private final float jumpFactor;
	private final int properties;
	private final long identity = WorldIdentity.next();

	/** Creates a uniform floor with explicit friction, speed and jump coefficients; {@code minY} is its lower bound. */
	public FlatFloorView(
	    final int floorTopY, final int minY, final float friction, final float speedFactor, final float jumpFactor) {
		this.floorTopY = floorTopY;
		this.minY = minY;
		this.friction = friction;
		this.speedFactor = speedFactor;
		this.jumpFactor = jumpFactor;
		// One block everywhere, so every span has the same answer and the
		// interface default's seven virtual calls would recompute a constant.
		this.properties = (friction != 0.6F ? PROPERTY_FRICTION : 0) | (speedFactor != 1.0F ? PROPERTY_SPEED_FACTOR : 0)
		    | (jumpFactor != 1.0F ? PROPERTY_JUMP_FACTOR : 0);
	}

	/** Ordinary blocks: friction 0.6, no speed or jump modification. */
	public static FlatFloorView ordinary(final int floorTopY, final int minY) {
		return new FlatFloorView(floorTopY, minY, 0.6F, 1.0F, 1.0F);
	}

	private boolean solid(final int y) {
		return y < this.floorTopY && y >= this.minY;
	}

	@Override
	public boolean movementFactsComplete() {
		return true;
	}

	@Override
	public long collisionVersion() {
		return 0L;
	}

	@Override
	public boolean movementFactsImmutable() {
		return true;
	}

	@Override
	public long identity() {
		return this.identity;
	}

	@Override
	public boolean hasFluids() {
		return false;
	}

	/** One ordinary block everywhere: no body anywhere. */
	@Override
	public BlockBehavior behaviorAt(final int x, final int y, final int z) {
		return BlockBehavior.INERT;
	}

	@Override
	public void fluidAt(final int x, final int y, final int z, final FluidSample target) {
		target.clear();
	}

	@Override
	public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final CollisionBuffer target) {
		CollisionQuerySpan.requireSupported(minX, minY, minZ, maxX, maxY, maxZ);
		target.clear();
		// BlockCollisions admits a full block by AABB.intersects, open on every
		// face: a cell whose face the query box only touches is left out. The
		// cells whose open interior the box reaches run from floor(min) to the
		// last integer strictly below max, on every axis alike.
		int y0 = Mth.floor(minY);
		int y1 = Mth.floor(Math.nextDown(maxY));
		int x0 = Mth.floor(minX);
		int x1 = Mth.floor(Math.nextDown(maxX));
		int z0 = Mth.floor(minZ);
		int z1 = Mth.floor(Math.nextDown(maxZ));
		for (int y = y0; y <= y1; y++) {
			if (!solid(y)) {
				continue;
			}
			for (int x = x0; x <= x1; x++) {
				for (int z = z0; z <= z1; z++) {
					target.addCanonicalFullCube(x, y, z);
				}
			}
		}
	}

	/**
	 * This world names one block by its coefficients, never by its identity, so
	 * it cannot say whether the floor suffocates — stone and glass are the same
	 * block here. It can say that air does not, which is every cell the query box
	 * reaches while the player is standing on the floor rather than inside it.
	 */
	@Override
	public boolean suffocatesAt(
	    final int cellX, final int cellZ, final double boundingBoxMinY, final double boundingBoxMaxY) {
		int y0 = Mth.floor(boundingBoxMinY + 1.0E-7);
		int y1 = Mth.floor(boundingBoxMaxY - 1.0E-7);
		for (int y = y0; y <= y1; y++) {
			if (solid(y)) {
				throw UnimplementedMechanicException.at(RefusalCause.UNDECLARED_WORLD_FACT,
				    "a flat-floor world cannot resolve suffocation for the block at ", cellX, y, cellZ, "");
			}
		}
		return false;
	}

	/**
	 * Every solid cell here is the same full cube, so the supporting block is
	 * decided entirely by {@code distToCenterSqr} and the {@code compareTo}
	 * tie-break — which is exactly the part of {@code findSupportingBlock} a
	 * uniform floor still exercises, and the part a box straddling a boundary
	 * depends on.
	 */
	@Override
	public void findSupportingBlock(final double minX, final double minY, final double minZ, final double maxX,
	    final double maxY, final double maxZ, final double atX, final double atY, final double atZ,
	    final SupportCell out) {
		CollisionQuerySpan.requireSupported(minX, minY, minZ, maxX, maxY, maxZ);
		out.clear();
		double best = Double.MAX_VALUE;
		// The same open-face admission as collectCollisionBoxes: a cell the slab
		// only touches is not a candidate.
		int x0 = Mth.floor(minX);
		int x1 = Mth.floor(Math.nextDown(maxX));
		int y0 = Mth.floor(minY);
		int y1 = Mth.floor(Math.nextDown(maxY));
		int z0 = Mth.floor(minZ);
		int z1 = Mth.floor(Math.nextDown(maxZ));
		for (int z = z0; z <= z1; z++) {
			for (int y = y0; y <= y1; y++) {
				if (!solid(y)) {
					continue;
				}
				for (int x = x0; x <= x1; x++) {
					// A full cube fills its cell, so reaching the query box is the
					// same question as the cell being in the scanned span.
					double dx = x + 0.5 - atX;
					double dy = y + 0.5 - atY;
					double dz = z + 0.5 - atZ;
					double distance = dx * dx + dy * dy + dz * dz;
					if (distance < best || distance == best && (!out.present || out.compareTo(x, y, z) < 0)) {
						best = distance;
						out.set(x, y, z);
					}
				}
			}
		}
	}

	@Override
	public boolean resetsFallDistanceAlong(final double fromX, final double fromY, final double fromZ, final double toX,
	    final double toY, final double toZ) {
		return false;
	}

	@Override
	public float friction(final int x, final int y, final int z) {
		return solid(y) ? this.friction : 0.6F;
	}

	@Override
	public float speedFactor(final int x, final int y, final int z) {
		return solid(y) ? this.speedFactor : 1.0F;
	}

	@Override
	public boolean suppressesSupportingSpeedFactor(final int x, final int y, final int z) {
		return false;
	}

	@Override
	public float jumpFactor(final int x, final int y, final int z) {
		return solid(y) ? this.jumpFactor : 1.0F;
	}

	@Override
	public boolean hasClimbables() {
		return false;
	}

	@Override
	public boolean hasNonDefaultFriction() {
		return this.friction != 0.6F;
	}

	@Override
	public boolean hasNonDefaultSpeedFactor() {
		return this.speedFactor != 1.0F;
	}

	@Override
	public boolean hasNonDefaultJumpFactor() {
		return this.jumpFactor != 1.0F;
	}

	@Override
	public int propertiesIn(
	    final int wanted, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		return this.properties & wanted;
	}

	@Override
	public boolean hasChunkAt(final int x, final int z) {
		return true;
	}

	@Override
	public int minY() {
		return this.minY;
	}
}
