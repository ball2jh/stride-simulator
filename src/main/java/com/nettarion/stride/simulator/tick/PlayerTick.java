package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.block.BlockEffects;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.server.FoodData;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * The shared {@code Entity}, {@code LivingEntity} and {@code Player} tick, run under an explicit
 * authority.
 *
 * <p>The same phase sequence ticks both copies of the player. Under client authority the
 * {@code LocalPlayer} controls are live; under server authority the copy enters through
 * {@link #serverTick}, each phase's server-only branches are live, and their hits go through
 * {@code TickAuthority.survival()}. Flattened state and the phase pipeline avoid entity allocation.
 * The player's own predicates ({@link #isShiftKeyDown}, {@link #hasInput}, {@link #onClimbable})
 * live here because the phases share them.
 */
public final class PlayerTick {
	private static final PlayerInput NO_INPUT = PlayerInput.idle(0.0F, 0.0F);

	private static final int NEGATIVE_ZERO_BITS = Float.floatToRawIntBits(-0.0F);

	/** {@code Player.tick}: the horizontal position is clamped this far inside the coordinate limit. */
	private static final double HORIZONTAL_LIMIT = 2.9999999E7;

	/** {@code LocalPlayer.aiStep}: the widest double-tap window vanilla's client holds, in ticks. */
	private static final int MAX_SPRINT_WINDOW_TICKS = 10;

	/** {@code FoodData}: the food bar's full level. */
	private static final int MAX_FOOD_LEVEL = 20;

	private PlayerTick() {}

	/**
	 * The same tick on the server's copy of the player: {@code ServerPlayer.doTick} with no input,
	 * on a scratch whose {@code TickAuthority} is bound to the copy. {@code Entity.move} on a
	 * server player refreshes the vertical latches and support only when the request moved
	 * vertically and never accumulates fall distance (the accepted packet does that).
	 *
	 * @throws IllegalStateException when the scratch's authority is not bound to a server copy
	 * @throws IllegalArgumentException when it is bound to a different copy than {@code state}
	 */
	public static void serverTick(final ServerPlayerState state, final WorldView world, final Scratch scratch) {
		scratch.authority.requireServer(state);
		// ServerPlayer.doTick has no keyboard input and installs no rotation: the
		// copy's own bits, signed zero included, are what its reads see.
		tick(state, NO_INPUT, state.yRot, state.xRot, world, scratch);
	}

	/**
	 * Vanilla's {@code isShiftKeyDown()}: the synced entity flag on the server's copy, and on the
	 * client the input as of this point in the tick, which is the previous one before
	 * {@code input.tick()} in {@link AiStep} and the current one after it, so {@link Travel},
	 * {@code BlockEffects}, and {@link PlayerPose} all read the current action.
	 */
	public static boolean isShiftKeyDown(final PlayerState state, final Scratch scratch) {
		return scratch.authority.isServer() ? scratch.authority.serverState().shiftKeyDown
		                                    : hasInput(state, PlayerInput.FLAG_SNEAK);
	}

	/**
	 * One complete tick of {@code state} under the authority {@code scratch} carries, with the
	 * action's rotation installed first. {@link ClientTick} is the ordinary client entry; this one
	 * exists for callers that hold a bound server scratch and want the shared body with an explicit
	 * input.
	 *
	 * @throws IllegalStateException when the scratch carries server authority but is not bound
	 * @throws IllegalArgumentException when it is bound to a different copy than {@code state}
	 * @throws UnimplementedMechanicException when the state, world, or a reached mechanic is
	 *         outside the admitted domain
	 */
	public static void tick(
	    final PlayerState state, final PlayerInput action, final WorldView world, final Scratch scratch) {
		if (scratch.authority.isServer()) {
			if (!(state instanceof ServerPlayerState server)) {
				throw new IllegalStateException("a server-authority scratch ticks only the server's copy");
			}
			scratch.authority.requireServer(server);
		}
		tick(state, action, action.yRot(), action.xRot(), world, scratch);
	}

	private static void tick(final PlayerState state, final PlayerInput action, final float yRot, final float xRot,
	    final WorldView world, final Scratch scratch) {
		if (!world.movementFactsComplete()) {
			throw new UnimplementedMechanicException(
			    RefusalCause.INADMISSIBLE_WORLD, "the world view has not declared complete movement facts");
		}
		PlayerState.requireSupportedCenter(state.x, state.y, state.z);
		validateSupportedState(state);
		// Rotation is part of the action. Vanilla applies it per rendered frame
		// before the tick reads it, so applying it at the top of the transition
		// is the declared one-rotation-per-tick specialization.
		state.yRot = yRot;
		state.xRot = xRot;

		// ClientLevel.tickNonPassenger saves the old position before the tick;
		// applyEffectsFromBlocks synthesizes a movement from it when move()
		// recorded none.
		scratch.oldX = state.x;
		scratch.oldY = state.y;
		scratch.oldZ = state.z;

		BaseTick.run(state, world, scratch);
		AiStep.run(state, action, world, scratch);
		Travel.run(state, action, world, scratch);
		BlockEffects.applyEffectsFromBlocks(state, world, scratch);
		Freezing.run(state, world, scratch);

		localPlayerAiStepTail(state, scratch);
		if (state.fallFlying) {
			state.fallFlyTicks++;
		} else {
			state.fallFlyTicks = 0;
		}
		// Player.tick after super.tick(): the horizontal position is clamped a
		// unit inside the coordinate limit and, when that changed it, re-set.
		double clampedX = Mth.clamp(state.x, -HORIZONTAL_LIMIT, HORIZONTAL_LIMIT);
		double clampedZ = Mth.clamp(state.z, -HORIZONTAL_LIMIT, HORIZONTAL_LIMIT);
		if (clampedX != state.x || clampedZ != state.z) {
			state.placeAt(clampedX, state.y, clampedZ);
		}
		if (scratch.authority.isServer()) {
			ServerPlayerState server = scratch.authority.serverState();
			if (server.currentImpulseContextResetGraceTime > 0) {
				server.currentImpulseContextResetGraceTime--;
			}
		}
		PlayerPose.updatePlayerPose(state, world, scratch);
	}

	/** {@code LocalPlayer.aiStep} after its inherited travel and effects have completed. */
	private static void localPlayerAiStepTail(final PlayerState state, final Scratch scratch) {
		if (!scratch.authority.isServer() && state.onGround && state.flying) {
			state.flying = false;
		}
	}

	/**
	 * Refuses a state vanilla's player never holds or that the admitted domain excludes. Structural
	 * validity ({@link PlayerState#requireValidForTransition()}) is the caller's; this checks the
	 * facts the tick reads.
	 */
	static void validateSupportedState(final PlayerState state) {
		if (state.autoJumpEnabled) {
			throw new UnimplementedMechanicException(RefusalCause.INADMISSIBLE_STATE, "auto-jump is enabled");
		}
		if (state.autoJumpTime != 0) {
			throw UnimplementedMechanicException.deferred(RefusalCause.INADMISSIBLE_STATE,
			    () -> "pending auto-jump input is outside the admitted domain: " + state.autoJumpTime);
		}
		if (state.sprintWindowTicks < 0 || state.sprintWindowTicks > MAX_SPRINT_WINDOW_TICKS) {
			throw UnimplementedMechanicException.deferred(RefusalCause.INADMISSIBLE_STATE,
			    () -> "sprint window outside vanilla range: " + state.sprintWindowTicks);
		}
		if (state.flying && !state.mayfly) {
			throw new UnimplementedMechanicException(RefusalCause.INADMISSIBLE_STATE,
			    "flying without mayfly is outside the admitted creative-flight domain");
		}
		// Values vanilla's player never holds: its counters never go negative,
		// powder snow caps the frozen count at the freeze threshold, the food
		// level is clamped to its bar, no player writes the vertical input, and
		// the retained move vector and rotation are always finite. (autoJumpTime
		// was already required to be zero above.)
		if (state.noJumpDelay < 0 || state.jumpTriggerTime < 0 || state.sprintTriggerTime < 0
		    || state.fallFlyTicks < 0) {
			throw new UnimplementedMechanicException(
			    RefusalCause.INADMISSIBLE_STATE, "negative tick counter is outside vanilla's range");
		}
		if (state.ticksFrozen < 0 || state.ticksFrozen > ServerPlayerState.DEFAULT_TICKS_REQUIRED_TO_FREEZE
		    || state.frostSpeedTicks < 0
		    || state.frostSpeedTicks > ServerPlayerState.DEFAULT_TICKS_REQUIRED_TO_FREEZE) {
			throw UnimplementedMechanicException.deferred(RefusalCause.INADMISSIBLE_STATE,
			    () -> "frozen count outside 0.." + ServerPlayerState.DEFAULT_TICKS_REQUIRED_TO_FREEZE);
		}
		if (state.foodLevel < 0 || state.foodLevel > MAX_FOOD_LEVEL) {
			throw UnimplementedMechanicException.deferred(
			    RefusalCause.INADMISSIBLE_STATE, () -> "food level outside the bar: " + state.foodLevel);
		}
		if (state.yya != 0.0F) {
			throw UnimplementedMechanicException.deferred(
			    RefusalCause.INADMISSIBLE_STATE, () -> "a player's vertical input is always zero: " + state.yya);
		}
		if (!Float.isFinite(state.inputMoveVectorX) || !Float.isFinite(state.inputMoveVectorY)
		    || !Float.isFinite(state.yRot) || !Float.isFinite(state.xRot)) {
			throw new UnimplementedMechanicException(
			    RefusalCause.INADMISSIBLE_STATE, "retained input and rotation must be finite");
		}
	}

	/** {@code Player.onClimbable}: never while flying, else {@code LivingEntity.onClimbable} at the feet cell. */
	public static boolean onClimbable(final PlayerState state, final WorldView world) {
		if (state.flying || world.propertiesAnywhere(WorldView.PROPERTY_CLIMBABLE) == 0) {
			return false;
		}
		int x = Mth.floor(state.x);
		int y = Mth.floor(state.y);
		int z = Mth.floor(state.z);
		return world.propertiesIn(WorldView.PROPERTY_CLIMBABLE, x, y, z, x, y, z) != 0
		    && world.onClimbableAt(x, y, z, state.fallFlying);
	}

	static boolean onScaffolding(final PlayerState state, final WorldView world) {
		WorldView.CollisionBehavior behavior =
		    world.collisionBehaviorAt(Mth.floor(state.x), Mth.floor(state.y), Mth.floor(state.z));
		return behavior == WorldView.CollisionBehavior.SCAFFOLDING_SUPPORTED
		    || behavior == WorldView.CollisionBehavior.SCAFFOLDING_UNSTABLE_BOTTOM;
	}

	/** Whether the retained {@code input.keyPresses} holds the given {@code PlayerInput.FLAG_*} bit. */
	public static boolean hasInput(final PlayerState state, final int flag) {
		return (Byte.toUnsignedInt(state.inputKeyPresses) & flag) != 0;
	}

	/**
	 * {@code Player.causeFoodExhaustion}: {@code FoodData.addExhaustion} unless
	 * {@code abilities.invulnerable}. That ability is not a captured fact for a player who may fly,
	 * so the charge refuses whenever the two answers differ: any non-zero amount, or a zero amount
	 * onto a negative-zero level, which the addition would turn positive. A zero amount onto any
	 * other level leaves it unchanged either way.
	 *
	 * @throws PendingServerWriteException when the answer depends on the uncaptured ability
	 */
	public static void causeFoodExhaustion(final ServerPlayerState state, final float amount) {
		if (state.mayfly && (amount != 0.0F || Float.floatToRawIntBits(state.exhaustionLevel) == NEGATIVE_ZERO_BITS)) {
			throw new PendingServerWriteException(RefusalCause.PENDING_ABILITY,
			    "food exhaustion depends on the"
			        + " uncaptured invulnerable ability of a player who may fly");
		}
		FoodData.addExhaustion(state, amount);
	}
}
