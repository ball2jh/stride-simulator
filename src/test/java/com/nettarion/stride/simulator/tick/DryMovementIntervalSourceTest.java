package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.world.CompleteWorldView;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.geometry.CollisionBuffer;
import com.nettarion.stride.simulator.geometry.Mth;
import com.nettarion.stride.simulator.world.FlatFloorView;
import com.nettarion.stride.simulator.world.SupportCell;
import com.nettarion.stride.simulator.world.WorldView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Test-only evidence for an outward-rounded horizontal dry-movement composer.
 *
 * <p>The deliberately small interface starts after a caller has proved the dry
 * phase, the absence of horizontal collision/step/edge behavior, and exhaustive
 * control and coefficient alternatives. It does not infer those facts from an
 * interval. That seam is the result under investigation: arithmetic composition
 * is sound inside a fixed phase schedule; phase discovery remains exact-kernel
 * work.
 */
final class DryMovementIntervalSourceTest {
	private static final double HORIZONTAL_DEAD_ZONE_SQUARED = 9.0E-6;
	private static final double POSITION_COMMIT_SQUARED = 1.0E-7;
	private static final double STABLE_GROUND_DELTA_Y = -0.0784000015258789;
	private static final float ORDINARY_CARDINAL = Float.intBitsToFloat(0x3f7a_e148);
	private static final float ORDINARY_DIAGONAL = Float.intBitsToFloat(0x3f35_04f2);
	private static final float SLOW_FACTOR = 0.4F;
	private static final float[] YAWS = {-179.75F, -90.0F, -37.125F, 0.0F, 61.875F, 179.98438F};

	@Test
	void fixedSchedulesContainExactGroundLaunchAirLandingAndTranslations() {
		ClientTick kernel = new ClientTick();
		WorldView world = FlatFloorView.ordinary(0, -64);
		Random random = new Random(0x2f46_6978_6564L);
		boolean sawGround = false;
		boolean sawLaunch = false;
		boolean sawAir = false;
		boolean sawLanding = false;
		long checkedSuccessors = 0L;
		long collisionUnknowns = 0L;

		for (double translation : new double[] {0.0, 1.0, 1_024.0, 1_048_576.0, 29_999_000.0}) {
			for (int sample = 0; sample < 48; sample++) {
				float yaw = YAWS[(sample + (int) translation) % YAWS.length];
				PlayerState state = stableGrounded(translation);
				state.deltaMovementX = finiteEntryVelocity(random, sample);
				state.deltaMovementZ = finiteEntryVelocity(random, sample + 7);
				state.setSprinting(true);
				HorizontalBox interval = HorizontalBox.point(state);

				for (int tick = 0; tick < 15; tick++) {
					boolean jump = tick == 0;
					PlayerInput action = new PlayerInput(true, false, false, false, jump, false, true, yaw, 0.0F);
					PlayerState entry = state.copy();
					Control control = fixedControl(entry.onGround, true, jump, yaw, LocalDirection.FORWARD);

					kernel.tick(state, action, world);
					Phase phase = phase(entry, state, jump);
					sawGround |= phase == Phase.GROUND;
					sawLaunch |= phase == Phase.LAUNCH;
					sawAir |= phase == Phase.AIR;
					sawLanding |= phase == Phase.LANDING;
					DryTick dryTick =
					    DryTick.fixed(phase, control, entry.onGround ? 0.6F : 1.0F, new float[] {1.0F}, contact(state));
					Composition composed = DryIntervalComposer.step(interval, dryTick);
					if (composed instanceof Unknown unknown) {
						assertEquals(UnknownBranch.HORIZONTAL_COLLISION_OR_STEP, unknown.branch(),
						    "only the per-axis landing clip may interrupt"
						        + " this admitted schedule");
						collisionUnknowns++;
						interval = HorizontalBox.point(state);
					} else {
						interval = ((Bounded) composed).box();
						assertContains(interval, state,
						    "fixed " + phase + " tick " + tick + " sample " + sample + " translation " + translation
						        + " entry(x=" + entry.x + ",vx=" + entry.deltaMovementX + ",z=" + entry.z
						        + ",vz=" + entry.deltaMovementZ + ")");
					}
					checkedSuccessors++;
				}
			}
		}

		assertTrue(sawGround && sawLaunch && sawAir && sawLanding,
		    "the differential schedule must actually traverse every claimed phase");
		assertEquals(3_600L, checkedSuccessors);
		assertTrue(collisionUnknowns > 0, "arbitrary entry components should exercise the honest landing UNKNOWN");
	}

