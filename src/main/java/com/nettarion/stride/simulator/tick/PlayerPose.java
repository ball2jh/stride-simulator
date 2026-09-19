package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.world.WorldView;
import java.util.Set;

/**
 * The pose the tick ends in and the box it resizes to:
 * {@code Player.updatePlayerPose}, the last phase of {@link ClientTick}.
 *
 * <p>Vanilla asks for swimming, gliding, crouching, or standing in that
 * order of precedence, probes whether the desired box and its shorter
 * fallbacks fit at the current position, keeps the pose when even the
 * smallest box is blocked, and resizes the box in place when the pose
 * changes. The shift state is the current input on the client, since
 * {@code KeyboardInput.tick} has run, and the synced flag on the server's copy.
 *
 * <p>Reads the flags, the current input, position, and the pose-fit
 * certificate. Writes the fields in {@link #WRITES}.
 */
public final class PlayerPose {
	/** Every {@link PlayerState} field this phase may write. */
	public static final Set<String> WRITES = Set.of("entityDataDirty", "pose", "boundingBoxMinX", "boundingBoxMinY",
	    "boundingBoxMinZ", "boundingBoxMaxX", "boundingBoxMaxY", "boundingBoxMaxZ");

	private PlayerPose() {}

	/** {@code Player.updatePlayerPose}: the desired pose, the fit probe, and the box resize. */
	public static void updatePlayerPose(final PlayerState state, final WorldView world, final Scratch scratch) {
		// getDesiredPose(): PlayerTick.isShiftKeyDown() is the *current* input by now,
		// because input.tick() ran earlier in this same tick. That is why the
		// pose changes one tick before `crouching` does.
		//
		// Hoisted above the probe. It reads only state and input, so moving it is
		// free of side effects, and it says how tall the single probe has to be.
		// Vanilla asks the swimming height first and the desired height only when
		// that fits; the one query at the desired height can meet an unclassified
		// block above the swimming box and refuse where vanilla proceeds, a
		// broader refusal, never a different answer.
		PlayerState.Pose desired = getDesiredPose(state, scratch);

		int fits = Clearance.fitPoses(state, world, desired, scratch);

		// Player.updatePlayerPose leaves the pose alone when even the smallest box
		// is blocked. The swimming bit is that same test.
		if ((fits & Clearance.FIT_SWIMMING) == 0) {
			return;
		}

		PlayerState.Pose actual;
		if (desired == PlayerState.Pose.STANDING) {
			actual = (fits & Clearance.FIT_STANDING) != 0 ? PlayerState.Pose.STANDING
			    : (fits & Clearance.FIT_CROUCHING) != 0   ? PlayerState.Pose.CROUCHING
			                                              : PlayerState.Pose.SWIMMING;
		} else if (desired == PlayerState.Pose.CROUCHING) {
			actual = (fits & Clearance.FIT_CROUCHING) != 0 ? PlayerState.Pose.CROUCHING : PlayerState.Pose.SWIMMING;
		} else {
			// FALL_FLYING and SWIMMING share width and height, so the swimming bit
			// proves either one.
			actual = desired;
		}

		if (actual != state.pose) {
			boolean hadActualPoseFit = state.hasPoseFitCertificate(world, actual);
			state.setPose(actual);
			state.placeAt(state.x, state.y, state.z);
			if (hadActualPoseFit) {
				state.certifyPoseFit(world, actual);
			}
		}
	}

	/** Player.getDesiredPose for the admitted, awake, non-spinning player. */
	private static PlayerState.Pose getDesiredPose(final PlayerState state, final Scratch scratch) {
		return state.swimming                                            ? PlayerState.Pose.SWIMMING
		    : state.fallFlying                                           ? PlayerState.Pose.FALL_FLYING
		    : PlayerTick.isShiftKeyDown(state, scratch) && !state.flying ? PlayerState.Pose.CROUCHING
		                                                                 : PlayerState.Pose.STANDING;
	}
}
