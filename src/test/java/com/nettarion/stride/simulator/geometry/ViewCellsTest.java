package com.nettarion.stride.simulator.geometry;

import com.nettarion.stride.simulator.AABB;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.SimulationState;
import com.nettarion.stride.simulator.Simulator;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import com.nettarion.stride.simulator.world.OutsidePolicy;
import com.nettarion.stride.simulator.world.ShapeBox;
import com.nettarion.stride.simulator.world.BlockEntry;

final class ViewCellsTest {
	private static final byte SPRINT_JUMP =
	    (byte) (PlayerInput.FLAG_FORWARD | PlayerInput.FLAG_SPRINT | PlayerInput.FLAG_JUMP);

	@Test
	void aYawCellIsExactlyTheTableEntriesEveryKinematicSiteReads() {
		Random random = new Random(7);
		int merged = 0;
		for (int trial = 0; trial < 200_000; trial++) {
			float a = (random.nextFloat() - 0.5F) * 1440.0F;
			float b = a + (random.nextFloat() - 0.5F) * 0.02F;
			float ra = a * (float) (Math.PI / 180.0);
			float rb = b * (float) (Math.PI / 180.0);
			float wa = -Mth.wrapDegrees(a) * (float) (Math.PI / 180.0);
			float wb = -Mth.wrapDegrees(b) * (float) (Math.PI / 180.0);
			boolean sameEntries = Mth.sin(ra) == Mth.sin(rb) && Mth.cos(ra) == Mth.cos(rb)
			    && Mth.sin(-a * (float) (Math.PI / 180.0)) == Mth.sin(-b * (float) (Math.PI / 180.0))
			    && Mth.cos(-a * (float) (Math.PI / 180.0)) == Mth.cos(-b * (float) (Math.PI / 180.0))
			    && Mth.sin(wa) == Mth.sin(wb) && Mth.cos(wa) == Mth.cos(wb);
			assertEquals(sameEntries, ViewCells.sameYaw(a, b), a + " vs " + b);
			if (sameEntries) merged++;
		}
		assertTrue(merged > 20_000, "the sweep must exercise merges: " + merged);
	}

	@Test
	void aSwimPitchCellIsExactlyTheSineEntryTheSwimSiteReads() {
		Random random = new Random(11);
		int merged = 0;
		for (int trial = 0; trial < 200_000; trial++) {
			float a = (random.nextFloat() - 0.5F) * 180.0F;
			float b = a + (random.nextFloat() - 0.5F) * 0.02F;
			float ra = a * (float) (Math.PI / 180.0);
			float rb = b * (float) (Math.PI / 180.0);
			boolean sameEntry = Mth.sin(ra) == Mth.sin(rb);
			assertEquals(sameEntry, ViewCells.sameSwimPitch(a, b), a + " vs " + b);
			if (sameEntry) merged++;
		}
		assertTrue(merged > 20_000, "the sweep must exercise merges: " + merged);
	}

	@Test
	void aGlidePitchCellIsExactlyTheTableEntriesAndTheRealCosineTheGlideSitesRead() {
		Random random = new Random(13);
		int merged = 0;
		int splitByLift = 0;
		for (int trial = 0; trial < 200_000; trial++) {
			float a = (random.nextFloat() - 0.5F) * 180.0F;
			float b = a + (random.nextFloat() - 0.5F) * 0.02F;
			float ra = a * (float) (Math.PI / 180.0);
			float rb = b * (float) (Math.PI / 180.0);
			boolean sameEntries = Mth.sin(ra) == Mth.sin(rb) && Mth.cos(ra) == Mth.cos(rb);
			boolean sameLift = Double.doubleToRawLongBits(Math.cos(ra)) == Double.doubleToRawLongBits(Math.cos(rb));
			assertEquals(sameEntries && sameLift, ViewCells.sameGlidePitch(a, b), a + " vs " + b);
			if (sameEntries && sameLift) merged++;
			if (sameEntries && !sameLift) splitByLift++;
		}
		assertTrue(merged > 0, "the sweep must exercise merges: " + merged);
		assertTrue(splitByLift > 20_000, "the real cosine must split table cells: " + splitByLift);
	}

	@Test
	void pitchesSharingTableEntriesButNotLiftAreOneSwimControlAndTwoGlideControls() {
		// Both lean angles truncate to sine index 0 and cosine index 16384, but the
		// real cosine of the lean angle is 1 - a^2/2, distinct in binary64 for these.
		float a = 0.001F, b = 0.002F;
		assertTrue(ViewCells.sameSwimPitch(a, b), "one sine entry");
		assertEquals(ViewCells.glidePitch(a), ViewCells.glidePitch(b), "one table cell");
		assertFalse(ViewCells.sameGlidePitch(a, b), "two lift values");
	}

