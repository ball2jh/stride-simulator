package com.nettarion.stride.simulator.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.ShapeProvenance;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;

import org.junit.jupiter.api.Test;

/**
 * What a grounded state's first tick does to its ground contact, as
 * {@code LivingEntity.travelInAir} orders it: the move runs with the
 * velocity retained from the previous tick, and gravity is added only after
 * it. A player standing in vanilla therefore retains {@code -0.0784}
 * vertically every tick, requests that descent, and collides it to zero. A
 * state constructed with zero retained velocity requests a zero move, and
 * {@code Entity.move}'s {@code verticalCollision = delta.y != movement.y} is
 * false for it: the ground and the supporting block are lost for one tick and
 * regained on the next, in vanilla as here.
 */
final class FirstTickGroundContactTest {
	/** {@code (0.0 - 0.08) * 0.98F}: the vertical velocity a standing player retains. */
	private static final double STANDING_VELOCITY = (0.0 - 0.08) * 0.98F;

	@Test
	void theRetainedStandingVelocityHoldsGroundOnTheFirstTick() {
		for (ShapeProvenance identity : ShapeProvenance.values()) {
			if (identity == ShapeProvenance.LEGACY_GEOMETRY) {
				continue;
			}
			SnapshotView world = floor(identity);
			PlayerState state = standing(0.5, 0.0, 0.5);
			state.deltaMovementY = STANDING_VELOCITY;
			new ClientTick().tick(state, PlayerInput.idle(0.0F, 0.0F), world, new Scratch());
			assertEquals(0.0, state.y, identity + ": the cube under the feet stops the descent");
			assertTrue(state.verticalCollision, identity + ": delta.y != movement.y");
			assertTrue(state.onGround, identity.toString());
			assertTrue(state.mainSupportingBlockPosPresent, identity.toString());
			assertEquals(-1, state.mainSupportingBlockPosY, identity.toString());
			assertEquals(STANDING_VELOCITY, state.deltaMovementY, identity + ": steady state");
		}
	}

	@Test
	void zeroRetainedVelocityRequestsNoMoveAndLosesGroundForOneTick() {
		SnapshotView world = floor(ShapeProvenance.GENERAL);
		PlayerState state = standing(0.5, 0.0, 0.5);
		ClientTick simulator = new ClientTick();
		Scratch scratch = new Scratch();

		simulator.tick(state, PlayerInput.idle(0.0F, 0.0F), world, scratch);
		assertEquals(0.0, state.y, "a zero request moves nothing");
		assertFalse(state.verticalCollision, "0.0 != 0.0 is false in Entity.move");
		assertFalse(state.onGround, "setOnGroundWithMovement(verticalCollisionBelow) clears it");
		assertFalse(state.mainSupportingBlockPosPresent);
		assertEquals(STANDING_VELOCITY, state.deltaMovementY, "gravity is added after the move");

		simulator.tick(state, PlayerInput.idle(0.0F, 0.0F), world, scratch);
		assertEquals(0.0, state.y);
		assertTrue(state.verticalCollision, "the retained descent now collides");
		assertTrue(state.onGround);
		assertTrue(state.mainSupportingBlockPosPresent);
	}

	private static PlayerState standing(final double x, final double y, final double z) {
		PlayerState state = new PlayerState();
		state.placeAt(x, y, z);
		state.onGround = true;
		state.mainSupportingBlockPosPresent = true;
		state.mainSupportingBlockPosX = Mth.floor(x);
		state.mainSupportingBlockPosY = Mth.floor(y) - 1;
		state.mainSupportingBlockPosZ = Mth.floor(z);
		return state;
	}

	private static SnapshotView floor(final ShapeProvenance identity) {
		BlockEntry air = BlockEntry.builder(0, "minecraft:air").build();
		BlockEntry.Builder stone = BlockEntry.builder(1, "minecraft:stone").boxes(ShapeBox.FULL_CUBE);
		if (identity == ShapeProvenance.CANONICAL_FULL) {
			stone.fullCube();
		}
		WorldSnapshot.Builder builder =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, -8, -3, -8, 16, 12, 16).palette(air, stone.build());
		for (int x = -8; x < 8; x++) {
			for (int z = -8; z < 8; z++) {
				builder.set(x, -1, z, 1);
			}
		}
		return SnapshotView.compile(builder.build());
	}
}
