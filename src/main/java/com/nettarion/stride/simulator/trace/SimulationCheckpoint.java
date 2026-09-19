package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.*;
import com.nettarion.stride.simulator.ExternalActor;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.MovementPacket;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.ScheduledSimulation;
import com.nettarion.stride.simulator.SimulationState;
import com.nettarion.stride.simulator.BlockUpdateWrite;
import com.nettarion.stride.simulator.DamageEvent;
import com.nettarion.stride.simulator.DamageWrite;
import com.nettarion.stride.simulator.EntityDataWrite;
import com.nettarion.stride.simulator.HealthWrite;
import com.nettarion.stride.simulator.HurtMotion;
import com.nettarion.stride.simulator.HurtMotionWrite;
import com.nettarion.stride.simulator.ImpulseWrite;
import com.nettarion.stride.simulator.PlayerPositionWrite;
import com.nettarion.stride.simulator.ServerWrite;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import java.io.*;
import java.util.*;

/**
 * Exact raw-state evidence covering both players, publication, transport, writes and both worlds.
 */
public final class SimulationCheckpoint {
	private SimulationCheckpoint() {}

	/**
     * Independently constructible raw evidence; observing it does not execute simulator mechanics.
     */
	public record Evidence(SimulationState players, ScheduledSimulation.Transport transport, List<ServerWrite> writes,
	    WorldSnapshot clientWorld, WorldSnapshot serverWorld) {
		public Evidence {
			Objects.requireNonNull(players);
			Objects.requireNonNull(transport);
			writes = List.copyOf(writes);
			Objects.requireNonNull(clientWorld);
			Objects.requireNonNull(serverWorld);
		}
	}

	public static byte[] capture(final ScheduledSimulation simulation) {
		return capture(new Evidence(new SimulationState(simulation.clientState(), simulation.publisher(),
		                                simulation.serverState(), simulation.completedActions(), null),
		    simulation.transportState(), simulation.writes(), simulation.clientWorld().materializedSnapshot(),
		    simulation.serverWorld().materializedSnapshot()));
	}

	/**
     * Fresh evidence encoded at the boundary schema an archived checkpoint was written at, so
     * an older archive compares byte for byte against what it could record; a state the older
     * schema cannot carry is encoded at the current one and the comparison fails honestly.
     */
	public static byte[] captureLike(final byte[] archived, final ScheduledSimulation simulation) {
		int schema = archived.length < 4
		    ? BoundaryCodec.schema()
		    : (archived[0] & 0xFF) << 24 | (archived[1] & 0xFF) << 16 | (archived[2] & 0xFF) << 8 | archived[3] & 0xFF;
		var players = new SimulationState(simulation.clientState(), simulation.publisher(), simulation.serverState(),
		    simulation.completedActions(), null);
		// Only a recognized older schema is re-encoded; anything else, corrupt
		// bytes included, compares at the current schema and fails honestly.
		if (schema != 1 || !BoundaryCodec.representable(players, schema)) {
			return capture(simulation);
		}
		return capture(
		    new Evidence(players, simulation.transportState(), simulation.writes(),
		        simulation.clientWorld().materializedSnapshot(), simulation.serverWorld().materializedSnapshot()),
		    schema);
	}

	/**
     * Encode observed facts with the same raw schema used by replay, without stepping the
     * simulator.
     */
	public static byte[] capture(final Evidence evidence) {
		return capture(evidence, BoundaryCodec.schema());
	}

	private static byte[] capture(final Evidence evidence, final int schema) {
		try {
			var bytes = new ByteArrayOutputStream();
			var out = new DataOutputStream(bytes);
			var players = evidence.players();
			BoundaryCodec.write(new SimulationState(players.clientState(), players.publisher(), players.serverState(),
			                        players.completedActions(), null),
			    out, schema);
			value(evidence.transport(), out);
			value(evidence.writes(), out);
			world(evidence.clientWorld(), out);
			world(evidence.serverWorld(), out);
			return bytes.toByteArray();
		} catch (IOException failure) {
			throw new UncheckedIOException(failure);
		}
	}

