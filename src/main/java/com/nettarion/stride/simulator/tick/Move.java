package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.geometry.CollisionCollector;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.geometry.ShapeCollision;
import com.nettarion.stride.simulator.world.WorldView;

import java.util.Set;

/**
 * Displacement against the world: {@code Entity.move} with its {@code collide}, the phase
 * {@link Travel} runs between acceleration and drag, and the one the server listener's transaction
 * runs on a packet's displacement.
 *
 * <p>In vanilla's order: the retained stuck vector consumes the request; the sneaking ledge
 * backoff; collision and step resolution; the fall-distance-resetting segment test and the
 * movement segment the block effects will traverse; the new position and box; the horizontal and
 * vertical latches, ground, and the retained support; fall accounting; the restitution of the
 * blocked components with the landed block's bounce; and the destination block's speed factor.
 *
 * <p>Reads the box, velocity, the stuck vector, ground, fall distance, the fluid heights, and the
 * previous support. Writes the fields in {@link #WRITES} and {@link Scratch}'s moved vector and
 * movement segment. On the server's copy the vertical latches survive a request with no vertical
 * component and no fall accumulates; {@code TickAuthority.isServer()} says which.
 *
 * <p>Refuses when the successor center leaves the coordinate domain ({@code INADMISSIBLE_COORDINATE}),
 * and through the collision, support and fluid queries on unclassified or unknown cells.
 */
public final class Move {
	/**
	 * Every {@link PlayerState} field this phase may write. Public because the root-package
	 * {@code PhaseWriteSetTest} reads it directly.
	 */
	public static final Set<String> WRITES = Set.of("x", "y", "z", "boundingBoxMinX", "boundingBoxMinY",
	    "boundingBoxMinZ", "boundingBoxMaxX", "boundingBoxMaxY", "boundingBoxMaxZ", "deltaMovementX", "deltaMovementY",
	    "deltaMovementZ", "stuckSpeedMultiplierX", "stuckSpeedMultiplierY", "stuckSpeedMultiplierZ",
	    "horizontalCollision", "verticalCollision", "verticalCollisionBelow", "onGround", "minorHorizontalCollision",
	    "mainSupportingBlockPosPresent", "mainSupportingBlockPosX", "mainSupportingBlockPosY",
	    "mainSupportingBlockPosZ", "onGroundNoBlocks", "fallDistance", "waterHeight", "lavaHeight", "eyeInWater");

	/** {@code Entity.move}: a stuck vector with a squared length above this consumes the request. */
	private static final double STUCK_VECTOR_THRESHOLD = 1.0E-7;

	/**
	 * {@code Entity.move}: a resolved movement with a squared length above this, or one the collision
	 * shortened by less than this, commits a position and a movement segment.
	 */
	private static final double MOVEMENT_SEGMENT_THRESHOLD = 1.0E-7;

	/** {@code Entity.move}: the fall-reset segment is at most this long, in blocks. */
	private static final double FALL_RESET_CHECK_DISTANCE = 8.0;

	/** {@code Mth.DEG_TO_RAD * 8}, the angle below which a collision is minor. */
	private static final double MINOR_COLLISION_ANGLE = 0.13962634F;

	/**
	 * {@code cos(MINOR_COLLISION_ANGLE)}, and a band around it wide enough that the direct comparison
	 * is certainly right outside it.
	 *
	 * <p>{@code acos} is decreasing, so {@code acos(r) < angle} is {@code r > cos(angle)} — but only
	 * exactly, and this is a bit-exact transcription. {@code Math.acos} is specified to within 1 ulp,
	 * about 3.1e-17 here, and {@code |dr/d(angle)| = sin(angle)} is about 0.139, so a disagreement
	 * needs {@code r} within roughly 4.3e-18 of the cosine; 1e-9 of slack is nine orders of magnitude
	 * more than that. Inside the band the call still runs, so the band only has to be generous,
	 * never tight.
	 */
	private static final double MINOR_COLLISION_COSINE = Math.cos(MINOR_COLLISION_ANGLE);

