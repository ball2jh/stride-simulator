package com.nettarion.stride.simulator.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.world.CompleteWorldView;
import com.nettarion.stride.simulator.world.SupportCell;
import com.nettarion.stride.simulator.world.WorldView;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Whether the same movement, run somewhere else, is the same movement.
 *
 * <p>Velocity updates are position-independent, but {@code x + dx} rounds against the exponent of
 * {@code x}, so the same velocity applied at a larger coordinate lands on a different double. These
 * tests pin both halves: velocity is translation-invariant in bits everywhere, displacement is not,
 * and the disagreement within a thousand blocks stays far below the collision epsilon. A caller
 * that reuses a trajectory computed elsewhere can rely on the first and must not rely on the second.
 *
 * <p>Deliberately on an infinite flat floor with a fixed heading, the friendliest possible case: no
 * collision resolution, no cell boundary, nothing that could amplify a difference except the
 * position accumulator itself.
 */
final class TranslationInvarianceTest {
	private static final int TICKS = 40;

	@Test
	void velocityIsTranslationInvariantEverywhere() {
		List<PlayerInput> actions = sprintJumpLoop();

		for (double offset : new double[] {0.0, 1.0, 16.0, 1024.0, 65_536.0, 1_048_576.0, 29_999_984.0}) {
			PlayerState origin = run(0.0, actions);
			PlayerState moved = run(offset, actions);

			assertEquals(Double.doubleToRawLongBits(origin.deltaMovementX),
			    Double.doubleToRawLongBits(moved.deltaMovementX),
			    "horizontal velocity must not depend on where the player is, at " + offset);
			assertEquals(Double.doubleToRawLongBits(origin.deltaMovementY),
			    Double.doubleToRawLongBits(moved.deltaMovementY),
			    "vertical velocity must not depend on where the player is, at " + offset);
		}
	}

	@Test
	void displacementStopsBeingInvariantAtLargeOffsets() {
		List<PlayerInput> actions = sprintJumpLoop();
		PlayerState origin = run(0.0, actions);
		// Z, because yaw 0 faces +Z: compare the axis the player actually travels along.
		double reference = origin.z - 0.5;
		assertTrue(Math.abs(reference) > 1.0,
		    "the reference run must actually travel, or every comparison below is"
		        + " an agreement about a player standing still; traveled " + reference);

		double lastExact = -1.0;
		double firstInexact = Double.NaN;
		for (double offset : new double[] {
		         0.0, 1.0, 16.0, 256.0, 1024.0, 4096.0, 65_536.0, 262_144.0, 1_048_576.0, 4_194_304.0, 29_999_984.0}) {
			PlayerState moved = run(offset, actions);
			double displaced = moved.z - (0.5 + offset);
			boolean exact = Double.doubleToRawLongBits(displaced) == Double.doubleToRawLongBits(reference);
			if (exact) {
				lastExact = offset;
			} else if (Double.isNaN(firstInexact)) {
				firstInexact = offset;
			}
		}

		assertTrue(lastExact >= 0.0, "not even the origin reproduced itself");
		assertTrue(!Double.isNaN(firstInexact),
		    "displacement was raw-bit identical at every offset out to the world"
		        + " border, a stronger result than the arithmetic predicts, which"
		        + " should be believed only after this test is checked for being vacuous");
	}

	/**
	 * How large the disagreement is. Not being bit-equal settles that a trajectory cannot be replayed
	 * verbatim; this settles that within a region it is still an excellent approximation.
	 */
	@Test
	void disagreementWithinOneRegionIsFarBelowTheCollisionEpsilon() {
		List<PlayerInput> actions = sprintJumpLoop();
		PlayerState origin = run(0.0, actions);

		double widest = 0.0;
		for (double offset : new double[] {1.0, 16.0, 64.0, 256.0, 1024.0}) {
			PlayerState moved = run(offset, actions);
			widest = Math.max(widest, Math.abs((moved.z - offset) - origin.z));
		}
		// The collision tolerances are around 1.0E-7. A disagreement three orders
		// under that cannot change which side of a block face the box resolves on.
		assertTrue(widest < 1.0E-10, "a disagreement this large inside one region is unexpected: " + widest);
		assertTrue(widest > 0.0,
		    "zero disagreement here would contradict the offset test above, which"
		        + " means one of the two is measuring nothing");
	}

	private static PlayerState run(final double offset, final List<PlayerInput> actions) {
		ClientTick simulator = new ClientTick();
		WorldView world = new InfiniteFloor();
		PlayerState state = new PlayerState();
		state.placeAt(0.5 + offset, 64.0, 0.5 + offset);
		state.onGround = true;
		for (PlayerInput action : actions) {
			simulator.tick(state, action, world);
		}
		return state;
	}

	/** Sprint forward, jumping whenever the cycle comes round. */
	private static List<PlayerInput> sprintJumpLoop() {
		List<PlayerInput> actions = new ArrayList<>(TICKS);
		for (int tick = 0; tick < TICKS; tick++) {
			actions.add(new PlayerInput(true, false, false, false, tick % 12 == 0, false, true, 0.0F, 0.0F));
		}
		return List.copyOf(actions);
	}

	/**
	 * Stone everywhere below y=64, air above, at every coordinate.
	 *
	 * <p>Unbounded on purpose: a {@code SnapshotView} cannot span the offsets this test asks about,
	 * and the question is about the position accumulator rather than about any snapshot.
	 */
	private static final class InfiniteFloor implements CompleteWorldView {
		@Override
		public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final CollisionBuffer target) {
			target.clear();
			int x0 = Mth.floor(minX);
			int x1 = Mth.floor(maxX);
			int z0 = Mth.floor(minZ);
			int z1 = Mth.floor(maxZ);
			int y0 = Mth.floor(minY);
			int y1 = Mth.floor(maxY);
			for (int y = y0; y <= y1 && y < 64; y++) {
				for (int z = z0; z <= z1; z++) {
					for (int x = x0; x <= x1; x++) {
						if (x + 1.0 > minX && x < maxX && y + 1.0 > minY && y < maxY && z + 1.0 > minZ && z < maxZ) {
							target.add(x, y, z, x + 1.0, y + 1.0, z + 1.0);
						}
					}
				}
			}
		}

		@Override
		public boolean suffocatesAt(
		    final int cellX, final int cellZ, final double boundingBoxMinY, final double boundingBoxMaxY) {
			return boundingBoxMinY < 64.0;
		}

		@Override
		public void findSupportingBlock(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final double atX, final double atY, final double atZ,
		    final SupportCell target) {
			target.set(Mth.floor(atX), 63, Mth.floor(atZ));
		}
	}
}
