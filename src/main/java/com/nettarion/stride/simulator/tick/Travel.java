package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.server.ServerDamage;
import com.nettarion.stride.simulator.world.WorldView;
import java.util.HashSet;
import java.util.Set;

/**
 * Velocity from the medium: {@code LivingEntity.travel} and the four bodies it selects, the phase
 * between the jump and the block effects.
 *
 * <p>In vanilla's order: {@code updateFallFlying}'s fall clamp (and on the server the glide
 * continuation check), {@code Player.travel}'s swimming pitch, then the travel for creative flight,
 * water, lava, gliding, or air, each of which accelerates from the input, calls {@link Move}, and
 * applies its gravity and drag; creative flight then damps the vertical component.
 * {@code handleFallFlyingCollisions} runs after the glide move on the server's copy only and deals
 * its hit through {@code TickAuthority.damage()}.
 *
 * <p>Reads the input vector, rotation, velocity, ground, support, the fluid heights, and the flags;
 * the shift state is the current input on the client and the synced flag on the server's copy.
 * Writes velocity, fall distance, and the glide flag directly, and everything {@link Move} writes
 * through it.
 *
 * <p>Refuses when the column below the player is unknown ({@code OUTSIDE_REGION}), when the
 * movement speed multiplier is not finite or is combined with frost speed
 * ({@code INADMISSIBLE_ATTRIBUTE}), when the server would damage the glider this tick
 * ({@code PENDING_ABILITY}), and through {@link Move} and {@code Clearance} on unclassified blocks.
 */
public final class Travel {
	/**
	 * Every {@link PlayerState} field this phase may write, {@link Move}'s included. Public because
	 * the root-package {@code PhaseWriteSetTest} reads it directly.
	 */
	public static final Set<String> WRITES;

	static {
		Set<String> writes = new HashSet<>(Move.WRITES);
		writes.addAll(ServerDamage.HURT_WRITES);
		writes.addAll(Set.of(
		    "entityDataDirty", "deltaMovementX", "deltaMovementY", "deltaMovementZ", "fallDistance", "fallFlying"));
		WRITES = Set.copyOf(writes);
	}

	/**
	 * Vanilla's vertical drag: {@code travelInAir}'s {@code y * 0.98F}, the glide's
	 * {@code y *= 0.98F}, and the float {@code restituteMovementAfterCollisions} lerps toward.
	 */
	static final float VERTICAL_DRAG = 0.98F;

	/** {@code LivingEntity.travelInAir}: the horizontal drag multiplied into the block friction. */
	private static final float AIR_DRAG = 0.91F;

	/** {@code Block.getFriction()} default, used when the support cell declares none. */
	private static final float DEFAULT_FRICTION = 0.6F;

	/** {@code Attributes.MOVEMENT_SPEED}'s {@code RangedAttribute} upper bound. */
	private static final double MAX_MOVEMENT_SPEED = 1024.0;

	private Travel() {}

	/**
	 * {@code LivingEntity.aiStep} from {@code updateFallFlying} through {@code Player.travel} and the
	 * creative-flight drag that follows it.
	 */
	public static void run(
	    final PlayerState state, final PlayerInput action, final WorldView world, final Scratch scratch) {
		// LivingEntity.aiStep runs updateFallFlying before Player.travel, whatever
		// travel then selects.
		if (state.fallFlying) {
			updateFallFlying(state, scratch);
		}
		playerTravel(state, action, world, scratch);
	}

	/** {@code Player.travel}: swimming pitch and the abilities-flying vertical tail. */
	private static void playerTravel(
	    final PlayerState state, final PlayerInput action, final WorldView world, final Scratch scratch) {
		if (state.swimming) {
			applySwimmingPitch(state, world, scratch);
		}
		boolean shiftDown =
		    scratch.authority.isServer() ? scratch.authority.serverState().shiftKeyDown : action.sneak();
		if (state.flying) {
			double originalMovementY = state.deltaMovementY;
			travel(state, shiftDown, world, scratch);
			state.deltaMovementY = originalMovementY * 0.6;
		} else {
			travel(state, shiftDown, world, scratch);
		}
	}

