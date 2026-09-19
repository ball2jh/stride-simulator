package com.nettarion.stride.simulator;

import com.nettarion.stride.simulator.block.BlockEffects;
import com.nettarion.stride.simulator.server.FoodData;
import com.nettarion.stride.simulator.server.Survival;
import com.nettarion.stride.simulator.server.TickAuthority;
import com.nettarion.stride.simulator.tick.AiStep;
import com.nettarion.stride.simulator.tick.BaseTick;
import com.nettarion.stride.simulator.tick.Freezing;
import com.nettarion.stride.simulator.tick.Move;
import com.nettarion.stride.simulator.tick.PlayerPose;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.tick.Travel;
import com.nettarion.stride.simulator.world.FlatFloorView;
import com.nettarion.stride.simulator.world.WorldView;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Each phase writes only the {@link PlayerState} fields its contract
 * declares, on both copies of the player. The declared sets are what a
 * reader of a phase relies on; this holds them to the code by running the
 * phases in tick order over a short schedule and diffing every public field
 * around each one.
 */
class PhaseContractTest {
	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);
	private static final PlayerInput SPRINT_JUMP =
	    new PlayerInput(true, false, false, false, true, true, false, 35.0F, -10.0F);
	private static final PlayerInput SNEAK_BACK =
	    new PlayerInput(false, true, false, true, false, false, true, 200.0F, 20.0F);

	private static final PlayerInput[] SCHEDULE = {IDLE, SPRINT_JUMP, SPRINT_JUMP, SPRINT_JUMP, SNEAK_BACK, SNEAK_BACK,
	    IDLE, SPRINT_JUMP, IDLE, SNEAK_BACK, IDLE, IDLE};

	@Test
	void everyPhaseWritesOnlyItsDeclaredFields() throws ReflectiveOperationException {
		PlayerState state = standingStart(new PlayerState());
		Scratch scratch = new Scratch();
		List<String> violations = new ArrayList<>();
		for (PlayerInput action : SCHEDULE) {
			runPhases(violations, state, action, scratch);
		}
		assertTrue(violations.isEmpty(), String.join("\n", violations));
	}

	/**
	 * The server's copy runs the same sequence under server authority with
	 * no input of its own and the synced shift flag, and the survival
	 * branches are live.
	 */
	@Test
	void everyPhaseWritesOnlyItsDeclaredFieldsOnTheServersCopy() throws ReflectiveOperationException {
		ServerPlayerState state = standingStart(new ServerPlayerState());
		TickAuthority authority = TickAuthority.server();
		authority.begin(state);
		Scratch scratch = new Scratch(authority);
		List<String> violations = new ArrayList<>();
		for (PlayerInput action : SCHEDULE) {
			state.shiftKeyDown = action.shift();
			runPhases(violations, state, PlayerInput.idle(action.yRot(), action.xRot()), scratch);
		}
		assertTrue(violations.isEmpty(), String.join("\n", violations));
	}

	@Test
	void everyDeclaredFieldExists() throws ReflectiveOperationException {
		Set<String> declared = new java.util.HashSet<>(BaseTick.WRITES);
		declared.addAll(AiStep.WRITES);
		declared.addAll(Travel.WRITES);
		declared.addAll(Move.WRITES);
		declared.addAll(BlockEffects.WRITES);
		declared.addAll(Freezing.WRITES);
		declared.addAll(PlayerPose.WRITES);
		declared.addAll(Survival.HURT_WRITES);
		declared.addAll(FoodData.WRITES);
		for (String name : declared) {
			ServerPlayerState.class.getField(name);
		}
	}

	private static <S extends PlayerState> S standingStart(final S state) {
		state.x = 0.5;
		state.y = 0.0;
		state.z = 0.5;
		state.onGround = true;
		state.setBox(AABB.around(state.x, state.y, state.z, state.pose.width, state.pose.height));
		return state;
	}

	private static void runPhases(final List<String> violations, final PlayerState state, final PlayerInput action,
	    final Scratch scratch) throws ReflectiveOperationException {
		WorldView world = FlatFloorView.ordinary(0, -64);
		state.yRot = action.yRot();
		state.xRot = action.xRot();
		scratch.oldX = state.x;
		scratch.oldY = state.y;
		scratch.oldZ = state.z;
		check(violations, "BaseTick", BaseTick.WRITES, state, () -> BaseTick.run(state, world, scratch));
		check(violations, "AiStep", AiStep.WRITES, state, () -> AiStep.run(state, action, world, scratch));
		check(violations, "Travel", Travel.WRITES, state, () -> Travel.run(state, action, world, scratch));
		check(violations, "BlockEffects", BlockEffects.WRITES, state,
		    () -> BlockEffects.applyEffectsFromBlocks(state, world, scratch));
		check(violations, "Freezing", Freezing.WRITES, state, () -> Freezing.run(state, world, scratch));
		check(violations, "PlayerPose", PlayerPose.WRITES, state,
		    () -> PlayerPose.updatePlayerPose(state, world, scratch));
		if (state instanceof ServerPlayerState server) {
			check(violations, "FoodData", FoodData.WRITES, state, () -> FoodData.tick(server));
		}
	}

	private static void check(final List<String> violations, final String phase, final Set<String> writes,
	    final PlayerState state, final Runnable run) throws ReflectiveOperationException {
		Map<String, Object> before = snapshot(state);
		run.run();
		Map<String, Object> after = snapshot(state);
		for (Map.Entry<String, Object> field : before.entrySet()) {
			Object was = field.getValue();
			Object now = after.get(field.getKey());
			if (!was.equals(now) && !writes.contains(field.getKey())) {
				violations.add(
				    phase + " wrote " + field.getKey() + " (" + was + " -> " + now + ") outside its declared set");
			}
		}
	}

	/** Every public instance field of the state's own class, raw bits for the floating-point ones. */
	private static Map<String, Object> snapshot(final PlayerState state) throws ReflectiveOperationException {
		Map<String, Object> values = new HashMap<>();
		for (Field field : state.getClass().getFields()) {
			if (Modifier.isStatic(field.getModifiers())) {
				continue;
			}
			Object value = field.get(state);
			if (value instanceof Double d) {
				value = Double.doubleToRawLongBits(d);
			} else if (value instanceof Float f) {
				value = Float.floatToRawIntBits(f);
			}
			values.put(field.getName(), value);
		}
		return values;
	}
}
