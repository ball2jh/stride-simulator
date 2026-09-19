package com.nettarion.stride.simulator.block;

import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.server.Survival;
import com.nettarion.stride.simulator.tick.EntityFluidInteraction;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.tick.SupportingBlock;
import com.nettarion.stride.simulator.FluidSample;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldView;
import java.util.HashSet;
import java.util.Set;

/**
 * The block bodies the tick's movement reached: {@code Entity.applyEffectsFromBlocks},
 * the phase between {@link com.nettarion.stride.simulator.tick.Travel} and {@link com.nettarion.stride.simulator.tick.Freezing}.
 *
 * <p>Vanilla runs the supporting block's {@code stepOn} while on the ground,
 * then traverses the movement {@link com.nettarion.stride.simulator.tick.Move} recorded (or one synthesised from
 * the old position) through {@link InsideBlockTraversal}, running each
 * reached cell's {@code entityInside} at the visit and collecting the
 * step-grouped effects for {@code InsideBlockEffectApplier.applyAndClear}
 * afterwards. Which cells can have a body is asked of the span first, so a
 * movement through plain blocks never traverses. On the server's copy each
 * body's server-only half runs at the visit through {@link com.nettarion.stride.simulator.server.TickAuthority#survival()},
 * the fluid cells the box swept are visits too, and the rain check and the
 * fire-immune reset close the phase. The client visits its fluid cells only
 * when the lava's CLEAR_FREEZE could write, which is when its frozen count is
 * non-zero or powder snow lies in the same span.
 *
 * <p>Reads position, box, velocity, ground, fall distance, the flags, and
 * the retained support. Writes the fields in {@link #WRITES}.
 */
public final class BlockEffects {
	/** Every {@link ServerPlayerState} field this phase may write. */
	public static final Set<String> WRITES;

	static {
		Set<String> writes = new HashSet<>(Survival.HURT_WRITES);
		writes.addAll(Set.of("entityDataDirty", "movementThisTickPresent", "movementAxisDependent", "movementFromX",
		    "movementFromY", "movementFromZ", "movementToX", "movementToY", "movementToZ", "movementRequestedX",
		    "movementRequestedZ", "deltaMovementX", "deltaMovementY", "deltaMovementZ", "fallDistance",
		    "stuckSpeedMultiplierX", "stuckSpeedMultiplierY", "stuckSpeedMultiplierZ", "isInPowderSnow", "ticksFrozen",
		    "remainingFireTicks"));
		WRITES = Set.copyOf(writes);
	}

	private BlockEffects() {}