	/** {@code LivingEntity.travel} selects the admitted medium; creative flight ignores fluids. */
	private static void travel(
	    final PlayerState state, final boolean shiftDown, final WorldView world, final Scratch scratch) {
		if (!state.flying && (state.waterHeight > 0.0 || state.lavaHeight > 0.0)) {
			travelInFluid(state, shiftDown, world, scratch);
		} else if (state.fallFlying) {
			travelFallFlying(state, shiftDown, world, scratch);
		} else {
			travelInAir(state, shiftDown, world, scratch);
		}
	}

	/** {@code LivingEntity.travelInFluid} samples the pre-move gravity, descent and height once. */
	private static void travelInFluid(
	    final PlayerState state, final boolean shiftDown, final WorldView world, final Scratch scratch) {
		boolean isFalling = state.deltaMovementY <= 0.0;
		double oldY = state.y;
		double baseGravity = PlayerAttributes.GRAVITY;
		if (state.waterHeight > 0.0) {
			travelInWater(state, shiftDown, world, scratch, baseGravity, isFalling, oldY);
		} else {
			travelInLava(state, shiftDown, world, scratch, baseGravity, isFalling, oldY);
		}
	}

	private static void travelInAir(
	    final PlayerState state, final boolean shiftDown, final WorldView world, final Scratch scratch) {
		int belowX = SupportingBlock.getBlockPosBelowThatAffectsMyMovementX(state);
		int belowZ = SupportingBlock.getBlockPosBelowThatAffectsMyMovementZ(state);
		int belowY = SupportingBlock.getBlockPosBelowThatAffectsMyMovementY(state, world);

		float blockFriction = 1.0F;
		if (state.onGround) {
			blockFriction = world.propertiesAnywhere(WorldView.PROPERTY_FRICTION) != 0
			        && world.propertiesIn(WorldView.PROPERTY_FRICTION, belowX, belowY, belowZ, belowX, belowY, belowZ)
			            != 0
			    ? world.friction(belowX, belowY, belowZ)
			    : DEFAULT_FRICTION;
		}

		double movementY = handleRelativeFrictionAndCalculateMovement(state, blockFriction, shiftDown, world, scratch);
		double movementX = state.deltaMovementX;
		double movementZ = state.deltaMovementZ;

		// Vanilla's client substitutes -0.1 (or 0.0 at the world floor) for the
		// gravity step over an unloaded column and the server has no such branch;
		// neither outcome is a captured fact here, so both authorities refuse.
		if (!world.hasChunkAt(belowX, belowZ)) {
			throw UnimplementedMechanicException.deferred(
			    RefusalCause.OUTSIDE_REGION, () -> "unknown space below the player at " + belowX + "," + belowZ);
		}
		movementY -= PlayerAttributes.GRAVITY;

		float friction = blockFriction * AIR_DRAG;
		state.deltaMovementX = movementX * friction;
		state.deltaMovementY = movementY * VERTICAL_DRAG;
		state.deltaMovementZ = movementZ * friction;
	}

	/** Scalar return of vanilla's result Y; result X/Z stay in the movement state. */
	private static double handleRelativeFrictionAndCalculateMovement(final PlayerState state, final float blockFriction,
	    final boolean shiftDown, final WorldView world, final Scratch scratch) {
		moveRelative(state, getFrictionInfluencedSpeed(state, blockFriction));
		handleOnClimbable(state, shiftDown, world);
		Move.move(state, shiftDown, world, scratch);
		double movementY = state.deltaMovementY;
		if ((state.horizontalCollision || state.jumping)
		    && (PlayerTick.onClimbable(state, world) || state.wasInPowderSnow && state.canWalkOnPowderSnow)) {
			movementY = 0.2;
		}

		return movementY;
	}