	@Test
	void unknownControlSchedulesContainEveryChosenExactSuccessor() {
		ClientTick kernel = new ClientTick();
		WorldView world = FlatFloorView.ordinary(0, -64);
		Random random = new Random(0x756e_6b6e_6f77_6eL);
		boolean sawLanding = false;
		long checkedSuccessors = 0L;

		for (int sample = 0; sample < 96; sample++) {
			float yaw = YAWS[sample % YAWS.length];
			PlayerState state = stableGrounded(1_024.0);
			// A large arbitrary entry keeps the accumulated unknown-control box on
			// one side of the dead zone for the whole launch/landing horizon.
			state.deltaMovementX = (sample & 1) == 0 ? 8.0 + sample / 16.0 : -8.0 - sample / 16.0;
			state.deltaMovementZ = (sample & 2) == 0 ? 9.0 + sample / 24.0 : -9.0 - sample / 24.0;
			state.sprintWindowTicks = 0;
			HorizontalBox interval = HorizontalBox.point(state);

			for (int tick = 0; tick < 15; tick++) {
				boolean jump = tick == 0;
				LocalDirection chosen = LocalDirection.values()[random.nextInt(LocalDirection.values().length)];
				PlayerInput action = chosen.action(jump, yaw);
				PlayerState entry = state.copy();
				List<Control> exhaustiveControls = controls(entry.onGround, false, jump, yaw);

				kernel.tick(state, action, world);
				Phase phase = phase(entry, state, jump);
				sawLanding |= phase == Phase.LANDING;
				DryTick dryTick = DryTick.unknownControls(
				    phase, exhaustiveControls, entry.onGround ? 0.6F : 1.0F, new float[] {1.0F}, contact(state));
				interval = requireBounded(DryIntervalComposer.step(interval, dryTick),
				    "unknown-control " + phase + " tick " + tick + " sample " + sample);
				assertContains(interval, state, "unknown-control " + phase + " tick " + tick + " sample " + sample);
				checkedSuccessors++;
			}
		}

		assertTrue(sawLanding, "unknown controls must be composed through a landing tick");
		assertEquals(1_440L, checkedSuccessors);
	}

	@Test
	void exhaustiveDestinationFactorsContainAChangedPostMoveFactor() {
		int translation = 1_024;
		WorldView world = new SpeedBoundaryFloor(translation + 1);
		PlayerState state = stableGrounded(translation);
		state.z = translation + 0.75;
		state.placeAt(state.x, state.y, state.z);
		state.deltaMovementZ = 0.3;
		HorizontalBox interval = HorizontalBox.point(state);
		PlayerInput action = new PlayerInput(true, false, false, false, false, false, false, 0.0F, 0.0F);
		PlayerState entry = state.copy();
		Control control = fixedControl(true, false, false, 0.0F, LocalDirection.FORWARD);

		new ClientTick().tick(state, action, world);
		assertTrue(state.z > translation + 1.0, "the exact tick must enter the slow destination strip");
		double noSlowFactorRetained = (entry.deltaMovementZ + control.inputZ) * (double) (0.6F * 0.91F);
		assertTrue(Math.abs(state.deltaMovementZ) < Math.abs(noSlowFactorRetained),
		    "the post-move destination factor must affect retained velocity");

		DryTick dryTick = DryTick.fixed(Phase.GROUND, control, 0.6F, new float[] {1.0F, SLOW_FACTOR}, contact(state));
		interval = requireBounded(DryIntervalComposer.step(interval, dryTick), "destination speed-factor entry");
		assertContains(interval, state, "destination speed-factor entry");
	}

