package com.nettarion.stride.simulator.server;

import com.nettarion.stride.simulator.EntityDataWrite;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.SharedFlag;
import com.nettarion.stride.simulator.Simulator;
import com.nettarion.stride.simulator.StateDigest;

import java.util.List;

/**
 * The entity tracker's sample of the server's copy: what vanilla's {@code ServerEntity.sendChanges} sends the
 * player about itself at the head of the level tick.
 *
 * <p>{@link #collectChanges} takes the sample where {@code ChunkMap.tick} takes it: after the packet handlers and
 * before the player entity and the connection tick. Dirty metadata and attributes go into one
 * {@code sendDirtyEntityData}, which {@link #write} binds to the client state it is delivered to; the sampled
 * velocity is what a hurt mark republishes. One instance holds one sample at a time and is not thread-safe.
 *
 * <p>The class is public only because {@link #sharedFlags} is read from {@code ServerPlayerState}. A sample whose
 * only change is the movement-speed attribute refuses; see {@link #collectChanges}.
 */
public final class ServerEntity {
	private int flags;

	private PlayerState.Pose pose;

	private int frozen;

	private int frost;

	private boolean sprintingAttribute;

	private boolean flagsDirty;

	private boolean poseDirty;

	private boolean frozenDirty;

	private boolean frostDirty;

	/** The sampled x velocity, in blocks per tick. */
	double motionX;

	/** The sampled y velocity, in blocks per tick. */
	double motionY;

	/** The sampled z velocity, in blocks per tick. */
	double motionZ;

	/** An empty sample. */
	public ServerEntity() {}

	/** The shared-flags byte of the server's copy, as the tracker samples it. */
	public static int sharedFlags(final ServerPlayerState server) {
		return sharedFlagsOf(server);
	}

	/**
	 * Take the sample and mark the copy's dirty entries sent.
	 *
	 * @throws PendingServerWriteException when the copy's delivery schedule is unknown, or when only the
	 *     movement-speed attribute changed
	 */
	void collectChanges(final ServerPlayerState server) {
		// The tracker sends attributes only inside the block guarded by
		// tickCount % updateInterval == 0 || needsSync || entityData.isDirty(),
		// and a player's update interval is two. A changed movement-speed
		// attribute with no dirty metadata is therefore sent on the tracker's
		// even ticks only, and the tracker's tick count is not a captured fact.
		if (!server.publicationScheduleKnown) {
			throw new PendingServerWriteException(
			    RefusalCause.UNMODELED_SCHEDULE, "server write and client delivery schedule is unknown");
		}
		this.flags = sharedFlagsOf(server);
		this.pose = server.pose;
		this.frozen = server.ticksFrozen;
		this.frost = server.frostSpeedTicks;
		this.sprintingAttribute = server.sprintingAttribute;
		this.flagsDirty =
		    (server.entityDataDirty & EntityDataWrite.FLAGS) != 0 || this.flags != server.lastSentSharedFlags;
		this.poseDirty = (server.entityDataDirty & EntityDataWrite.POSE) != 0 || this.pose != server.lastSentPose;
		this.frozenDirty =
		    (server.entityDataDirty & EntityDataWrite.FROZEN) != 0 || this.frozen != server.lastSentTicksFrozen;
		this.frostDirty = server.movementSpeedAttributeDirty || this.frost != server.lastSentFrostSpeedTicks;
		if (this.frost != server.lastSentFrostSpeedTicks && !this.flagsDirty && !this.poseDirty && !this.frozenDirty) {
			throw new PendingServerWriteException(RefusalCause.PENDING_SERVER_RANDOM,
			    "the movement-speed attribute changed"
			        + " from a frost modifier of " + server.lastSentFrostSpeedTicks + " to " + this.frost
			        + " with no dirty entity data; ServerEntity sends attributes"
			        + " alone only on its even ticks and the tracker's tick parity is not a"
			        + " captured fact");
		}
		server.movementSpeedAttributeDirty = false;
		server.entityDataDirty = 0;
		// removeFrost/tryAddFrost dirty the attribute even if the final modifier is equal.
		server.lastSentSharedFlags = this.flags;
		server.lastSentPose = this.pose;
		server.lastSentTicksFrozen = this.frozen;
		server.lastSentFrostSpeedTicks = this.frost;
		this.motionX = server.deltaMovementX;
		this.motionY = server.deltaMovementY;
		this.motionZ = server.deltaMovementZ;
	}

	/** Forget the sample, so a transaction that took none delivers and records nothing. */
	void clear() {
		this.flagsDirty = false;
		this.poseDirty = false;
		this.frozenDirty = false;
		this.frostDirty = false;
	}

	/** The server copy's shared-flags byte as vanilla's {@code Entity} packs it. */
	static int sharedFlagsOf(final ServerPlayerState server) {
		return (server.sharedFlagOnFire ? SharedFlag.ON_FIRE_MASK : 0)
		    | (server.shiftKeyDown ? SharedFlag.SHIFT_KEY_DOWN_MASK : 0)
		    | (server.sprinting ? SharedFlag.SPRINTING_MASK : 0) | (server.swimming ? SharedFlag.SWIMMING_MASK : 0)
		    | (server.fallFlying ? SharedFlag.FALL_FLYING_MASK : 0);
	}

	/**
	 * Append the sample's confirmations, one per value the client may see change. The glide flag has no
	 * confirmation of its own: a caller attributes a glide echo through the flags byte of the
	 * {@link EntityDataWrite}, since {@code Simulator.Confirmation} has no glide member.
	 */
	void recordChanges(final int action, final List<Simulator.Confirmation> output) {
		if (this.flagsDirty) {
			output.add(new Simulator.SprintConfirmation(action, (this.flags & SharedFlag.SPRINTING_MASK) != 0));
			output.add(new Simulator.SwimmingConfirmation(action, (this.flags & SharedFlag.SWIMMING_MASK) != 0));
		}
		if (this.poseDirty) {
			output.add(new Simulator.PoseConfirmation(action, this.pose));
		}
		if (this.frozenDirty) {
			output.add(new Simulator.FreezeConfirmation(action, this.frozen));
		}
	}

	/** The sample as one write bound to {@code client}, or {@code null} when nothing was dirty. */
	EntityDataWrite write(final int action, final PlayerState client) {
		int dirty = (this.flagsDirty ? EntityDataWrite.FLAGS : 0) | (this.poseDirty ? EntityDataWrite.POSE : 0)
		    | (this.frozenDirty ? EntityDataWrite.FROZEN : 0) | (this.frostDirty ? EntityDataWrite.MOVEMENT_SPEED : 0);
		return dirty == 0 ? null
		                  : new EntityDataWrite(action, StateDigest.state(client), dirty, this.flags, this.pose,
		                        this.frozen, this.frost, this.sprintingAttribute);
	}
}
