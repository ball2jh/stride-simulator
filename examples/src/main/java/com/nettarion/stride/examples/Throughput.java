package com.nettarion.stride.examples;

import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.Rollout;
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
 * Measures how many steps per second one thread can simulate: a player walking and sprint-jumping across
 * a flat stone floor, twenty steps per run, through {@link Simulator#advance} and through a {@link Rollout}.
 *
 * <p>Run with {@code ./gradlew runThroughput}. The figures depend on the machine, the JIT, and the
 * scenario; this one exercises collision against a floor, jumping, and the composed server tick, but no
 * fluids, block effects, or hits. Treat it as an order-of-magnitude check, not a benchmark suite.
 */
public final class Throughput {
	private static final int STEPS_PER_RUN = 20;

	private static final int RUNS_PER_ROUND = 20_000;

	private static final int ROUNDS = 6;

	private Throughput() {}

	/** Runs six rounds and prints the nanoseconds per step of each; the later rounds are the warmed figures. */
	public static void main(final String[] args) {
		SnapshotView world = flatWorld();
		PlayerState player = new PlayerState();
		player.placeAt(0.5, 1.0, 0.5);
		player.onGround = true;
		player.requireValidForTransition();
		SimulationState start = new SimulationState(player, ServerPlayerState.atBoundary(player), 0);
		PlayerInput walk = PlayerInput.of(0.0F, 0.0F, PlayerInput.Key.FORWARD);
		PlayerInput sprintJump =
		    PlayerInput.of(0.0F, 0.0F, PlayerInput.Key.FORWARD, PlayerInput.Key.SPRINT, PlayerInput.Key.JUMP);
		Simulator simulator = new Simulator();
		Rollout rollout = new Rollout();
		long steps = (long) RUNS_PER_ROUND * STEPS_PER_RUN;
		long checksum = 0;
		for (int round = 1; round <= ROUNDS; round++) {
			long before = System.nanoTime();
			for (int run = 0; run < RUNS_PER_ROUND; run++) {
				SimulationState state = start;
				for (int step = 0; step < STEPS_PER_RUN; step++) {
					state = simulator.advance(state, step % 7 == 0 ? sprintJump : walk, world).state();
				}
				checksum += state.completedActions();
			}
			long middle = System.nanoTime();
			for (int run = 0; run < RUNS_PER_ROUND; run++) {
				rollout.load(start);
				for (int step = 0; step < STEPS_PER_RUN; step++) {
					rollout.tick(step % 7 == 0 ? sprintJump : walk, world);
				}
				checksum += rollout.boundary().completedActions();
			}
			long after = System.nanoTime();
			System.out.printf(Locale.ROOT,
			    "round %d: Simulator.advance %4.0f ns/step (%.2f M steps/s), "
			        + "Rollout.tick %4.0f ns/step (%.2f M steps/s)%n",
			    round, (middle - before) / (double) steps, steps * 1e3 / (middle - before),
			    (after - middle) / (double) steps, steps * 1e3 / (after - middle));
		}
		if (checksum != 2L * ROUNDS * RUNS_PER_ROUND * STEPS_PER_RUN) {
			throw new IllegalStateException("unexpected step count " + checksum);
		}
	}

	/** A finite flat stone floor with air above it, large enough that twenty steps never reach an edge. */
	private static SnapshotView flatWorld() {
		WorldSnapshot.Builder builder =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, -8, -2, -8, 32, 12, 40)
		        .palette(BlockEntry.builder(0, "minecraft:air").build(),
		            BlockEntry.builder(1, "minecraft:stone").fullCube().suffocation(Suffocation.YES).build());
		for (int x = -8; x < 24; x++) {
			for (int z = -8; z < 32; z++) {
				builder.set(x, 0, z, 1);
			}
		}
		return SnapshotView.compile(builder.build());
	}
}