	@Test
	void pitchesInOneGlideCellProduceBitIdenticalGlideTicksAndNeighboringCellsDoNot() {
		Simulator kernel = new Simulator();
		SnapshotView world = flatWorld(64);
		long previousTable = 0;
		long previousLift = 0;
		long[] previousKinematics = null;
		int cells = 0;
		int distinctKinematics = 0;
		int splitByLift = 0;
		for (float pitch = -0.02F; pitch < 0.02F; pitch += 0.00025F) {
			long[] kinematics = glideTick(kernel, world, pitch);
			long table = ViewCells.glidePitch(pitch);
			long lift = ViewCells.glideLift(pitch);
			if (previousKinematics == null || table != previousTable || lift != previousLift) {
				if (previousKinematics != null) {
					if (!java.util.Arrays.equals(previousKinematics, kinematics)) distinctKinematics++;
					if (table == previousTable) splitByLift++;
				}
				cells++;
			} else {
				assertTrue(java.util.Arrays.equals(previousKinematics, kinematics),
				    "same glide cell, different kinematics at " + pitch);
			}
			previousTable = table;
			previousLift = lift;
			previousKinematics = kinematics;
		}
		assertTrue(cells >= 8, "the sweep must cross several cells: " + cells);
		assertTrue(splitByLift >= 4, "the sweep must cross cells the table alone would merge: " + splitByLift);
		assertTrue(distinctKinematics >= cells / 2, "cells must mostly differ: " + distinctKinematics + " of " + cells);
		// The real cosine rounds to 1.0 only for lean angles below about 1e-8 radians,
		// so these two share the glide cell; the witness pair shares only the table cell.
		assertTrue(ViewCells.sameGlidePitch(1.0E-7F, 2.0E-7F));
		assertTrue(java.util.Arrays.equals(glideTick(kernel, world, 1.0E-7F), glideTick(kernel, world, 2.0E-7F)),
		    "one glide cell, one tick");
		assertFalse(java.util.Arrays.equals(glideTick(kernel, world, 0.001F), glideTick(kernel, world, 0.002F)),
		    "one table cell but two lift values, two ticks");
	}

	private static long[] glideTick(final Simulator kernel, final SnapshotView world, final float pitch) {
		PlayerState start = glidingState();
		PlayerState after =
		    kernel.advance(kernel.start(start, start), PlayerInput.idle(0.0F, pitch), world).state().clientState();
		return new long[] {Double.doubleToRawLongBits(after.y), Double.doubleToRawLongBits(after.z),
		    Double.doubleToRawLongBits(after.deltaMovementY), Double.doubleToRawLongBits(after.deltaMovementZ)};
	}

	@Test
	void yawsInOneCellProduceBitIdenticalSprintJumpsAndNeighboringCellsDoNot() {
		Simulator kernel = new Simulator();
		SnapshotView world = flatWorld(8);
		long previousCell = 0;
		long[] previousKinematics = null;
		int cells = 0;
		int distinctKinematics = 0;
		for (float yaw = 30.0F; yaw < 30.05F; yaw += 0.0005F) {
			PlayerState client = sprintingState();
			SimulationState next = kernel
			                           .advance(kernel.start(client, ServerPlayerState.atBoundary(client)),
			                               PlayerInput.ofPacked(SPRINT_JUMP, yaw, 0.0F), world)
			                           .state();
			PlayerState after = next.clientState();
			long[] kinematics = {Double.doubleToRawLongBits(after.x), Double.doubleToRawLongBits(after.z),
			    Double.doubleToRawLongBits(after.deltaMovementX), Double.doubleToRawLongBits(after.deltaMovementZ)};
			long cell = ViewCells.yawHeading(yaw) ^ Long.rotateLeft(ViewCells.yawLook(yaw), 17);
			if (previousKinematics == null || cell != previousCell) {
				if (previousKinematics != null) {
					assertFalse(java.util.Arrays.equals(previousKinematics, kinematics) && cells < 3,
					    "the first cells must be kinematically distinct at " + yaw);
					if (!java.util.Arrays.equals(previousKinematics, kinematics)) distinctKinematics++;
				}
				cells++;
			} else {
				assertTrue(java.util.Arrays.equals(previousKinematics, kinematics),
				    "same cell, different kinematics at " + yaw);
			}
			previousCell = cell;
			previousKinematics = kinematics;
		}
		assertTrue(cells >= 8, "the sweep must cross several cells: " + cells);
		assertTrue(distinctKinematics >= cells / 2, "cells must mostly differ: " + distinctKinematics + " of " + cells);
	}

