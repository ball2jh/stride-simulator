package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * The memoized collision result a {@link PlayerState} keeps for one pose at one position in one world.
 *
 * <p>Optimization metadata only: absent from {@link StateDigest} and from the trace state. Every use
 * rechecks the recorded key and the raw position bits, so a stale entry can only become a cache miss.
 * The entry is keyed on the world's identity number and the collision version of the cells the box
 * can touch, and never holds the world: a reference here once kept every superseded view alive through
 * every retained state. After {@link #carry} it is also keyed on the {@link WorldView#spanRevision} of
 * those cells, which a republished view that kept the same sections answers again. Zero names no world,
 * so a state never checked, or checked in a view without an identity, holds no entry.
 */
final class PoseFitCache {
	/** The inflation vanilla's collision query applies to a box before scanning cells, in blocks. */
	private static final double COLLISION_EPSILON = 1.0E-7;

	/** The identity of the view the entry was taken in, or zero for no entry. */
	private long world;

	/** The collision version of the span at the time of the entry, or zero in an immutable view. */
	private long collisionVersion;

	/**
	 * The span revision the entry stands under, read from the view it was taken in the first time
	 * another view asks, and zero until then. The world is named and not held, so an entry first asked
	 * about elsewhere after its view is gone cannot recover a revision and is a miss there.
	 */
	private long spanRevision;

	private long xBits;

	private long yBits;

	private long zBits;

	private int widthBits;

	/** The height of the pose that fit; negative once cleared, which fails every comparison. */
	private float height;

	/**
	 * Invalidates the entry. The height is what every check compares against a pose, so a negative one
	 * fails them all; the world identity is kept, since the next entry is almost always in the same
	 * world.
	 */
	void clear() {
		this.height = -1.0F;
	}

	/** Whether the entry covers {@code pose} at the raw position bits given, in {@code world}. */
	boolean covers(final WorldView world, final double x, final double y, final double z, final PlayerState.Pose pose) {
		return this.world != 0L && this.xBits == Double.doubleToRawLongBits(x)
		    && this.yBits == Double.doubleToRawLongBits(y) && this.zBits == Double.doubleToRawLongBits(z)
		    && this.widthBits == Float.floatToRawIntBits(pose.width) && this.height >= pose.height && holds(world);
	}

	/**
	 * Whether the entry holds in {@code world}: in the view it was taken in, by the span's collision
	 * version unless the view is immutable; in another view, by span revision when the entry carries
	 * one and the world answers the same.
	 */
	private boolean holds(final WorldView world) {
		long identity = world.identity();
		if (identity != 0L && this.world == identity) {
			// The very view the entry was taken in: immutable, nothing to read;
			// mutable, the span's version says whether an edit reached it.
			return world.movementFactsImmutable() || this.collisionVersion == span(world, false);
		}
		// Another view: the entry holds there when it answers the same span
		// revision, which a republication that kept the sections does.
		return this.spanRevision != 0L && this.spanRevision == span(world, true);
	}

	/**
	 * Records, from the view the entry was taken in, the span revision it stands under, so a later
	 * check in another view can honor it. Nothing is read when the entry is absent, already carried, or
	 * stale in its own view.
	 */
	void carry(final WorldView world) {
		if (this.spanRevision != 0L || this.world == 0L || this.height < 0.0F) {
			return;
		}
		long identity = world.identity();
		if (identity == 0L || this.world != identity) {
			return;
		}
		if (!world.movementFactsImmutable() && this.collisionVersion != span(world, false)) {
			return;
		}
		this.spanRevision = span(world, true);
	}

	/** Records that {@code pose} fits at the given position in {@code world}; no collision query runs here. */
	void record(final WorldView world, final double x, final double y, final double z, final PlayerState.Pose pose) {
		long xBits = Double.doubleToRawLongBits(x);
		long yBits = Double.doubleToRawLongBits(y);
		long zBits = Double.doubleToRawLongBits(z);
		int widthBits = Float.floatToRawIntBits(pose.width);
		// A view without an identity cannot be keyed on: nothing to record,
		// and every later check is a miss.
		long identity = world.identity();
		if (identity == 0L) {
			clear();
			return;
		}
		// A fresh shorter entry cannot renew a still-valid taller one.
		if (this.world != 0L && this.xBits == xBits && this.yBits == yBits && this.zBits == zBits
		    && this.widthBits == widthBits && this.height >= pose.height && holds(world)) {
			return;
		}
		this.world = identity;
		this.xBits = xBits;
		this.yBits = yBits;
		this.zBits = zBits;
		this.widthBits = widthBits;
		this.height = pose.height;
		// Read last, over the span the fields above now describe. The span
		// revision is not read here: see carry.
		this.spanRevision = 0L;
		this.collisionVersion = world.movementFactsImmutable() ? 0L : span(world, false);
	}

	/** Copies the entry as recorded; every use revalidates against the world it is asked about. */
	void copyInto(final PoseFitCache copy) {
		copy.world = this.world;
		copy.collisionVersion = this.collisionVersion;
		copy.spanRevision = this.spanRevision;
		copy.xBits = this.xBits;
		copy.yBits = this.yBits;
		copy.zBits = this.zBits;
		copy.widthBits = this.widthBits;
		copy.height = this.height;
	}

	/**
	 * The {@link WorldView#spanRevision} ({@code revision}) or {@link WorldView#collisionVersionIn} of the
	 * cells the recorded box can touch.
	 *
	 * <p>The span is rebuilt from the entry's own record rather than stored beside it:
	 * {@code Float.intBitsToFloat} inverts {@code floatToRawIntBits} exactly, and the height is held as
	 * a float already, so this reproduces the probe's box bit for bit. It uses the recorded height,
	 * which {@link #covers} allows to exceed the pose being asked about, so the span is the taller one
	 * and can only be too large. Shapes are cell-bounded wherever an entry can be recorded (recording
	 * is gated on {@code !hasContextSensitiveCollision}), so no cell outside this span can contribute a
	 * box that reaches the recorded one, and a write out there cannot invalidate the entry.
	 */
	private long span(final WorldView world, final boolean revision) {
		float halfWidth = Float.intBitsToFloat(this.widthBits) / 2.0F;
		double cachedX = Double.longBitsToDouble(this.xBits);
		double cachedY = Double.longBitsToDouble(this.yBits);
		double cachedZ = Double.longBitsToDouble(this.zBits);
		double minX = cachedX - halfWidth + COLLISION_EPSILON;
		double minY = cachedY + COLLISION_EPSILON;
		double minZ = cachedZ - halfWidth + COLLISION_EPSILON;
		double maxX = cachedX + halfWidth - COLLISION_EPSILON;
		double maxY = cachedY + this.height - COLLISION_EPSILON;
		double maxZ = cachedZ + halfWidth - COLLISION_EPSILON;
		// The span collectCollisions scans for that box, derived the same way.
		int fromX = Mth.floor(minX - COLLISION_EPSILON) - 1;
		int fromY = Mth.floor(minY - COLLISION_EPSILON) - 1;
		int fromZ = Mth.floor(minZ - COLLISION_EPSILON) - 1;
		int toX = Mth.floor(maxX + COLLISION_EPSILON) + 1;
		int toY = Mth.floor(maxY + COLLISION_EPSILON) + 1;
		int toZ = Mth.floor(maxZ + COLLISION_EPSILON) + 1;
		return revision ? world.spanRevision(fromX, fromY, fromZ, toX, toY, toZ)
		                : world.collisionVersionIn(fromX, fromY, fromZ, toX, toY, toZ);
	}
}
