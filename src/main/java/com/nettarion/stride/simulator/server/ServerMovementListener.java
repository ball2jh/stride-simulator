package com.nettarion.stride.simulator.server;

import com.nettarion.stride.simulator.CorrectionReason;
import com.nettarion.stride.simulator.MovementCorrection;
import com.nettarion.stride.simulator.MovementPacket;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.SharedFlag;
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
 * The packet handlers of vanilla's {@code ServerGamePacketListenerImpl} that the admitted domain reaches: the input
 * packet, the sprint command, the end-of-tick marker, the teleport acknowledgement, and {@code handleMovePlayer},
 * which decides whether a movement packet is accepted or corrected.
 *
 * <p>{@link #handleMovePlayer} collision-resolves the packet's displacement on the server's copy and applies the
 * moved-too-quickly, moved-wrongly and new-collision tests in vanilla's order; acceptance snaps to the target and
 * lands the descent, a correction sends the client back and awaits the acknowledgement. Every method advances the
 * server's copy in place; a landing's hit goes to {@link TickAuthority#damage()}, which {@link ServerTick} bound
 * to the copy. Positions are in blocks, rotations in degrees.
 */
public final class ServerMovementListener {
	/** {@code ServerGamePacketListenerImpl.clampHorizontal}: coordinates are clamped to this magnitude, in blocks. */
	private static final double HORIZONTAL_COORDINATE_LIMIT = 3.0E7;

	/** {@code ServerGamePacketListenerImpl.clampVertical}: the vertical clamp, in blocks. */
	private static final double VERTICAL_COORDINATE_LIMIT = 2.0E7;

	/** More movement packets than this since the last connection tick are counted as one for the speed budget. */
	private static final int PACKET_BURST_LIMIT = 5;

	/** The squared movement a walking client may exceed its velocity by per packet, in blocks squared. */
	private static final float MOVED_TOO_QUICKLY_BUDGET = 100.0F;

	/** The same budget for a gliding client. */
	private static final float ELYTRA_MOVED_TOO_QUICKLY_BUDGET = 300.0F;

	/** A pending correction is resent when a movement packet arrives after this many connection ticks. */
	private static final int TELEPORT_RETRY_TICKS = 20;

	/** The strict quarter block squared: a larger horizontal residual after collision moved wrongly. */
	private static final double MOVED_WRONGLY_SQUARED = 0.0625;

	/** The float {@code 1.0E-5F} the new-collision test deflates both boxes by. */
	private static final double DEFLATION = (double) 1.0E-5F;

	/** {@code IndirectMerger}'s coordinate merge: an overlap within it is empty. */
	private static final double COORDINATE_MERGE = 1.0E-7;

	/** A packet descending by less than this, in blocks, still counts as a floating candidate. */
	private static final double FLOATING_DESCENT_ALLOWANCE = -0.03125;

	/** {@code noBlocksAround} inflates the box by this on every side, in blocks. */
	private static final double NO_BLOCKS_AROUND_INFLATION = 0.0625;

	/** {@code noBlocksAround} extends the inflated box this far downward, in blocks. */
	private static final double NO_BLOCKS_AROUND_DEPTH = 0.55;

	private ServerMovementListener() {}

	/** Set both position anchors to the copy's current position, as the connection tick and the first packet do. */
	public static void resetPosition(final ServerPlayerState server) {
		server.firstGoodX = server.lastGoodX = server.x;
		server.firstGoodY = server.lastGoodY = server.y;
		server.firstGoodZ = server.lastGoodZ = server.z;
	}

	/**
	 * {@code ServerGamePacketListenerImpl.handleAcceptTeleportPacket}: an id other than the awaited one is ignored;
	 * the awaited one installs the awaited position and clears the pending correction.
	 *
	 * @throws UnimplementedMechanicException when the awaited id arrives with no correction pending, which
	 *     disconnects the client
	 */
	static void handleAcceptTeleportPacket(final ServerPlayerState server, final int id) {
		if (id != server.awaitingTeleport) {
			return;
		}
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

	/** {@code handleClientTickEnd}: known movement is cleared when the client tick had no accepted movement packet. */
	static void handleClientTickEnd(final ServerPlayerState server, final boolean receivedMovementThisTick) {
		if (!receivedMovementThisTick) {
			server.lastKnownClientMovementX = 0.0;
			server.lastKnownClientMovementY = 0.0;
			server.lastKnownClientMovementZ = 0.0;
		}
	}

	/** {@code handlePlayerInput} for an already loaded client: the sneak key becomes the synced shift flag. */
	static void handlePlayerInput(final ServerPlayerState server, final PlayerInput input) {
		server.setSharedFlag(SharedFlag.SHIFT_KEY_DOWN, input.sneak());
	}

	/** {@code handlePlayerCommand}'s {@code START_SPRINTING} and {@code STOP_SPRINTING} cases. */
	static void handlePlayerCommand(final ServerPlayerState server, final boolean sprinting) {
		server.setSprinting(sprinting);
	}

	/**
	 * {@code handleMovePlayer} on the decoded packet, against the position the connection tick restored.
	 *
	 * @param clientAfter the client's copy after its tick, which the packet was encoded from
	 * @param kind the packet kind the client's publisher selected for that tick, never {@link MovementPacket#NONE}
	 * @param scratch the server copy's scratch, carrying the bound {@link ServerDamage}
	 * @throws UnimplementedMechanicException when the packet carries a NaN coordinate or a non-finite angle, which
	 *     disconnects the client
	 */
	static ServerTick.Transaction handleMovePlayer(final PlayerState clientAfter, final MovementPacket kind,
	    final ServerPlayerState server, final SnapshotView world, final Scratch scratch) {
		if ((kind.hasPosition()
		        && (Double.isNaN(clientAfter.x) || Double.isNaN(clientAfter.y) || Double.isNaN(clientAfter.z)))
		    || (kind.hasRotation() && (!Float.isFinite(clientAfter.yRot) || !Float.isFinite(clientAfter.xRot)))) {
			throw new UnimplementedMechanicException(
			    RefusalCause.UNMODELED_SESSION_END, "invalid movement packet disconnects the client");
		}
		ServerDamage damage = scratch.authority.damage();
		if (server.connectionTickCount == 0) {
			resetPosition(server);
		}
		// Absent fields decode to the server's current values.
		float targetYRot = Mth.wrapDegrees(kind.hasRotation() ? clientAfter.yRot : server.yRot);
		float targetXRot = Mth.wrapDegrees(kind.hasRotation() ? clientAfter.xRot : server.xRot);
		if (server.correctionPending) {
			return awaitingCorrection(kind, server, targetYRot, targetXRot, damage);
		}
		server.awaitingTeleportTime = server.connectionTickCount;
		double targetX = Mth.clamp(
		    kind.hasPosition() ? clientAfter.x : server.x, -HORIZONTAL_COORDINATE_LIMIT, HORIZONTAL_COORDINATE_LIMIT);
		double targetY = Mth.clamp(
		    kind.hasPosition() ? clientAfter.y : server.y, -VERTICAL_COORDINATE_LIMIT, VERTICAL_COORDINATE_LIMIT);
		double targetZ = Mth.clamp(
		    kind.hasPosition() ? clientAfter.z : server.z, -HORIZONTAL_COORDINATE_LIMIT, HORIZONTAL_COORDINATE_LIMIT);
		double startX = server.x;
		double startY = server.y;
		double startZ = server.z;
		// Speed validation measures from the beginning of this connection tick.
		double xDist = targetX - server.firstGoodX;
		double yDist = targetY - server.firstGoodY;
		double zDist = targetZ - server.firstGoodZ;
		int deltaPackets = ++server.receivedMovePacketCount - server.knownMovePacketCount;
		if (deltaPackets > PACKET_BURST_LIMIT) {
			deltaPackets = 1;
		}
		double expectedDist = server.deltaMovementX * server.deltaMovementX
		    + server.deltaMovementY * server.deltaMovementY + server.deltaMovementZ * server.deltaMovementZ;
		double movedDist = xDist * xDist + yDist * yDist + zDist * zDist;
		if (shouldCheckPlayerMovement(server)
		    && movedDist - expectedDist
		        > (server.fallFlying ? ELYTRA_MOVED_TOO_QUICKLY_BUDGET : MOVED_TOO_QUICKLY_BUDGET) * deltaPackets) {
			zeroVelocity(server);
			awaitTeleport(server);
			// The server never moved: the correction is the start position with
			// the copy's own rotation, read after awaitTeleport canonicalized it.
			return corrected(kind, CorrectionReason.MOVED_TOO_QUICKLY,
			    new MovementCorrection.Target(targetX, targetY, targetZ),
			    new MovementCorrection.Resolved(startX, startY, startZ, movedDist),
			    new MovementCorrection.Teleport(startX, startY, startZ, server.yRot, server.xRot), damage);
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
		Move.resolveAdmitted(server, xDist, yDist, zDist, server.shiftKeyDown, world, scratch);
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
		// finite vertical residual never counts. Only a NaN fails both halves,
		// and the NaN packet was refused above, so the branch is always taken.
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
			return correct(kind, newCollision ? CorrectionReason.NEW_COLLISION : CorrectionReason.MOVED_WRONGLY,
			    clientAfter, server, world, scratch, new MovementCorrection.Target(targetX, targetY, targetZ),
			    new MovementCorrection.Resolved(resolvedX, resolvedY, resolvedZ, residualSquared),
			    new MovementCorrection.Teleport(startX, startY, startZ, targetYRot, targetXRot), damage);
		}
		return accept(kind, clientAfter, server, world, scratch, targetX, targetY, targetZ, targetYRot, targetXRot,
		    startX, startY, startZ, yDist, standsOnSomething, residualSquared, fail && !oldBoxFree, damage);
	}

	/**
	 * A movement packet during a pending correction: rotation is installed, position is not, and a packet more
	 * than twenty connection ticks late resends the correction.
	 */
	private static ServerTick.Transaction awaitingCorrection(final MovementPacket kind, final ServerPlayerState server,
	    final float targetYRot, final float targetXRot, final ServerDamage damage) {
		ServerTick.Transaction result;
		if (server.connectionTickCount - server.awaitingTeleportTime > TELEPORT_RETRY_TICKS) {
			server.placeAt(server.awaitingPositionX, server.awaitingPositionY, server.awaitingPositionZ);
			zeroVelocity(server);
			awaitTeleport(server);
			// The resent correction is read off the copy after awaitTeleport
			// canonicalized it, before the packet's rotation is installed.
			MovementCorrection.Target target = new MovementCorrection.Target(server.x, server.y, server.z);
			result = corrected(kind, CorrectionReason.TELEPORT_RETRY, target,
			    new MovementCorrection.Resolved(server.x, server.y, server.z, 0),
			    new MovementCorrection.Teleport(server.x, server.y, server.z, server.yRot, server.xRot), damage);
		} else {
			result = new ServerTick.AwaitingTeleport(kind, damage.effects());
		}
		installRotation(server, targetYRot, targetXRot);
		return result;
	}

	/**
	 * {@code teleport(startX, startY, startZ, targetYRot, targetXRot)}: an absolute destination with zero velocity,
	 * then fall handling on the snapped (zero) movement with the packet's ground bit.
	 */
	private static ServerTick.Corrected correct(final MovementPacket kind, final CorrectionReason reason,
	    final PlayerState clientAfter, final ServerPlayerState server, final SnapshotView world, final Scratch scratch,
	    final MovementCorrection.Target target, final MovementCorrection.Resolved resolved,
	    final MovementCorrection.Teleport teleport, final ServerDamage damage) {
		if (scratch.hasMovementSegment) {
			server.removeLatestMovementRecording();
		}
		snapTo(server, teleport.x(), teleport.y(), teleport.z());
		installRotation(server, teleport.yRot(), teleport.xRot());
		zeroVelocity(server);
		awaitTeleport(server);
		// doCheckFallDamage on the snapped (zero) movement passes the packet's
		// ground bit to the support search without installing it.
		SupportingBlock.checkSupportingBlockWithGround(
		    server, clientAfter.onGround, 0.0, 0.0, server.shiftKeyDown, world, scratch);
		checkFallDamage(server, 0.0, clientAfter.onGround, world, scratch);
		return corrected(kind, reason, target, resolved, teleport, damage);
	}

	/**
	 * The accepted packet: snap to the target, install the rotation, the floating latch, the packet's ground and
	 * contact bits, support, fall, known movement and movement statistics, and advance the last-good anchor.
	 */
	private static ServerTick.Accepted accept(final MovementPacket kind, final PlayerState clientAfter,
	    final ServerPlayerState server, final SnapshotView world, final Scratch scratch, final double targetX,
	    final double targetY, final double targetZ, final float targetYRot, final float targetXRot, final double startX,
	    final double startY, final double startZ, final double yDist, final boolean standsOnSomething,
	    final double residualSquared, final boolean forgivenByOccupiedStart, final ServerDamage damage) {
		snapTo(server, targetX, targetY, targetZ);
		installRotation(server, targetYRot, targetXRot);
		boolean airborneCandidate = yDist >= FLOATING_DESCENT_ALLOWANCE && !standsOnSomething && !server.allowFlight
		    && !server.mayfly && !server.fallFlying;
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
		if (yDist > 0.0) {
			server.fallDistance = 0.0;
		}
		// LivingEntity.tryResetCurrentImpulseContext's landing test: on the
		// ground, nearly still in a fluid (the 1.0E-5F is vanilla's float
		// literal), climbing, or gliding.
		if (clientAfter.onGround
		    || server.deltaMovementY < (double) 1.0E-5F && (server.waterHeight > 0.0 || server.lavaHeight > 0.0)
		    || PlayerTick.onClimbable(server, world) || server.fallFlying) {
			server.tryResetCurrentImpulseContext();
		}
		ServerPlayerTick.checkMovementStatistics(server, clientDeltaX, clientDeltaY, clientDeltaZ, world);
		server.lastGoodX = server.x;
		server.lastGoodY = server.y;
		server.lastGoodZ = server.z;
		return new ServerTick.Accepted(kind, targetX, targetY, targetZ, residualSquared, forgivenByOccupiedStart,
		    server.clientIsFloating, server.floatingUnknown, damage.effects());
	}

	private static ServerTick.Corrected corrected(final MovementPacket kind, final CorrectionReason reason,
	    final MovementCorrection.Target target, final MovementCorrection.Resolved resolved,
	    final MovementCorrection.Teleport teleport, final ServerDamage damage) {
		return new ServerTick.Corrected(
		    new MovementCorrection(kind, reason, target, resolved, teleport), damage.effects());
	}

	/**
	 * Install a packet's rotation as {@code Entity.setYRot} and {@code setXRot} do. Vanilla installs the pitch as
	 * {@code Mth.clamp(xRot, -90, 90) % 360}; the remainder is exact (JLS 15.17.3) and its quotient is zero on the
	 * whole clamped range, so it returns the clamped value itself, signed zero and NaN included, and is not taken.
	 */
	private static void installRotation(final ServerPlayerState server, final float yRot, final float xRot) {
		server.yRot = yRot;
		server.xRot = Mth.clamp(xRot, -90.0F, 90.0F);
	}

	private static void zeroVelocity(final ServerPlayerState server) {
		server.deltaMovementX = 0.0;
		server.deltaMovementY = 0.0;
		server.deltaMovementZ = 0.0;
	}

	/**
	 * {@code ServerGamePacketListenerImpl.teleport}'s bookkeeping: the awaited position, a fresh teleport id, and
	 * the pending flag. Vanilla re-installs the position and rotation through {@code Entity.moveTo}, and
	 * {@code 0.0 + v} reproduces the one thing that does: a {@code -0.0} becomes {@code +0.0}, every other value is
	 * unchanged; the pitch is clamped as in {@link #installRotation}.
	 */
	private static void awaitTeleport(final ServerPlayerState server) {
		server.placeAt(0.0 + server.x, 0.0 + server.y, 0.0 + server.z);
		server.yRot = 0.0F + server.yRot;
		server.xRot = Mth.clamp(0.0F + server.xRot, -90.0F, 90.0F);
		if (++server.awaitingTeleport == Integer.MAX_VALUE) {
			server.awaitingTeleport = 0;
		}
		server.awaitingPositionX = server.x;
		server.awaitingPositionY = server.y;
		server.awaitingPositionZ = server.z;
		server.clearMovementThisTick();
		server.correctionPending = true;
		server.awaitingTeleportTime = server.connectionTickCount;
	}

	private static boolean shouldCheckPlayerMovement(final ServerPlayerState server) {
		return !server.singleplayerOwner && server.playerMovementCheck
		    && (!server.fallFlying || server.elytraMovementCheck);
	}

	/**
	 * {@code Entity.checkFallDamage} as the accepted packet runs it: fall accumulates from the packet's descent,
	 * then the packet's ground bit lands it through the landed block's {@code fallOn} and resets it. The landing is
	 * {@code Player.causeFallDamage}: nothing when the player may fly, else a hit whenever the floored fall power
	 * times the block's multiplier is positive.
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
		    .fallOn(server.fallDistance, cell.x, cell.y, cell.z, scratch.authority.damage());
	}

	/**
	 * {@code Entity.setPosRaw}: the position is rewritten only when a coordinate differs numerically, so a target of
	 * {@code -0.0} leaves a retained {@code +0.0} in place.
	 */
	private static void snapTo(final PlayerState server, final double x, final double y, final double z) {
		if (server.x != x || server.y != y || server.z != z) {
			server.placeAt(x, y, z);
		}
	}

	/**
	 * {@code Level.noCollision(entity, box)} over block shapes, under {@code CollisionContext.of(entity)}: the
	 * entity's own feet at the time of the query, {@code isDescending}, fall distance and boots.
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
	 * {@code isEntityCollidingWithAnythingNew}: any block shape meeting the target box deflated by float
	 * {@code 1.0E-5} that does not also meet the old box deflated the same way. A shape is one block's whole
	 * {@code VoxelShape}: it is new only when none of its boxes meets the old box, and the boolean join treats an
	 * overlap within the {@code 1.0E-7} coordinate merge as empty.
	 *
	 * <p>The shapes come from {@code getPreMoveCollisions}, whose {@code CollisionContext.withPosition} is a
	 * placement context under which powder snow and scaffolding have no shape:
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
	 * {@code noBlocksAround}: every block state in the box inflated by a sixteenth and extended {@code 0.55}
	 * downward is air.
	 */
	private static WorldView.Air noBlocksAround(final PlayerState server, final SnapshotView world) {
		return world.airIn(Mth.floor(server.boundingBoxMinX - NO_BLOCKS_AROUND_INFLATION),
		    Mth.floor(server.boundingBoxMinY - NO_BLOCKS_AROUND_INFLATION - NO_BLOCKS_AROUND_DEPTH),
		    Mth.floor(server.boundingBoxMinZ - NO_BLOCKS_AROUND_INFLATION),
		    Mth.floor(server.boundingBoxMaxX + NO_BLOCKS_AROUND_INFLATION),
		    Mth.floor(server.boundingBoxMaxY + NO_BLOCKS_AROUND_INFLATION),
		    Mth.floor(server.boundingBoxMaxZ + NO_BLOCKS_AROUND_INFLATION));
	}
}
