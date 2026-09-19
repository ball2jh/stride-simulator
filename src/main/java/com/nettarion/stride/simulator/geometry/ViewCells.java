package com.nettarion.stride.simulator.geometry;

/**
 * The finite partition of view rotations the pinned physics can tell apart.
 *
 * <p>Yaw and pitch are floats on the wire, but the kernel reads them almost
 * only through {@link Mth#sin} and {@link Mth#cos}, a 65536-entry table
 * indexed by the truncated scaled radian. A cell is the values every read at
 * a site family returns, keyed on the entries' values rather than their
 * indices, so two rotations share a cell exactly when every read of them at
 * those sites returns the same number. Near the table's peaks adjacent
 * entries round to one float and their cells merge, as the physics does.
 *
 * <p>Yaw is read at four sites, all as {@code yRot * (float) (Math.PI / 180.0)}:
 * the input vector rotation in {@code Travel}, the minor-collision test in
 * {@code Move}, which only the client runs, and the sprint-jump boost in
 * {@code AiStep} read the positive angle and form the heading cell; the glide
 * look vector in {@code Travel}
 * negates the angle first and, because the table index truncates after an
 * offset, may read different entries for {@code -a} than for {@code a}, so it
 * forms the look cell. The server's copy holds the packet's yaw wrapped into
 * {@code [-180, 180)} and its own tick, with no input and no jump, reads only
 * the glide look vector, at that wrapped float; for {@code |yRot| >= 180}
 * the wrapped angle rounds differently and may read other entries, so the
 * composed step also keys the look at the wrapped yaw.
 *
 * <p>Pitch is read only in {@code Travel}, and which reads happen depends on
 * the travel branch. On land and in creative flight nothing reads it, so every
 * pitch is one control. The swimming pitch reads one table sine, the swim
 * cell. Fall flying reads the table sine and cosine of the lean angle, the
 * glide cell, and also the real {@code Math.cos} of that angle for lift,
 * which the table does not quantize, so the glide keys are the table cell and
 * the lift value together. A caller merging proposals keys on the reads the
 * coming tick will make, or on a superset of them; a key coarser than the
 * reads merges distinct kinematics.
 *
 * <p>This is kinematic equivalence, not state identity: the rotation floats
 * themselves remain in the state, and {@code Publisher} sends a rotation
 * packet on any float change. A search may merge proposals whose cells agree
 * and keep whichever it proposed first; a delivered plan still carries one
 * exact float per tick and replays bit for bit.
 */
public final class ViewCells {
	private static final float DEGREES_TO_RADIANS = (float) (Math.PI / 180.0);

	private ViewCells() {}

	/** The entries the input, collision and sprint-jump reads of {@code yRot} return. */
	public static long yawHeading(final float yRot) {
		float radians = yRot * DEGREES_TO_RADIANS;
		return pack(Mth.sin(radians), Mth.cos(radians));
	}

	/** The entries the glide look vector's negated read of {@code yRot} returns. */
	public static long yawLook(final float yRot) {
		float negated = -yRot * DEGREES_TO_RADIANS;
		return pack(Mth.sin(negated), Mth.cos(negated));
	}

	/** The entries the server copy's glide look vector reads at the packet's wrapped {@code yRot}. */
	public static long yawWrappedLook(final float yRot) {
		return yawLook(Mth.wrapDegrees(yRot));
	}

	/** The one sine entry the swimming pitch read of {@code xRot} returns. */
	public static long swimPitch(final float xRot) {
		return Float.floatToRawIntBits(Mth.sin(xRot * DEGREES_TO_RADIANS)) & 0xFFFFFFFFL;
	}

	/** The entries the fall-flying table reads of {@code xRot} return. */
	public static long glidePitch(final float xRot) {
		float radians = xRot * DEGREES_TO_RADIANS;
		return pack(Mth.sin(radians), Mth.cos(radians));
	}

	/** The real cosine the fall-flying lift read of {@code xRot} returns, unquantized by the table. */
	public static long glideLift(final float xRot) {
		return Double.doubleToRawLongBits(Math.cos(xRot * DEGREES_TO_RADIANS));
	}

	/** Whether two yaws are kinematically indistinguishable. */
	public static boolean sameYaw(final float a, final float b) {
		return yawHeading(a) == yawHeading(b) && yawLook(a) == yawLook(b) && yawWrappedLook(a) == yawWrappedLook(b);
	}

	/** Whether two pitches are indistinguishable to a swimmer. */
	public static boolean sameSwimPitch(final float a, final float b) {
		return swimPitch(a) == swimPitch(b);
	}

	/** Whether two pitches are indistinguishable to a glider. */
	public static boolean sameGlidePitch(final float a, final float b) {
		return glidePitch(a) == glidePitch(b) && glideLift(a) == glideLift(b);
	}

	private static long pack(final float sin, final float cos) {
		return (long) Float.floatToRawIntBits(sin) << 32 | Float.floatToRawIntBits(cos) & 0xFFFFFFFFL;
	}
}