	@Test
	void yawsSharingClientEntriesButNotTheServersWrappedLookAreTwoComposedGlides() {
		// Both client reads agree, but the server copy holds 180.0 and -179.99998,
		// whose negated look angles truncate to sine entries 32768 and 32767.
		float a = 180.0F, b = 180.00002F;
		assertEquals(ViewCells.yawHeading(a), ViewCells.yawHeading(b), "one heading cell");
		assertEquals(ViewCells.yawLook(a), ViewCells.yawLook(b), "one client look cell");
		assertFalse(ViewCells.sameYaw(a, b), "two server look cells");
		Simulator kernel = new Simulator();
		SnapshotView world = flatWorld(64);
		assertTrue(java.util.Arrays.equals(clientGlide(kernel, world, a), clientGlide(kernel, world, b)),
		    "the client's glide is one tick");
		assertFalse(java.util.Arrays.equals(serverGlide(kernel, world, a), serverGlide(kernel, world, b)),
		    "the server copy's glide is two ticks");
	}

	/** Two composed ticks at one yaw: the server copy's tick runs before the packet installs the yaw. */
	private static SimulationState composedGlide(final Simulator kernel, final SnapshotView world, final float yaw) {
		PlayerState start = glidingState();
		PlayerInput glide = PlayerInput.idle(yaw, 0.0F);
		return kernel.advance(kernel.advance(kernel.start(start, start), glide, world).state(), glide, world).state();
	}

	private static long[] clientGlide(final Simulator kernel, final SnapshotView world, final float yaw) {
		return horizontal(composedGlide(kernel, world, yaw).clientState());
	}

	private static long[] serverGlide(final Simulator kernel, final SnapshotView world, final float yaw) {
		return horizontal(composedGlide(kernel, world, yaw).serverState());
	}

	private static long[] horizontal(final PlayerState after) {
		return new long[] {Double.doubleToRawLongBits(after.x), Double.doubleToRawLongBits(after.z),
		    Double.doubleToRawLongBits(after.deltaMovementX), Double.doubleToRawLongBits(after.deltaMovementZ)};
	}

	@Test
	void tinyYawsAroundZeroShareOneCellBecauseTheirEntriesAgree() {
		assertTrue(ViewCells.sameYaw(-0.005F, 0.0027F), "cos entries 16383 and 16384 are both 1.0f");
		assertFalse(ViewCells.sameYaw(0.0F, (float) (2 * 360.0 / 65536)), "two table steps apart differ");
	}

	private static PlayerState sprintingState() {
		PlayerState state = new PlayerState();
		state.placeAt(8.5, 1.0, 8.5);
		state.setBox(
		    AABB.around(state.x, state.y, state.z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));
		state.onGround = true;
		state.sprinting = true;
		state.deltaMovementZ = 0.2;
		state.mainSupportingBlockPosPresent = true;
		state.mainSupportingBlockPosX = 8;
		state.mainSupportingBlockPosY = 0;
		state.mainSupportingBlockPosZ = 8;
		return state;
	}

	/** A gliding player well above the floor with the level-glide velocity, mid-way through a glide. */
	private static PlayerState glidingState() {
		PlayerState state = new PlayerState();
		state.pose = PlayerState.Pose.FALL_FLYING;
		state.placeAt(8.5, 40.0, 8.5);
		state.fallFlying = true;
		state.gliderUsable = true;
		state.fallFlyTicks = 5;
		state.deltaMovementY = -0.18;
		state.deltaMovementZ = 0.73;
		return state;
	}

	private static SnapshotView flatWorld(final int height) {
		int size = 32;
		int[] cells = new int[size * height * size];
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++)
				cells[z * size + x] = 1;
		}
		BlockEntry air = new BlockEntry(0, "air", 0.6F, 1.0F, 1.0F, List.of());
		BlockEntry stone = new BlockEntry(
		    1, "stone", 0.6F, 1.0F, 1.0F, List.of(new ShapeBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)));
		return SnapshotView.compile(new WorldSnapshot(
		    0, 0, 0, size, height, size, OutsidePolicy.REFUSING, List.of(air, stone), cells));
	}
}