	/** {@code LivingEntity.handleOnClimbable}, mutating the retained scalar vector. */
	private static void handleOnClimbable(final PlayerState state, final boolean shiftDown, final WorldView world) {
		boolean onClimbable = PlayerTick.onClimbable(state, world);
		if (onClimbable) {
			state.fallDistance = 0.0;
			state.deltaMovementX = Mth.clamp(state.deltaMovementX, -0.15F, 0.15F);
			state.deltaMovementZ = Mth.clamp(state.deltaMovementZ, -0.15F, 0.15F);
			state.deltaMovementY = Math.max(state.deltaMovementY, -0.15F);
			// Scaffolding deliberately keeps the negative climb-clamped velocity:
			// shift both removes its support collision and permits descent. Other
			// climbables suppress sliding while shift is held (unless flying).
			if (state.deltaMovementY < 0.0 && !PlayerTick.onScaffolding(state, world) && shiftDown && !state.flying) {
				state.deltaMovementY = 0.0;
			}
		}
	}

	/** {@code LivingEntity.travelFallFlying}: movement update, collision resolution, then server damage. */
	private static void travelFallFlying(
	    final PlayerState state, final boolean shiftDown, final WorldView world, final Scratch scratch) {
		if (PlayerTick.onClimbable(state, world)) {
			travelInAir(state, shiftDown, world, scratch);
			state.setSharedFlag(7, false);
			return;
		}
		double lastSpeed =
		    Math.sqrt(state.deltaMovementX * state.deltaMovementX + state.deltaMovementZ * state.deltaMovementZ);
		// The movement update and server collision damage use the same pre-update speed.
		updateFallFlyingMovement(state, lastSpeed);
		Move.move(state, shiftDown, world, scratch);
		if (scratch.authority.isServer()) {
			handleFallFlyingCollisions(state, lastSpeed, scratch);
		}
	}

	/** {@code LivingEntity.updateFallFlyingMovement} with scalar storage instead of Vec3 allocation. */
	private static void updateFallFlyingMovement(final PlayerState state, final double moveHorLength) {
		// Entity.calculateViewVector negates the *angle* and then looks it up.
		// Taking cos(+yaw) and folding the sign into the product instead is not
		// the same number: Mth.cos indexes the sine table at
		// (long)(radians * SCALE + 16384.0), and truncating after the offset makes
		// it not an even function — cos(-a) reads a different entry than cos(a),
		// by up to ~1e-4. Mth.sin happens to be odd here, but only by accident of
		// the table's own symmetry, so both are transcribed as written.
		float leanAngle = state.xRot * (float) (Math.PI / 180.0);
		float realYRot = -state.yRot * (float) (Math.PI / 180.0);
		float yCos = Mth.cos(realYRot);
		float ySin = Mth.sin(realYRot);
		float cosPitch = Mth.cos(leanAngle);
		float sinPitch = Mth.sin(leanAngle);
		double lookX = ySin * cosPitch;
		double lookZ = yCos * cosPitch;
		double lookHorLength = Math.sqrt(lookX * lookX + lookZ * lookZ);
		double liftForce = Math.cos(leanAngle);
		liftForce *= liftForce;
		// `+= 0.0` is vanilla's Vec3.add on the untouched components: it turns a
		// -0.0 into +0.0, which is observable raw-bit.
		state.deltaMovementX += 0.0;
		state.deltaMovementY += PlayerAttributes.GRAVITY * (-1.0 + liftForce * 0.75);
		state.deltaMovementZ += 0.0;
		if (state.deltaMovementY < 0.0 && lookHorLength > 0.0) {
			double convert = state.deltaMovementY * -0.1 * liftForce;
			state.deltaMovementX += lookX * convert / lookHorLength;
			state.deltaMovementY += convert;
			state.deltaMovementZ += lookZ * convert / lookHorLength;
		}
		if (leanAngle < 0.0F && lookHorLength > 0.0) {
			double convert = moveHorLength * -sinPitch * 0.04;
			state.deltaMovementX += -lookX * convert / lookHorLength;
			state.deltaMovementY += convert * 3.2;
			state.deltaMovementZ += -lookZ * convert / lookHorLength;
		}
		if (lookHorLength > 0.0) {
			state.deltaMovementX += (lookX / lookHorLength * moveHorLength - state.deltaMovementX) * 0.1;
			// Vec3.add's untouched Y component: -0.0 becomes +0.0.
			state.deltaMovementY += 0.0;
			state.deltaMovementZ += (lookZ / lookHorLength * moveHorLength - state.deltaMovementZ) * 0.1;
		}
		state.deltaMovementX *= 0.99F;
		state.deltaMovementY *= VERTICAL_DRAG;
		state.deltaMovementZ *= 0.99F;
	}

