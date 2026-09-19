package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** A derived server copy re-anchors only the published facts, and saturation only from a health packet. */
class ServerPlayerStateReanchorTest {
	@Test
	void takesPublishedFactsAndKeepsWhatAClientCannotSee() {
		PlayerState client = new PlayerState();
		client.placeAt(0.5, 64.0, 0.5);
		ServerPlayerState carried = ServerPlayerState.atBoundary(client);
		carried.exhaustionLevel = 2.5F;
		carried.saturationLevel = 3.0F;
		carried.aboveGroundTickCount = 7;
		carried.connectionTickCount = 41;
		ServerPlayerState observed = ServerPlayerState.atBoundary(client);
		observed.health = 17.0F;
		observed.foodLevel = 18;
		observed.saturationLevel = 1.0F;
		observed.invulnerableTime = 12;
		observed.awaitingTeleport = 3;

		int changed = carried.reanchorFrom(observed, false);

		assertEquals(4, changed);
		assertEquals(17.0F, carried.health);
		assertEquals(18, carried.foodLevel);
		assertEquals(12, carried.invulnerableTime);
		assertEquals(3, carried.awaitingTeleport);
		assertEquals(3.0F, carried.saturationLevel, "saturation is the simulator's between health packets");
		assertEquals(2.5F, carried.exhaustionLevel);
		assertEquals(7, carried.aboveGroundTickCount);
		assertEquals(41, carried.connectionTickCount);

		assertEquals(1, carried.reanchorFrom(observed, true));
		assertEquals(1.0F, carried.saturationLevel, "a health packet carries saturation");
	}
}
