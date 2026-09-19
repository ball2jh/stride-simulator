package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.server.ServerDamage;
import com.nettarion.stride.simulator.tick.EntityFluidInteraction;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.tick.SupportingBlock;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldView;

import java.util.HashSet;
import java.util.Set;

/**
 * Runs the block hooks the tick's movement reached: {@code Entity.applyEffectsFromBlocks}.
 *
 * <p>This is the phase between {@code Travel} and {@code Freezing}. Vanilla runs the supporting block's
 * {@code stepOn} while on the ground, then traverses the movement {@code Move} recorded (or one synthesized from
 * the old position) through {@link InsideBlockTraversal}, running each reached cell's {@code entityInside} at the
 * visit and applying the step-grouped effects through {@link InsideBlockEffectCollector} afterwards, so immediate
 * hooks precede collected damage. The cover is asked for hook-bearing cells first, so a movement through plain
 * blocks never traverses. On the server's copy the fluid cells the box swept are visits too, the movements the
 * accepted packets recorded this tick are replayed, and the rain check and the fire-immune reset close the phase.
 * The client visits its fluid cells only when the lava's CLEAR_FREEZE could write, which is when its frozen count
 * is non-zero or powder snow lies in the same cover.
 *
 * <p>Reads position, box, velocity, ground, fall distance, the flags and the retained support. Writes the fields in
 * {@link #WRITES}. Internal plumbing: the tick calls {@link #applyEffectsFromBlocks} with its own {@code Scratch}.
 */
public final class BlockEffects {
	/**
	 * Every {@link ServerPlayerState} field this phase may write.
	 *
	 * <p>Public so that {@code PhaseWriteSetTest} can check every phase's declared writes against the fields a
	 * tick actually changed; nothing else reads it.
	 */
	public static final Set<String> WRITES;

	/** {@code Mth.square(1.0E-5F)}, the float 9.9999994E-11F widened: a shorter replay gap is not traversed. */
	private static final double MIN_REPLAY_GAP_SQR = 1.0E-5F * 1.0E-5F;

	/** The cap on movements a server tick records; the current movement is recorded once the cap is reached. */
	private static final int MAX_ADDITIONAL_MOVEMENTS = ServerPlayerState.MAXIMUM_MOVEMENTS_THIS_TICK - 1;

	static {
		Set<String> writes = new HashSet<>(ServerDamage.HURT_WRITES);
		writes.addAll(Set.of("entityDataDirty", "movementThisTickPresent", "movementAxisDependent", "movementFromX",
		    "movementFromY", "movementFromZ", "movementToX", "movementToY", "movementToZ", "movementRequestedX",
		    "movementRequestedZ", "deltaMovementX", "deltaMovementY", "deltaMovementZ", "fallDistance",
		    "stuckSpeedMultiplierX", "stuckSpeedMultiplierY", "stuckSpeedMultiplierZ", "isInPowderSnow", "ticksFrozen",
		    "remainingFireTicks"));
		WRITES = Set.copyOf(writes);
	}

	private BlockEffects() {}