	private static final double MINOR_COLLISION_BAND = 1.0E-9;

	private Move() {}

	static void move(final PlayerState state, final boolean shiftDown, final WorldView world, final Scratch scratch) {
		resolveUnchecked(
		    state, state.deltaMovementX, state.deltaMovementY, state.deltaMovementZ, shiftDown, world, scratch);
	}

	/**
	 * Resolves one reported player displacement, in blocks, through {@code Entity.move}.
	 *
	 * <p>This is the checked entry the server listener's transaction uses when a movement packet
	 * supplies a displacement while the server retains its own velocity, and the one tests drive
	 * directly. It is not a complete movement tick. The requested displacement therefore remains
	 * separate from {@code state.delta*}, which the move may modify only through collision
	 * restitution and block speed factors.
	 *
	 * <p>A normal return commits the resolved center, box, contact facts, and any movement state
	 * changed by {@code Entity.move}. The same admission and failed-call discard rules as
	 * {@link ClientTick#tick} apply; unlike that entry, this one also checks structural validity,
	 * because a packet's state arrives from outside the tick.
	 *
	 * @throws NullPointerException when a required object is {@code null}
	 * @throws IllegalArgumentException when a requested displacement is not finite
	 * @throws IllegalStateException when {@code state} is structurally malformed
	 * @throws UnimplementedMechanicException when the displacement reaches an unadmitted state,
	 *         coordinate, mechanic, or world fact
	 */
	public static void resolve(final PlayerState state, final double requestedX, final double requestedY,
	    final double requestedZ, final boolean shiftDown, final WorldView world, final Scratch scratch) {
		if (state == null || world == null || scratch == null) {
			throw new NullPointerException("displacement arguments must be non-null");
		}
		if (!world.movementFactsComplete()) {
			throw new UnimplementedMechanicException(
			    RefusalCause.INADMISSIBLE_WORLD, "the world view has not declared complete movement facts");
		}
		if (!Double.isFinite(requestedX) || !Double.isFinite(requestedY) || !Double.isFinite(requestedZ)) {
			throw new IllegalArgumentException("reported displacement must be finite");
		}
		state.requireValidForTransition();
		PlayerTick.validateSupportedState(state);
		resolveUnchecked(state, requestedX, requestedY, requestedZ, shiftDown, world, scratch);
	}

	/**
	 * {@link #resolve} for a state the simulator itself produced and already admitted at the last
	 * packet boundary: the same transition without re-deriving that admission on every packet.
	 *
	 * @throws IllegalArgumentException when a requested displacement is not finite
	 */
	public static void resolveAdmitted(final PlayerState state, final double requestedX, final double requestedY,
	    final double requestedZ, final boolean shiftDown, final WorldView world, final Scratch scratch) {
		if (!Double.isFinite(requestedX) || !Double.isFinite(requestedY) || !Double.isFinite(requestedZ)) {
			throw new IllegalArgumentException("reported displacement must be finite");
		}
		resolveUnchecked(state, requestedX, requestedY, requestedZ, shiftDown, world, scratch);
	}