	@Test
	void unresolvedSourceBranchesReturnUnknownInsteadOfChoosingAMidpoint() {
		HorizontalBox point =
		    new HorizontalBox(Interval.point(0.5), Interval.point(0.5), Interval.point(0.25), Interval.point(0.5));
		DryTick ordinary = DryTick.fixed(Phase.AIR, Control.none(), 1.0F, new float[] {1.0F}, Contact.FREE);

		for (UnknownBranch branch : UnknownBranch.environmentalBranches()) {
			DryTick unresolved = ordinary.withUnresolved(branch);
			Unknown unknown = assertInstanceOf(Unknown.class, DryIntervalComposer.step(point, unresolved));
			assertEquals(branch, unknown.branch());
		}

		HorizontalBox deadZoneStraddle = new HorizontalBox(
		    Interval.point(0.5), Interval.point(0.5), new Interval(-0.004, 0.004), Interval.point(0.0));
		assertUnknown(UnknownBranch.DEAD_ZONE_BRANCH, DryIntervalComposer.step(deadZoneStraddle, ordinary));

		double commit = Math.sqrt(POSITION_COMMIT_SQUARED);
		DryTick clipped = DryTick.fixed(
		    Phase.GROUND, new Control(false, 0.0, 0.0, commit, 0.0), 0.6F, new float[] {1.0F}, Contact.VERTICAL_CLIP);
		HorizontalBox stopped =
		    new HorizontalBox(Interval.point(0.5), Interval.point(0.5), Interval.point(0.0), Interval.point(0.0));
		assertUnknown(UnknownBranch.POSITION_COMMIT_BRANCH, DryIntervalComposer.step(stopped, clipped));

		HorizontalBox overflowing = new HorizontalBox(Interval.point(Double.MAX_VALUE), Interval.point(0.5),
		    Interval.point(Double.MAX_VALUE), Interval.point(0.0));
		assertUnknown(UnknownBranch.NONFINITE_ARITHMETIC, DryIntervalComposer.step(overflowing, ordinary));

		for (double boundary : new double[] {Math.nextDown(0.003), 0.003, Math.nextUp(0.003)}) {
			HorizontalBox exactBoundary = new HorizontalBox(
			    Interval.point(0.5), Interval.point(0.5), Interval.point(boundary), Interval.point(0.0));
			assertInstanceOf(Bounded.class, DryIntervalComposer.step(exactBoundary, ordinary),
			    "an exact point can take the source's strict dead-zone branch");
		}
	}

	private static HorizontalBox requireBounded(final Composition result, final String context) {
		if (result instanceof Bounded bounded) {
			return bounded.box();
		}
		Unknown unknown = (Unknown) result;
		return fail(context + " unexpectedly returned UNKNOWN: " + unknown.branch());
	}

	private static void assertUnknown(final UnknownBranch expected, final Composition result) {
		Unknown unknown = assertInstanceOf(Unknown.class, result);
		assertEquals(expected, unknown.branch());
	}

	private static void assertContains(final HorizontalBox interval, final PlayerState exact, final String context) {
		assertTrue(interval.x.contains(exact.x), context + " x " + exact.x + " outside " + interval.x);
		assertTrue(interval.z.contains(exact.z), context + " z " + exact.z + " outside " + interval.z);
		assertTrue(interval.vx.contains(exact.deltaMovementX),
		    context + " vx " + exact.deltaMovementX + " outside " + interval.vx);
		assertTrue(interval.vz.contains(exact.deltaMovementZ),
		    context + " vz " + exact.deltaMovementZ + " outside " + interval.vz);
	}