	/**
	 * {@code Entity.applyEffectsFromBlocks}: one inside-block traversal per tick, shared by every effect this
	 * simulator models.
	 *
	 * <p>{@code Entity.checkInsideBlocks} splits the tick's recorded movement into up to three axis-aligned
	 * sub-movements, each a separate {@code BlockGetter.forEachBlockIntersectedBetween} with its own origin box,
	 * swept DDA and destination box, over one shared visited set and one shared step collector. Collapsing that
	 * into a single origin/destination pair puts every origin cell ahead of every destination cell, and the
	 * bubble-column clamps do not commute, so traversal order is observable.
	 */
	public static void applyEffectsFromBlocks(final PlayerState state, final WorldView world, final Scratch scratch) {
		double fromX;
		double fromY;
		double fromZ;
		boolean axisDependent;
		if (scratch.hasMovementSegment) {
			fromX = scratch.segmentFromX;
			fromY = scratch.segmentFromY;
			fromZ = scratch.segmentFromZ;
			axisDependent = true;
		} else {
			// move() recorded no segment, so applyEffectsFromBlocks synthesizes
			// Movement(oldPosition, position), which carries no axis-dependent
			// original movement and is therefore traversed as one sub-movement.
			fromX = scratch.oldX;
			fromY = scratch.oldY;
			fromZ = scratch.oldZ;
			axisDependent = false;
		}
		double toX = state.x;
		double toY = state.y;
		double toZ = state.z;
		// Entity.applyEffectsFromBlocks runs stepOn first, before the traversal,
		// at getOnPosLegacy and only while on the ground. getOnPosLegacy reads
		// the supporting identity the state retains, which the server's copy
		// keeps from the last accepted packet while its own move refreshes
		// nothing, so the ground bit alone is the gate.
		if (state.onGround) {
			applyStepOn(state, world, scratch);
		}
		ServerPlayerState server = scratch.authority.isServer() ? scratch.authority.serverState() : null;
		// previousRemainingFireTicks, sampled after stepOn and before the
		// traversal, decides the fire-immune reset after it.
		int previousFireTicks = server != null ? server.remainingFireTicks : 0;
		boolean currentRecorded = server != null && scratch.hasMovementSegment
		    && server.additionalMovementCount() >= MAX_ADDITIONAL_MOVEMENTS;
		if (currentRecorded) {
			server.addMovementThisTick(scratch.segmentFromX, scratch.segmentFromY, scratch.segmentFromZ, toX, toY, toZ,
			    scratch.segmentRequestedX, scratch.segmentRequestedZ);
		}
		if (server != null && server.movementThisTickPresent) {
			replayServerMovements(
			    state, world, scratch, server, fromX, fromY, fromZ, toX, toY, toZ, axisDependent, currentRecorded);
		} else {
			collectInsideBlocks(state, world, scratch, fromX, fromY, fromZ, toX, toY, toZ, axisDependent,
			    scratch.segmentRequestedX, scratch.segmentRequestedZ, true);
		}
		if (server != null) {
			closeServerEffects(server, world, scratch.authority.damage(), previousFireTicks);
		}
	}

	/**
	 * Whether the world supplies vanilla-extracted entity-inside shapes for every cell and this is the server's
	 * copy, which switches the collector's ambiguity tracking off.
	 *
	 * <p>The tracking ({@code beginSweep} and {@code abortSweep}) exists because a cell without a modeled hook is
	 * invisible to this traversal, while vanilla would still have visited it and flushed the collector's step.
	 * With extracted inside shapes every cell vanilla would visit is visited here too, since
	 * {@code SnapshotView.sourceInsideReached} answers for the hook-less cells and refuses rather than guess, so
	 * the step grouping is exact and the tracking would only raise false refusals.
	 */
	static boolean usesSourceInsideShapes(final WorldView world, final Scratch scratch) {
		return scratch.authority.isServer() && world instanceof SnapshotView snapshot
		    && snapshot.hasSourceInsideShapes();
	}

	/**
	 * Every property the inside-block traversal could meet between these two positions.
	 *
	 * @implNote The cover is the cell span of the AABB union of the box at the origin and the box at the
	 *     destination, widened by one cell on each face. That is sound for all three of the traversal's parts. The
	 *     per-axis split walks one coordinate at a time from origin to destination, so every sub-movement's
	 *     endpoints, and therefore every box it builds, lie componentwise between the two.
	 *     {@code addCollisionsAlongTravel} sweeps a corner of the destination box back along the same travel
	 *     vector, so its ray lies in the union too. The one-cell margin covers the neighbor cell the bubble column
	 *     reads above itself, and the {@code floor} of a box face that lands exactly on a cell edge. It is sound
	 *     above the {@code movedFar} threshold as well: a far movement's cover is still the two boxes and the ray
	 *     between them.
	 */
	private static int traversalProperties(final PlayerState state, final WorldView world, final double fromX,
	    final double fromY, final double fromZ, final double toX, final double toY, final double toZ) {
		int wanted = WorldView.PROPERTY_BUBBLE_COLUMN | WorldView.PROPERTY_POWDER_SNOW
		    | WorldView.PROPERTY_INSIDE_EFFECT | WorldView.PROPERTY_CONTACT | WorldView.PROPERTY_FLUID;
		if (world.propertiesAnywhere(wanted) == 0) {
			return 0;
		}
		double halfWidth = state.pose.width / 2.0F;
		int x0 = Mth.floor(Math.min(fromX, toX) - halfWidth) - 1;
		int x1 = Mth.floor(Math.max(fromX, toX) + halfWidth) + 1;
		int y0 = Mth.floor(Math.min(fromY, toY)) - 1;
		int y1 = Mth.floor(Math.max(fromY, toY) + state.pose.height) + 1;
		int z0 = Mth.floor(Math.min(fromZ, toZ) - halfWidth) - 1;
		int z1 = Mth.floor(Math.max(fromZ, toZ) + halfWidth) + 1;
		return world.propertiesIn(wanted, x0, y0, z0, x1, y1, z1);
	}