	private static void resolveUnchecked(final PlayerState state, final double requestedX, final double requestedY,
	    final double requestedZ, final boolean shiftDown, final WorldView world, final Scratch scratch) {
		double deltaMovementX = requestedX;
		double deltaMovementY = requestedY;
		double deltaMovementZ = requestedZ;
		if (state.stuckSpeedMultiplierX * state.stuckSpeedMultiplierX
		        + state.stuckSpeedMultiplierY * state.stuckSpeedMultiplierY
		        + state.stuckSpeedMultiplierZ * state.stuckSpeedMultiplierZ
		    > STUCK_VECTOR_THRESHOLD) {
			deltaMovementX *= state.stuckSpeedMultiplierX;
			deltaMovementY *= state.stuckSpeedMultiplierY;
			deltaMovementZ *= state.stuckSpeedMultiplierZ;
			state.stuckSpeedMultiplierX = 0.0;
			state.stuckSpeedMultiplierY = 0.0;
			state.stuckSpeedMultiplierZ = 0.0;
			state.deltaMovementX = 0.0;
			state.deltaMovementY = 0.0;
			state.deltaMovementZ = 0.0;
		}

		// Transcribed guard: `shiftDown` is the isStayingOnGroundSurface() conjunct
		// of Player.maybeBackOffFromEdge's condition, hoisted so the common
		// non-sneaking path is the identity with no helper call or scratch store;
		// the helper still tests vanilla's full condition.
		if (shiftDown) {
			maybeBackOffFromEdge(state, deltaMovementX, deltaMovementY, deltaMovementZ, shiftDown, world, scratch);
			deltaMovementX = scratch.movedX;
			deltaMovementZ = scratch.movedZ;
		}

		// Pose-fit cache invariant: the cache on PlayerState is valid only for the
		// exact position bits, pose width and world revision it was recorded at,
		// and placeAt() below clears it. When collision moved the box nowhere new
		// relative to the shapes it was checked against, the fit still holds at
		// the successor and is re-recorded there (see Clearance).
		boolean hadCurrentPoseFit = !world.hasContextSensitiveCollision() && state.hasCachedPoseFit(world, state.pose);
		collide(state, deltaMovementX, deltaMovementY, deltaMovementZ, shiftDown, world, scratch);
		double movedX = scratch.movedX;
		double movedY = scratch.movedY;
		double movedZ = scratch.movedZ;

		double lengthSqr = movedX * movedX + movedY * movedY + movedZ * movedZ;
		scratch.hasMovementSegment = false;
		if (lengthSqr > MOVEMENT_SEGMENT_THRESHOLD
		    || (deltaMovementX * deltaMovementX + deltaMovementY * deltaMovementY + deltaMovementZ * deltaMovementZ)
		            - lengthSqr
		        < MOVEMENT_SEGMENT_THRESHOLD) {
			if (state.fallDistance != 0.0 && lengthSqr >= 1.0) {
				// position().add(movement.normalize().scale(checkDistance)): each
				// component is divided by the length first, then scaled.
				double length = Math.sqrt(lengthSqr);
				double checkDistance = Math.min(length, FALL_RESET_CHECK_DISTANCE);
				if (world.resetsFallDistanceAlong(state.x, state.y, state.z, state.x + movedX / length * checkDistance,
				        state.y + movedY / length * checkDistance, state.z + movedZ / length * checkDistance)) {
					state.fallDistance = 0.0;
				}
			}
			// Entity.addMovementThisTick. The requested delta travels with the
			// segment because checkInsideBlocks orders its per-axis sub-movements
			// by that delta, not by the one collision resolved.
			scratch.hasMovementSegment = true;
			scratch.segmentFromX = state.x;
			scratch.segmentFromY = state.y;
			scratch.segmentFromZ = state.z;
			scratch.segmentRequestedX = deltaMovementX;
			scratch.segmentRequestedZ = deltaMovementZ;
			double successorX = state.x + movedX;
			double successorY = state.y + movedY;
			double successorZ = state.z + movedZ;
			PlayerState.requireSupportedCenter(successorX, successorY, successorZ);
			state.placeAt(successorX, successorY, successorZ);
			if (hadCurrentPoseFit) {
				state.cacheCurrentPoseAfterCollision(world);
			}
		}

		boolean xCollision = !Mth.equal(deltaMovementX, movedX);
		boolean zCollision = !Mth.equal(deltaMovementZ, movedZ);
		state.horizontalCollision = xCollision || zCollision;
		// Entity.move refreshes the vertical latches when the request moved
		// vertically or the mover is the locally authoritative client. For the
		// client that is always: retaining the previous grounded flags is
		// observably wrong on the first level swimming tick. The server's copy of
		// a player is client-authoritative, so a request with no vertical
		// component (either signed zero: Math.abs(delta.y) > 0.0) leaves its
		// vertical latches, ground, and support exactly as they were.
		boolean movedVertically = Math.abs(deltaMovementY) > 0.0;
		boolean refreshVertical = movedVertically || !scratch.authority.isServer();
		if (refreshVertical) {
			state.verticalCollision = deltaMovementY != movedY;
			state.verticalCollisionBelow = state.verticalCollision && deltaMovementY < 0.0;
			state.onGround = state.verticalCollisionBelow;
		}
		// Only LocalPlayer overrides isHorizontalCollisionMinor; Entity's answer,
		// which the server's copy gives, is false.
		state.minorHorizontalCollision = state.horizontalCollision && !scratch.authority.isServer()
		    && isHorizontalCollisionMinor(state, movedX, movedZ);

		// Entity.setOnGroundWithMovement -> checkSupportingBlock. This identity is
		// retained because the next tick reads it before movement for friction and
		// jumping; it cannot live only in scratch or be reconstructed later.
		if (refreshVertical) {
			SupportingBlock.checkSupportingBlock(state, movedX, movedZ, shiftDown, world, scratch);
		}

		// Entity.checkFallDamage runs inside move only for the local authority.
		// The server accumulates fall from each accepted packet instead.
		if (!scratch.authority.isServer()) {
			checkFallDamage(state, movedY, world, scratch);
		}

		// Entity.restituteMovementAfterCollisions, written inline rather than as
		// a Vec3-returning helper: this branch runs on every landing tick and the
		// inline arithmetic measured faster.
		if (movedVertically && state.verticalCollision || state.horizontalCollision) {
			double currentY = state.deltaMovementY;
			// LivingEntity.getBounciness reads the BOUNCINESS attribute, whose
			// default for the admitted bare player is zero.
			double restitution = 0.0;
			// The negation is observable: a positive blocked component becomes
			// -0.0, not +0.0.
			if (xCollision) {
				state.deltaMovementX = -state.deltaMovementX * restitution;
			}
			if (zCollision) {
				state.deltaMovementZ = -state.deltaMovementZ * restitution;
			}
			if (state.verticalCollision) {
				// The two cheap terms of blockRestitution's zero are asked before
				// the region query: a resting or sneaking player has nothing to
				// bounce with, whatever is under the box, and they decide the same
				// zero the query would have led to. The block terms still follow
				// the query, because deriving the landed cell can refuse.
				if (state.verticalCollisionBelow && !(-currentY < PlayerAttributes.GRAVITY) && !shiftDown
				    && bounceIn(state, world)) {
					restitution = blockRestitution(state, currentY, world, scratch);
				}
				double gravityCompensation;
				double effectiveDrag;
				if (restitution > 0.0) {
					// Mth.lerp(portion, 1.0, drag), and the drag is a float
					// widened to double, not the double 0.98.
					double portionWithMovement = movedY / currentY;
					gravityCompensation = portionWithMovement * PlayerAttributes.GRAVITY;
					effectiveDrag = 1.0 + portionWithMovement * ((double) Travel.VERTICAL_DRAG - 1.0);
				} else {
					gravityCompensation = 0.0;
					effectiveDrag = 1.0;
				}
				state.deltaMovementY = (gravityCompensation - currentY) * effectiveDrag * restitution;
			}
		}

		float speedFactor = SupportingBlock.getBlockSpeedFactor(state, world);
		state.deltaMovementX *= speedFactor;
		state.deltaMovementZ *= speedFactor;
	}

