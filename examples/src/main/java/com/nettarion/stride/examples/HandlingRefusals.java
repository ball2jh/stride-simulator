package com.nettarion.stride.examples;

import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerInput.Key;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.RefusalException;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.SimulationState;
import com.nettarion.stride.simulator.Simulator;
import com.nettarion.stride.simulator.StaleServerWriteException;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.UnpredictedServerWriteException;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.Suffocation;
import com.nettarion.stride.simulator.world.WorldSnapshot;

import java.util.Locale;

/**
 * Classifies refusals two ways: by the sealed exception type, and by the group its {@link RefusalCause}
 * belongs to. Run it with {@code ./gradlew runHandlingRefusals}.
 */
public final class HandlingRefusals {
	private HandlingRefusals() {}

	/** What a consumer can do about one group of refusal causes. */
	enum Group {
		/** The mechanic exists in vanilla but not here; route around it or request it. */
		UNMODELED_MECHANIC,
		/** The supplied state or world is not one the simulator computes; fix the input. */
		INADMISSIBLE_INPUT,
		/** The world does not carry the fact that was asked; capture or declare more of it. */
		WORLD_FACT_MISSING,
		/** The server's outcome is not determined by what was supplied; supply the fact or stop here. */
		SERVER_OUTCOME_PENDING,
		/** The server would correct the client; the predicted trajectory is wrong from here on. */
		SERVER_CORRECTION,
		/** A predicted write met a state it was not derived from; a bookkeeping bug in the caller. */
		STALE_WRITE
	}

	/** Runs a player off a small region to obtain a real refusal, then prints how every cause is grouped. */
	public static void main(String[] args) {
		SnapshotView world = smallWorld();
		PlayerState client = new PlayerState();
		client.placeAt(0.5, 1.0, 0.5);
		client.onGround = true;
		client.requireValidForTransition();
		SimulationState state = new SimulationState(client, ServerPlayerState.atBoundary(client), 0);
		PlayerInput forward = PlayerInput.of(0.0F, 0.0F, Key.FORWARD);
		Simulator simulator = new Simulator();
		try {
			for (int step = 0; step < 1000; step++) {
				state = simulator.advance(state, forward, world).state();
			}
			System.out.println("No refusal after 1000 steps.");
		} catch (RefusalException refusal) {
			System.out.printf(Locale.ROOT, "Refused after %d actions.%n", state.completedActions());
			System.out.println("  by type:  " + describe(refusal));
			System.out.println("  by cause: " + refusal.cause() + " -> " + groupOf(refusal.cause()));
		}
		System.out.println("Every cause and its group:");
		for (RefusalCause cause : RefusalCause.values()) {
			System.out.printf(Locale.ROOT, "  %-26s %s%n", cause, groupOf(cause));
		}
	}

	/** Names the refusal by its type; the sealed hierarchy makes the switch exhaustive without a default. */
	static String describe(RefusalException refusal) {
		return switch (refusal) {
			case UnimplementedMechanicException e -> "unimplemented mechanic: " + e.getMessage();
			case PendingServerWriteException e -> "server outcome pending: " + e.getMessage();
			case UnpredictedServerWriteException e ->
				"server correction: " + e.correction().reason() + " on a " + e.correction().packet() + " packet";
			case StaleServerWriteException e -> "stale write: " + e.getMessage();
		};
	}

	/**
	 * Groups a cause. The switch lists every constant, so adding a cause to the library fails this compile,
	 * which is what a consumer that counts refusals wants.
	 */
	static Group groupOf(RefusalCause cause) {
		return switch (cause) {
			case UNMODELED_BLOCK, UNMODELED_WORLD_WRITE, UNMODELED_SESSION_END, UNMODELED_SCHEDULE, UNMODELED_IMPULSE ->
				Group.UNMODELED_MECHANIC;
			case INADMISSIBLE_STATE, INADMISSIBLE_ATTRIBUTE, INADMISSIBLE_COORDINATE, INADMISSIBLE_WORLD,
			    INADMISSIBLE_BLOCK_FACTS ->
				Group.INADMISSIBLE_INPUT;
			case OUTSIDE_REGION, UNDECLARED_BLOCK_STATE, UNDECLARED_WORLD_FACT, UNDECLARED_SERVER_MUTABLE ->
				Group.WORLD_FACT_MISSING;
			case PENDING_ABILITY, PENDING_SERVER_RANDOM, PENDING_WRITE, PENDING_SURVIVAL_FACT ->
				Group.SERVER_OUTCOME_PENDING;
			case CORRECTED_MOVEMENT -> Group.SERVER_CORRECTION;
			case STALE_WRITE -> Group.STALE_WRITE;
		};
	}

	/** An 8 x 6 x 8 block region with a stone floor at y = 0, small enough to walk out of in a few steps. */
	private static SnapshotView smallWorld() {
		BlockEntry air = BlockEntry.builder(0, "minecraft:air").build();
		BlockEntry stone = BlockEntry.builder(1, "minecraft:stone").fullCube().suffocation(Suffocation.YES).build();
		WorldSnapshot.Builder builder =
		    WorldSnapshot.builder(OutsidePolicy.REFUSING, -4, -1, -4, 8, 6, 8).palette(air, stone);
		for (int x = -4; x < 4; x++) {
			for (int z = -4; z < 4; z++) {
				builder.set(x, 0, z, 1);
			}
		}
		return SnapshotView.compile(builder.build());
	}
}
