package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.Refusal;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.server.ServerPlayerTick;
import com.nettarion.stride.simulator.world.WorldView;
import java.util.Set;

/**
 * The control decisions of the tick: {@code LocalPlayer.aiStep} and the head
 * of {@code LivingEntity.aiStep} through the jump, the second phase of
 * {@link ClientTick}.
 *
 * <p>In vanilla's order: the sprint trigger countdown; the previous input's
 * shift, jump, and forward impulse sampled before {@code KeyboardInput.tick}
 * installs the current one; the private crouch decision from the pose fit;
 * {@code KeyboardInput.tick} itself, which is where the retained input
 * facts become the current action, so every {@code isShiftKeyDown()} from
 * here to the end of the tick reads the current input, including the block
 * bodies two phases on; the four-corner suffocation escape; the sprint
 * start and stop rules; the creative double-tap flight toggle; the local
 * glide start; the water shift sink and creative vertical input;
 * {@code Player.aiStep}'s jump trigger countdown and flying fall reset; then
 * {@code LivingEntity.aiStep}'s jump delay, velocity dead zone,
 * {@code applyInput}, and the jump itself. What follows in vanilla,
 * {@code travel} and the block effects, is the next two
 * phases; {@code LocalPlayer.aiStep}'s tail after them is written in
 * {@link ClientTick}.
 *
 * <p>Reads the previous input, the timers, the collision latches, the fluid
 * heights, and the flags. Writes the fields in {@link #WRITES}; the pose fit
 * probe also refreshes the private certificate on {@link PlayerState}. On the
 * server's copy the crouch field stays absent and {@code isShiftKeyDown()}
 * reads the synced flag.
 */
public final class AiStep {
	/** Every movement or server food field this phase may write. */
	public static final Set<String> WRITES = Set.of("entityDataDirty", "sprintingAttribute",
	    "movementSpeedAttributeDirty", "sprintTriggerTime", "crouching", "inputKeyPresses", "inputMoveVectorX",
	    "inputMoveVectorY", "deltaMovementX", "deltaMovementY", "deltaMovementZ", "sprinting", "flying",
	    "jumpTriggerTime", "fallFlying", "fallDistance", "noJumpDelay", "xxa", "zza", "jumping", "exhaustionLevel");

	/** {@code Attributes.JUMP_STRENGTH} default. */
	static final float JUMP_STRENGTH = 0.42F;
	/** {@code Attributes.SNEAKING_SPEED} default. */
	private static final float SNEAKING_SPEED = 0.3F;
	private static final float[] MOVE_VECTOR_X = new float[9];
	private static final float[] MOVE_VECTOR_Y = new float[9];
	private static final float[] INPUT_XXA = new float[18];
	private static final float[] INPUT_ZZA = new float[18];

	static {
		// There are only nine net direction pairs and two slow-movement states.
		// Build the table by executing the canonical float expressions once;
		// lookup therefore memoizes exact results instead of approximating them.
		for (int forward = -1; forward <= 1; forward++) {
			for (int left = -1; left <= 1; left++) {
				int direction = directionIndex(forward, left);
				float forwardImpulse = forward;
				float leftImpulse = left;
				float length = Mth.sqrt(leftImpulse * leftImpulse + forwardImpulse * forwardImpulse);
				float moveX;
				float moveY;
				if (length < 1.0E-4F) {
					moveX = 0.0F;
					moveY = 0.0F;
				} else {
					moveX = leftImpulse / length;
					moveY = forwardImpulse / length;
				}
				MOVE_VECTOR_X[direction] = moveX;
				MOVE_VECTOR_Y[direction] = moveY;

				for (int slow = 0; slow < 2; slow++) {
					float inputX = moveX;
					float inputY = moveY;
					if (inputX * inputX + inputY * inputY != 0.0F) {
						inputX *= 0.98F;
						inputY *= 0.98F;
						if (slow != 0) {
							inputX *= SNEAKING_SPEED;
							inputY *= SNEAKING_SPEED;
						}
						float inputLength = Mth.sqrt(inputX * inputX + inputY * inputY);
						if (inputLength > 0.0F) {
							// `Vec2.scale(1.0F / length)`, not `x / length`. The
							// reciprocal rounds, then the multiply rounds again;
							// a single divide rounds once and lands one ULP away
							// on every diagonal. Vec2.normalized() above really
							// does divide, so the two forms are not
							// interchangeable even though both "normalize".
							float reciprocal = 1.0F / inputLength;
							float dirX = inputX * reciprocal;
							float dirY = inputY * reciprocal;
							float absX = Math.abs(dirX);
							float absY = Math.abs(dirY);
							float tan = absY > absX ? absX / absY : absY / absX;
							float toUnitSquare = Mth.sqrt(1.0F + Mth.square(tan));
							float modified = Math.min(inputLength * toUnitSquare, 1.0F);
							inputX = dirX * modified;
							inputY = dirY * modified;
						}
					}
					int input = direction * 2 + slow;
					INPUT_XXA[input] = inputX;
					INPUT_ZZA[input] = inputY;
				}
			}
		}
	}

