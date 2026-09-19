package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rollout is the composed step on owned storage: the same boundaries tick for tick, the same
 * writes, and no successor after a refusal.
 */
final class RolloutTest {
	private static final PlayerInput SPRINT_JUMP =
	    PlayerInput.of(30.0F, 0.0F, PlayerInput.Key.FORWARD, PlayerInput.Key.JUMP, PlayerInput.Key.SPRINT);

	private static final PlayerInput WALK = PlayerInput.of(-45.0F, 10.0F, PlayerInput.Key.FORWARD);

	private static final PlayerInput IDLE = PlayerInput.idle(0.0F, 0.0F);

	@Test
	void theRolloutReproducesEveryPublishedBoundaryAndWrite() {
		SnapshotView world = Worlds.ledge();
		List<PlayerInput> actions = schedule();
		Simulator published = new Simulator();
		Rollout rollout = new Rollout();
		SimulationState boundary = new SimulationState(
		    Worlds.groundedAt(0.5, 5.0, 0.5), ServerPlayerState.atBoundary(Worlds.groundedAt(0.5, 5.0, 0.5)), 0);
		rollout.load(boundary);
		assertEquals(boundary.digest(), rollout.digest());
		boolean sawWrite = false;
		for (PlayerInput action : actions) {
			Simulator.Step step = published.advance(boundary, action, world);
			List<ServerWrite> writes = new ArrayList<>();
			boolean pending = rollout.tick(action, world, writes::add);
			boundary = step.state();
			assertEquals(step.writes(), writes);
			assertEquals(boundary.hasPendingHurt(), pending);
			assertEquals(boundary.digest(), rollout.digest());
			assertEquals(boundary.digest(), rollout.boundary().digest());
			assertTrue(PlayerState.rawEquals(boundary.clientState(), rollout.client()));
			assertTrue(Publisher.rawEquals(boundary.publisher(), rollout.publisher()));
			assertTrue(ServerPlayerState.rawEquals(boundary.serverState(), rollout.server()));
			assertEquals(boundary.completedActions(), rollout.completedActions());
			sawWrite |= !writes.isEmpty();
		}
		assertTrue(sawWrite, "the fixture must deliver a server write so the comparison covers one");
	}

	@Test
	void aRefusalLeavesNoSuccessorUntilTheNextLoad() {
		SnapshotView world = Worlds.ledge();
		Rollout rollout = new Rollout();
		assertThrows(IllegalStateException.class, () -> rollout.tick(IDLE, world));
		PlayerState client = Worlds.groundedAt(0.5, 5.0, 0.5);
		SimulationState boundary = new SimulationState(client, ServerPlayerState.atBoundary(client), 0);
		rollout.load(boundary);
		// Walk off the captured region: the first tick that reads outside refuses.
		PlayerInput away = PlayerInput.of(0.0F, 0.0F, PlayerInput.Key.FORWARD, PlayerInput.Key.SPRINT);
		boolean refused = false;
		for (int tick = 0; tick < 400 && !refused; tick++) {
			try {
				rollout.tick(away, world);
			} catch (UnimplementedMechanicException outside) {
				refused = true;
			}
		}
		assertTrue(refused, "the fixture must leave its region");
		assertFalse(rollout.isLoaded());
		assertThrows(IllegalStateException.class, () -> rollout.tick(IDLE, world));
		assertThrows(IllegalStateException.class, rollout::boundary);
		rollout.load(boundary);
		assertTrue(rollout.isLoaded());
		assertEquals(boundary.digest(), rollout.digest());
	}

	@Test
	void theBoundaryDigestSeparatesWhatTheStepReads() {
		PlayerState client = Worlds.groundedAt(0.5, 5.0, 0.5);
		ServerPlayerState server = ServerPlayerState.atBoundary(client);
		SimulationState base = new SimulationState(client, server, 3);
		assertEquals(base.digest(), new SimulationState(client, server, 3).digest());
		assertNotEquals(base.digest(), new SimulationState(client, server, 4).digest());
		assertNotEquals(base.digest(), new SimulationState(client, server, 3, HurtCause.FALL).digest());
		Publisher advanced = Publisher.atBoundary(client);
		advanced.positionReminder = 7;
		assertNotEquals(base.digest(), new SimulationState(client, advanced, server, 3, null).digest());
	}

	@Test
	void carryingKeepsThePoseFitCacheAcrossARepublicationThatKeptTheSections() {
		SnapshotView first = Worlds.ledge();
		// The same snapshot compiled again: every section object is shared, so
		// a carried cache entry holds; an uncarried one is keyed on the first view alone.
		SnapshotView again = SnapshotView.compile(first.snapshot());
		assertNotEquals(first.identity(), again.identity());
		Rollout rollout = new Rollout();
		PlayerState start = Worlds.groundedAt(0.5, 5.0, -8.5);
		rollout.load(new SimulationState(start, ServerPlayerState.atBoundary(start), 0));
		for (int tick = 0; tick < 4; tick++) {
			rollout.tick(WALK, first);
		}
		assertTrue(rollout.client().hasCachedPoseFit(first, rollout.client().pose));
		assertFalse(rollout.client().hasCachedPoseFit(again, rollout.client().pose));
		rollout.carry(first);
		assertTrue(rollout.client().hasCachedPoseFit(again, rollout.client().pose));
		SimulationState kept = rollout.boundary();
		assertTrue(kept.clientState().hasCachedPoseFit(again, kept.clientState().pose),
		    "a published boundary carries the carried cache entry");
		// Stepping on in the new view agrees bit for bit with a fresh rollout there.
		Rollout fresh = new Rollout().load(kept);
		for (int tick = 0; tick < 8; tick++) {
			rollout.tick(WALK, again);
			fresh.tick(WALK, again);
		}
		assertEquals(fresh.digest(), rollout.digest());
	}

	private static List<PlayerInput> schedule() {
		List<PlayerInput> actions = new ArrayList<>();
		for (int tick = 0; tick < 48; tick++) {
			actions.add(tick < 6 ? SPRINT_JUMP : tick < 30 ? IDLE : WALK);
		}
		return actions;
	}

	/** Small authored worlds shared by the stepping-form tests. */
	static final class Worlds {
		private Worlds() {}

		/** A stone floor at y 0 with a raised ledge whose edge a sprint jump clears and falls from. */
		static SnapshotView ledge() {
			BlockEntry air = BlockEntry.builder(0, "minecraft:air").build();
			BlockEntry stone = BlockEntry.builder(1, "minecraft:stone").fullCube().build();
			WorldSnapshot.Builder builder =
			    WorldSnapshot.builder(OutsidePolicy.REFUSING, -16, -1, -16, 32, 16, 32).palette(air, stone);
			for (int x = -16; x < 16; x++) {
				for (int z = -16; z < 16; z++) {
					builder.set(x, -1, z, 1);
					if (z < 3) {
						for (int y = 0; y < 5; y++) {
							builder.set(x, y, z, 1);
						}
					}
				}
			}
			return SnapshotView.compile(builder.build());
		}

		static PlayerState groundedAt(final double x, final double y, final double z) {
			PlayerState state = new PlayerState();
			state.placeAt(x, y, z);
			state.deltaMovementY = -0.0784000015258789;
			state.onGround = true;
			state.verticalCollision = true;
			state.verticalCollisionBelow = true;
			return state;
		}
	}
}