	/**
	 * {@code ServerPlayer}'s replay of the movements the accepted packets recorded this tick.
	 *
	 * <p>Each recorded movement is traversed in order, with its own requested vector, over one visited set and one
	 * collector; then the tick's own movement when it was not itself recorded; then the gap from the last
	 * destination to the current position when it is longer than {@link #MIN_REPLAY_GAP_SQR}. One
	 * {@code applyAndClear} closes the whole replay.
	 */
	private static void replayServerMovements(final PlayerState state, final WorldView world, final Scratch scratch,
	    final ServerPlayerState server, final double fromX, final double fromY, final double fromZ, final double toX,
	    final double toY, final double toZ, final boolean axisDependent, final boolean currentRecorded) {
		scratch.insideEffects.begin();
		scratch.insideTraversal.begin();
		collectInsideBlocks(state, world, scratch, server.movementFromX, server.movementFromY, server.movementFromZ,
		    server.movementToX, server.movementToY, server.movementToZ, server.movementAxisDependent,
		    server.movementRequestedX, server.movementRequestedZ, false);
		double lastX = server.movementToX;
		double lastY = server.movementToY;
		double lastZ = server.movementToZ;
		for (int index = 0, count = server.additionalMovementCount(); index < count; index++) {
			ServerPlayerState.Movement movement = server.additionalMovement(index);
			collectInsideBlocks(state, world, scratch, movement.fromX(), movement.fromY(), movement.fromZ(),
			    movement.toX(), movement.toY(), movement.toZ(), true, movement.requestedX(), movement.requestedZ(),
			    false);
			lastX = movement.toX();
			lastY = movement.toY();
			lastZ = movement.toZ();
		}
		if (axisDependent && !currentRecorded) {
			collectInsideBlocks(state, world, scratch, fromX, fromY, fromZ, toX, toY, toZ, true,
			    scratch.segmentRequestedX, scratch.segmentRequestedZ, false);
			lastX = toX;
			lastY = toY;
			lastZ = toZ;
		}
		double dx = lastX - toX;
		double dy = lastY - toY;
		double dz = lastZ - toZ;
		if (dx * dx + dy * dy + dz * dz > MIN_REPLAY_GAP_SQR) {
			collectInsideBlocks(state, world, scratch, lastX, lastY, lastZ, toX, toY, toZ, false,
			    scratch.segmentRequestedX, scratch.segmentRequestedZ, false);
		}
		server.clearMovementThisTick();
		scratch.insideEffects.applyAndClear(state);
	}

	/**
	 * The tail of {@code Entity.applyEffectsFromBlocks} on a server level, after {@code applyAndClear}:
	 * {@code isInRain} at the player's cell and the top of its box puts a burning player out, and a player who is
	 * not burning and was not ignited this tick is set to the immune window.
	 */
	private static void closeServerEffects(
	    final ServerPlayerState server, final WorldView world, final ServerDamage damage, final int previousFireTicks) {
		if (server.remainingFireTicks > 0) {
			// Asked only while burning, which is when the answer changes the state.
			int cellX = Mth.floor(server.x);
			int cellZ = Mth.floor(server.z);
			if (world.rainReaches(cellX, Mth.floor(server.y), cellZ, Mth.floor(server.boundingBoxMaxY))) {
				damage.clearFire();
			}
		}
		boolean ignitedThisTick = server.remainingFireTicks > previousFireTicks;
		if (server.remainingFireTicks <= 0 && !ignitedThisTick) {
			server.remainingFireTicks = -ServerPlayerState.FIRE_IMMUNE_TICKS;
		}
	}