	/** {@code Player.maybeBackOffFromEdge}, with its scalar result in the reusable move buffer. */
	private static void maybeBackOffFromEdge(final PlayerState state, double deltaMovementX,
	    final double deltaMovementY, double deltaMovementZ, final boolean shiftDown, final WorldView world,
	    final Scratch scratch) {
		if (!state.flying && !(deltaMovementY > 0.0) && shiftDown
		    && Clearance.isAboveGround(state, world, shiftDown, scratch)) {
			double stepX = Math.signum(deltaMovementX) * 0.05;
			double stepZ = Math.signum(deltaMovementZ) * 0.05;
			while (deltaMovementX != 0.0
			    && Clearance.canFallAtLeast(
			        state, world, deltaMovementX, 0.0, PlayerAttributes.MAX_UP_STEP, shiftDown, scratch)) {
				if (Math.abs(deltaMovementX) <= 0.05) {
					deltaMovementX = 0.0;
					break;
				}
				deltaMovementX -= stepX;
			}
			while (deltaMovementZ != 0.0
			    && Clearance.canFallAtLeast(
			        state, world, 0.0, deltaMovementZ, PlayerAttributes.MAX_UP_STEP, shiftDown, scratch)) {
				if (Math.abs(deltaMovementZ) <= 0.05) {
					deltaMovementZ = 0.0;
					break;
				}
				deltaMovementZ -= stepZ;
			}
			while (deltaMovementX != 0.0 && deltaMovementZ != 0.0
			    && Clearance.canFallAtLeast(
			        state, world, deltaMovementX, deltaMovementZ, PlayerAttributes.MAX_UP_STEP, shiftDown, scratch)) {
				if (Math.abs(deltaMovementX) <= 0.05) {
					deltaMovementX = 0.0;
				} else {
					deltaMovementX -= stepX;
				}
				if (Math.abs(deltaMovementZ) <= 0.05) {
					deltaMovementZ = 0.0;
				} else {
					deltaMovementZ -= stepZ;
				}
			}
		}

		scratch.movedX = deltaMovementX;
		scratch.movedZ = deltaMovementZ;
	}