	/**
	 * {@code LivingEntity.updateFallFlying}: the fall clamp on both sides, then on the server the
	 * continuation check and the durability write every twentieth glide tick.
	 *
	 * <p>The continuation check is transcribed: when the server's copy can no longer glide, the
	 * shared flag is cleared, so this tick's travel selects the air, and the flag is dirty for the
	 * tracker sample at the head of the next server tick. The composed step delivers that sample as
	 * an entity-data echo the client applies before its third tick after the landing packet's, so
	 * the client keeps gliding on the ground for two more ticks, as vanilla's client does. The
	 * durability write still refuses, since a damaged glider's usability is not a captured fact.
	 *
	 * @throws PendingServerWriteException when the server would damage the glider at this
	 *         connection tick
	 */
	private static void updateFallFlying(final PlayerState state, final Scratch scratch) {
		// checkFallDistanceAccumulation
		if (state.deltaMovementY > -0.5 && state.fallDistance > 1.0) {
			state.fallDistance = 1.0;
		}
		if (!scratch.authority.isServer()) {
			return;
		}
		if (!canGlide(state)) {
			state.setSharedFlag(7, false);
			return;
		}
		int checkFallFlyTicks = state.fallFlyTicks + 1;
		if (checkFallFlyTicks % 10 == 0 && checkFallFlyTicks / 10 % 2 == 0) {
			throw new PendingServerWriteException(RefusalCause.PENDING_ABILITY,
			    "the server damages the glider at this"
			        + " connection tick; whether it stays usable is not a captured fact");
		}
	}

	/**
	 * {@code Player.canGlide} for the bare player: not in creative flight, off the ground, with a
	 * usable glider. Passengers and Levitation are outside the admitted domain.
	 * {@code Player.canGlide} never asks about water; only {@code tryToStartFallFlying} does.
	 */
	public static boolean canGlide(final PlayerState state) {
		return !state.flying && !state.onGround && state.gliderUsable;
	}

	/**
	 * {@code LivingEntity.handleFallFlyingCollisions}, which only the server's travel runs: a
	 * horizontal collision that shed enough of the old horizontal speed hurts.
	 */
	private static void handleFallFlyingCollisions(
	    final PlayerState state, final double lastSpeed, final Scratch scratch) {
		if (state.horizontalCollision) {
			double newSpeed =
			    Math.sqrt(state.deltaMovementX * state.deltaMovementX + state.deltaMovementZ * state.deltaMovementZ);
			double diff = lastSpeed - newSpeed;
			float damage = (float) (diff * 10.0 - 3.0);
			if (damage > 0.0F) {
				scratch.authority.damage().hurtServer(HurtCause.FLY_INTO_WALL, damage);
			}
		}
	}

	private static void applySwimmingPitch(final PlayerState state, final WorldView world, final Scratch scratch) {
		double lookAngleY = -Mth.sin(state.xRot * (float) (Math.PI / 180.0));
		double multiplier = lookAngleY < -0.2 ? 0.085 : 0.06;
		if (lookAngleY <= 0.0 || state.jumping
		    || EntityFluidInteraction.kindAt(
		           world, Mth.floor(state.x), Mth.floor(state.y + 1.0 - 0.1), Mth.floor(state.z), scratch)
		        != FluidSample.Kind.EMPTY) {
			// Vec3.add on the untouched components: -0.0 becomes +0.0.
			state.deltaMovementX += 0.0;
			state.deltaMovementY += (lookAngleY - state.deltaMovementY) * multiplier;
			state.deltaMovementZ += 0.0;
		}
	}