	private AiStep() {}

	public static void run(
	    final PlayerState state, final PlayerInput action, final WorldView world, final Scratch scratch) {
		if (!scratch.authority.isServer()) {
			localPlayerAiStep(state, action, world, scratch);
		}
		playerAiStep(state);
		livingEntityAiStepThroughJump(state, action, world, scratch);
	}

	/** Player.aiStep's prefix before LivingEntity.aiStep. */
	private static void playerAiStep(final PlayerState state) {
		// Player.aiStep runs after LocalPlayer has handled the flight toggle and
		// vertical input, but before LivingEntity travel.
		if (state.jumpTriggerTime > 0) {
			state.jumpTriggerTime--;
		}
		if (state.flying) {
			state.fallDistance = 0.0;
		}
	}

	/** LocalPlayer's control prefix, which ServerPlayer never executes. */
	private static void localPlayerAiStep(
	    final PlayerState state, final PlayerInput action, final WorldView world, final Scratch scratch) {
		if (state.sprintTriggerTime > 0) {
			state.sprintTriggerTime--;
		}

		// Sampled before input.tick() overwrites them. hasForwardImpulse reads
		// the *previous* move vector, which is what makes the sprint double-tap
		// window work at all.
		boolean wasShiftKeyDown = PlayerTick.hasInput(state, PlayerInput.FLAG_SHIFT);
		boolean wasJumping = PlayerTick.hasInput(state, PlayerInput.FLAG_JUMP);
		boolean hadForwardImpulse = state.inputMoveVectorY > 1.0E-5F;

		// isShiftKeyDown() on LocalPlayer is the previous input, not the synced
		// flag, and not yet the current action. The field itself is
		// LocalPlayer.crouching: the server has none, and its isCrouching() is
		// the pose, so the server's copy carries the absent value.
		state.crouching = false;
		if (!state.flying && !state.swimming) {
			// Shift held never consults the standing fit, so probe only as tall as
			// the answer needs: crouching alone, or both poses from one query.
			// Vanilla asks crouching first and never asks standing when it fails;
			// the one taller query here can meet an unclassified block in the band
			// between the two heights and refuse where vanilla proceeds, which is
			// a broader refusal, never a different answer.
			int fits = Clearance.fitPoses(
			    state, world, wasShiftKeyDown ? PlayerState.Pose.CROUCHING : PlayerState.Pose.STANDING, scratch);
			boolean crouchingFits = (fits & Clearance.FIT_CROUCHING) != 0;
			boolean standingFits = !wasShiftKeyDown && crouchingFits && (fits & Clearance.FIT_STANDING) != 0;
			state.crouching = crouchingFits && (wasShiftKeyDown || !standingFits);
		}

		// KeyboardInput.tick(): the current action becomes input.keyPresses and
		// input.moveVector. Everything vanilla runs after this point that asks
		// isShiftKeyDown() — travel, the block bodies, the pose — sees the
		// current input, and the two samples above are the only readers of the
		// previous one.
		int direction =
		    directionIndex(netImpulse(action.forward(), action.backward()), netImpulse(action.left(), action.right()));
		float moveVectorX = MOVE_VECTOR_X[direction];
		float moveVectorY = MOVE_VECTOR_Y[direction];
		state.inputKeyPresses = action.packedInput();
		state.inputMoveVectorX = moveVectorX;
		state.inputMoveVectorY = moveVectorY;

		// LocalPlayer.aiStep, gated only on noPhysics, which no audited state can
		// set. Four corners of the player's own footprint, in this order, each
		// writing deltaMovement directly — so the order is observable whenever two
		// corners choose different axes .
		double corner = state.pose.width * 0.35;
		double westX = state.x - corner;
		double eastX = state.x + corner;
		double northZ = state.z - corner;
		double southZ = state.z + corner;
		// The four corners are 0.42 of a block apart, so they occupy one column
		// when the box is inside a cell, two when it straddles one axis, and four
		// only when it straddles both. Each call's first act is to ask its own
		// column, so asking the distinct ones here answers all four, and a no is a
		// no-op four times over.
		//
		// Asked in the corner order below, and short-circuited, so the first query
		// that can fail closed is the same one vanilla would reach first. A player
		// in motion often straddles an axis, so the distinct-column cases matter.
		// Query counting and the p3 workload own the cost of this grouping.
		int westCell = Mth.floor(westX);
		int eastCell = Mth.floor(eastX);
		int northCell = Mth.floor(northZ);
		int southCell = Mth.floor(southZ);
		boolean sameX = westCell == eastCell;
		boolean sameZ = northCell == southCell;
		if (world.suffocatesAt(westCell, southCell, state.boundingBoxMinY, state.boundingBoxMaxY)
		    || !sameZ && world.suffocatesAt(westCell, northCell, state.boundingBoxMinY, state.boundingBoxMaxY)
		    || !sameX && !sameZ && world.suffocatesAt(eastCell, northCell, state.boundingBoxMinY, state.boundingBoxMaxY)
		    || !sameX && world.suffocatesAt(eastCell, southCell, state.boundingBoxMinY, state.boundingBoxMaxY)) {
			moveTowardsClosestSpace(state, westX, southZ, world);
			moveTowardsClosestSpace(state, westX, northZ, world);
			moveTowardsClosestSpace(state, eastX, northZ, world);
			moveTowardsClosestSpace(state, eastX, southZ, world);
		}

		if (wasShiftKeyDown || action.backward()) {
			state.sprintTriggerTime = 0;
		}

		if (canStartSprinting(state, action, scratch, moveVectorY)) {
			if (!hadForwardImpulse) {
				if (state.sprintTriggerTime > 0) {
					state.setSprinting(true);
				} else {
					state.sprintTriggerTime = state.sprintWindowTicks;
				}
			}

			if (action.sprint()) {
				state.setSprinting(true);
			}
		}

		if (state.sprinting) {
			if (state.swimming) {
				if (shouldStopSwimSprinting(state, action, moveVectorY)) {
					state.setSprinting(false);
				}
			} else if (shouldStopRunSprinting(state, scratch, moveVectorY)) {
				state.setSprinting(false);
			}
		}

		// LocalPlayer's non-spectator creative double-tap state machine. The
		// previous input is sampled before KeyboardInput.tick, while the current
		// action is used for the new edge.
		boolean justToggledCreativeFlight = false;
		if (state.mayfly && !wasJumping && action.jump()) {
			if (state.jumpTriggerTime == 0) {
				state.jumpTriggerTime = 7;
			} else if (!state.swimming) {
				state.flying = !state.flying;
				if (state.flying && state.onGround) {
					jumpFromGround(state, world);
				}
				justToggledCreativeFlight = true;
				state.jumpTriggerTime = 0;
			}
		}

		// LocalPlayer locally starts gliding on a rising jump edge. Levitation is
		// outside the current audited state, while climbable contact is a captured
		// world capability and is therefore checked exactly.
		if (action.jump() && !justToggledCreativeFlight && !wasJumping && !state.flying && !state.fallFlying
		    && state.gliderUsable && !state.onGround && !(state.waterHeight > 0.0)
		    && !PlayerTick.onClimbable(state, world)) {
			state.setSharedFlag(7, true);
			scratch.startFallFlying = true;
		}

		if (state.waterHeight > 0.0 && action.shift() && !state.flying) {
			state.deltaMovementY += -0.04F;
		}
		if (state.flying) {
			int verticalInput = (action.jump() ? 1 : 0) - (action.shift() ? 1 : 0);
			if (verticalInput != 0) {
				state.deltaMovementY += verticalInput * state.flyingSpeed * 3.0F;
			}
		}
	}