	private static double finiteEntryVelocity(final Random random, final int sample) {
		return switch (Math.floorMod(sample, 12)) {
			case 0 -> Double.MIN_VALUE;
			case 1 -> -Double.MIN_VALUE;
			case 2 -> Math.nextDown(0.003);
			case 3 -> 0.003;
			case 4 -> Math.nextUp(0.003);
			case 5 -> -Math.nextDown(0.003);
			default -> {
				double significand = 0.5 + random.nextDouble() * 0.5;
				int exponent = random.nextInt(1_027) - 1_022;
				double value = Math.scalb(significand, exponent);
				yield random.nextBoolean() ? value : -value;
			}
		};
	}

	private static PlayerState stableGrounded(final double translation) {
		PlayerState state = new PlayerState();
		state.placeAt(translation + 0.5, 0.0, translation + 0.5);
		state.onGround = true;
		state.mainSupportingBlockPosPresent = true;
		state.mainSupportingBlockPosX = Mth.floor(state.x);
		state.mainSupportingBlockPosY = -1;
		state.mainSupportingBlockPosZ = Mth.floor(state.z);
		state.deltaMovementY = STABLE_GROUND_DELTA_Y;
		return state;
	}

	private static Phase phase(final PlayerState entry, final PlayerState successor, final boolean jump) {
		if (entry.onGround && jump) {
			return Phase.LAUNCH;
		}
		if (!entry.onGround && successor.onGround) {
			return Phase.LANDING;
		}
		return entry.onGround ? Phase.GROUND : Phase.AIR;
	}

	private static Contact contact(final PlayerState successor) {
		return successor.verticalCollision ? Contact.VERTICAL_CLIP : Contact.FREE;
	}

	private static List<Control> controls(
	    final boolean grounded, final boolean sprinting, final boolean jump, final float yaw) {
		List<Control> controls = new ArrayList<>(LocalDirection.values().length);
		for (LocalDirection direction : LocalDirection.values()) {
			controls.add(fixedControl(grounded, sprinting, jump, yaw, direction));
		}
		return List.copyOf(controls);
	}

	private static Control fixedControl(final boolean grounded, final boolean sprinting, final boolean jump,
	    final float yaw, final LocalDirection direction) {
		float speed;
		if (grounded) {
			double movementSpeed = (double) 0.1F;
			if (sprinting) {
				movementSpeed *= 1.0 + (double) 0.3F;
			}
			speed = (float) movementSpeed;
		} else {
			speed = sprinting ? 0.025999999F : 0.02F;
		}

		double inputX = direction.localX;
		double inputZ = direction.localZ;
		inputX *= speed;
		inputZ *= speed;
		float radians = yaw * (float) (Math.PI / 180.0);
		float sin = Mth.sin(radians);
		float cos = Mth.cos(radians);
		double worldX = inputX * cos - inputZ * sin;
		double worldZ = inputZ * cos + inputX * sin;

		if (grounded && jump && sprinting) {
			double boostX = -sin * 0.2;
			double boostZ = cos * 0.2;
			return new Control(true, boostX, boostZ, worldX, worldZ);
		}
		return new Control(false, 0.0, 0.0, worldX, worldZ);
	}

	private enum Phase { GROUND, LAUNCH, AIR, LANDING }

	private enum Contact {
		/** No collision: Entity.move's second movement-segment clause commits. */
		FREE,
		/** A vertical-only collision: horizontal norm must prove the first clause. */
		VERTICAL_CLIP
	}