	/**
	 * Every property the inside-block traversal could meet between these two
	 * positions.
	 *
	 * <p>The cover is the cell span of the AABB union of the box at the origin and
	 * the box at the destination, widened by one cell on each face. That is sound
	 * for all three of the traversal's parts. The per-axis split walks one
	 * coordinate at a time from origin to destination, so every sub-movement's
	 * endpoints — and therefore every box it builds — lie componentwise between
	 * the two. {@code addCollisionsAlongTravel} sweeps a corner of the
	 * destination box back along the same travel vector, so its ray lies in the
	 * union too. The one-cell margin covers the neighbor cell
	 * {@code applyBubbleColumnAt} reads above a column, and the {@code floor} of a
	 * box face that lands exactly on a cell boundary.
	 *
	 * <p>Sound above the {@code movedFar} threshold as well: a far movement's
	 * cover is still the two boxes and the ray between them, so a far movement
	 * whose cover holds no bubble column and no powder snow still short-circuits
	 * before {@link InsideBlockTraversal} runs, exactly as a fluid-free world
	 * does today.
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
	 * {@code Entity.applyEffectsFromBlocks}: one inside-block traversal per tick,
	 * shared by every effect this slice models.
	 *
	 * <p>{@code Entity.checkInsideBlocks} splits the tick's recorded movement into
	 * up to three axis-aligned sub-movements, each a separate
	 * {@code BlockGetter.forEachBlockIntersectedBetween} with its own origin box,
	 * swept DDA and destination box, over one shared visited set and one shared
	 * step collector. Collapsing that into a single origin/destination pair puts
	 * every origin cell ahead of every destination cell, and the bubble-column
	 * clamps do not commute, so traversal order is observable.
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
			// move() recorded no segment, so applyEffectsFromBlocks synthesises
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
		// previousRemainingFireTicks, sampled after stepOn and before the
		// traversal, decides the fire-immune reset after it.
		int previousFireTicks = scratch.authority.isServer() ? scratch.authority.serverState().remainingFireTicks : 0;

		ServerPlayerState server = scratch.authority.isServer() ? scratch.authority.serverState() : null;
		boolean currentRecorded =
		    server != null && scratch.hasMovementSegment && server.additionalMovementCount() >= 99;
		if (currentRecorded && scratch.hasMovementSegment) {
			server.addMovementThisTick(scratch.segmentFromX, scratch.segmentFromY, scratch.segmentFromZ, toX, toY, toZ,
			    scratch.segmentRequestedX, scratch.segmentRequestedZ);
		}
		if (server != null && server.movementThisTickPresent) {
			scratch.insideEffects.begin();
			scratch.insideTraversal.begin();
			double requestedX = scratch.segmentRequestedX, requestedZ = scratch.segmentRequestedZ;
			scratch.segmentRequestedX = server.movementRequestedX;
			scratch.segmentRequestedZ = server.movementRequestedZ;
			collectInsideBlocks(state, world, scratch, server.movementFromX, server.movementFromY, server.movementFromZ,
			    server.movementToX, server.movementToY, server.movementToZ, server.movementAxisDependent);
			double lastX = server.movementToX, lastY = server.movementToY, lastZ = server.movementToZ;
			for (int index = 0, count = server.additionalMovementCount(); index < count; index++) {
				ServerPlayerState.Movement movement = server.additionalMovement(index);
				scratch.segmentRequestedX = movement.requestedX();
				scratch.segmentRequestedZ = movement.requestedZ();
				collectInsideBlocks(state, world, scratch, movement.fromX(), movement.fromY(), movement.fromZ(),
				    movement.toX(), movement.toY(), movement.toZ(), true);
				lastX = movement.toX();
				lastY = movement.toY();
				lastZ = movement.toZ();
			}
			scratch.segmentRequestedX = requestedX;
			scratch.segmentRequestedZ = requestedZ;
			if (axisDependent && !currentRecorded) {
				collectInsideBlocks(state, world, scratch, fromX, fromY, fromZ, toX, toY, toZ, true);
				lastX = toX;
				lastY = toY;
				lastZ = toZ;
			}
			double dx = lastX - toX, dy = lastY - toY, dz = lastZ - toZ;
			if (dx * dx + dy * dy + dz * dz > (double) 9.9999994E-11F) {
				collectInsideBlocks(state, world, scratch, lastX, lastY, lastZ, toX, toY, toZ, false);
			}
			server.clearMovementThisTick();
			scratch.insideEffects.applyAndClear(state);
		} else {
			checkInsideBlocks(state, world, scratch, fromX, fromY, fromZ, toX, toY, toZ, axisDependent);
		}
		if (scratch.authority.isServer()) {
			closeServerEffects(scratch.authority.serverState(), world, scratch.authority.survival(), previousFireTicks);
		}
	}

	/**
	 * The tail of {@code Entity.applyEffectsFromBlocks} on a server level,
	 * after {@code applyAndClear}: {@code isInRain} at the player's cell and
	 * the top of its box puts a burning player out, and a player who is not
	 * burning and was not ignited this tick is set to the immune window.
	 */
	private static void closeServerEffects(
	    final ServerPlayerState server, final WorldView world, final Survival survival, final int previousFireTicks) {
		if (server.remainingFireTicks > 0) {
			// Asked only while burning, which is when the answer changes the state.
			int cellX = Mth.floor(server.x);
			int cellZ = Mth.floor(server.z);
			if (world.rainReaches(cellX, Mth.floor(server.y), cellZ, Mth.floor(server.boundingBoxMaxY))) {
				survival.clearFire();
			}
		}
		boolean ignitedThisTick = server.remainingFireTicks > previousFireTicks;
		if (server.remainingFireTicks <= 0 && !ignitedThisTick) {
			server.remainingFireTicks = -ServerPlayerState.FIRE_IMMUNE_TICKS;
		}
	}

	/**
	 * {@code Entity.checkInsideBlocks} and {@code applyAndClear}: the
	 * traversal, gated on whether the span holds any body it could reach.
	 */
	private static void checkInsideBlocks(final PlayerState state, final WorldView world, final Scratch scratch,
	    final double fromX, final double fromY, final double fromZ, final double toX, final double toY,
	    final double toZ, final boolean axisDependent) {
		collectInsideBlocks(state, world, scratch, fromX, fromY, fromZ, toX, toY, toZ, axisDependent, true);
	}

