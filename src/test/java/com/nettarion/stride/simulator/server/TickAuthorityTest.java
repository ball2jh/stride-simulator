package com.nettarion.stride.simulator.server;

import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.tick.ClientTick;
import com.nettarion.stride.simulator.tick.PlayerTick;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.FlatFloorView;
import com.nettarion.stride.simulator.world.WorldView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Authority binding keeps movement and survival on the same server transaction. */
class TickAuthorityTest {
	@Test
	void rebindingMovesSurvivalAndSyncedInputTogetherAndClearsPreviousHits() {
		TickAuthority authority = TickAuthority.server();
		Scratch scratch = new Scratch(authority);
		ServerPlayerState first = new ServerPlayerState();
		first.shiftKeyDown = true;
		authority.begin(first);
		assertTrue(PlayerTick.isShiftKeyDown(first, scratch));
		authority.survival().hurtServer(HurtCause.CACTUS, 1.0F);
		assertEquals(19.0F, first.health);

		ServerPlayerState second = new ServerPlayerState();
		authority.begin(second);
		assertSame(second, authority.serverState());
		assertFalse(PlayerTick.isShiftKeyDown(second, scratch));
		assertTrue(authority.survival().dealt().isEmpty());
		authority.survival().hurtServer(HurtCause.LAVA, 4.0F);
		assertEquals(16.0F, second.health);
		assertEquals(19.0F, first.health);
	}

	@Test
	void serverWorkspaceRejectsClientTicksAndAnUnboundOrDifferentServerCopy() {
		TickAuthority authority = TickAuthority.server();
		Scratch scratch = new Scratch(authority);
		ClientTick tick = new ClientTick();
		ServerPlayerState bound = new ServerPlayerState();
		PlayerInput input = PlayerInput.idle(0.0F, 0.0F);
		WorldView world = FlatFloorView.ordinary(0, -64);
		assertThrows(IllegalStateException.class, () -> PlayerTick.serverTick(bound, world, scratch));
		authority.begin(bound);
		assertThrows(IllegalArgumentException.class, () -> tick.tick(bound, input, world, scratch));
		assertThrows(
		    IllegalArgumentException.class, () -> PlayerTick.serverTick(new ServerPlayerState(), world, scratch));
	}

	@Test
	void clientAuthorityCannotAcquireServerEffects() {
		TickAuthority authority = TickAuthority.client();
		assertThrows(IllegalStateException.class, authority::survival);
		assertThrows(IllegalStateException.class, () -> authority.begin(new ServerPlayerState()));
	}
}
