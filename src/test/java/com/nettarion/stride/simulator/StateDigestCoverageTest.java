package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Every public semantic field must reach {@link StateDigest}.
 *
 * <p>Two reflection guards cover the other consumers of the state vector:
 * {@link PlayerStateCopyTest} requires every public field to survive
 * {@code copyInto}, and the planner's {@code FutureIdentityTest} requires every
 * one to change both the identity hash and {@code rawEquals}. The digest had
 * none, and it is the consumer where a gap is least visible — it is the
 * persisted cross-implementation identity, so a field that misses it is a field two implementations
 * can disagree on while every trace comparison reports agreement.
 *
 * <p>This closes that gap by construction rather than by remembering: a field
 * added to {@link PlayerState} either reaches the digest or fails here.
 */
class StateDigestCoverageTest {
	@Test
	void everyPublicSemanticFieldReachesTheDigest() throws Exception {
		PlayerState baseline = new PlayerState();
		long baselineDigest = StateDigest.state(baseline);
		int ordinal = 1;

		for (Field field : PlayerState.class.getFields()) {
			if (Modifier.isStatic(field.getModifiers())) {
				continue;
			}
			PlayerState changed = baseline.copy();
			mutate(field, changed, ordinal++);
			assertNotEquals(baselineDigest, StateDigest.state(changed),
			    field.getName() + " does not reach the persisted digest, so two implementations"
			        + " could disagree on it without any trace comparison saying so");
		}
	}

	@Test
	void everyServerFieldReachesTheServerDigest() throws Exception {
		ServerPlayerState baseline = new ServerPlayerState();
		long baselineDigest = StateDigest.server(baseline);
		int ordinal = 1;

		for (Field field : ServerPlayerState.class.getFields()) {
			if (Modifier.isStatic(field.getModifiers())) {
				continue;
			}
			ServerPlayerState changed = baseline.copy();
			mutate(field, changed, ordinal++);
			assertNotEquals(baselineDigest, StateDigest.server(changed),
			    field.getName() + " does not reach the server digest, so a stress chain"
			        + " could agree while the server copies differ");
		}
	}

	@Test
	void everyPublisherFieldAndBoundaryFactReachesTheBoundaryDigest() throws Exception {
		PlayerState client = new PlayerState();
		ServerPlayerState server = new ServerPlayerState();
		Publisher baseline = new Publisher();
		long baselineDigest = StateDigest.boundary(client, baseline, server, 0, null);
		int ordinal = 1;
		for (Field field : Publisher.class.getFields()) {
			if (Modifier.isStatic(field.getModifiers())) {
				continue;
			}
			Publisher changed = baseline.copy();
			mutate(field, changed, ordinal++);
			assertNotEquals(baselineDigest, StateDigest.boundary(client, changed, server, 0, null),
			    field.getName() + " does not reach the boundary digest, so two boundaries"
			        + " the next step publishes differently could share an identity");
		}
		assertNotEquals(baselineDigest, StateDigest.boundary(client, baseline, server, 1, null));
		assertNotEquals(baselineDigest, StateDigest.boundary(client, baseline, server, 0, HurtCause.FALL));
		PlayerState movedClient = client.copy();
		movedClient.x = 1.0;
		assertNotEquals(baselineDigest, StateDigest.boundary(movedClient, baseline, server, 0, null));
		ServerPlayerState fedServer = server.copy();
		fedServer.foodLevel ^= 1;
		assertNotEquals(baselineDigest, StateDigest.boundary(client, baseline, fedServer, 0, null));
	}

	/**
	 * Raw bit patterns rather than ordinary values, so a field that reaches the
	 * digest only through a lossy conversion still fails. NaN payloads and signed
	 * zero are both reachable in vanilla collision code and both invisible to
	 * {@code ==}.
	 */
	private static void mutate(final Field field, final Object state, final int ordinal) throws Exception {
		Class<?> type = field.getType();
		if (type == double.class) {
			field.setDouble(state, Double.longBitsToDouble(0x7ff8_0000_0000_0000L + ordinal));
		} else if (type == float.class) {
			field.setFloat(state, Float.intBitsToFloat(0x7fc0_0000 + ordinal));
		} else if (type == boolean.class) {
			field.setBoolean(state, !field.getBoolean(state));
		} else if (type == int.class) {
			field.setInt(state, field.getInt(state) ^ (0x1357_0000 + ordinal));
		} else if (type == byte.class) {
			field.setByte(state, (byte) (field.getByte(state) ^ (0x40 + ordinal)));
		} else if (type == long.class) {
			field.setLong(state, field.getLong(state) ^ (0x1357_0000_0000L + ordinal));
		} else if (type == ServerPlayerState.Difficulty.class) {
			field.set(state, ServerPlayerState.Difficulty.HARD);
		} else if (type == PlayerState.Pose.class) {
			field.set(state,
			    field.get(state) == PlayerState.Pose.SWIMMING ? PlayerState.Pose.STANDING : PlayerState.Pose.SWIMMING);
		} else {
			throw new AssertionError("unhandled PlayerState field type " + type + " for " + field.getName()
			    + "; extend this guard rather than"
			    + " letting a field slip past it");
		}
	}
}