	/**
	 * One {@code Entity.Movement} of {@code checkInsideBlocks}, traversed only when its cover holds a hook that
	 * could write.
	 *
	 * @param completeTraversal whether this movement is the tick's whole traversal, so the visited set and the
	 *     collector begin here and {@code applyAndClear} follows; a replayed movement shares both with its
	 *     neighbors
	 */
	private static void collectInsideBlocks(final PlayerState state, final WorldView world, final Scratch scratch,
	    final double fromX, final double fromY, final double fromZ, final double toX, final double toY,
	    final double toZ, final boolean axisDependent, final double requestedX, final double requestedZ,
	    final boolean completeTraversal) {
		int properties = traversalProperties(state, world, fromX, fromY, fromZ, toX, toY, toZ);
		boolean server = scratch.authority.isServer();
		boolean bubbles = (properties & WorldView.PROPERTY_BUBBLE_COLUMN) != 0;
		boolean powder = (properties & WorldView.PROPERTY_POWDER_SNOW) != 0;
		boolean fluids = (properties & WorldView.PROPERTY_FLUID) != 0;
		// Cobweb, sweet berry bush, honey, and lava cauldron. The traversal runs
		// whenever any inside-effect fact lies in the cover; only the bubble
		// column drops out for a flying player here, because only its hook is
		// gated on abilities.flying by its caller, Player.aiStep. Every other
		// hook owns its own gate, documented on the override: makeStuckInBlock
		// for the cobweb and the bush, HoneyBlock.entityInside (no gate at all),
		// PowderSnowBlock.entityInside (gated for its vector, not for FREEZE).
		boolean insideEffects = (properties & WorldView.PROPERTY_INSIDE_EFFECT) != 0;
		// Server-only contact hooks, and on the server's copy the fluid cells
		// too: a lava or water cell the box sweeps through is a visit whose
		// ignition, hit and extinguish only the server's damage state sees.
		boolean contacts = (properties & WorldView.PROPERTY_CONTACT) != 0 || server && fluids;
		// LavaFluid.entityInside collects CLEAR_FREEZE on the client as well, and
		// applyAndClear runs on both sides. The clear writes only when the
		// client's frozen count is non-zero or a FREEZE of this same traversal
		// precedes it, so the client visits its fluid cells exactly then and
		// keeps the ordinary swim tick's traversal off. (A fire block's
		// CLEAR_FREEZE is a contact visit and always traverses.)
		boolean clientFluidFreeze = !server && fluids && (powder || state.ticksFrozen > 0);
		// With extracted inside shapes the step indices must stay exact across a
		// replay, so a movement with nothing of its own still traverses while an
		// earlier movement's effects are pending.
		boolean exactSteps = usesSourceInsideShapes(world, scratch);
		if (!powder && !insideEffects && !contacts && !clientFluidFreeze && (!bubbles || state.flying)
		    && !(exactSteps && !completeTraversal && scratch.insideEffects.hasPendingEffects())) {
			return;
		}
		if (completeTraversal) {
			scratch.insideEffects.begin();
			scratch.insideTraversal.begin();
		}
		scratch.insideTraversal.traverseRetainingVisited(state, fromX, fromY, fromZ, toX, toY, toZ, axisDependent,
		    requestedX, requestedZ, (contacts || exactSteps) && server || clientFluidFreeze, world, scratch);
		if (completeTraversal) {
			scratch.insideEffects.applyAndClear(state);
		}
	}

	/**
	 * {@code WaterFluid} and {@code LavaFluid.entityInside}: the lava's CLEAR_FREEZE on both sides, the ignition,
	 * hit and extinguish on the server's copy, whose damage state is the only one they write.
	 */
	private static void visitFluid(final FluidSample.Kind fluid, final Scratch scratch) {
		boolean server = scratch.authority.isServer();
		if (fluid == FluidSample.Kind.WATER) {
			if (server) {
				scratch.insideEffects.collectExtinguish();
			}
		} else {
			scratch.insideEffects.collectClearFreeze();
			if (server) {
				scratch.insideEffects.collectLavaContact();
			}
		}
	}

	/**
	 * {@code Block.stepOn} for the block underfoot, at {@code getOnPosLegacy}: the behavior's, which on the
	 * server's copy deals the hot floor's hit and refuses a hook the simulator does not model, and on both sides
	 * runs the slime's damping.
	 */
	private static void applyStepOn(final PlayerState state, final WorldView world, final Scratch scratch) {
		SupportingBlock.getOnPosLegacy(state, world, scratch);
		world.behaviorAt(scratch.support.x, scratch.support.y, scratch.support.z)
		    .stepOn(state, scratch.support.x, scratch.support.y, scratch.support.z, scratch);
	}

