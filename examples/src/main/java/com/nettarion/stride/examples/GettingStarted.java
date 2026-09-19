package com.nettarion.stride.examples;

import com.nettarion.stride.simulator.ActionSchedule;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerInput.Key;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalException;
import com.nettarion.stride.simulator.Rollout;
import com.nettarion.stride.simulator.ScheduledSimulation;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.SimulationState;
import com.nettarion.stride.simulator.Simulator;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.Suffocation;
import com.nettarion.stride.simulator.world.WorldSnapshot;

import java.util.Locale;

/**
 * A tutorial in five numbered sections: build a flat world, walk, sprint-jump, meet a refusal, and fork a
 * scheduled simulation. Run it with {@code ./gradlew runExample}; every section prints what it observes.
 */
public final class GettingStarted {
	/** The y coordinate of the stone floor, in blocks. */
	private static final int FLOOR_Y = 0;
	/** Palette index of air; every cell of a new snapshot starts here. */
	private static final int AIR = 0;
	/** Palette index of stone. */
	private static final int STONE = 1;
	/** The region's exclusive upper z edge, in blocks. */
	private static final int EDGE_Z = 24;

	private GettingStarted() {}

	/** Runs the five sections in order. Exits with status 1 if the two stepping APIs disagree. */
	public static void main(String[] args) {
		SnapshotView world = buildFlatWorld();
		SimulationState start = standingAt(0.5, FLOOR_Y + 1.0, 0.5);
		SimulationState afterWalk = walkForward(start, world);
		sprintJump(afterWalk, world);
		walkOffTheRegion(afterWalk, world);
		forkASchedule(start, world);
	}

	/** Section 1: a finite flat world with a stone floor at {@link #FLOOR_Y} and refusing edges. */
	private static SnapshotView buildFlatWorld() {
		System.out.println("1. Building a 16 x 12 x 32 block region with a stone floor");
		// Air is declared like any other block. The simulator never assumes an undeclared cell is air.
		BlockEntry air = BlockEntry.builder(AIR, "minecraft:air").build();
		// Stone has a full-cube collision shape and a declared suffocation answer. The builder's default is
		// Suffocation.UNKNOWN, which refuses the first time the player's body reaches such a block (pressed
		// against a wall, for example). Declare YES for ordinary opaque blocks and NO for glass or leaves.
		BlockEntry stone = BlockEntry.builder(STONE, "minecraft:stone").fullCube().suffocation(Suffocation.YES).build();
		// What lies beyond the region: REFUSING refuses any query that reaches it, SEALED treats it as solid.
		OutsidePolicy outside = OutsidePolicy.REFUSING;
		// The region covers x in [-8, 8), y in [-2, 10) and z in [-8, 24): the origin is its lowest corner and the
		// size its extent along each axis, both in blocks. It must hold the player's body and every cell the
		// collision queries around it reach.
		WorldSnapshot.Builder builder =
		    WorldSnapshot
		        .builder(outside, -8, -2, -8, 16, 12, 32)
		        // Palette index 0 is air and index 1 is stone; cells refer to palette indices.
		        .palette(air, stone);
		for (int x = -8; x < 8; x++) {
			for (int z = -8; z < EDGE_Z; z++) {
				builder.set(x, FLOOR_Y, z, STONE);
			}
		}
		// compile() freezes the snapshot into a query view that is safe to share between threads.
		SnapshotView world = SnapshotView.compile(builder.build());
		System.out.printf(Locale.ROOT, "   compiled; stone at y=%d, air above, refusing beyond the edges%n", FLOOR_Y);
		return world;
	}

	/** A player standing still on the floor, with a fresh full-health server counterpart. */
	private static SimulationState standingAt(double x, double y, double z) {
		PlayerState client = new PlayerState();
		// x and z are the center of the body and y is the feet, all in blocks.
		client.placeAt(x, y, z);
		// Must agree with the world: the floor is directly under the feet.
		client.onGround = true;
		client.requireValidForTransition();
		// atBoundary builds a server counterpart that agrees with the client and has full health and food. It
		// does not reconstruct the history of a player observed in a live session.
		return new SimulationState(client, ServerPlayerState.atBoundary(client), 0);
	}