	private static void travelInWater(final PlayerState state, final boolean shiftDown, final WorldView world,
	    final Scratch scratch, final double baseGravity, final boolean isFalling, final double oldY) {
		float slowDown = state.sprinting ? 0.9F : 0.8F;
		moveRelative(state, 0.02F);
		Move.move(state, shiftDown, world, scratch);
		double movementX = state.deltaMovementX;
		double movementY = state.deltaMovementY;
		double movementZ = state.deltaMovementZ;
		if (state.horizontalCollision && PlayerTick.onClimbable(state, world)) {
			movementY = 0.2;
		}
		movementX *= slowDown;
		movementY *= 0.8F;
		movementZ *= slowDown;
		movementY = getFluidFallingAdjustedMovement(state, baseGravity, isFalling, movementY);
		state.deltaMovementX = movementX;
		state.deltaMovementY = movementY;
		state.deltaMovementZ = movementZ;

		jumpOutOfFluid(state, oldY, shiftDown, world, scratch);
	}

	private static void travelInLava(final PlayerState state, final boolean shiftDown, final WorldView world,
	    final Scratch scratch, final double baseGravity, final boolean isFalling, final double oldY) {
		moveRelative(state, 0.02F);
		Move.move(state, shiftDown, world, scratch);
		double movementX;
		double movementY;
		double movementZ;
		if (state.lavaHeight <= 0.4) {
			movementX = state.deltaMovementX * 0.5;
			movementY = state.deltaMovementY * 0.8F;
			movementZ = state.deltaMovementZ * 0.5;
			movementY = getFluidFallingAdjustedMovement(state, baseGravity, isFalling, movementY);
		} else {
			movementX = state.deltaMovementX * 0.5;
			movementY = state.deltaMovementY * 0.5;
			movementZ = state.deltaMovementZ * 0.5;
		}
		// Vec3.add(0.0, -gravity / 4.0, 0.0): the X and Z adds turn -0.0 into +0.0.
		movementX += 0.0;
		movementY += -baseGravity / 4.0;
		movementZ += 0.0;
		state.deltaMovementX = movementX;
		state.deltaMovementY = movementY;
		state.deltaMovementZ = movementZ;

		jumpOutOfFluid(state, oldY, shiftDown, world, scratch);
	}

	/** {@code LivingEntity.getFluidFallingAdjustedMovement}; only its Y component changes. */
	private static double getFluidFallingAdjustedMovement(
	    final PlayerState state, final double baseGravity, final boolean isFalling, final double movementY) {
		if (baseGravity != 0.0 && !state.sprinting) {
			if (isFalling && Math.abs(movementY - 0.005) >= 0.003 && Math.abs(movementY - baseGravity / 16.0) < 0.003) {
				return -0.003;
			}
			return movementY - baseGravity / 16.0;
		}
		return movementY;
	}

	/** {@code LivingEntity.jumpOutOfFluid} after the fluid's gravity and drag. */
	private static void jumpOutOfFluid(final PlayerState state, final double oldY, final boolean shiftDown,
	    final WorldView world, final Scratch scratch) {
		if (state.horizontalCollision
		    && Clearance.isFree(state, state.deltaMovementX, state.deltaMovementY + 0.6F - state.y + oldY,
		        state.deltaMovementZ, shiftDown, world, scratch)) {
			state.deltaMovementY = 0.3F;
		}
	}

