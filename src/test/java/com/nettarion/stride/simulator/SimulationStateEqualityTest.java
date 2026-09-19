package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class SimulationStateEqualityTest {
	@Test
	void everyBoundaryComponentParticipates() {
		var client = new PlayerState();
		var server = ServerPlayerState.atBoundary(client);
		var publisher = Publisher.atBoundary(client);
		var base = new SimulationState(client, publisher, server, 0, null);
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
		var base = new SimulationState(new PlayerState(), new Publisher(), new ServerPlayerState(), 0, null);
		for (int part = 0; part < 3; part++) {
			Object original = switch (part) {
				case 0 -> base.clientState();
				case 1 -> base.publisher();
				default -> base.serverState();
			};
			for (var field : original.getClass().getFields()) {
				if (Modifier.isStatic(field.getModifiers())) continue;
				var client = base.clientState();
				var publisher = base.publisher();
				var server = base.serverState();
				Object changed = part == 0 ? client : part == 1 ? publisher : server;
				// Use simulator-owned test construction: arbitrary raw mutations need not pass public admission.
				if (field.getType() == boolean.class)
					field.setBoolean(changed, !field.getBoolean(changed));
				else if (field.getType() == double.class)
					field.setDouble(changed, field.getDouble(changed) + 1);
				else if (field.getType() == float.class)
					field.setFloat(changed, field.getFloat(changed) + 1);
				else if (field.getType() == int.class)
					field.setInt(changed, field.getInt(changed) ^ 1);
				else if (field.getType() == long.class)
					field.setLong(changed, field.getLong(changed) ^ 1L);
				else if (field.getType() == byte.class)
					field.setByte(changed, (byte) (field.getByte(changed) ^ 1));
				else if (field.getType().isEnum()) {
					Object[] values = field.getType().getEnumConstants();
					field.set(changed, values[field.get(changed) == values[0] ? 1 : 0]);
				} else
					fail("Uncovered field: " + field);
				var boundary = SimulationState.takeOwnership(client, publisher, server, 0, null);
				assertFalse(SimulationState.rawEquals(base, boundary), field.toString());
			}
		}
	}
}