	/**
	 * Whether any cell the landed box can reach bounces or suppresses a bounce.
	 *
	 * <p>Asked where the answer is read rather than beside the support search: this is the widest
	 * property span the phase asks for, and its only reader is the landing branch, which needs it on
	 * the ticks a descent actually ends against a surface.
	 */
	private static boolean bounceIn(final PlayerState state, final WorldView world) {
		return world.propertiesIn(WorldView.PROPERTY_BOUNCE, Mth.floor(state.boundingBoxMinX) - 1,
		           Mth.floor(state.boundingBoxMinY) - 1, Mth.floor(state.boundingBoxMinZ) - 1,
		           Mth.floor(state.boundingBoxMaxX) + 1, Mth.floor(state.boundingBoxMaxY) + 1,
		           Mth.floor(state.boundingBoxMaxZ) + 1)
		    != 0;
	}

	/**
	 * {@code restituteMovementAfterCollisions}'s block term: the bounciness of the block at
	 * {@code getOnPosLegacy}, or zero when the landing cannot bounce.
	 *
	 * <p>Three ways it cannot. Too slow — the descent must be at least the effective gravity, which
	 * is what stops a resting player bouncing forever. Sneaking, through
	 * {@code isSuppressingBounce}. And the block being in {@code BlockTags.SUPPRESSES_BOUNCE}, which
	 * the capture records per cell as {@link WorldView#suppressesBounceAt}; the tag's membership is
	 * the capture's fact and is not assumed here. The caller has already excluded the first two.
	 */
	private static double blockRestitution(
	    final PlayerState state, final double currentY, final WorldView world, final Scratch scratch) {
		SupportingBlock.getOnPosLegacy(state, world, scratch);
		int cellX = scratch.support.x;
		int cellY = scratch.support.y;
		int cellZ = scratch.support.z;
		if (world.suppressesBounceAt(cellX, cellY, cellZ)) {
			return 0.0;
		}
		// getBlockBounciness scales by 0.8 for a non-LivingEntity. A player is one,
		// so the float is used as it stands, widened once.
		return world.bounceRestitutionAt(cellX, cellY, cellZ);
	}

