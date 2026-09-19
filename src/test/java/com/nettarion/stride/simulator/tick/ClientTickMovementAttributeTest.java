package com.nettarion.stride.simulator.tick;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.world.FlatFloorView;

import org.junit.jupiter.api.Test;

final class ClientTickMovementAttributeTest {
	private static final PlayerInput WALK_FORWARD =
	    new PlayerInput(true, false, false, false, false, false, false, 0.0F, 0.0F);

	@Test
	void movementSpeedMultiplierChangesGroundAcceleration() {
		PlayerState ordinary = groundedState();
		PlayerState speed = groundedState();
		speed.movementSpeedMultiplier = 1.2;
		PlayerState slowness = groundedState();
		slowness.movementSpeedMultiplier = 0.85;
		ClientTick simulator = new ClientTick();
		FlatFloorView world = FlatFloorView.ordinary(0, -64);

		simulator.tick(ordinary, WALK_FORWARD, world);
		simulator.tick(speed, WALK_FORWARD, world);
		simulator.tick(slowness, WALK_FORWARD, world);

		assertTrue(speed.deltaMovementZ > ordinary.deltaMovementZ);
		assertTrue(ordinary.deltaMovementZ > slowness.deltaMovementZ);
	}

	@Test
	void highSlownessClampsMovementSpeedToZero() {
		PlayerState state = groundedState();
		state.movementSpeedMultiplier = 1.0 + (double) -0.15F * 7.0;
		new ClientTick().tick(state, WALK_FORWARD, FlatFloorView.ordinary(0, -64));
		assertTrue(state.deltaMovementX == 0.0 && state.deltaMovementZ == 0.0);
	}

	@Test
	void jumpBoostPowerAddsToTheGroundJumpImpulse() {
		PlayerState ordinary = groundedState();
		PlayerState boosted = groundedState();
		boosted.jumpBoostPower = 0.1F;
		PlayerInput jump = new PlayerInput(false, false, false, false, true, false, false, 0.0F, 0.0F);
		ClientTick simulator = new ClientTick();
		FlatFloorView world = FlatFloorView.ordinary(0, -64);

		simulator.tick(ordinary, jump, world);
		simulator.tick(boosted, jump, world);

		assertTrue(boosted.deltaMovementY > ordinary.deltaMovementY);
	}

	@Test
	void invalidJumpBoostPowerFailsByName() {
		PlayerState state = groundedState();
		state.jumpBoostPower = Float.NaN;
		ClientTick simulator = new ClientTick();
		assertThrows(UnimplementedMechanicException.class,
		    ()
		        -> simulator.tick(state, new PlayerInput(false, false, false, false, true, false, false, 0.0F, 0.0F),
		            FlatFloorView.ordinary(0, -64)));
	}

	private static PlayerState groundedState() {
		PlayerState state = new PlayerState();
		state.placeAt(0.5, 0.0, 0.5);
		state.onGround = true;
		state.mainSupportingBlockPosPresent = true;
		state.mainSupportingBlockPosX = 0;
		state.mainSupportingBlockPosY = -1;
		state.mainSupportingBlockPosZ = 0;
		return state;
	}
}
