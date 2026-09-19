package com.nettarion.stride.simulator.server;

import com.nettarion.stride.simulator.EntityDataWrite;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.Simulator;
import com.nettarion.stride.simulator.StateDigest;

/**
 * {@code ServerEntity.sendChanges} for the player's own tracker, sampled where
 * {@code ChunkMap.tick} runs it: at the head of the level tick, after the
 * packet processor handled the client's packets and before the player entity
 * and the connection tick. Dirty metadata and attributes go to the player
 * itself in one {@code sendDirtyEntityData}; a hurt mark sends the copy's
 * velocity after the tracker's tick count advanced. The composed step takes
 * this sample at its head, which is that point: nothing touches the server's
 * copy between one step's packet drain and the next step's sample.
 * ScheduledSimulation retains the sampled values for later FIFO delivery;
 * live timing is supplied explicitly.
 *
 * <p>The tracker sends attributes only inside the block guarded by
 * {@code tickCount % updateInterval == 0 || needsSync || entityData.isDirty()},
 * and a player's update interval is two. A changed movement-speed attribute
 * with no dirty metadata is therefore sent on the tracker's even ticks only,
 * and the tracker's tick count is not a captured fact, so that sample refuses.
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
	double motionX;
	double motionY;
	double motionZ;

	public static int sharedFlags(final ServerPlayerState server) {
		return (server.sharedFlagOnFire ? 1 : 0) | (server.shiftKeyDown ? 2 : 0) | (server.sprinting ? 8 : 0)
		    | (server.swimming ? 16 : 0) | (server.fallFlying ? 128 : 0);
	}

	void collectChanges(final ServerPlayerState server) {
		if (!server.publicationScheduleKnown) {
			throw new PendingServerWriteException(
			    RefusalCause.UNMODELED_SCHEDULE, "server publication and client delivery schedule is unknown");
		}
		this.flags = sharedFlags(server);
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

	void recordChanges(final int action, final java.util.List<Simulator.Confirmation> output) {
		if (this.flagsDirty) {
			output.add(new Simulator.SprintConfirmation(action, (this.flags & 8) != 0));
			output.add(new Simulator.SwimmingConfirmation(action, (this.flags & 16) != 0));
		}
		if (this.poseDirty) output.add(new Simulator.PoseConfirmation(action, this.pose));
		if (this.frozenDirty) output.add(new Simulator.FreezeConfirmation(action, this.frozen));
	}

	EntityDataWrite publication(final int action, final PlayerState client) {
		int dirty = (this.flagsDirty ? EntityDataWrite.FLAGS : 0) | (this.poseDirty ? EntityDataWrite.POSE : 0)
		    | (this.frozenDirty ? EntityDataWrite.FROZEN : 0) | (this.frostDirty ? EntityDataWrite.MOVEMENT_SPEED : 0);
		return dirty == 0 ? null
		                  : new EntityDataWrite(action, StateDigest.state(client), dirty, this.flags, this.pose,
		                        this.frozen, this.frost, this.sprintingAttribute);
	}
}