	/**
	 * {@code LocalPlayer.moveTowardsClosestSpace}: if this corner's column
	 * suffocates, pick the nearest column side that does not and set the velocity
	 * on that axis to 0.1 towards it.
	 *
	 * <p>The distance compared is to the column's own edge, not to the player, and
	 * the neighbour test is only made when the distance improves — vanilla's
	 * {@code &&} short-circuit, kept because each test can throw on unclassified
	 * space and refusing a query vanilla never makes would be a different engine.
	 *
	 * <p>The Y span is the live bounding box's, which at this point in the tick is
	 * still the previous tick's box.
	 */
	private static void moveTowardsClosestSpace(
	    final PlayerState state, final double x, final double z, final WorldView world) {
		int cellX = Mth.floor(x);
		int cellZ = Mth.floor(z);
		if (!world.suffocatesAt(cellX, cellZ, state.boundingBoxMinY, state.boundingBoxMaxY)) {
			return;
		}
		double xd = x - cellX;
		double zd = z - cellZ;
		double closest = Double.MAX_VALUE;
		// 0 none, 1 west, 2 east, 3 north, 4 south, in Direction[] order.
		int chosen = 0;
		if (xd < closest && !world.suffocatesAt(cellX - 1, cellZ, state.boundingBoxMinY, state.boundingBoxMaxY)) {
			closest = xd;
			chosen = 1;
		}
		if (1.0 - xd < closest && !world.suffocatesAt(cellX + 1, cellZ, state.boundingBoxMinY, state.boundingBoxMaxY)) {
			closest = 1.0 - xd;
			chosen = 2;
		}
		if (zd < closest && !world.suffocatesAt(cellX, cellZ - 1, state.boundingBoxMinY, state.boundingBoxMaxY)) {
			closest = zd;
			chosen = 3;
		}
		if (1.0 - zd < closest && !world.suffocatesAt(cellX, cellZ + 1, state.boundingBoxMinY, state.boundingBoxMaxY)) {
			chosen = 4;
		}
		// setDeltaMovement(0.1 * dir.getStepX(), old.y, old.z) and its Z twin: one
		// axis is written, the other two keep the values this tick already gave
		// them, including whatever an earlier corner wrote.
		switch (chosen) {
			case 1 -> state.deltaMovementX = -0.1;
			case 2 -> state.deltaMovementX = 0.1;
			case 3 -> state.deltaMovementZ = -0.1;
			case 4 -> state.deltaMovementZ = 0.1;
			default -> {
			}
		}
	}