	private static void collectInsideBlocks(final PlayerState state, final WorldView world, final Scratch scratch,
	    final double fromX, final double fromY, final double fromZ, final double toX, final double toY,
	    final double toZ, final boolean axisDependent) {
		collectInsideBlocks(state, world, scratch, fromX, fromY, fromZ, toX, toY, toZ, axisDependent, false);
	}

	/**
	 * One {@code Entity.Movement} of {@code checkInsideBlocks}, traversed only
	 * when its cover holds a body that could write, and on a complete traversal
	 * followed by {@code applyAndClear}.
	 */
	private static void collectInsideBlocks(final PlayerState state, final WorldView world, final Scratch scratch,
	    final double fromX, final double fromY, final double fromZ, final double toX, final double toY,
	    final double toZ, final boolean axisDependent, final boolean completeTraversal) {
		int properties = traversalProperties(state, world, fromX, fromY, fromZ, toX, toY, toZ);
		boolean server = scratch.authority.isServer();
		boolean bubbles = (properties & WorldView.PROPERTY_BUBBLE_COLUMN) != 0;
		boolean powder = (properties & WorldView.PROPERTY_POWDER_SNOW) != 0;
		boolean fluids = (properties & WorldView.PROPERTY_FLUID) != 0;
		// Cobweb, sweet berry bush, honey, and lava cauldron. Only the bubble
		// callbacks drop out for a flying player at this level, because only they
		// are gated on abilities.flying by their *caller* — Player.aiStep. Each
		// other body owns its own gate, and getting that wrong is silent:
		//
		//   cobweb, bush  gated, but inside Player.makeStuckInBlock, so the gate
		//                 belongs in the applier and not here
		//   honey         not gated at all. doSlideMovement calls setDeltaMovement
		//                 directly, so a flying player descending past a honey wall
		//                 does slide
		//   powder snow   gated for its stuck vector, not for FREEZE, which
		//                 PowderSnowBlock.entityInside applies after that branch
		//                 closes — a flying player still accumulates ticksFrozen
		//
		//   lava cauldron not gated; CLEAR_FREEZE is a collected client effect
		//
		// So the traversal runs whenever any inside-effect fact is present, and
		// each applier owns its own gate.
		boolean insideEffects = (properties & WorldView.PROPERTY_INSIDE_EFFECT) != 0;
		// Server-only contact bodies, and on the server's copy the fluid cells
		// too: a lava or water cell the box sweeps through is a visit whose
		// ignition, hit and extinguish only the server's survival state sees.
		boolean contacts = (properties & WorldView.PROPERTY_CONTACT) != 0 || server && fluids;
		// LavaFluid.entityInside collects CLEAR_FREEZE on the client as well, and
		// applyAndClear runs on both sides. The clear writes only when the
		// client's frozen count is non-zero or a FREEZE of this same traversal
		// precedes it, so the client visits its fluid cells exactly then and
		// keeps the ordinary swim tick's traversal off. (A fire block's
		// CLEAR_FREEZE is a contact visit and always traverses.)
		boolean clientFluidFreeze = !server && fluids && (powder || state.ticksFrozen > 0);
		boolean exactSteps = server && world instanceof SnapshotView snapshot && snapshot.hasSourceInsideShapes();
		if (!powder && !insideEffects && !contacts && !clientFluidFreeze && (!bubbles || state.flying)
		    && !(exactSteps && !completeTraversal && scratch.insideEffects.hasPendingHurts())) {
			return;
		}
		if (completeTraversal) {
			scratch.insideEffects.begin();
			scratch.insideTraversal.begin();
		}
		scratch.insideTraversal.traverseRetainingVisited(state, fromX, fromY, fromZ, toX, toY, toZ, axisDependent,
		    (contacts || exactSteps) && server || clientFluidFreeze, world, scratch);
		if (completeTraversal) scratch.insideEffects.applyAndClear(state);
	}

	/**
	 * Reusable detection result for one candidate cell, owned by a traversal.
	 * Detection reads the block and fluid once; application consumes the result
	 * only after traversal accepts the contact. No visited sets or step counters
	 * belong here. A false detection leaves no result to apply.
	 *
	 * <p>Cells without a modeled callback are invisible to this slice. If that
	 * omission makes collected-step grouping ambiguous, the
	 * {@link InsideBlockEffectCollector} refuses.
	 */
	static final class Contact {
		private BlockBehavior behavior;
		private boolean contactBody;
		private FluidSample.Kind fluid;
		private boolean insideBlock;
		private boolean insideFluid;
		/** One fluid cell's box; only its height varies. */
		private final double[] fluidShape = {0.0, 0.0, 0.0, 1.0, 0.0, 1.0};

