package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.world.CompleteWorldView;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.world.SupportCell;
import com.nettarion.stride.simulator.world.WorldView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Whether the same movement, run somewhere else, is the same movement.
 *
 * <p>This decides an architecture rather than a detail. PLAN 19.2's second
 * tractability claim is offline primitive synthesis: enumerate movement
 * primitives once, then search over them online, "with every edge guaranteed
 * executable because exact physics verified it". That sentence has two readings
 * and they cost very different amounts. If a trajectory is translation-invariant
 * in bits, a primitive verified once at the origin is verified everywhere and
 * the catalog is a lookup. If it is not, a primitive can only ever be a
 * <em>proposal</em>, and every edge has to be re-simulated at the location it is
 * used before it can be believed.
 *
 * <p>The arithmetic says it cannot be invariant: velocity updates are
 * position-independent, but {@code x + dx} rounds against the exponent of
 * {@code x}, so the same velocity applied at a larger coordinate lands on a
 * different double. What is not obvious from the arithmetic is <em>where</em>
 * that starts to matter, and that is what this measures — a bound in blocks
 * inside which a primitive may be reused verbatim.
 *
 * <p>Deliberately on an infinite flat floor with a fixed heading, which is the
 * friendliest possible case: no collision resolution, no cell boundary, nothing
 * that could amplify a difference except the position accumulator itself. A
 * bound measured here is an upper bound on the bound anywhere else.
 */
class TranslationInvarianceTest {
	private static final int TICKS = 40;

	@Test
	void velocityIsTranslationInvariantEverywhere() {
		List<PlayerInput> plan = sprintJumpLoop();

		for (double offset : new double[] {0.0, 1.0, 16.0, 1024.0, 65_536.0, 1_048_576.0, 29_999_984.0}) {
			PlayerState origin = run(0.0, plan);
			PlayerState moved = run(offset, plan);

			assertEquals(Double.doubleToRawLongBits(origin.deltaMovementX),
			    Double.doubleToRawLongBits(moved.deltaMovementX),
			    "horizontal velocity must not depend on where the player is, at " + offset);
			assertEquals(Double.doubleToRawLongBits(origin.deltaMovementY),
			    Double.doubleToRawLongBits(moved.deltaMovementY),
			    "vertical velocity must not depend on where the player is, at " + offset);
		}
	}

	@Test
	void displacementStopsBeingInvariantAndTheBoundIsMeasured() {
		List<PlayerInput> plan = sprintJumpLoop();
		PlayerState origin = run(0.0, plan);
		// Z, because yaw 0 faces +Z. The first version of this test measured X and
		// every row printed a displacement of exactly zero — eleven offsets
		// agreeing raw-bit on a player that had not moved along the axis being
		// compared. It only showed up because the table prints the number rather
		// than the verdict.
		double reference = origin.z - 0.5;
		assertTrue(Math.abs(reference) > 1.0,
		    "the reference run must actually travel, or every comparison below is"
		        + " an agreement about a player standing still; traveled " + reference);

		StringBuilder table =
		    new StringBuilder("\ntranslation invariance of " + TICKS + " sprint-jump ticks on flat ground:\n");
		double lastExact = -1.0;
		double firstInexact = Double.NaN;
		for (double offset : new double[] {
		         0.0, 1.0, 16.0, 256.0, 1024.0, 4096.0, 65_536.0, 262_144.0, 1_048_576.0, 4_194_304.0, 29_999_984.0}) {
			PlayerState moved = run(offset, plan);
			double displaced = moved.z - (0.5 + offset);
			boolean exact = Double.doubleToRawLongBits(displaced) == Double.doubleToRawLongBits(reference);
			table.append(String.format(
			    "  offset %12.0f  displacement %.17f  %s%n", offset, displaced, exact ? "raw-bit equal" : "DIFFERS"));
			if (exact) {
				lastExact = offset;
			} else if (Double.isNaN(firstInexact)) {
				firstInexact = offset;
			}
		}
		System.out.print(table);

		assertTrue(lastExact >= 0.0, "not even the origin reproduced itself");
		assertTrue(!Double.isNaN(firstInexact),
		    "displacement was raw-bit identical at every offset out to the world"
		        + " border, which would make a synthesized primitive reusable verbatim"
		        + " anywhere — a stronger result than the arithmetic predicts, and one"
		        + " that should be believed only after this test is checked for being"
		        + " vacuous");
		System.out.printf("exact through offset %.0f, first difference at %.0f%n", lastExact, firstInexact);
	}

	/**
	 * How large the disagreement is, which is the number that decides the design.
	 *
	 * <p>Not being bit-equal settles that a primitive cannot be replayed verbatim.
	 * It does not settle whether one is worth having: if the disagreement inside a
	 * region were comparable to the collision epsilons, a primitive would not even
	 * be a usable proposal, and the whole of PLAN 19.2's second bullet would be a
	 * dead end rather than a re-verification cost.
	 */
	@Test
	void disagreementWithinOneRegionIsFarBelowTheCollisionEpsilon() {
		List<PlayerInput> plan = sprintJumpLoop();
		PlayerState origin = run(0.0, plan);

		double widest = 0.0;
		for (double offset : new double[] {1.0, 16.0, 64.0, 256.0, 1024.0}) {
			PlayerState moved = run(offset, plan);
			widest = Math.max(widest, Math.abs((moved.z - offset) - origin.z));
		}
		System.out.printf("widest positional disagreement within 1024 blocks: %.3e%n", widest);
		// The kernel's own collision tolerances are around 1.0E-7. A disagreement
		// three orders under that cannot change which side of a block face the box
		// resolves on, so a primitive stays an excellent proposal at region scale
		// even though it is never an exact one.
		assertTrue(widest < 1.0E-10,
		    "a disagreement this large inside one region would make primitives"
		        + " useless even as proposals: " + widest);
		assertTrue(widest > 0.0,
		    "zero disagreement here would contradict the offset table above, which"
		        + " means one of the two is measuring nothing");
	}

	private static PlayerState run(final double offset, final List<PlayerInput> plan) {
		ClientTick kernel = new ClientTick();
		WorldView world = new InfiniteFloor();
		PlayerState state = new PlayerState();
		state.placeAt(0.5 + offset, 64.0, 0.5 + offset);
		state.onGround = true;
		for (PlayerInput action : plan) {
			kernel.tick(state, action, world);
		}
		return state;
	}

	/** Sprint forward, jumping whenever the cycle comes round. */
	private static List<PlayerInput> sprintJumpLoop() {
		List<PlayerInput> plan = new ArrayList<>(TICKS);
		for (int tick = 0; tick < TICKS; tick++) {
			plan.add(new PlayerInput(true, false, false, false, tick % 12 == 0, false, true, 0.0F, 0.0F));
		}
		return List.copyOf(plan);
	}

	/**
	 * Stone everywhere below y=64, air above, at every coordinate.
	 *
	 * <p>Unbounded on purpose: a {@code SnapshotView} cannot span the offsets
	 * this test asks about, and the question is about the position accumulator
	 * rather than about any snapshot.
	 */
	private static final class InfiniteFloor implements CompleteWorldView {
		@Override
		public long collisionVersion() {
			return 0L;
		}

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
		public float friction(final int x, final int y, final int z) {
			return 0.6F;
		}

		@Override
		public float speedFactor(final int x, final int y, final int z) {
			return 1.0F;
		}

		@Override
		public float jumpFactor(final int x, final int y, final int z) {
			return 1.0F;
		}

		@Override
		public boolean hasChunkAt(final int x, final int z) {
			return true;
		}

		@Override
		public int minY() {
			return -64;
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