	/**
	 * {@code LivingEntity.aiStep} through the jump: the jump delay, the velocity
	 * dead zone, the current input, and the jump. {@code travel} and the block
	 * effects that follow it in vanilla are the next two phases.
	 */
	private static void livingEntityAiStepThroughJump(
	    final PlayerState state, final PlayerInput action, final WorldView world, final Scratch scratch) {
		if (state.noJumpDelay > 0) {
			state.noJumpDelay--;
		}

		// Player-specific dead zone: horizontal below 3e-3 in magnitude is
		// snapped to zero, which is what brings the player exactly to rest.
		double dx = state.deltaMovementX;
		double dy = state.deltaMovementY;
		double dz = state.deltaMovementZ;
		if (dx * dx + dz * dz < 9.0E-6) {
			dx = 0.0;
			dz = 0.0;
		}
		if (Math.abs(dy) < 0.003) {
			dy = 0.0;
		}
		state.deltaMovementX = dx;
		state.deltaMovementY = dy;
		state.deltaMovementZ = dz;

		if (scratch.authority.isServer()) {
			// LivingEntity.applyInput: no keyboard, sprint decision, or jump edge.
			state.xxa *= 0.98F;
			state.zza *= 0.98F;
		} else {
			applyInput(state, action, scratch);
		}

		// `this.jumping && this.isAffectedByFluids()`, and Player overrides that
		// to `!abilities.flying`. So enabling flight suppresses the whole jump
		// block, not just the fluid branches — including the ground jump and its
		// noJumpDelay. Reachable on the exact tick the creative double-tap
		// toggles flight while still on the ground, which validation's schedule never
		// produced.
		if (state.jumping && !state.flying) {
			boolean inLava = state.lavaHeight > 0.0;
			double selectedFluidHeight = inLava ? state.lavaHeight : state.waterHeight;
			boolean inWaterWithSelectedHeight = state.waterHeight > 0.0 && selectedFluidHeight > 0.0;
			if (inWaterWithSelectedHeight && (!state.onGround || selectedFluidHeight > 0.4)) {
				state.deltaMovementY += 0.04F;
			} else if (inLava && (!state.onGround || state.lavaHeight > 0.4)) {
				state.deltaMovementY += 0.04F;
			} else if (state.onGround && state.noJumpDelay == 0) {
				if (scratch.authority.isServer()) {
					ServerPlayerTick.jumpFromGround(scratch.authority.serverState(), world);
				} else {
					jumpFromGround(state, world);
				}
				state.noJumpDelay = 10;
			}
		} else {
			state.noJumpDelay = 0;
		}
	}

	/** {@code LocalPlayer.applyInput} → {@code modifyInput} → {@code modifyInputSpeedForSquareMovement}. */
	private static void applyInput(final PlayerState state, final PlayerInput action, final Scratch scratch) {
		int direction =
		    directionIndex(netImpulse(action.forward(), action.backward()), netImpulse(action.left(), action.right()));
		int input = direction * 2 + (isMovingSlowly(state, scratch) ? 1 : 0);
		state.xxa = INPUT_XXA[input];
		state.zza = INPUT_ZZA[input];
		state.jumping = action.jump();
	}