		boolean find(final PlayerState state, final int x, final int y, final int z, final boolean includeFluids,
		    final WorldView world, final Scratch scratch) {
			this.behavior = world.behaviorAt(x, y, z);
			// The server-only entityInside bodies are the behavior's contact half.
			// A hot floor is a stepOn body and runs from applyStepOn, not from a
			// visit, so a magma block has none.
			this.contactBody = this.behavior.hasContact();
			this.fluid =
			    includeFluids ? EntityFluidInteraction.kindAt(world, x, y, z, scratch) : FluidSample.Kind.EMPTY;
			if (!this.behavior.hasEntityInside() && !this.contactBody && this.fluid == FluidSample.Kind.EMPTY) {
				if (includeFluids && scratch.authority.isServer() && world instanceof SnapshotView snapshot
				    && snapshot.hasSourceInsideShapes()) {
					this.insideBlock = snapshot.sourceInsideReached(state, x, y, z, scratch.insideTraversal);
					this.insideFluid = false;
					return this.insideBlock;
				}
				return false;
			}
			// BubbleColumnBlock keeps the default full-cube
			// getEntityInsideCollisionShape, so `intersectShape == Shapes.block()`
			// short-circuits and insideBlock holds for every visit. WebBlock and
			// SweetBerryBushBlock keep it too, so the same short-circuit carries them:
			// neither overrides the hook, and a visit is therefore unconditional.
			//
			// PowderSnowBlock does not. It overrides the hook to return its own
			// collision shape, which for a bootless player is empty — and therefore
			// the default cube — only while fallDistance stays at or below 2.5.
			// Above that it returns FALLING_COLLISION_SHAPE, a 0.9-high box, the
			// identity short-circuit fails, and vanilla has to ask
			// collidedWithShapeMovingFrom whether the box swept from this
			// sub-movement's origin reached it at all. A box that came to rest on top
			// of the shape did not, so nothing applies and the cell is not even
			// marked visited.
			//
			// Layered cauldrons are inert in the audited client vector. Their server
			// contact requires a source catalog and branch-owned mutation delivery.
			// Lava cauldrons have their own modeled shape and collected effect.
			this.insideBlock = this.behavior.hasEntityInside() || this.contactBody;
			if (!this.behavior.entityInsideShapeReached(state, x, y, z, scratch)) {
				this.insideBlock = false;
			}
			// Entity.collidedWithFluid: the fluid's own box, the cell up to its
			// height, swept from this sub-movement's origin like a shape.
			this.insideFluid = this.fluid != FluidSample.Kind.EMPTY
			    && collidedWithFluidCell(state, x, y, z, scratch, scratch.fluidCell.height());
			return this.insideBlock || this.insideFluid;
		}

		void apply(final PlayerState state, final int x, final int y, final int z, final double minX, final double minY,
		    final double minZ, final double maxX, final double maxY, final double maxZ, final boolean movedFar,
		    final WorldView world, final Scratch scratch) {
			if (this.insideFluid && !this.insideBlock) {
				visitFluid(this.fluid, scratch);
				return;
			}
			// The client-visible body applies immediately; only the collected
			// effects wait for applyAndClear. isPrecise is whether the destination
			// box overlaps the cell, which the bubble column's body reads.
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

	/**
	 * {@code WaterFluid} and {@code LavaFluid.entityInside}: the lava's
	 * CLEAR_FREEZE on both sides, the ignition, hit and extinguish on the
	 * server's copy, whose survival state is the only one they write.
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
	 * {@code Block.stepOn} for the block underfoot, at {@code getOnPosLegacy}:
	 * the behavior's, which on the server's copy deals the hot floor's hit
	 * and refuses a body the slice does not model, and on both sides runs
	 * the slime's damping.
	 */
	private static void applyStepOn(final PlayerState state, final WorldView world, final Scratch scratch) {
		SupportingBlock.getOnPosLegacy(state, world, scratch);
		world.behaviorAt(scratch.support.x, scratch.support.y, scratch.support.z)
		    .stepOn(state, scratch.support.x, scratch.support.y, scratch.support.z, scratch);
	}
}
