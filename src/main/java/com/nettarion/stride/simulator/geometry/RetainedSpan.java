package com.nettarion.stride.simulator.geometry;

import com.nettarion.stride.simulator.world.WorldView;

/**
 * One retained span of collision cells: the boxes a closed cell span owns,
 * unfiltered and grouped by source shape, with the world, version and
 * bounds that make them reusable.
 *
 * <p>Optimization metadata only: every use rechecks world identity, the span's
 * collision version and its bounds, so a stale retention can only become a
 * miss. It is a property of the world neighbourhood rather than of one player,
 * yet each workspace keeps its own: the client's and the server's copies are
 * one packet apart, and sharing a span between them measured a repeatable loss
 * on the captured terrain route, where the two positions straddle cell
 * boundaries and each copy evicts the other's span. A query inside the span
 * reselects the span's groups in place and reads them there; nothing is copied
 * between the span and the resolver.
 *
 * <p>The span is keyed on the {@link WorldView#spanRevision} of its cells
 * when the world gives one: a republished corridor that kept the sections
 * the span read answers the same number, and the span survives the
 * publication. Otherwise the world is named by its
 * {@link WorldView#identity()} number and the span's collision version, and
 * not held: a workspace outlives the corridors it has stepped through, and
 * the span must not keep a superseded one alive. Zero names no world, so a
 * view without an identity is never covered.
 */
public final class RetainedSpan {
	final CollisionBuffer boxes = new CollisionBuffer();
	private long world;
	private long version;
	/**
	 * The span revision retained under, read from the view the span was
	 * retained in the first time another view asks, and zero until then; a
	 * workspace steps in one view for a whole plan, and pays for the revision
	 * only at the publication that follows.
	 */
	private long revision;
	private int x0;
	private int y0;
	private int z0;
	private int x1;
	private int y1;
	private int z1;

	/**
	 * Whether a well-formed, epsilon-expanded query fits the span.
	 *
	 * <p>Faces are compared before flooring: floor(min) >= low iff min >= low,
	 * and {@code floor(max) <= high} iff {@code max < high + 1}. The query is required to be
	 * ordered on each axis, so a hit stands in for the admission a span-building
	 * miss performs; an inverted or NaN face fails and takes the admitted path.
	 */
	public boolean covers(final WorldView world, final double minX, final double minY, final double minZ,
	    final double maxX, final double maxY, final double maxZ) {
		return this.world != 0L && minX >= this.x0 && maxX < this.x1 + 1.0 && minX <= maxX && minY >= this.y0
		    && maxY < this.y1 + 1.0 && minY <= maxY && minZ >= this.z0 && maxZ < this.z1 + 1.0 && minZ <= maxZ
		    && holds(world);
	}

	/** Whether this span was retained in the very view {@code world} is; no version is read. */
	boolean names(final WorldView world) {
		long identity = world.identity();
		return identity != 0L && this.world == identity;
	}

	/**
	 * Carry the retention across a publication: record, from the view it was
	 * retained in, the span revision it stands under. Nothing is read when
	 * there is no retention, it is already carried, or it is stale in its
	 * own view. {@link com.nettarion.stride.simulator.tick.Scratch#carry} calls this for a workspace kept across
	 * a republication.
	 */
	public void carry(final WorldView world) {
		if (this.revision != 0L || this.world == 0L) {
			return;
		}
		long identity = world.identity();
		if (identity == 0L || this.world != identity) {
			return;
		}
		if (!world.movementFactsImmutable()
		    && this.version != world.collisionVersionIn(this.x0, this.y0, this.z0, this.x1, this.y1, this.z1)) {
			return;
		}
		this.revision = world.spanRevision(this.x0, this.y0, this.z0, this.x1, this.y1, this.z1);
	}

	/**
	 * Whether the retention stands in {@code world}: by span revision when
	 * retained under one and the world answers the same over the span, else
	 * by identity and, unless the world is immutable, its collision version.
	 */
	boolean holds(final WorldView world) {
		long identity = world.identity();
		if (identity != 0L && this.world == identity) {
			// The very view the span was retained in: immutable, nothing to
			// read; mutable, the span's version says whether an edit reached it.
			return world.movementFactsImmutable()
			    || this.version == world.collisionVersionIn(this.x0, this.y0, this.z0, this.x1, this.y1, this.z1);
		}
		// Another view: the retention holds there when it answers the same
		// span revision, which a republication that kept the sections does.
		return this.revision != 0L
		    && this.revision == world.spanRevision(this.x0, this.y0, this.z0, this.x1, this.y1, this.z1);
	}

	public void retain(
	    final WorldView world, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
		this.world = world.identity();
		this.version = world.movementFactsImmutable() ? 0L : world.collisionVersionIn(x0, y0, z0, x1, y1, z1);
		this.revision = 0L;
		this.x0 = x0;
		this.y0 = y0;
		this.z0 = z0;
		this.x1 = x1;
		this.y1 = y1;
		this.z1 = z1;
	}

	void discard() {
		this.world = 0L;
	}
}
