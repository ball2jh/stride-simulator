package com.nettarion.stride.simulator.world;

import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.StateDigest;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.tick.ClientTick;
import com.nettarion.stride.simulator.tick.Scratch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotViewOverlayTest {
	private static final int AIR = 0;
	private static final int STONE = 1;

	@Test
	void aFrozenForkRejectsPublicationAndSurvivesSourceMutation() {
		SnapshotView source = world(OutsidePolicy.SEALED);
		SnapshotView frozen = source.frozen();

		assertTrue(frozen.movementFactsImmutable());
		assertThrows(IllegalStateException.class, () -> frozen.replaceCell(0, 0, 0, STONE));
		source.replaceCell(0, 0, 0, STONE);
		assertEquals(AIR, frozen.paletteIndexAt(0, 0, 0));
	}

	@Test
	void effectiveReplacementVersionsNoOpsAndForksIndependently() {
		SnapshotView base = world(OutsidePolicy.SEALED);
		SnapshotView child = base.fork();

		child.replaceCell(0, 0, 0, AIR);
		assertEquals(0L, child.collisionVersion(), "effective no-op must keep the token stable");

		child.replaceCell(0, 0, 0, STONE);
		assertEquals(1L, child.collisionVersion());
		assertEquals(STONE, child.paletteIndexAt(0, 0, 0));
		assertEquals(AIR, base.paletteIndexAt(0, 0, 0));

		child.replaceCell(0, 0, 0, STONE);
		assertEquals(1L, child.collisionVersion());
		SnapshotView grandchild = child.fork();
		grandchild.replaceCell(0, 0, 0, AIR);
		assertEquals(2L, grandchild.collisionVersion());
		assertEquals(AIR, grandchild.paletteIndexAt(0, 0, 0));
		assertEquals(STONE, child.paletteIndexAt(0, 0, 0));

		assertThrows(IndexOutOfBoundsException.class, () -> child.replaceCell(99, 0, 0, STONE));
		assertThrows(IndexOutOfBoundsException.class, () -> child.replaceCell(0, 0, 0, 99));
	}

	@Test
	void cellStateReplacementPublishesBlockAndFluidTogether() {
		BlockEntry air = entry(AIR, "air", 0.6F, List.of());
		BlockEntry waterBlock = entry(2, "water", 0.6F, List.of());
		FluidEntry water =
		    new FluidEntry(7, "minecraft:water", FluidKind.WATER, 1.0, 0.0, 0.0, 0.0, true);
		SnapshotView world = new SnapshotView(new WorldSnapshot(0, 0, 0, 1, 1, 1, OutsidePolicy.SEALED,
		    List.of(air, waterBlock), new int[] {AIR}, List.of(FluidEntry.EMPTY, water), new int[0]));
		FluidSample sample = new FluidSample();

		world.fluidAt(0, 0, 0, sample);
		assertEquals(FluidSample.Kind.EMPTY, sample.kind());

		world.replaceCellState(0, 0, 0, 1, 1);

		assertEquals(1, world.paletteIndexAt(0, 0, 0));
		world.fluidAt(0, 0, 0, sample);
		assertEquals(FluidSample.Kind.WATER, sample.kind());
		assertTrue(world.hasFluids());
		assertEquals(WorldView.PROPERTY_FLUID, world.propertiesIn(WorldView.PROPERTY_FLUID, 0, 0, 0, 0, 0, 0));
	}

	@Test
	void invalidFluidReplacementCannotPublishOnlyTheBlockHalf() {
		SnapshotView world = world(OutsidePolicy.SEALED);

		assertThrows(IndexOutOfBoundsException.class, () -> world.replaceCellState(0, 0, 0, STONE, 99));

		assertEquals(AIR, world.paletteIndexAt(0, 0, 0));
		assertEquals(0L, world.collisionVersion());
	}

	@Test
	void fluidOverlaysForkIndependently() {
		BlockEntry air = entry(AIR, "air", 0.6F, List.of());
		FluidEntry water =
		    new FluidEntry(7, "minecraft:water", FluidKind.WATER, 1.0, 0.0, 0.0, 0.0, true);
		SnapshotView parent = new SnapshotView(new WorldSnapshot(0, 0, 0, 1, 1, 1, OutsidePolicy.SEALED,
		    List.of(air), new int[] {AIR}, List.of(FluidEntry.EMPTY, water), new int[0]));
		SnapshotView child = parent.fork();
		FluidSample parentFluid = new FluidSample();
		FluidSample childFluid = new FluidSample();

		child.replaceCellState(0, 0, 0, AIR, 1);
		parent.fluidAt(0, 0, 0, parentFluid);
		child.fluidAt(0, 0, 0, childFluid);

		assertEquals(FluidSample.Kind.EMPTY, parentFluid.kind());
		assertEquals(FluidSample.Kind.WATER, childFluid.kind());
		assertFalse(parent.hasFluids());
		assertTrue(child.hasFluids());
	}

	@Test
	void fluidOverlayHashAndForkedGridsRemainIndependent() {
		BlockEntry air = entry(AIR, "air", 0.6F, List.of());
		FluidEntry water =
		    new FluidEntry(7, "minecraft:water", FluidKind.WATER, 1.0, 0.0, 0.0, 0.0, true);
		SnapshotView parent = new SnapshotView(new WorldSnapshot(0, 0, 0, 32, 1, 1, OutsidePolicy.SEALED,
		    List.of(air), new int[32], List.of(FluidEntry.EMPTY, water), new int[0]));
		for (int x = 0; x < 24; x++) {
			parent.replaceFluidCell(x, 0, 0, 1);
		}
		SnapshotView child = parent.fork();
		parent.replaceFluidCell(0, 0, 0, 0);
		child.replaceFluidCell(1, 0, 0, 0);
		FluidSample parentSample = new FluidSample();
		FluidSample childSample = new FluidSample();
		for (int x = 0; x < 32; x++) {
			parent.fluidAt(x, 0, 0, parentSample);
			child.fluidAt(x, 0, 0, childSample);
			assertEquals(x == 0 || x >= 24 ? FluidSample.Kind.EMPTY : FluidSample.Kind.WATER, parentSample.kind(),
			    "parent fluid at " + x);
			assertEquals(x == 1 || x >= 24 ? FluidSample.Kind.EMPTY : FluidSample.Kind.WATER, childSample.kind(),
			    "child fluid at " + x);
		}
	}

	@Test
	void fallResetRaycastReadsTheEffectiveFluidOverlay() {
		BlockEntry air = entry(AIR, "air", 0.6F, List.of());
		FluidEntry water =
		    new FluidEntry(7, "minecraft:water", FluidKind.WATER, 1.0, 0.0, 0.0, 0.0, true);
		SnapshotView world = new SnapshotView(new WorldSnapshot(0, 0, 0, 2, 1, 1, OutsidePolicy.SEALED,
		    List.of(air), new int[] {AIR, AIR}, List.of(FluidEntry.EMPTY, water), new int[0]));

		assertFalse(world.resetsFallDistanceAlong(0.1, 0.5, 0.5, 1.9, 0.5, 0.5));

		world.replaceCellState(1, 0, 0, AIR, 1);

		assertTrue(world.resetsFallDistanceAlong(0.1, 0.5, 0.5, 1.9, 0.5, 0.5),
		    "the raycast must see water installed by a timed fluid publication");

		world.replaceCellState(1, 0, 0, AIR, 0);

		assertFalse(world.resetsFallDistanceAlong(0.1, 0.5, 0.5, 1.9, 0.5, 0.5),
		    "the raycast must stop seeing water after the overlay is removed");
	}

	@Test
	void effectiveMutationInvalidatesAnEstablishedPoseCertificate() {
		SnapshotView world = world(OutsidePolicy.SEALED);
		PlayerState state = fallingState();
		state.cachePoseFit(world, PlayerState.Pose.STANDING);
		assertTrue(state.hasCachedPoseFit(world, PlayerState.Pose.STANDING));

		world.replaceCell(0, 0, 0, STONE);

		assertFalse(state.hasCachedPoseFit(world, PlayerState.Pose.STANDING));
	}

	@Test
	void replacementBeforeMovementMatchesPrebuiltSuccessorAtThatTick() {
		SnapshotView base = world(OutsidePolicy.SEALED);
		SnapshotView dynamic = base.fork();
		SnapshotView successor = base.fork();
		successor.replaceCell(0, 0, 0, STONE);
		PlayerState dynamicState = fallingState();
		PlayerState selectedWorldState = fallingState();
		PlayerState lateState = fallingState();
		ClientTick dynamicKernel = new ClientTick();
		ClientTick selectedKernel = new ClientTick();
		ClientTick lateKernel = new ClientTick();
		PlayerInput idle = PlayerInput.idle(0.0F, 0.0F);

		for (int tick = 0; tick < 4; tick++) {
			if (tick == 1) {
				dynamic.replaceCell(0, 0, 0, STONE);
			}
			dynamicKernel.tick(dynamicState, idle, dynamic);
			selectedKernel.tick(selectedWorldState, idle, tick < 1 ? base : successor);
			assertEquals(StateDigest.state(selectedWorldState), StateDigest.state(dynamicState),
			    "timed sparse publication diverged at tick " + tick);

			if (tick <= 1) {
				lateKernel.tick(lateState, idle, base);
				if (tick == 1) {
					assertNotEquals(StateDigest.state(dynamicState), StateDigest.state(lateState),
					    "publishing after movement must not affect the already-finished tick");
				}
			}
		}
		assertTrue(dynamicState.onGround);
		assertEquals(Double.doubleToRawLongBits(1.0), Double.doubleToRawLongBits(dynamicState.y));
	}

	@Test
	void overlayKeepsPaletteShapeOrderSurfaceDataAndOutsideSemantics() {
		BlockEntry air = entry(AIR, "air", 0.6F, List.of());
		BlockEntry ordered = entry(STONE, "ordered", 0.91F,
		    List.of(new ShapeBox(0.0, 0.0, 0.0, 0.75, 1.0, 1.0),
		        new ShapeBox(0.25, 0.0, 0.0, 1.0, 0.5, 1.0)));
		SnapshotView world = snapshot(OutsidePolicy.REFUSING, List.of(air, ordered));
		world.replaceCell(0, 0, 0, STONE);
		CollisionBuffer collisions = new CollisionBuffer(1);

		world.collectCollisionBoxes(0.5, 0.2, 0.2, 0.6, 0.4, 0.8, collisions);

		assertEquals(2, collisions.size());
		assertEquals(Double.doubleToRawLongBits(0.75), Double.doubleToRawLongBits(collisions.maxX(0)));
		assertEquals(Double.doubleToRawLongBits(0.5), Double.doubleToRawLongBits(collisions.maxY(1)));
		assertEquals(Float.floatToRawIntBits(0.91F), Float.floatToRawIntBits(world.friction(0, 0, 0)));
		assertThrows(UnimplementedMechanicException.class,
		    () -> world.collectCollisionBoxes(2.9, 0.2, 0.2, 3.1, 0.8, 0.8, collisions));
	}

	@Test
	void emptySectionIndexTracksOverlayAddRemoveAndForksAtUnalignedOrigins() {
		BlockEntry air = entry(AIR, "air", 0.6F, List.of());
		BlockEntry stone =
		    entry(STONE, "stone", 0.6F, List.of(new ShapeBox(0, 0, 0, 1, 1, 1)));
		int size = 34;
		SnapshotView parent = new SnapshotView(new WorldSnapshot(-17, -17, -17, size, size, size,
		    OutsidePolicy.SEALED, List.of(air, stone), new int[size * size * size]));
		SnapshotView child = parent.fork();
		CollisionBuffer collisions = new CollisionBuffer(1);

		child.replaceCell(16, 16, 16, STONE);
		child.collectCollisionBoxes(16.2, 16.2, 16.2, 16.8, 16.8, 16.8, collisions);
		assertEquals(1, collisions.size(), "an overlay must publish occupancy in the last partial local section");

		parent.collectCollisionBoxes(16.2, 16.2, 16.2, 16.8, 16.8, 16.8, collisions);
		assertEquals(0, collisions.size(), "fork section occupancy must remain independent");

		child.replaceCell(16, 16, 16, AIR);
		child.collectCollisionBoxes(16.2, 16.2, 16.2, 16.8, 16.8, 16.8, collisions);
		assertEquals(0, collisions.size(), "reverting an overlay must clear its section count");
	}

	@Test
	void sectionIndexTreatsAnOverlayContextShapeAsPotentialCollision() {
		BlockEntry scaffolding =
		    new BlockEntry(2, "minecraft:scaffolding", 0.6F, 1.0F, 1.0F, false, false,
		        WorldView.BubbleColumnMode.NONE, true, WorldView.CollisionBehavior.SCAFFOLDING_SUPPORTED,
		        Suffocation.UNKNOWN, WorldView.InsideEffect.NONE, 0.0F, false, WorldView.StepOn.NONE,
		        false, WorldView.Climbability.CLIMBABLE, List.of());
		int size = 18;
		SnapshotView world =
		    new SnapshotView(new WorldSnapshot(0, 0, 0, size, size, size, OutsidePolicy.SEALED,
		        List.of(entry(AIR, "air", 0.6F, List.of()), scaffolding), new int[size * size * size]));
		CollisionBuffer collisions = new CollisionBuffer(5);

		world.replaceCell(16, 16, 16, 1);
		world.collectCollisionBoxes(16.2, 16.8, 16.2, 16.8, 17.2, 16.8, 17.0, false, collisions);

		assertTrue(collisions.size() >= 1, "an empty stored shape can still have context-selected collision potential");
		assertEquals(Double.doubleToRawLongBits(16.875), Double.doubleToRawLongBits(collisions.minY(0)));
	}

	@Test
	void overlayDensityMatchesFreshReconstructionAfterEveryCheckpoint() {
		int size = 18;
		int[] effective = new int[size * 4 * size];
		for (int x = 0; x < size; x++) {
			for (int z = 0; z < size; z++) {
				effective[z * size + x] = STONE;
			}
		}
		List<BlockEntry> palette = List.of(exactEntry(AIR, "air", List.of()),
		    exactEntry(STONE, "stone", List.of(new ShapeBox(0, 0, 0, 1, 1, 1))));
		SnapshotView overlay = new SnapshotView(
		    new WorldSnapshot(0, 0, 0, size, 4, size, OutsidePolicy.SEALED, palette, effective));
		int[] checkpoints = {0, 1, 4, 16, 17, 64, 256};
		int checkpoint = 0;
		for (int edit = 0; edit <= 256; edit++) {
			if (edit > 0) {
				int cell = edit - 1;
				int x = 1 + cell % 16;
				int z = 1 + cell / 16;
				int y = 1;
				effective[(y * size + z) * size + x] = STONE;
				overlay.replaceCell(x, y, z, STONE);
			}
			if (checkpoint < checkpoints.length && edit == checkpoints[checkpoint]) {
				SnapshotView rebuilt = new SnapshotView(
				    new WorldSnapshot(0, 0, 0, size, 4, size, OutsidePolicy.SEALED, palette, effective));
				assertWorldAndMovementAgree(overlay, rebuilt, edit);
				checkpoint++;
			}
		}

		int[] childEffective = effective.clone();
		SnapshotView child = overlay.fork();
		effective[(1 * size + 1) * size + 1] = AIR;
		overlay.replaceCell(1, 1, 1, AIR);
		childEffective[(1 * size + 1) * size + 2] = AIR;
		child.replaceCell(2, 1, 1, AIR);
		assertWorldAndMovementAgree(overlay,
		    new SnapshotView(
		        new WorldSnapshot(0, 0, 0, size, 4, size, OutsidePolicy.SEALED, palette, effective)),
		    255);
		assertWorldAndMovementAgree(child,
		    new SnapshotView(
		        new WorldSnapshot(0, 0, 0, size, 4, size, OutsidePolicy.SEALED, palette, childEffective)),
		    255);
		assertEquals(STONE, child.paletteIndexAt(1, 1, 1));
		assertEquals(STONE, overlay.paletteIndexAt(2, 1, 1));
	}

	private static void assertWorldAndMovementAgree(
	    final SnapshotView overlay, final SnapshotView rebuilt, final int edits) {
		assertEquals(rebuilt.propertiesIn(WorldView.PROPERTIES_ALL, 0, 0, 0, 17, 3, 17),
		    overlay.propertiesIn(WorldView.PROPERTIES_ALL, 0, 0, 0, 17, 3, 17),
		    "property mask after " + edits + " edits");
		for (int y = 0; y < 4; y++) {
			for (int z = 0; z < 18; z++) {
				for (int x = 0; x < 18; x++) {
					assertEquals(rebuilt.paletteIndexAt(x, y, z), overlay.paletteIndexAt(x, y, z),
					    "palette after " + edits + " edits at " + x + "," + y + "," + z);
				}
			}
		}
		PlayerState overlayState = new PlayerState();
		overlayState.placeAt(8.5, 1.0, 8.5);
		overlayState.onGround = true;
		overlayState.verticalCollision = true;
		overlayState.verticalCollisionBelow = true;
		PlayerState rebuiltState = overlayState.copy();
		ClientTick overlayKernel = new ClientTick();
		ClientTick rebuiltKernel = new ClientTick();
		Scratch overlayScratch = new Scratch();
		Scratch rebuiltScratch = new Scratch();
		PlayerInput action = new PlayerInput(true, false, false, false, false, false, true, 17.0F, 0.0F);
		for (int tick = 0; tick < 4; tick++) {
			overlayKernel.tick(overlayState, action, overlay, overlayScratch);
			rebuiltKernel.tick(rebuiltState, action, rebuilt, rebuiltScratch);
			assertEquals(StateDigest.state(rebuiltState), StateDigest.state(overlayState),
			    "movement after " + edits + " edits at tick " + tick);
		}
	}

	private static BlockEntry exactEntry(
	    final int id, final String name, final List<ShapeBox> boxes) {
		return new BlockEntry(id, name, 0.6F, 1.0F, 1.0F, false, false, WorldView.BubbleColumnMode.NONE,
		    false, WorldView.CollisionBehavior.ORDINARY, Suffocation.NO, WorldView.InsideEffect.NONE,
		    0.0F, false, WorldView.StepOn.NONE, false, WorldView.Climbability.NONE, boxes);
	}

	private static SnapshotView world(final OutsidePolicy outside) {
		return snapshot(outside,
		    List.of(entry(AIR, "air", 0.6F, List.of()),
		        entry(STONE, "stone", 0.6F, List.of(new ShapeBox(0, 0, 0, 1, 1, 1)))));
	}

	/**
	 * Tall enough to contain the standing player's own box. A region whose top
	 * cell is inside the box makes every tick ask what suffocates outside it,
	 * which no snapshot can answer.
	 */
	private static SnapshotView snapshot(
	    final OutsidePolicy outside, final List<BlockEntry> palette) {
		return new SnapshotView(new WorldSnapshot(-2, -2, -2, 5, 8, 5, outside, palette, new int[200]));
	}

	private static BlockEntry entry(
	    final int id, final String name, final float friction, final List<ShapeBox> boxes) {
		return new BlockEntry(id, name, friction, 1.0F, 1.0F, boxes);
	}

	private static PlayerState fallingState() {
		PlayerState state = new PlayerState();
		state.x = 0.5;
		state.y = 1.25;
		state.z = 0.5;
		state.deltaMovementY = -0.2;
		state.setBox(
		    AABB.around(state.x, state.y, state.z, PlayerState.Pose.STANDING.width, PlayerState.Pose.STANDING.height));
		return state;
	}
}