	private enum LocalDirection {
		IDLE(0.0F, 0.0F, false, false, false, false),
		FORWARD(0.0F, ORDINARY_CARDINAL, true, false, false, false),
		BACKWARD(0.0F, -ORDINARY_CARDINAL, false, true, false, false),
		LEFT(ORDINARY_CARDINAL, 0.0F, false, false, true, false),
		RIGHT(-ORDINARY_CARDINAL, 0.0F, false, false, false, true),
		FORWARD_LEFT(ORDINARY_DIAGONAL, ORDINARY_DIAGONAL, true, false, true, false),
		FORWARD_RIGHT(-ORDINARY_DIAGONAL, ORDINARY_DIAGONAL, true, false, false, true),
		BACKWARD_LEFT(ORDINARY_DIAGONAL, -ORDINARY_DIAGONAL, false, true, true, false),
		BACKWARD_RIGHT(-ORDINARY_DIAGONAL, -ORDINARY_DIAGONAL, false, true, false, true);

		private final float localX;
		private final float localZ;
		private final boolean forward;
		private final boolean backward;
		private final boolean left;
		private final boolean right;

		LocalDirection(final float localX, final float localZ, final boolean forward, final boolean backward,
		    final boolean left, final boolean right) {
			this.localX = localX;
			this.localZ = localZ;
			this.forward = forward;
			this.backward = backward;
			this.left = left;
			this.right = right;
		}

		PlayerInput action(final boolean jump, final float yaw) {
			return new PlayerInput(this.forward, this.backward, this.left, this.right, jump, false, false, yaw, 0.0F);
		}
	}

	private record Control(boolean boostPresent, double boostX, double boostZ, double inputX, double inputZ) {
		static Control none() {
			return new Control(false, 0.0, 0.0, 0.0, 0.0);
		}
	}

	private record DryTick(Phase phase, List<Control> controls, float entryFriction, float[] destinationSpeedFactors,
	    Contact contact, EnumSet<UnknownBranch> unresolved) {
		DryTick {
			controls = List.copyOf(controls);
			destinationSpeedFactors = destinationSpeedFactors.clone();
			unresolved = unresolved.clone();
		}

		static DryTick fixed(final Phase phase, final Control control, final float entryFriction,
		    final float[] destinationSpeedFactors, final Contact contact) {
			return new DryTick(phase, List.of(control), entryFriction, destinationSpeedFactors, contact,
			    EnumSet.noneOf(UnknownBranch.class));
		}

		static DryTick unknownControls(final Phase phase, final List<Control> controls, final float entryFriction,
		    final float[] destinationSpeedFactors, final Contact contact) {
			return new DryTick(
			    phase, controls, entryFriction, destinationSpeedFactors, contact, EnumSet.noneOf(UnknownBranch.class));
		}

		DryTick withUnresolved(final UnknownBranch branch) {
			return new DryTick(this.phase, this.controls, this.entryFriction, this.destinationSpeedFactors,
			    this.contact, EnumSet.of(branch));
		}
	}

	private enum UnknownBranch {
		NON_DRY_MODE,
		FLYING_OR_FALL_FLYING,
		EXTERNAL_VELOCITY_WRITE,
		STUCK_CLIMB_POWDER_OR_BOUNCE,
		LEDGE_BACKOFF,
		HORIZONTAL_COLLISION_OR_STEP,
		UNRESOLVED_PHASE_OR_SUPPORT,
		UNRESOLVED_ENTRY_FRICTION,
		INCOMPLETE_CONTROL_OR_LATCH_SET,
		INCOMPLETE_DESTINATION_SPEED_FACTOR_SET,
		DESTINATION_CELL_OR_SUPPORT_BRANCH,
		DEAD_ZONE_BRANCH,
		POSITION_COMMIT_BRANCH,
		NONFINITE_ARITHMETIC;

		static List<UnknownBranch> environmentalBranches() {
			return List.of(NON_DRY_MODE, FLYING_OR_FALL_FLYING, EXTERNAL_VELOCITY_WRITE, STUCK_CLIMB_POWDER_OR_BOUNCE,
			    LEDGE_BACKOFF, HORIZONTAL_COLLISION_OR_STEP, UNRESOLVED_PHASE_OR_SUPPORT, UNRESOLVED_ENTRY_FRICTION,
			    INCOMPLETE_CONTROL_OR_LATCH_SET, INCOMPLETE_DESTINATION_SPEED_FACTOR_SET,
			    DESTINATION_CELL_OR_SUPPORT_BRANCH);
		}
	}

