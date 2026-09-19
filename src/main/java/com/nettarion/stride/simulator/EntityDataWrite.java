package com.nettarion.stride.simulator;

import java.util.Objects;

/**
 * The entity tracker's sample of the server's copy as one {@code sendDirtyEntityData}, bound to the client state it
 * is delivered to. {@link #dirty} says which groups the packet carries; the other components are meaningful only for
 * those groups. The flags group installs sprinting, swimming and gliding on the client's copy; the pose group installs
 * the pose and re-fits the bounding box.
 *
 * @param actionIndex the completed action after which the write is applied
 * @param beforeDigest the digest of the client state the write is bound to
 * @param dirty the groups carried, a mask of {@link #FLAGS}, {@link #POSE}, {@link #FROZEN}, {@link #MOVEMENT_SPEED}
 * @param sharedFlags the sampled shared-flags byte
 * @param pose the sampled pose
 * @param ticksFrozen the sampled frozen ticks
 * @param frostSpeedTicks the sampled frost modifier of the movement-speed attribute, in ticks
 * @param sprintingAttribute whether the sampled movement-speed attribute carried the sprint modifier
 */
public record EntityDataWrite(int actionIndex, long beforeDigest, int dirty, int sharedFlags, PlayerState.Pose pose,
    int ticksFrozen, int frostSpeedTicks, boolean sprintingAttribute) implements ServerWrite {
	/** Dirty-mask bit: the packet carries the shared-flags byte. */
	public static final int FLAGS = 1;

	/** Dirty-mask bit: the packet carries the pose. */
	public static final int POSE = 2;

	/** Dirty-mask bit: the packet carries the frozen tick count. */
	public static final int FROZEN = 4;

	/** Dirty-mask bit: the packet carries the movement-speed attribute. */
	public static final int MOVEMENT_SPEED = 8;

	private static final int ALL_GROUPS = FLAGS | POSE | FROZEN | MOVEMENT_SPEED;

	// The shared-flags byte's bits, numbered as vanilla's Entity.setSharedFlag
	// call sites number them; server.ServerSharedFlags keeps the same numbers.
	private static final int SPRINTING_BIT = 1 << 3;
	private static final int SWIMMING_BIT = 1 << 4;
	private static final int FALL_FLYING_BIT = 1 << 7;

	/** Validates the action index, that the mask names at least one group, and the pose. */
	public EntityDataWrite {
		if (actionIndex < 0 || dirty == 0 || (dirty & ~ALL_GROUPS) != 0) {
			throw new IllegalArgumentException("invalid entity-data action index or dirty mask");
		}
		Objects.requireNonNull(pose, "pose");
	}

	@Override
	public void applyAfterAction(final int completedAction, final PlayerState state) {
		if (completedAction != this.actionIndex) {
			return;
		}
		long actual = StateDigest.state(state);
		if (actual != this.beforeDigest) {
			throw new StaleServerWriteException(this.beforeDigest, actual);
		}
		applyUnchecked(state);
	}

	/**
	 * Apply without checking the action or the digest. Only for the very state {@link #beforeDigest} was taken
	 * from, by the code that took it; every other caller uses {@link #applyAfterAction}.
	 */
	public void applyUnchecked(final PlayerState state) {
		if ((this.dirty & FLAGS) != 0) {
			state.sprinting = (this.sharedFlags & SPRINTING_BIT) != 0;
			state.swimming = (this.sharedFlags & SWIMMING_BIT) != 0;
			state.fallFlying = (this.sharedFlags & FALL_FLYING_BIT) != 0;
		}
		if ((this.dirty & POSE) != 0) {
			state.pose = this.pose;
			state.placeAt(state.x, state.y, state.z);
		}
		if ((this.dirty & FROZEN) != 0) {
			state.ticksFrozen = this.ticksFrozen;
		}
		if ((this.dirty & MOVEMENT_SPEED) != 0) {
			state.frostSpeedTicks = this.frostSpeedTicks;
			state.sprintingAttribute = this.sprintingAttribute;
		}
	}

	/** Former name of {@link #applyUnchecked}, retained for a caller in the root package. */
	public void applyToDigestedState(final PlayerState state) {
		applyUnchecked(state);
	}

	@Override
	public EntityDataWrite withActionIndex(final int index) {
		return new EntityDataWrite(index, this.beforeDigest, this.dirty, this.sharedFlags, this.pose, this.ticksFrozen,
		    this.frostSpeedTicks, this.sprintingAttribute);
	}
}