	private static void world(final WorldSnapshot snapshot, final DataOutputStream out) throws IOException {
		var text = new StringWriter();
		WorldSnapshotCodec.write(snapshot, text);
		byte[] data = text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
		out.writeInt(data.length);
		out.write(data);
	}

	// Frozen version-1 tags retain old archives without deriving a schema from implementation
	// classes.
	private static void value(final Object value, final DataOutputStream out) throws IOException {
		if (value == null) {
			out.writeUTF("null");
			return;
		}
		if (value instanceof List<?> list) {
			out.writeUTF("list");
			out.writeInt(list.size());
			for (var item : list)
				value(item, out);
			return;
		}
		if (value instanceof java.lang.Double v) {
			out.writeUTF("java.lang.Double");
			out.writeLong(Double.doubleToRawLongBits(v));
			return;
		}
		if (value instanceof java.lang.Float v) {
			out.writeUTF("java.lang.Float");
			out.writeInt(Float.floatToRawIntBits(v));
			return;
		}
		if (value instanceof java.lang.Integer v) {
			out.writeUTF("java.lang.Integer");
			out.writeLong(v.longValue());
			return;
		}
		if (value instanceof java.lang.Long v) {
			out.writeUTF("java.lang.Long");
			out.writeLong(v.longValue());
			return;
		}
		if (value instanceof java.lang.Byte v) {
			out.writeUTF("java.lang.Byte");
			out.writeLong(v.longValue());
			return;
		}
		if (value instanceof java.lang.Boolean v) {
			out.writeUTF("java.lang.Boolean");
			out.writeBoolean(v);
			return;
		}
		if (value instanceof java.lang.String v) {
			out.writeUTF("java.lang.String");
			out.writeUTF(v);
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.ExternalActor.Kind v) {
			out.writeUTF("com.nettarion.stride.simulator.ExternalActor$Kind");
			out.writeUTF(v.name());
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.HurtCause v) {
			out.writeUTF("com.nettarion.stride.simulator.HurtCause");
			out.writeUTF(v.name());
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.ImpulseWrite.Operation v) {
			out.writeUTF("com.nettarion.stride.simulator.ImpulseWrite$Operation");
			out.writeUTF(v.name());
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.ImpulseWrite.Phase v) {
			out.writeUTF("com.nettarion.stride.simulator.ImpulseWrite$Phase");
			out.writeUTF(v.name());
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.MovementPacket v) {
			out.writeUTF("com.nettarion.stride.simulator.MovementPacket");
			out.writeUTF(v.name());
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.PlayerState.Pose v) {
			out.writeUTF("com.nettarion.stride.simulator.PlayerState$Pose");
			out.writeUTF(v.name());
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.BlockUpdateWrite v) {
			out.writeUTF("com.nettarion.stride.simulator.BlockUpdateWrite");
			out.writeUTF("actionIndex");
			value(v.actionIndex(), out);
			out.writeUTF("x");
			value(v.x(), out);
			out.writeUTF("y");
			value(v.y(), out);
			out.writeUTF("z");
			value(v.z(), out);
			out.writeUTF("paletteIndex");
			value(v.paletteIndex(), out);
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.DamageEvent v) {
			out.writeUTF("com.nettarion.stride.simulator.DamageEvent");
			out.writeUTF("cause");
			value(v.cause(), out);
			out.writeUTF("attempted");
			value(v.attempted(), out);
			out.writeUTF("absorbed");
			value(v.absorbed(), out);
			out.writeUTF("healthDamage");
			value(v.healthDamage(), out);
			out.writeUTF("healthAfter");
			value(v.healthAfter(), out);
			out.writeUTF("full");
			value(v.full(), out);
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.DamageWrite v) {
			out.writeUTF("com.nettarion.stride.simulator.DamageWrite");
			out.writeUTF("actionIndex");
			value(v.actionIndex(), out);
			out.writeUTF("event");
			value(v.event(), out);
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.EntityDataWrite v) {
			out.writeUTF("com.nettarion.stride.simulator.EntityDataWrite");
			out.writeUTF("actionIndex");
			value(v.actionIndex(), out);
			out.writeUTF("beforeDigest");
			value(v.beforeDigest(), out);
			out.writeUTF("dirty");
			value(v.dirty(), out);
			out.writeUTF("sharedFlags");
			value(v.sharedFlags(), out);
			out.writeUTF("pose");
			value(v.pose(), out);
			out.writeUTF("ticksFrozen");
			value(v.ticksFrozen(), out);
			out.writeUTF("frostSpeedTicks");
			value(v.frostSpeedTicks(), out);
			out.writeUTF("sprintingAttribute");
			value(v.sprintingAttribute(), out);
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.ExternalActor v) {
			out.writeUTF("com.nettarion.stride.simulator.ExternalActor");
			out.writeUTF("kind");
			value(v.kind(), out);
			out.writeUTF("entityId");
			value(v.entityId(), out);
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.HealthWrite v) {
			out.writeUTF("com.nettarion.stride.simulator.HealthWrite");
			out.writeUTF("actionIndex");
			value(v.actionIndex(), out);
			out.writeUTF("beforeDigest");
			value(v.beforeDigest(), out);
			out.writeUTF("health");
			value(v.health(), out);
			out.writeUTF("foodLevel");
			value(v.foodLevel(), out);
			out.writeUTF("saturationLevel");
			value(v.saturationLevel(), out);
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.HurtMotionWrite v) {
			out.writeUTF("com.nettarion.stride.simulator.HurtMotionWrite");
			out.writeUTF("actionIndex");
			value(v.actionIndex(), out);
			out.writeUTF("event");
			value(v.event(), out);
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.ImpulseWrite v) {
			out.writeUTF("com.nettarion.stride.simulator.ImpulseWrite");
			out.writeUTF("actionIndex");
			value(v.actionIndex(), out);
			out.writeUTF("actor");
			value(v.actor(), out);
			out.writeUTF("sequence");
			value(v.sequence(), out);
			out.writeUTF("phase");
			value(v.phase(), out);
			out.writeUTF("operation");
			value(v.operation(), out);
			out.writeUTF("x");
			value(v.x(), out);
			out.writeUTF("y");
			value(v.y(), out);
			out.writeUTF("z");
			value(v.z(), out);
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.PlayerInput v) {
			out.writeUTF("com.nettarion.stride.simulator.PlayerInput");
			out.writeUTF("forward");
			value(v.forward(), out);
			out.writeUTF("backward");
			value(v.backward(), out);
			out.writeUTF("left");
			value(v.left(), out);
			out.writeUTF("right");
			value(v.right(), out);
			out.writeUTF("jump");
			value(v.jump(), out);
			out.writeUTF("shift");
			value(v.sneak(), out);
			out.writeUTF("sprint");
			value(v.sprint(), out);
			out.writeUTF("yRot");
			value(v.yRot(), out);
			out.writeUTF("xRot");
			value(v.xRot(), out);
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.PlayerPositionWrite v) {
			out.writeUTF("com.nettarion.stride.simulator.PlayerPositionWrite");
			out.writeUTF("actionIndex");
			value(v.actionIndex(), out);
			out.writeUTF("beforeDigest");
			value(v.beforeDigest(), out);
			out.writeUTF("teleportId");
			value(v.teleportId(), out);
			out.writeUTF("x");
			value(v.x(), out);
			out.writeUTF("y");
			value(v.y(), out);
			out.writeUTF("z");
			value(v.z(), out);
			out.writeUTF("yRot");
			value(v.yRot(), out);
			out.writeUTF("xRot");
			value(v.xRot(), out);
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.ScheduledSimulation.AcceptTeleport v) {
			out.writeUTF("com.nettarion.stride.simulator.ScheduledSimulation$AcceptTeleport");
			out.writeUTF("id");
			value(v.id(), out);
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.ScheduledSimulation.BlockUpdate v) {
			out.writeUTF("com.nettarion.stride.simulator.ScheduledSimulation$BlockUpdate");
			out.writeUTF("write");
			value(v.write(), out);
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.ScheduledSimulation.Data v) {
			out.writeUTF("com.nettarion.stride.simulator.ScheduledSimulation$Data");
			out.writeUTF("value");
			value(v.value(), out);
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.ScheduledSimulation.Glide v) {
			out.writeUTF("com.nettarion.stride.simulator.ScheduledSimulation$Glide");
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.ScheduledSimulation.Health v) {
			out.writeUTF("com.nettarion.stride.simulator.ScheduledSimulation$Health");
			out.writeUTF("health");
			value(v.health(), out);
			out.writeUTF("food");
			value(v.food(), out);
			out.writeUTF("saturation");
			value(v.saturation(), out);
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.ScheduledSimulation.Input v) {
			out.writeUTF("com.nettarion.stride.simulator.ScheduledSimulation$Input");
			out.writeUTF("input");
			value(v.input(), out);
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.ScheduledSimulation.Motion v) {
			out.writeUTF("com.nettarion.stride.simulator.ScheduledSimulation$Motion");
			out.writeUTF("cause");
			value(v.cause(), out);
			out.writeUTF("action");
			value(v.action(), out);
			out.writeUTF("x");
			value(v.x(), out);
			out.writeUTF("y");
			value(v.y(), out);
			out.writeUTF("z");
			value(v.z(), out);
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.ScheduledSimulation.ClientTickEnd) {
			out.writeUTF("com.nettarion.stride.simulator.ScheduledSimulation$ClientTickEnd");
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.ScheduledSimulation.MovePacket v) {
			out.writeUTF("com.nettarion.stride.simulator.ScheduledSimulation$MovePacket");
			out.writeUTF("form");
			value(v.form(), out);
			out.writeUTF("x");
			value(v.x(), out);
			out.writeUTF("y");
			value(v.y(), out);
			out.writeUTF("z");
			value(v.z(), out);
			out.writeUTF("yRot");
			value(v.yRot(), out);
			out.writeUTF("xRot");
			value(v.xRot(), out);
			out.writeUTF("onGround");
			value(v.onGround(), out);
			out.writeUTF("horizontalCollision");
			value(v.horizontalCollision(), out);
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.ScheduledSimulation.Position v) {
			out.writeUTF("com.nettarion.stride.simulator.ScheduledSimulation$Position");
			out.writeUTF("id");
			value(v.id(), out);
			out.writeUTF("x");
			value(v.x(), out);
			out.writeUTF("y");
			value(v.y(), out);
			out.writeUTF("z");
			value(v.z(), out);
			out.writeUTF("yRot");
			value(v.yRot(), out);
			out.writeUTF("xRot");
			value(v.xRot(), out);
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.ScheduledSimulation.Sprint v) {
			out.writeUTF("com.nettarion.stride.simulator.ScheduledSimulation$Sprint");
			out.writeUTF("sprinting");
			value(v.sprinting(), out);
			return;
		}
		if (value instanceof com.nettarion.stride.simulator.ScheduledSimulation.Transport v) {
			out.writeUTF("com.nettarion.stride.simulator.ScheduledSimulation$Transport");
			out.writeUTF("serverbound");
			value(v.serverbound(), out);
			out.writeUTF("clientbound");
			value(v.clientbound(), out);
			out.writeUTF("pendingHurt");
			value(v.pendingHurt(), out);
			out.writeUTF("hurtAction");
			value(v.hurtAction(), out);
			out.writeUTF("mayInteract");
			value(v.mayInteract(), out);
			out.writeUTF("receivedMovementThisTick");
			value(v.receivedMovementThisTick(), out);
			return;
		}
		if (value instanceof HurtMotion v) {
			out.writeUTF("com.nettarion.stride.simulator.HurtMotion");
			out.writeUTF("cause");
			value(v.cause(), out);
			out.writeUTF("causeAction");
			value(v.causeAction(), out);
			out.writeUTF("expectedStateDigest");
			value(v.expectedStateDigest(), out);
			out.writeUTF("writeAfterAction");
			value(v.writeAfterAction(), out);
			out.writeUTF("writeX");
			value(v.writeX(), out);
			out.writeUTF("writeY");
			value(v.writeY(), out);
			out.writeUTF("writeZ");
			value(v.writeZ(), out);
			return;
		}
		throw new IOException("type is outside the declared checkpoint schema: " + value.getClass());
	}
}