	private sealed interface Composition permits Bounded, Unknown {}

	private record Bounded(HorizontalBox box) implements Composition {}

	private record Unknown(UnknownBranch branch) implements Composition {}

	private record HorizontalBox(Interval x, Interval z, Interval vx, Interval vz) {
		static HorizontalBox point(final PlayerState state) {
			return new HorizontalBox(Interval.point(state.x), Interval.point(state.z),
			    Interval.point(state.deltaMovementX), Interval.point(state.deltaMovementZ));
		}

		HorizontalBox hull(final HorizontalBox other) {
			return new HorizontalBox(
			    this.x.hull(other.x), this.z.hull(other.z), this.vx.hull(other.vx), this.vz.hull(other.vz));
		}
	}

	private record Interval(double lower, double upper) {
		Interval {
			if (Double.isNaN(lower) || Double.isNaN(upper) || lower > upper) {
				throw new IllegalArgumentException("invalid interval " + lower + ".." + upper);
			}
		}

		static Interval point(final double value) {
			return new Interval(value, value);
		}

		boolean isPoint() {
			return Double.doubleToRawLongBits(this.lower) == Double.doubleToRawLongBits(this.upper);
		}

		boolean isZero() {
			return this.lower == 0.0 && this.upper == 0.0;
		}

		boolean contains(final double value) {
			return this.lower <= value && value <= this.upper;
		}

		Interval hull(final Interval other) {
			return new Interval(Math.min(this.lower, other.lower), Math.max(this.upper, other.upper));
		}
	}

	private static final class DryIntervalComposer {
		private DryIntervalComposer() {}

		static Composition step(final HorizontalBox start, final DryTick tick) {
			if (!tick.unresolved.isEmpty()) {
				return new Unknown(tick.unresolved.iterator().next());
			}
			if (tick.controls.isEmpty()) {
				return new Unknown(UnknownBranch.INCOMPLETE_CONTROL_OR_LATCH_SET);
			}
			if (tick.destinationSpeedFactors.length == 0) {
				return new Unknown(UnknownBranch.INCOMPLETE_DESTINATION_SPEED_FACTOR_SET);
			}
			if (!finite(start) || !Float.isFinite(tick.entryFriction)) {
				return new Unknown(UnknownBranch.NONFINITE_ARITHMETIC);
			}

			HorizontalBox snapped = applyDeadZone(start);
			if (snapped == null) {
				return new Unknown(UnknownBranch.DEAD_ZONE_BRANCH);
			}
			float retention = tick.entryFriction * 0.91F;
			if (!Float.isFinite(retention)) {
				return new Unknown(UnknownBranch.NONFINITE_ARITHMETIC);
			}

			HorizontalBox result = null;
			for (Control control : tick.controls) {
				HorizontalBox controlled = applyControl(snapped, control);
				if (controlled == null) {
					return new Unknown(UnknownBranch.NONFINITE_ARITHMETIC);
				}
				if (tick.contact == Contact.VERTICAL_CLIP
				    && (!collisionAxisSafe(controlled.vx) || !collisionAxisSafe(controlled.vz))) {
					return new Unknown(UnknownBranch.HORIZONTAL_COLLISION_OR_STEP);
				}
				if (tick.contact == Contact.VERTICAL_CLIP && (!controlled.vx.isZero() || !controlled.vz.isZero())) {
					NormBounds movedNorm = squaredNorm(controlled.vx, controlled.vz);
					if (movedNorm == null || !(movedNorm.lower > POSITION_COMMIT_SQUARED)) {
						return new Unknown(UnknownBranch.POSITION_COMMIT_BRANCH);
					}
				}

				Interval nextX = add(controlled.x, controlled.vx);
				Interval nextZ = add(controlled.z, controlled.vz);
				if (nextX == null || nextZ == null) {
					return new Unknown(UnknownBranch.NONFINITE_ARITHMETIC);
				}
				for (float speedFactor : tick.destinationSpeedFactors) {
					if (!Float.isFinite(speedFactor)) {
						return new Unknown(UnknownBranch.NONFINITE_ARITHMETIC);
					}
					Interval factoredX = multiply(controlled.vx, (double) speedFactor);
					Interval factoredZ = multiply(controlled.vz, (double) speedFactor);
					Interval nextVx = factoredX == null ? null : multiply(factoredX, (double) retention);
					Interval nextVz = factoredZ == null ? null : multiply(factoredZ, (double) retention);
					if (nextVx == null || nextVz == null) {
						return new Unknown(UnknownBranch.NONFINITE_ARITHMETIC);
					}
					HorizontalBox branch = new HorizontalBox(nextX, nextZ, nextVx, nextVz);
					result = result == null ? branch : result.hull(branch);
				}
			}
			return new Bounded(result);
		}