	/**
	 * Reusable detection result for one candidate cell, owned by a traversal.
	 *
	 * <p>Detection reads the block and fluid once; application consumes the result only after the traversal
	 * accepts the contact. No visited sets or step counters belong here, and a false detection leaves no result to
	 * apply. Cells without a modeled hook are invisible to the traversal; if that omission makes collected-step
	 * grouping ambiguous, the {@link InsideBlockEffectCollector} refuses.
	 */
	static final class Contact {
		private BlockBehavior behavior;

		private boolean contactHook;

		private FluidSample.Kind fluid;

		private boolean insideBlock;

		private boolean insideFluid;

		/** One fluid cell's box; only its height varies. */
		private final double[] fluidShape = {0.0, 0.0, 0.0, 1.0, 0.0, 1.0};

		/**
		 * Detects whether the sweep reached a hook or a fluid at the cell. When the cell has neither but the world
		 * carries extracted inside shapes, the server's copy asks the world whether the sweep reached the cell's
		 * vanilla shape, so the step index advances as vanilla's would.
		 */
		boolean find(final PlayerState state, final int x, final int y, final int z, final boolean includeFluids,
		    final WorldView world, final Scratch scratch) {
			this.behavior = world.behaviorAt(x, y, z);
			// The server-only entityInside hooks are the behavior's contact half.
			// A hot floor is a stepOn hook and runs from applyStepOn, not from a
			// visit, so a magma block has none.
			this.contactHook = this.behavior.hasContact();
			// kindAt fills scratch.fluidCell with this cell's sample as a side
			// effect; collidedWithFluidCell below reads that cell's height from it.
			this.fluid =
			    includeFluids ? EntityFluidInteraction.kindAt(world, x, y, z, scratch) : FluidSample.Kind.EMPTY;
			if (!this.behavior.hasEntityInside() && !this.contactHook && this.fluid == FluidSample.Kind.EMPTY) {
				if (includeFluids && usesSourceInsideShapes(world, scratch)) {
					this.insideBlock =
					    ((SnapshotView) world).sourceInsideReached(state, x, y, z, scratch.insideTraversal);
					this.insideFluid = false;
					return this.insideBlock;
				}
				return false;
			}
			// A block keeping the default full-cube getEntityInsideCollisionShape
			// answers true from entityInsideShapeReached, as vanilla's
			// `intersectShape == Shapes.block()` short-circuit does; powder snow
			// and the cauldrons sweep for their own shape (see their overrides).
			// A layered water or powder-snow cauldron has a hook only when a
			// BlockStateCatalog supplied its shape and successor; otherwise its
			// entry has none and it is skipped above.
			this.insideBlock = this.behavior.hasEntityInside() || this.contactHook;
			if (!this.behavior.entityInsideShapeReached(state, x, y, z, scratch)) {
				this.insideBlock = false;
			}
			// Entity.collidedWithFluid: the fluid's own box, the cell up to its
			// height, swept from this sub-movement's origin like a shape.
			this.insideFluid = this.fluid != FluidSample.Kind.EMPTY
			    && collidedWithFluidCell(state, x, y, z, scratch, scratch.fluidCell.height());
			return this.insideBlock || this.insideFluid;
		}

		/** Runs the detected hook and fluid at an accepted visit; the box is the sub-movement's destination box. */
		void apply(final PlayerState state, final int x, final int y, final int z, final double minX, final double minY,
		    final double minZ, final double maxX, final double maxY, final double maxZ, final boolean movedFar,
		    final WorldView world, final Scratch scratch) {
			if (this.insideFluid && !this.insideBlock) {
				visitFluid(this.fluid, scratch);
				return;
			}
			// The client-visible hook applies immediately; only the collected
			// effects wait for applyAndClear. isPrecise is whether the destination
			// box overlaps the cell, which the bubble column's hook reads.
			boolean isPrecise =
			    movedFar || (minX < x + 1.0 && maxX > x && minY < y + 1.0 && maxY > y && minZ < z + 1.0 && maxZ > z);
			this.behavior.entityInside(state, x, y, z, isPrecise, world, scratch);
			if (this.insideFluid) {
				visitFluid(this.fluid, scratch);
			}
		}

		/** {@code Entity.collidedWithFluid}: the cell up to the fluid's height, swept like a shape. */
		private boolean collidedWithFluidCell(final PlayerState state, final int x, final int y, final int z,
		    final Scratch scratch, final double height) {
			double[] shape = this.fluidShape;
			shape[4] = height;
			return scratch.insideTraversal.collidedWithShapeMovingFrom(state, x, y, z, shape);
		}
	}
}
