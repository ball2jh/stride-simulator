package com.nettarion.stride.simulator.server;

import com.nettarion.stride.simulator.CorrectionReason;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.MovementPacket;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.geometry.CollisionCollector;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.tick.EntityFluidInteraction;
import com.nettarion.stride.simulator.tick.Move;
import com.nettarion.stride.simulator.tick.PlayerTick;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.tick.SupportingBlock;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.SupportCell;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * The admitted input, sprint-command and movement handlers of the server listener:
 * {@code ServerMovementListener.handleMovePlayer} decoded on the
 * server's copy, deciding whether the packet is accepted or corrected.
 *
 * <p>Absent coordinates or rotation fall back to the server's current
 * values, a rising ground jump is re-applied with the server's sprint flag,
 * the displacement is collision-resolved on the server's copy, the finite
 * vertical residual is erased before the strict quarter-block moved-wrongly
 * test, that test is forgiven when the pre-packet box was already occupied,
 * and a target that meets any block shape the old box did not (powder snow
 * and scaffolding never counting, as under a placement context) is rejected
 * regardless. Acceptance snaps to the target and installs the packet's
 * ground and contact bits, support, fall, floating, and known movement,
 * landing the packet's descent through the landed block's {@code fallOn};
 * a correction sends the client back to the pre-packet position with the
 * packet's rotation, zero velocity, and a pending acknowledgement. Pending packets update rotation and can trigger a timed retry.
 *
 * <p>Advances the server's copy in place. The hit a landing deals goes to
 * {@link TickAuthority#survival()}, which {@link ServerTick} bound to the copy.
 */
public final class ServerMovementListener {
	/*
	 * Vanilla installs a packet's pitch as `Mth.clamp(xRot, -90, 90) % 360`.
	 * The remainder is exact (JLS 15.17.3) and its quotient is zero on the whole
	 * clamped range, so it returns the clamped value itself, signed zero and NaN
	 * included; it is therefore not taken.
	 */
	private static final double MOVED_WRONGLY_SQUARED = 0.0625;
	/** The float {@code 1.0E-5F} the new-collision test deflates both boxes by. */
	private static final double DEFLATION = (double) 1.0E-5F;
	/** {@code IndirectMerger}'s coordinate merge: an overlap within it is empty. */
	private static final double COORDINATE_MERGE = 1.0E-7;

	private ServerMovementListener() {}

	public static void resetPosition(final ServerPlayerState server) {
		server.firstGoodX = server.lastGoodX = server.x;
		server.firstGoodY = server.lastGoodY = server.y;
		server.firstGoodZ = server.lastGoodZ = server.z;
	}

	private static void awaitTeleport(final ServerPlayerState server) {
		server.placeAt(0.0 + server.x, 0.0 + server.y, 0.0 + server.z);
		server.yRot = 0.0F + server.yRot;
		server.xRot = Mth.clamp(0.0F + server.xRot, -90.0F, 90.0F);
		if (++server.awaitingTeleport == Integer.MAX_VALUE) server.awaitingTeleport = 0;
		server.awaitingPositionX = server.x;
		server.awaitingPositionY = server.y;
		server.awaitingPositionZ = server.z;
		server.clearMovementThisTick();
		server.correctionPending = true;
		server.awaitingTeleportTime = server.connectionTickCount;
	}

	static void handleAcceptTeleportPacket(final ServerPlayerState server, final int id) {
		if (id != server.awaitingTeleport) return;
		if (!server.correctionPending) {
			throw new UnimplementedMechanicException(RefusalCause.UNMODELED_SESSION_END,
			    "the listener disconnects an acknowledgement without an awaiting position");
		}
		server.placeAt(server.awaitingPositionX, server.awaitingPositionY, server.awaitingPositionZ);
		server.correctionPending = false;
		server.lastGoodX = server.awaitingPositionX;
		server.lastGoodY = server.awaitingPositionY;
		server.lastGoodZ = server.awaitingPositionZ;
	}

	/**
         * ClientTickEnd clears known movement when this client's tick had no
         * accepted movement packet.
         */
	static void handleClientTickEnd(final ServerPlayerState server, final boolean receivedMovementThisTick) {
		if (!receivedMovementThisTick) {
			server.lastKnownClientMovementX = 0.0;
			server.lastKnownClientMovementY = 0.0;
			server.lastKnownClientMovementZ = 0.0;
		}
	}

	/** ServerMovementListener.handlePlayerInput for an already loaded client. */
	static void handlePlayerInput(final ServerPlayerState server, final PlayerInput input) {
		server.setSharedFlag(1, input.sneak());
	}

	/** ServerMovementListener.handlePlayerCommand's START/STOP_SPRINTING cases. */
	static void handlePlayerCommand(final ServerPlayerState server, final boolean sprinting) {
		server.setSprinting(sprinting);
	}

	/**
	 * {@code handleMovePlayer} on the decoded packet, against the position
	 * the connection tick restored.
	 *
	 * @param clientAfter the client's copy after its tick
	 * @param packet the movement packet form the client's publisher selected
	 *     for that result, never {@link MovementPacket#NONE}
	 * @param scratch the server copy's scratch, carrying the bound
	 *     {@link Survival}
	 * @param reportSuccess whether to construct the accepted report; corrections always report
	 */
	static ServerTick.Transaction handleMovePlayer(final PlayerState clientAfter, final MovementPacket packet,
	    final ServerPlayerState server, final SnapshotView world, final Scratch scratch, final boolean reportSuccess) {
		if (packet.hasPosition()
		        && (Double.isNaN(clientAfter.x) || Double.isNaN(clientAfter.y) || Double.isNaN(clientAfter.z))
		    || packet.hasRotation() && (!Float.isFinite(clientAfter.yRot) || !Float.isFinite(clientAfter.xRot))) {
			throw new UnimplementedMechanicException(
			    RefusalCause.UNMODELED_SESSION_END, "invalid movement packet disconnects the client");
		}
		Survival survival = scratch.authority.survival();
		if (server.connectionTickCount == 0) resetPosition(server);
		// Absent fields decode to the server's current values.
		float targetYRot = Mth.wrapDegrees(packet.hasRotation() ? clientAfter.yRot : server.yRot);
		float targetXRot = Mth.wrapDegrees(packet.hasRotation() ? clientAfter.xRot : server.xRot);
		if (server.correctionPending) {
			ServerTick.Transaction result;
			if (server.connectionTickCount - server.awaitingTeleportTime > 20) {
				server.placeAt(server.awaitingPositionX, server.awaitingPositionY, server.awaitingPositionZ);
				server.deltaMovementX = server.deltaMovementY = server.deltaMovementZ = 0.0;
				awaitTeleport(server);
				result = new ServerTick.Corrected(packet, CorrectionReason.TELEPORT_RETRY, server.x, server.y, server.z,
				    server.x, server.y, server.z, 0, server.x, server.y, server.z, server.yRot, server.xRot,
				    survival.marked(), survival.dealt());
			} else
				result = new ServerTick.AwaitingTeleport(packet, survival.marked(), survival.dealt());
			server.yRot = targetYRot;
			server.xRot = Mth.clamp(targetXRot, -90.0F, 90.0F);
			return result;
		}
		server.awaitingTeleportTime = server.connectionTickCount;
		double targetX = Mth.clamp(packet.hasPosition() ? clientAfter.x : server.x, -3.0E7, 3.0E7);
		double targetY = Mth.clamp(packet.hasPosition() ? clientAfter.y : server.y, -2.0E7, 2.0E7);
		double targetZ = Mth.clamp(packet.hasPosition() ? clientAfter.z : server.z, -3.0E7, 3.0E7);
		double startX = server.x;
		double startY = server.y;
		double startZ = server.z;
		// Speed validation measures from the beginning of this connection tick.
		double xDist = targetX - server.firstGoodX;
		double yDist = targetY - server.firstGoodY;
		double zDist = targetZ - server.firstGoodZ;
		int deltaPackets = ++server.receivedMovePacketCount - server.knownMovePacketCount;
		if (deltaPackets > 5) deltaPackets = 1;
		double expectedDist = server.deltaMovementX * server.deltaMovementX
		    + server.deltaMovementY * server.deltaMovementY + server.deltaMovementZ * server.deltaMovementZ;
		double movedDist = xDist * xDist + yDist * yDist + zDist * zDist;
		if (shouldCheckPlayerMovement(server)
		    && movedDist - expectedDist > (server.fallFlying ? 300.0F : 100.0F) * deltaPackets) {
			server.deltaMovementX = 0.0;
			server.deltaMovementY = 0.0;
			server.deltaMovementZ = 0.0;
			awaitTeleport(server);
			return new ServerTick.Corrected(packet, CorrectionReason.MOVED_TOO_QUICKLY, targetX, targetY, targetZ,
			    startX, startY, startZ, movedDist, startX, startY, startZ, server.yRot, server.xRot, survival.marked(),
			    survival.dealt());
		}
		xDist = targetX - server.lastGoodX;
		yDist = targetY - server.lastGoodY;
		zDist = targetZ - server.lastGoodZ;
		boolean movedUpwards = yDist > 0.0;
		if (server.onGround && !clientAfter.onGround && movedUpwards) {
			ServerPlayerTick.jumpFromGround(server, world);
		}
		boolean standsOnSomething = server.verticalCollisionBelow;
		double oldMinX = server.boundingBoxMinX;
		double oldMinY = server.boundingBoxMinY;
		double oldMinZ = server.boundingBoxMinZ;
		double oldMaxX = server.boundingBoxMaxX;
		double oldMaxY = server.boundingBoxMaxY;
		double oldMaxZ = server.boundingBoxMaxZ;
		Move.resolveOwned(server, xDist, yDist, zDist, server.shiftKeyDown, world, scratch);
		double resolvedX = server.x;
		double resolvedY = server.y;
		double resolvedZ = server.z;
		if (scratch.hasMovementSegment) {
			server.addMovementThisTick(scratch.segmentFromX, scratch.segmentFromY, scratch.segmentFromZ, resolvedX,
			    resolvedY, resolvedZ, scratch.segmentRequestedX, scratch.segmentRequestedZ);
		}
		double residualX = targetX - resolvedX;
		double residualY = targetY - resolvedY;
		// `yDist > -0.5 || yDist < 0.5` holds for every finite value, so a
		// finite vertical residual never counts.
		if (residualY > -0.5 || residualY < 0.5) {
			residualY = 0.0;
		}
		double residualZ = targetZ - resolvedZ;
		double residualSquared = residualX * residualX + residualY * residualY + residualZ * residualZ;
		boolean fail = residualSquared > MOVED_WRONGLY_SQUARED && server.currentImpulseContextResetGraceTime == 0;
		// Level.noCollision(player, oldAABB) runs after the move, and its
		// CollisionContext.of(player) reads the player's current feet, which
		// are the resolved position's, not the old box's.
		boolean oldBoxFree = fail
		    && noCollision(server, oldMinX, oldMinY, oldMinZ, oldMaxX, oldMaxY, oldMaxZ, server.y, server.shiftKeyDown,
		        world, scratch);
		// Vanilla's `(!fail || !noCollision(old)) && !collidingNew` short-circuits:
		// a wrong move from a free start is corrected without the new-block query,
		// whose air it may not need declared.
		boolean newCollision = !(fail && oldBoxFree)
		    && collidesWithAnythingNew(server, oldMinX, oldMinY, oldMinZ, oldMaxX, oldMaxY, oldMaxZ, targetX, targetY,
		        targetZ, server.shiftKeyDown, world, scratch);
		if (fail && oldBoxFree || newCollision) {
			if (scratch.hasMovementSegment) server.removeLatestMovementRecording();
			// teleport(startX, startY, startZ, targetYRot, targetXRot): an absolute
			// destination with zero velocity, then fall handling on the snapped
			// (zero) movement with the packet's ground bit.
			snapTo(server, startX, startY, startZ);
			server.yRot = targetYRot;
			server.xRot = Mth.clamp(targetXRot, -90.0F, 90.0F);
			server.deltaMovementX = 0.0;
			server.deltaMovementY = 0.0;
			server.deltaMovementZ = 0.0;
			awaitTeleport(server);
			// doCheckFallDamage on the snapped (zero) movement passes the packet's
			// ground bit to the support search without installing it.
			SupportingBlock.checkSupportingBlock(
			    server, clientAfter.onGround, 0.0, 0.0, server.shiftKeyDown, world, scratch);
			checkFallDamage(server, 0.0, clientAfter.onGround, world, scratch);
			return new ServerTick.Corrected(packet,
			    newCollision ? CorrectionReason.NEW_COLLISION : CorrectionReason.MOVED_WRONGLY, targetX, targetY,
			    targetZ, resolvedX, resolvedY, resolvedZ, residualSquared, startX, startY, startZ, targetYRot,
			    targetXRot, survival.marked(), survival.dealt());
		}

		snapTo(server, targetX, targetY, targetZ);
		server.yRot = targetYRot;
		server.xRot = Mth.clamp(targetXRot, -90.0F, 90.0F);
		boolean airborneCandidate =
		    yDist >= -0.03125 && !standsOnSomething && !server.allowFlight && !server.mayfly && !server.fallFlying;
		WorldView.Air around = airborneCandidate ? noBlocksAround(server, world) : WorldView.Air.NOT_AIR;
		server.clientIsFloating = around == WorldView.Air.ALL_AIR;
		server.floatingUnknown = around == WorldView.Air.UNKNOWN;
		double clientDeltaX = server.x - startX;
		double clientDeltaY = server.y - startY;
		double clientDeltaZ = server.z - startZ;
		// setOnGroundWithMovement(packet ground, packet horizontal, delta), then
		// doCheckFallDamage, whose support search repeats the same query.
		server.onGround = clientAfter.onGround;
		server.horizontalCollision = clientAfter.horizontalCollision;
		SupportingBlock.checkSupportingBlock(server, clientDeltaX, clientDeltaZ, server.shiftKeyDown, world, scratch);
		checkFallDamage(server, clientDeltaY, clientAfter.onGround, world, scratch);
		server.lastKnownClientMovementX = clientDeltaX;
		server.lastKnownClientMovementY = clientDeltaY;
		server.lastKnownClientMovementZ = clientDeltaZ;
		if (movedUpwards) {
			server.fallDistance = 0.0;
		}
		if (clientAfter.onGround
		    || server.deltaMovementY < (double) 1.0E-5F && (server.waterHeight > 0.0 || server.lavaHeight > 0.0)
		    || PlayerTick.onClimbable(server, world) || server.fallFlying) {
			server.tryResetCurrentImpulseContext();
		}
		ServerPlayerTick.checkMovementStatistics(server, clientDeltaX, clientDeltaY, clientDeltaZ, world);
		server.lastGoodX = server.x;
		server.lastGoodY = server.y;
		server.lastGoodZ = server.z;
		if (!reportSuccess) {
			return null;
		}
		return new ServerTick.Accepted(packet, targetX, targetY, targetZ, residualSquared, fail && !oldBoxFree,
		    server.clientIsFloating, server.floatingUnknown, survival.marked(), survival.dealt());
	}

	private static boolean shouldCheckPlayerMovement(final ServerPlayerState server) {
		return !server.singleplayerOwner && server.playerMovementCheck
		    && (!server.fallFlying || server.elytraMovementCheck);
	}

	/**
	 * {@code Entity.checkFallDamage} as the accepted packet runs it: fall
	 * accumulates from the packet's descent, then the packet's ground bit
	 * lands it through the landed block's {@code fallOn} and resets it. The
	 * landing is {@code Player.causeFallDamage}: nothing when the player may
	 * fly, else a hit whenever the floored fall power times the block's
	 * multiplier is positive.
	 */
	private static void checkFallDamage(final ServerPlayerState server, final double ya, final boolean onGround,
	    final SnapshotView world, final Scratch scratch) {
		// LivingEntity.checkFallDamage refreshes the fluid tracker at the landed
		// position when the copy was dry, so a descent that ends in water resets
		// the fall and takes the current before the ground test reads either.
		EntityFluidInteraction.refreshWhenDry(server, world, scratch);
		if (!(server.waterHeight > 0.0) && ya < 0.0) {
			server.fallDistance -= (float) ya;
		}
		if (onGround) {
			if (server.fallDistance > 0.0) {
				fallOn(server, world, scratch);
			}
			server.fallDistance = 0.0;
		}
	}

	/** {@code Block.fallOn} of the block at {@code getOnPosLegacy}, on the accumulated fall. */
	private static void fallOn(final ServerPlayerState server, final SnapshotView world, final Scratch scratch) {
		SupportingBlock.getOnPosLegacy(server, world, scratch);
		SupportCell cell = scratch.support;
		world.behaviorAt(cell.x, cell.y, cell.z)
		    .fallOn(server.fallDistance, cell.x, cell.y, cell.z, scratch.authority.survival());
	}

	/**
	 * {@code Entity.setPosRaw}: the position is rewritten only when a
	 * coordinate differs numerically, so a target of {@code -0.0} leaves a
	 * retained {@code +0.0} in place.
	 */
	private static void snapTo(final PlayerState server, final double x, final double y, final double z) {
		if (server.x != x || server.y != y || server.z != z) {
			server.placeAt(x, y, z);
		}
	}

	/**
	 * {@code Level.noCollision(entity, box)} over block shapes, under
	 * {@code CollisionContext.of(entity)}: the entity's own feet at the time
	 * of the query, {@code isDescending}, fall distance and boots.
	 */
	private static boolean noCollision(final ServerPlayerState server, final double minX, final double minY,
	    final double minZ, final double maxX, final double maxY, final double maxZ, final double entityBottom,
	    final boolean descending, final SnapshotView world, final Scratch scratch) {
		return CollisionCollector
		    .collect(world, scratch, minX, minY, minZ, maxX, maxY, maxZ, entityBottom, descending, server.fallDistance,
		        server.canWalkOnPowderSnow)
		    .isEmpty();
	}

	/**
	 * {@code isEntityCollidingWithAnythingNew}: any block shape meeting the
	 * target box deflated by float {@code 1.0E-5} that does not also meet the
	 * old box deflated the same way. A shape is one block's whole
	 * {@code VoxelShape}: it is new only when none of its boxes meets the old
	 * box, and the boolean join treats an overlap within the {@code 1.0E-7}
	 * coordinate merge as empty.
	 *
	 * <p>The shapes come from {@code getPreMoveCollisions}, whose
	 * {@code CollisionContext.withPosition} is a placement context under which
	 * powder snow and scaffolding have no shape:
	 * {@link CollisionCollector#collectPreMove}.
	 */
	private static boolean collidesWithAnythingNew(final ServerPlayerState server, final double oldMinX,
	    final double oldMinY, final double oldMinZ, final double oldMaxX, final double oldMaxY, final double oldMaxZ,
	    final double targetX, final double targetY, final double targetZ, final boolean descending,
	    final SnapshotView world, final Scratch scratch) {
		double shiftX = targetX - server.x;
		double shiftY = targetY - server.y;
		double shiftZ = targetZ - server.z;
		double newMinX = server.boundingBoxMinX + shiftX + DEFLATION;
		double newMinY = server.boundingBoxMinY + shiftY + DEFLATION;
		double newMinZ = server.boundingBoxMinZ + shiftZ + DEFLATION;
		double newMaxX = server.boundingBoxMaxX + shiftX - DEFLATION;
		double newMaxY = server.boundingBoxMaxY + shiftY - DEFLATION;
		double newMaxZ = server.boundingBoxMaxZ + shiftZ - DEFLATION;
		CollisionBuffer shapes =
		    CollisionCollector.collectPreMove(world, scratch, newMinX, newMinY, newMinZ, newMaxX, newMaxY, newMaxZ);
		double deflatedOldMinX = oldMinX + DEFLATION;
		double deflatedOldMinY = oldMinY + DEFLATION;
		double deflatedOldMinZ = oldMinZ + DEFLATION;
		double deflatedOldMaxX = oldMaxX - DEFLATION;
		double deflatedOldMaxY = oldMaxY - DEFLATION;
		double deflatedOldMaxZ = oldMaxZ - DEFLATION;
		final int selected = shapes.selectedGroupCount();
		for (int k = 0; k < selected; k++) {
			final int group = shapes.selectedGroup(k);
			final int end = shapes.groupEnd(group);
			// Shapes.joinIsNotEmpty(shape, oldShape, AND) over the whole shape.
			boolean shapeMeetsOld = false;
			for (int i = shapes.groupStart(group); i < end && !shapeMeetsOld; i++) {
				shapeMeetsOld = overlaps(shapes.minX(i), shapes.maxX(i), deflatedOldMinX, deflatedOldMaxX)
				    && overlaps(shapes.minY(i), shapes.maxY(i), deflatedOldMinY, deflatedOldMaxY)
				    && overlaps(shapes.minZ(i), shapes.maxZ(i), deflatedOldMinZ, deflatedOldMaxZ);
			}
			if (!shapeMeetsOld) {
				return true;
			}
		}
		return false;
	}

	private static boolean overlaps(final double minA, final double maxA, final double minB, final double maxB) {
		return Math.min(maxA, maxB) - Math.max(minA, minB) > COORDINATE_MERGE;
	}

	/**
	 * {@code noBlocksAround}: every block state in the box inflated by
	 * {@code 0.0625} and extended {@code 0.55} downward is air.
	 */
	private static WorldView.Air noBlocksAround(final PlayerState server, final SnapshotView world) {
		return world.airIn(Mth.floor(server.boundingBoxMinX - 0.0625),
		    Mth.floor(server.boundingBoxMinY - 0.0625 - 0.55), Mth.floor(server.boundingBoxMinZ - 0.0625),
		    Mth.floor(server.boundingBoxMaxX + 0.0625), Mth.floor(server.boundingBoxMaxY + 0.0625),
		    Mth.floor(server.boundingBoxMaxZ + 0.0625));
	}
}