	/** {@code LocalPlayer.isHorizontalCollisionMinor}, including vanilla's table trig. */
	private static boolean isHorizontalCollisionMinor(
	    final PlayerState state, final double movedX, final double movedZ) {
		float yawRadians = state.yRot * (float) (Math.PI / 180.0);
		double sin = Mth.sin(yawRadians);
		double cos = Mth.cos(yawRadians);
		double globalXA = state.xxa * cos - state.zza * sin;
		double globalZA = state.zza * cos + state.xxa * sin;
		double inputLengthSquared = globalXA * globalXA + globalZA * globalZA;
		double movementLengthSquared = movedX * movedX + movedZ * movedZ;
		if (inputLengthSquared < 1.0E-5F || movementLengthSquared < 1.0E-5F) {
			return false;
		}
		double dot = globalXA * movedX + globalZA * movedZ;
		double ratio = dot / Math.sqrt(inputLengthSquared * movementLengthSquared);
		return isMinorAngle(ratio);
	}

	/**
	 * Vanilla's {@code Math.acos(ratio) < MINOR_COLLISION_ANGLE}, deciding the cheap cases by
	 * comparing against the cosine and calling {@code acos} only inside the band; package-private so
	 * {@code MinorCollisionAngleTest} can sweep it against the verbatim form.
	 */
	static boolean isMinorAngle(final double ratio) {
		// The ratio > 1 case is not an edge case to be defensive about, it is the
		// common one: walking straight into a wall makes the vectors parallel and
		// rounding puts the ratio a hair above 1. Vanilla's acos returns NaN
		// there and the comparison is false, so this returns false — reading it
		// as "most aligned, therefore minor" would invert the answer in exactly
		// the situation the predicate is asked about most.
		if (ratio > 1.0) {
			return false;
		}
		if (ratio > MINOR_COLLISION_COSINE + MINOR_COLLISION_BAND) {
			return true;
		}
		if (ratio < MINOR_COLLISION_COSINE - MINOR_COLLISION_BAND) {
			return false;
		}
		return Math.acos(ratio) < MINOR_COLLISION_ANGLE;
	}