		private static HorizontalBox applyDeadZone(final HorizontalBox start) {
			if (start.vx.isPoint() && start.vz.isPoint()) {
				double x = start.vx.lower;
				double z = start.vz.lower;
				if (x * x + z * z < HORIZONTAL_DEAD_ZONE_SQUARED) {
					return new HorizontalBox(start.x, start.z, Interval.point(0.0), Interval.point(0.0));
				}
				return start;
			}
			NormBounds norm = squaredNorm(start.vx, start.vz);
			if (norm == null) {
				return null;
			}
			if (norm.upper < HORIZONTAL_DEAD_ZONE_SQUARED) {
				return new HorizontalBox(start.x, start.z, Interval.point(0.0), Interval.point(0.0));
			}
			if (norm.lower >= HORIZONTAL_DEAD_ZONE_SQUARED) {
				return start;
			}
			return null;
		}

		private static HorizontalBox applyControl(final HorizontalBox start, final Control control) {
			if (!Double.isFinite(control.boostX) || !Double.isFinite(control.boostZ) || !Double.isFinite(control.inputX)
			    || !Double.isFinite(control.inputZ)) {
				return null;
			}
			Interval vx = start.vx;
			Interval vz = start.vz;
			if (control.boostPresent) {
				vx = add(vx, Interval.point(control.boostX));
				vz = add(vz, Interval.point(control.boostZ));
				if (vx == null || vz == null) {
					return null;
				}
			}
			vx = add(vx, Interval.point(control.inputX));
			vz = add(vz, Interval.point(control.inputZ));
			return vx == null || vz == null ? null : new HorizontalBox(start.x, start.z, vx, vz);
		}

		private static Interval add(final Interval left, final Interval right) {
			double lower = left.lower + right.lower;
			double upper = left.upper + right.upper;
			if (!Double.isFinite(lower) || !Double.isFinite(upper)) {
				return null;
			}
			if (left.isPoint() && right.isPoint()) {
				return Interval.point(lower);
			}
			return new Interval(Math.nextDown(lower), Math.nextUp(upper));
		}

		private static Interval multiply(final Interval interval, final double scalar) {
			double first = interval.lower * scalar;
			double second = interval.upper * scalar;
			if (!Double.isFinite(first) || !Double.isFinite(second)) {
				return null;
			}
			if (interval.isPoint()) {
				return Interval.point(first);
			}
			return new Interval(Math.nextDown(Math.min(first, second)), Math.nextUp(Math.max(first, second)));
		}

		private static NormBounds squaredNorm(final Interval x, final Interval z) {
			double minX = minimumAbsolute(x);
			double minZ = minimumAbsolute(z);
			double maxX = maximumAbsolute(x);
			double maxZ = maximumAbsolute(z);
			double lower = minX * minX + minZ * minZ;
			double upper = maxX * maxX + maxZ * maxZ;
			if (Double.isNaN(lower) || Double.isNaN(upper)) {
				return null;
			}
			return new NormBounds(Math.nextDown(lower), Math.nextUp(upper));
		}