	/**
	 * {@code Player.aiStep}: {@code setSpeed((float) getAttributeValue(MOVEMENT_SPEED))}.
	 *
	 * <p>The width matters. {@code AttributeInstance.calculateValue} accumulates in {@code double}
	 * and the result is narrowed to float exactly once, at that call. Both the base and the sprint
	 * modifier are float literals widened to double, so they carry their float representation error
	 * into the double multiply. Computing this as {@code 0.1F * 1.3F} in float instead diverges from
	 * vanilla in the eighth decimal at the first sprinting tick, invisible to any tolerance.
	 *
	 * <p>The frost speed ticks were range-checked by {@code PlayerTick.validateSupportedState}
	 * before this phase ran.
	 */
	private static float movementSpeed(final boolean sprinting, final int frostSpeedTicks, final double multiplier) {
		if (!Double.isFinite(multiplier)) {
			throw UnimplementedMechanicException.deferred(
			    RefusalCause.INADMISSIBLE_ATTRIBUTE, () -> "invalid movement speed multiplier: " + multiplier);
		}
		if (frostSpeedTicks != 0 && Double.doubleToRawLongBits(multiplier) != Double.doubleToRawLongBits(1.0)) {
			throw new UnimplementedMechanicException(
			    RefusalCause.INADMISSIBLE_ATTRIBUTE, "movement speed modifiers combined with powder-snow frost speed");
		}
		// LivingEntity.tryAddFrost: -0.05F * getPercentFrozen(), where the percent
		// is the frozen count over the ticks required to freeze, capped at one.
		double value = (double) PlayerAttributes.BASE_MOVEMENT_SPEED
		    - (double) (0.05F
		        * Math.min(1.0F, frostSpeedTicks / (float) ServerPlayerState.DEFAULT_TICKS_REQUIRED_TO_FREEZE));
		value *= multiplier;
		if (sprinting) {
			value *= 1.0 + (double) PlayerAttributes.SPRINT_SPEED_BONUS;
		}
		// Attributes.MOVEMENT_SPEED is a RangedAttribute. Sanitization happens
		// after the ADD_MULTIPLIED_TOTAL modifiers, so high Slowness levels clamp
		// a negative intermediate value to zero rather than becoming unsupported.
		value = Math.max(0.0, Math.min(MAX_MOVEMENT_SPEED, value));
		return (float) value;
	}

	private static float getFrictionInfluencedSpeed(final PlayerState state, final float blockFriction) {
		float speed = movementSpeed(state.sprintingAttribute, state.frostSpeedTicks, state.movementSpeedMultiplier);
		if (state.onGround) {
			return blockFriction > DEFAULT_FRICTION
			    ? speed * (0.21600002F / (blockFriction * blockFriction * blockFriction))
			    : speed;
		}
		if (state.flying) {
			return state.sprinting ? state.flyingSpeed * 2.0F : state.flyingSpeed;
		}
		// Player.getFlyingSpeed(): in-air acceleration is higher while
		// sprinting. The literal is 0.025999999F in vanilla, not 0.026F —
		// copied verbatim, because the two are different floats and the
		// difference shows up in the first airborne sprint tick.
		return state.sprinting ? 0.025999999F : 0.02F;
	}

	/** {@code Entity.moveRelative} → {@code getInputVector}. */
	private static void moveRelative(final PlayerState state, final float speed) {
		double inputX = state.xxa;
		double inputY = state.yya;
		double inputZ = state.zza;
		double lengthSqr = inputX * inputX + inputY * inputY + inputZ * inputZ;
		if (lengthSqr < 1.0E-7) {
			// Vanilla returns Vec3.ZERO here and still performs the add. That add
			// is not a no-op: -0.0 + 0.0 is +0.0, so a component left negative
			// zero by an earlier collision is normalized every tick the player
			// has no movement input. Returning early preserves the -0.0 and
			// diverges raw-bit while comparing equal under `==`.
			state.deltaMovementX += 0.0;
			state.deltaMovementY += 0.0;
			state.deltaMovementZ += 0.0;
			return;
		}

		if (lengthSqr > 1.0) {
			double norm = Math.sqrt(lengthSqr);
			inputX /= norm;
			inputY /= norm;
			inputZ /= norm;
		}
		inputX *= speed;
		inputY *= speed;
		inputZ *= speed;

		float sin = Mth.sin(state.yRot * (float) (Math.PI / 180.0));
		float cos = Mth.cos(state.yRot * (float) (Math.PI / 180.0));
		state.deltaMovementX += inputX * cos - inputZ * sin;
		state.deltaMovementY += inputY;
		state.deltaMovementZ += inputZ * cos + inputX * sin;
	}
}
