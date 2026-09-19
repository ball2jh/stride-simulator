package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/** Boundary equality reads every component and every public field of each component. */
final class SimulationStateEqualityTest {
	@Test
	void everyBoundaryComponentParticipates() {
		PlayerState client = new PlayerState();
		ServerPlayerState server = ServerPlayerState.atBoundary(client);
		Publisher publisher = Publisher.atBoundary(client);
		SimulationState base = new SimulationState(client, publisher, server, 0, null);
		assertTrue(SimulationState.rawEquals(base, new SimulationState(client, publisher, server, 0, null)));
		assertFalse(SimulationState.rawEquals(base, new SimulationState(client, publisher, server, 1, null)));
		assertFalse(SimulationState.rawEquals(base, new SimulationState(client, publisher, server, 0, HurtCause.FALL)));
		client.x = -0.0;
		assertFalse(SimulationState.rawEquals(base, new SimulationState(client, publisher, server, 0, null)));
		client.x = 0;
		publisher.positionReminder++;
		assertFalse(SimulationState.rawEquals(base, new SimulationState(client, publisher, server, 0, null)));
		publisher.positionReminder--;
		server.foodLevel--;
		assertFalse(SimulationState.rawEquals(base, new SimulationState(client, publisher, server, 0, null)));
		assertFalse(SimulationState.rawEquals(base, null));
	}

	@Test
	void everyDeclaredRawFieldReachesEquality() throws Exception {
		SimulationState base =
		    new SimulationState(new PlayerState(), new Publisher(), new ServerPlayerState(), 0, null);
		for (int part = 0; part < 3; part++) {
			Object original = switch (part) {
				case 0 -> base.clientState();
				case 1 -> base.publisher();
				default -> base.serverState();
			};
			int ordinal = 1;
			for (Field field : original.getClass().getFields()) {
				if (!ReflectiveFields.isInstanceField(field)) {
					continue;
				}
				PlayerState client = base.clientState();
				Publisher publisher = base.publisher();
				ServerPlayerState server = base.serverState();
				Object changed = part == 0 ? client : part == 1 ? publisher : server;
				// Bypass validation: arbitrary raw mutations need not describe an admitted state.
				ReflectiveFields.mutate(field, changed, ordinal++);
				SimulationState boundary = SimulationState.takeOwnership(client, publisher, server, 0, null);
				assertFalse(SimulationState.rawEquals(base, boundary), field.toString());
			}
		}
	}
}