		private static double minimumAbsolute(final Interval interval) {
			if (interval.lower <= 0.0 && interval.upper >= 0.0) {
				return 0.0;
			}
			return Math.min(Math.abs(interval.lower), Math.abs(interval.upper));
		}

		private static double maximumAbsolute(final Interval interval) {
			return Math.max(Math.abs(interval.lower), Math.abs(interval.upper));
		}

		private static boolean collisionAxisSafe(final Interval interval) {
			return interval.isZero() || minimumAbsolute(interval) > 1.0E-7;
		}

		private static boolean finite(final HorizontalBox box) {
			return Double.isFinite(box.x.lower) && Double.isFinite(box.x.upper) && Double.isFinite(box.z.lower)
			    && Double.isFinite(box.z.upper) && Double.isFinite(box.vx.lower) && Double.isFinite(box.vx.upper)
			    && Double.isFinite(box.vz.lower) && Double.isFinite(box.vz.upper);
		}
	}

	private record NormBounds(double lower, double upper) {}

	/** Infinite ordinary floor whose support speed factor changes at one Z cell. */
	private static final class SpeedBoundaryFloor implements CompleteWorldView {
		private final int slowFromZ;

		SpeedBoundaryFloor(final int slowFromZ) {
			this.slowFromZ = slowFromZ;
		}

		@Override
		public long collisionVersion() {
			return 0L;
		}

		@Override
		public boolean movementFactsImmutable() {
			return true;
		}

		@Override
		public void collectCollisionBoxes(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final CollisionBuffer target) {
			target.clear();
			int x0 = Mth.floor(minX);
			int x1 = Mth.floor(Math.nextDown(maxX));
			int y0 = Math.max(-64, Mth.floor(minY));
			int y1 = Math.min(-1, Mth.floor(maxY));
			int z0 = Mth.floor(minZ);
			int z1 = Mth.floor(Math.nextDown(maxZ));
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					for (int x = x0; x <= x1; x++) {
						target.add(x, y, z, x + 1.0, y + 1.0, z + 1.0);
					}
				}
			}
		}

		@Override
		public boolean suffocatesAt(
		    final int cellX, final int cellZ, final double boundingBoxMinY, final double boundingBoxMaxY) {
			if (boundingBoxMinY < 0.0) {
				throw new UnimplementedMechanicException("test player entered the speed-factor floor");
			}
			return false;
		}

		@Override
		public void findSupportingBlock(final double minX, final double minY, final double minZ, final double maxX,
		    final double maxY, final double maxZ, final double atX, final double atY, final double atZ,
		    final SupportCell target) {
			target.set(Mth.floor(atX), -1, Mth.floor(atZ));
		}

		@Override
		public float friction(final int x, final int y, final int z) {
			return 0.6F;
		}

		@Override
		public float speedFactor(final int x, final int y, final int z) {
			return y < 0 && z >= this.slowFromZ ? SLOW_FACTOR : 1.0F;
		}

		@Override
		public float jumpFactor(final int x, final int y, final int z) {
			return 1.0F;
		}

		@Override
		public boolean hasClimbables() {
			return false;
		}

		@Override
		public boolean hasNonDefaultFriction() {
			return false;
		}

		@Override
		public boolean hasNonDefaultSpeedFactor() {
			return true;
		}

		@Override
		public boolean hasNonDefaultJumpFactor() {
			return false;
		}

		@Override
		public int propertiesIn(
		    final int wanted, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
			return wanted & PROPERTY_SPEED_FACTOR;
		}

		@Override
		public boolean hasChunkAt(final int x, final int z) {
			return true;
		}

		@Override
		public int minY() {
			return -64;
		}
	}
}