	/**
	 * {@code Entity.collide}: collect collision shapes, resolve ordinary movement, then try step-up
	 * heights.
	 *
	 * <p>Vanilla's collider list also holds the entity collisions and, within two blocks of the
	 * world border, the border's own shape. Neither is a world fact the view carries:
	 * {@link ClientTick} admits only a state with no external entity or border collision, and that
	 * admission is the caller's to keep, so the lists here are the block shapes alone.
	 * {@code descending} is vanilla's {@code isDescending()}, the shift key; see
	 * {@code CollisionCollector}.
	 */
	private static void collide(final PlayerState state, final double dx, final double dy, final double dz,
	    final boolean descending, final WorldView world, final Scratch scratch) {
		if (dx * dx + dy * dy + dz * dz == 0.0) {
			scratch.movedX = dx;
			scratch.movedY = dy;
			scratch.movedZ = dz;
			return;
		}

		CollisionBuffer shapes = CollisionCollector.collect(world, scratch, state.boundingBoxMinX + Math.min(dx, 0.0),
		    state.boundingBoxMinY + Math.min(dy, 0.0), state.boundingBoxMinZ + Math.min(dz, 0.0),
		    state.boundingBoxMaxX + Math.max(dx, 0.0), state.boundingBoxMaxY + Math.max(dy, 0.0),
		    state.boundingBoxMaxZ + Math.max(dz, 0.0), state.y, descending, state.fallDistance,
		    state.canWalkOnPowderSnow);
		ShapeCollision.collideWithShapes(state.boundingBoxMinX, state.boundingBoxMinY, state.boundingBoxMinZ,
		    state.boundingBoxMaxX, state.boundingBoxMaxY, state.boundingBoxMaxZ, dx, dy, dz, shapes, scratch);
		double ordinaryX = scratch.movedX;
		double ordinaryY = scratch.movedY;
		double ordinaryZ = scratch.movedZ;
		boolean xCollision = dx != ordinaryX;
		boolean yCollision = dy != ordinaryY;
		boolean zCollision = dz != ordinaryZ;
		boolean onGroundAfterCollision = yCollision && dy < 0.0;
		// Transcribed guard: vanilla tests `this.maxUpStep() > 0.0F` first. The
		// admitted player's step height is a positive constant, so the term is
		// always true and is kept only to read as vanilla does.
		if (PlayerAttributes.MAX_UP_STEP > 0.0F && (onGroundAfterCollision || state.onGround)
		    && (xCollision || zCollision)) {
			double groundMinX = state.boundingBoxMinX;
			double groundMinY = onGroundAfterCollision ? state.boundingBoxMinY + ordinaryY : state.boundingBoxMinY;
			double groundMinZ = state.boundingBoxMinZ;
			double groundMaxX = state.boundingBoxMaxX;
			double groundMaxY = onGroundAfterCollision ? state.boundingBoxMaxY + ordinaryY : state.boundingBoxMaxY;
			double groundMaxZ = state.boundingBoxMaxZ;
			double queryMinY = onGroundAfterCollision ? groundMinY : groundMinY - 1.0E-5F;
			shapes = CollisionCollector.collect(world, scratch, groundMinX + Math.min(dx, 0.0), queryMinY,
			    groundMinZ + Math.min(dz, 0.0), groundMaxX + Math.max(dx, 0.0),
			    groundMaxY + PlayerAttributes.MAX_UP_STEP, groundMaxZ + Math.max(dz, 0.0), state.y, descending,
			    state.fallDistance, state.canWalkOnPowderSnow);
			ShapeCollision.collectCandidateStepUpHeights(
			    groundMinY, shapes, PlayerAttributes.MAX_UP_STEP, (float) ordinaryY, scratch);
			for (int i = 0; i < scratch.stepCandidateCount(); i++) {
				float candidate = scratch.stepCandidate(i);
				ShapeCollision.collideWithShapes(groundMinX, groundMinY, groundMinZ, groundMaxX, groundMaxY, groundMaxZ,
				    dx, candidate, dz, shapes, scratch);
				if (scratch.movedX * scratch.movedX + scratch.movedZ * scratch.movedZ
				    > ordinaryX * ordinaryX + ordinaryZ * ordinaryZ) {
					scratch.movedY -= state.boundingBoxMinY - groundMinY;
					return;
				}
			}
		}
		scratch.movedX = ordinaryX;
		scratch.movedY = ordinaryY;
		scratch.movedZ = ordinaryZ;
	}

	/**
	 * {@code Entity.checkFallDamage}. Two details that are easy to get wrong and invisible without a
	 * raw-bit comparison against vanilla.
	 *
	 * <p>The subtraction is {@code fallDistance -= (float) ya} — the movement is narrowed to float
	 * first, so the accumulated distance carries float resolution even though the field is a
	 * double. Subtracting the raw double agrees to about the fifteenth digit and diverges.
	 *
	 * <p>The accumulate happens before the on-ground reset, not instead of it. On the landing tick
	 * vanilla adds the final descent and then zeroes.
	 */
	private static void checkFallDamage(
	    final PlayerState state, final double movedY, final WorldView world, final Scratch scratch) {
		EntityFluidInteraction.refreshWhenDry(state, world, scratch);
		if (!(state.waterHeight > 0.0) && movedY < 0.0) {
			state.fallDistance -= (float) movedY;
		}

		if (state.onGround) {
			state.fallDistance = 0.0;
		}
	}
}