	public static void jumpFromGround(final PlayerState state, final WorldView world) {
		if (!Float.isFinite(state.jumpBoostPower) || state.jumpBoostPower < 0.0F) {
			throw UnimplementedMechanicException.deferred(
			    Refusal.INADMISSIBLE_ATTRIBUTE, () -> "invalid Jump Boost power: " + state.jumpBoostPower);
		}
		float jumpPower = JUMP_STRENGTH * SupportingBlock.getBlockJumpFactor(state, world) + state.jumpBoostPower;
		if (jumpPower <= 1.0E-5F) {
			return;
		}

		state.deltaMovementY = Math.max(jumpPower, state.deltaMovementY);
		if (state.sprinting) {
			float angle = state.yRot * (float) (Math.PI / 180.0);
			state.deltaMovementX += -Mth.sin(angle) * 0.2;
			state.deltaMovementZ += Mth.cos(angle) * 0.2;
		}
	}

	private static boolean canStartSprinting(
	    final PlayerState state, final PlayerInput action, final Scratch scratch, final float moveVectorY) {
		return !state.sprinting && moveVectorY > 1.0E-5F && (state.foodLevel > 6 || state.mayfly)
		    && (state.flying || !isInShallowWater(state, scratch))
		    // A gliding player cannot start sprinting unless underwater. Omitted
		    // until the action fuzz reached it: validation gated Elytra with a schedule
		    // that never pressed sprint mid-glide, so the missing term could not
		    // change any recorded transition.
		    && (!state.fallFlying || isUnderWater(state, scratch))
		    && (!isMovingSlowly(state, scratch) || isUnderWater(state, scratch));
	}

	private static boolean shouldStopRunSprinting(
	    final PlayerState state, final Scratch scratch, final float moveVectorY) {
		return !(state.foodLevel > 6 || state.mayfly) || !state.flying && isInShallowWater(state, scratch)
		    || !(moveVectorY > 1.0E-5F) || (state.horizontalCollision && !state.minorHorizontalCollision);
	}

	private static boolean shouldStopSwimSprinting(
	    final PlayerState state, final PlayerInput action, final float moveVectorY) {
		return !(state.foodLevel > 6 || state.mayfly) || !(state.waterHeight > 0.0)
		    || !(moveVectorY > 1.0E-5F) && !state.onGround && !action.shift();
	}

	/**
	 * {@code LocalPlayer.isUnderWater}, which overrides {@code Entity}'s and
	 * returns {@code wasUnderwater} alone: the tracker's eye flag as
	 * {@code Player.tick} samples it before {@code baseTick} refreshes the
	 * tracker, with no in-water conjunct. Only the server's copy asks
	 * {@code Entity.isUnderWater}, and this prefix never runs there. A player
	 * whose eyes were under at the previous refresh but whose box now holds
	 * no water is still under water to this tick's sprint rules.
	 */
	private static boolean isUnderWater(final PlayerState state, final Scratch scratch) {
		return scratch.wasUnderWaterAtTickStart;
	}

	private static boolean isInShallowWater(final PlayerState state, final Scratch scratch) {
		return state.waterHeight > 0.0 && !isUnderWater(state, scratch);
	}

	private static boolean isMovingSlowly(final PlayerState state, final Scratch scratch) {
		// LocalPlayer.isMovingSlowly is isCrouching() || isVisuallyCrawling(), and
		// LivingEntity.isVisuallySwimming is the SWIMMING pose or the FALL_FLYING
		// pose without the glide flag. Both poses outlive their cause by a tick:
		// baseTick clears water and swimming on exit, and the server's stop echo
		// clears the glide flag, while Player.updatePlayerPose only reselects
		// the pose at the end of this same tick. isCrouching() is
		// LocalPlayer.crouching on the client and the pose on the server.
		boolean crouching = scratch.authority.isServer() ? state.pose == PlayerState.Pose.CROUCHING : state.crouching;
		boolean visuallySwimming =
		    state.pose == PlayerState.Pose.SWIMMING || !state.fallFlying && state.pose == PlayerState.Pose.FALL_FLYING;
		return crouching || visuallySwimming && !(state.waterHeight > 0.0);
	}

	private static int netImpulse(final boolean positive, final boolean negative) {
		if (positive == negative) {
			return 0;
		}
		return positive ? 1 : -1;
	}

	private static int directionIndex(final int forward, final int left) {
		return (forward + 1) * 3 + left + 1;
	}
}