	/** Section 2: hold the forward key for 20 steps and print the client after each one. */
	private static SimulationState walkForward(SimulationState start, SnapshotView world) {
		System.out.println("2. Walking forward for 20 steps (yaw 0 faces +z)");
		// Yaw and pitch are in degrees with vanilla's convention: yaw 0 faces +z, yaw 90 faces -x, and a
		// negative pitch looks up. One step is one client tick, a twentieth of a second at the normal rate.
		PlayerInput forward = PlayerInput.of(0.0F, 0.0F, Key.FORWARD);
		Simulator simulator = new Simulator();
		// A rollout steps one owned working copy in place; advance() returns a fresh boundary each step.
		// Both run the same transition, so they must agree bit for bit.
		Rollout rollout = new Rollout().load(start);
		SimulationState state = start;
		boolean agree = true;
		for (int step = 0; step < 20; step++) {
			state = simulator.advance(state, forward, world).state();
			rollout.tick(forward, world);
			agree &= SimulationState.rawEquals(state, rollout.boundary());
			PlayerState client = state.clientState();
			System.out.printf(Locale.ROOT, "   tick %2d  x=%.6f  z=%.6f  onGround=%b%n", state.completedActions(),
			    client.x, client.z, client.onGround);
		}
		System.out.println("   fixed and in-place stepping agree at every tick: " + agree);
		System.out.println("   the start boundary is unchanged: " + (start.completedActions() == 0));
		if (!agree) {
			System.err.println("Simulator.advance and Rollout.tick disagree; this is a library bug.");
			System.exit(1);
		}
		return state;
	}

	/** Section 3: hold forward, sprint and jump for six steps and print the velocity in blocks per tick. */
	private static void sprintJump(SimulationState from, SnapshotView world) {
		System.out.println("3. Sprint-jumping for 6 steps (velocity in blocks per tick)");
		PlayerInput sprintJump = PlayerInput.of(0.0F, 0.0F, Key.FORWARD, Key.SPRINT, Key.JUMP);
		Simulator simulator = new Simulator();
		SimulationState state = from;
		for (int step = 0; step < 6; step++) {
			state = simulator.advance(state, sprintJump, world).state();
			PlayerState client = state.clientState();
			System.out.printf(Locale.ROOT, "   tick %2d  dx=%.6f  dy=%.6f  dz=%.6f  sprinting=%b  onGround=%b%n",
			    state.completedActions(), client.deltaMovementX, client.deltaMovementY, client.deltaMovementZ,
			    client.sprinting, client.onGround);
		}
	}

	/** Section 4: keep walking until a query leaves the region, then read the refusal's cause. */
	private static void walkOffTheRegion(SimulationState from, SnapshotView world) {
		System.out.printf(Locale.ROOT, "4. Walking toward the region's edge at z=%d%n", EDGE_Z);
		PlayerInput forward = PlayerInput.of(0.0F, 0.0F, Key.FORWARD);
		Simulator simulator = new Simulator();
		SimulationState state = from;
		try {
			for (int step = 0; step < 1000; step++) {
				state = simulator.advance(state, forward, world).state();
			}
			System.out.println("   never reached the edge");
		} catch (RefusalException refusal) {
			// A refusal is an outcome, not a fault: the transition after `state` was not computed. Classify it by
			// cause(); the message is written for people. `state` itself is untouched and still valid.
			System.out.printf(Locale.ROOT, "   refused after %d actions at z=%.6f%n", state.completedActions(),
			    state.clientState().z);
			System.out.println("   cause:   " + refusal.cause());
			System.out.println("   message: " + refusal.getMessage());
		}
	}

	/** Section 5: a scheduled simulation owns its queues and worlds, so a fork fed the same events agrees. */
	private static void forkASchedule(SimulationState start, SnapshotView world) {
		System.out.println("5. Forking a scheduled simulation");
		PlayerInput forward = PlayerInput.of(0.0F, 0.0F, Key.FORWARD);
		// The composed schedule performs the same client and server phases as Simulator.advance, one
		// explicit event at a time, with FIFO packet delivery in each direction.
		ScheduledSimulation scheduled = new ScheduledSimulation(start, world);
		ActionSchedule schedule = ActionSchedule.composed();
		schedule.advance(scheduled, forward);
		// fork() copies the runner: both worlds, every queued packet, and the player copies.
		ScheduledSimulation branch = scheduled.fork();
		schedule.advance(scheduled, forward);
		schedule.advance(branch, forward);
		System.out.println(
		    "   two forks fed the same events agree: " + ScheduledSimulation.sameState(scheduled, branch));
		System.out.printf(Locale.ROOT, "   both completed %d actions; z=%.6f%n", scheduled.completedActions(),
		    scheduled.clientState().z);
	}
}
