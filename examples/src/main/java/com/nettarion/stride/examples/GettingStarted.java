package com.nettarion.stride.examples;

import com.nettarion.stride.simulator.ActionSchedule;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.Rollout;
import com.nettarion.stride.simulator.ScheduledSimulation;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.SimulationState;
import com.nettarion.stride.simulator.Simulator;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.BlockEntry;

/** A runnable introduction to snapshot construction, exact stepping, and branch ownership. */
public final class GettingStarted {
	private GettingStarted() {}

	/** Runs a synthetic flat-world route and checks the documented ownership patterns. */
	public static void main(String[] args) {
		SnapshotView world = flatWorld();
		PlayerState player = new PlayerState();
		player.placeAt(0.5, 1.0, 0.5);
		player.onGround = true;
		player.requireValidForTransition();

		// This deliberately constructs a fresh full-health server boundary. It does
		// not infer the hidden facts of a player observed in a live session.
		SimulationState start = new SimulationState(player, ServerPlayerState.atBoundary(player), 0);
		PlayerInput forward = new PlayerInput(true, false, false, false, false, false, false, 0, 0);
		Simulator simulator = new Simulator();
		SimulationState state = start;
		Rollout rollout = new Rollout().load(start);
		for (int tick = 0; tick < 20; tick++) {
			state = simulator.advance(state, forward, world).state();
			rollout.tick(forward, world);
			if (!SimulationState.rawEquals(state, rollout.boundary())) {
				throw new IllegalStateException("fixed and in-place stepping disagree at tick " + tick);
			}
		}
		if (start.completedActions() != 0 || state.clientState().z <= player.z) {
			throw new IllegalStateException("the start must remain unchanged while the route advances");
		}

		// An explicit transport schedule owns queued messages and separate world
		// overlays. Fork the entire runner, not just its visible player position.
		ScheduledSimulation scheduled = new ScheduledSimulation(start, world);
		ActionSchedule schedule = ActionSchedule.composed();
		schedule.advance(scheduled, forward);
		ScheduledSimulation branch = scheduled.fork();
		schedule.advance(scheduled, forward);
		schedule.advance(branch, forward);
		if (!ScheduledSimulation.sameState(scheduled, branch)) {
			throw new IllegalStateException("forks receiving the same events must agree");
		}
		PlayerState result = state.clientState();
		System.out.printf(java.util.Locale.ROOT, "After %d ticks: x=%.6f y=%.6f z=%.6f%n", state.completedActions(),
		    result.x, result.y, result.z);
		System.out.println("Fixed/in-place parity and scheduled fork replay passed.");
	}

	/** Builds a finite, explicitly declared air-and-stone world for this example. */
	private static SnapshotView flatWorld() {
		WorldSnapshot.Builder builder =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, -8, -2, -8, 32, 12, 40)
		        .palette(BlockEntry.builder(0, "minecraft:air").build(),
		            BlockEntry.builder(1, "minecraft:stone")
		                .boxes(ShapeBox.FULL_CUBE)
		                .build());
		for (int x = -8; x < 24; x++) {
			for (int z = -8; z < 32; z++)
				builder.set(x, 0, z, 1);
		}
		return SnapshotView.compile(builder.build());
	}
}
