# Pre-release review of Stride Simulator

Untracked working document produced by a full-repo review on 2026-09-18. Delete it once the
work is scheduled. Findings were produced by eight parallel reviewers (one per package plus
docs/build and a cross-cutting API audit) and the strongest claims in each were re-verified
by hand against the source. Severity: HIGH = fix before publishing; MEDIUM = fix in the
first public cycle; LOW/NIT = polish.

## 1. Verdict

The engineering core does not need to change. The arithmetic transcription is meticulous
(rounding order, float/double widening points, signed zero, sine-table asymmetry, all
explained and ULP-swept against reference transcriptions); the "refuse, never guess" policy
is applied consistently; the reflection-driven guarantee tests (every field participates in
copy/equality/digest/wire; every phase writes only its declared set; every refusal carries a
cause) are stronger than most public libraries have; build hygiene is exemplary (zero
TODO/suppressions/console output/static mutable state, 100% clang-format conformance,
SHA-pinned CI, reproducible archives, hashed fixtures).

What a senior engineer would notice within minutes is that this is visibly a module cut out
of a larger private system and not yet re-cut for strangers:

1. The Javadoc addresses consumers that do not exist in this repo: "planner", "pilot",
   "adapter", "the search", "arena", "corridor", "oracle", "the recorder", "validation"
   used as a proper noun, "PLAN 19.2", "build/audit-fixes2", "the p3 workload",
   `:block-catalog:checkClassification`, `live-powder-snow-crossing`, and test classes that
   are cited but do not exist. ~40 sentences in main Javadoc, more in tests.
2. The public surface is roughly three times what the root package doc says it is: 174 of
   211 compiled classes are public, there is no module-info or Automatic-Module-Name, and
   the "mechanism" packages (tick, server, block, geometry) with `Scratch`'s 23 public
   fields, `Mth`, and `ServerGamePacketListenerImpl` are de facto API.
3. In-house vocabulary is undefined and overloaded. "boundary" has five meanings,
   "publication" means both directions, "copy" means both `copy()` and "the server's copy of
   the player", "proof"/"certificate"/"evidence" describe a cache, and "admitted", "kernel",
   "pinned", "slice" are never defined anywhere a newcomer would look.
4. Spelling is split inside single public interfaces (`WorldView.behaviourAt` beside
   `WorldView.collisionBehaviorAt`; `BlockBehaviour` beside `CollisionBehavior`;
   `Refusal.UNMODELLED_*` beside README "modeled").
5. Consumer ergonomics at first contact are the weakest part of the API: a 7-boolean
   `PlayerInput` constructor in the README's first sample, 18/19/21-argument `BlockEntry`
   constructors, a `SnapshotView` constructor that silently yields a mutable view, refusal
   exceptions that extend `IllegalStateException` while README says invalid state is a
   separate error class, and an example that is a regression test rather than a tutorial.

Almost all of this is renaming, visibility, and documentation rewriting, not mechanics.

## 2. Decisions only you can make

- **PROVENANCE.md wording.** It publicly states the code is "a behavioral transcription ...
  not a clean-room implementation" of Mojang's mapped source and then says this "is not a
  legal review". Comparable public projects describe themselves as reimplementing the
  mechanics and cite upstream member names for correspondence. Decide deliberately, ideally
  with advice; do not publish the current wording by inertia.
- **Spelling dialect.** Recommendation: American for all project-owned identifiers and prose
  (README, `CollisionBehavior`, `catalog`, `center` already are). The one wrinkle: Mojang's
  own class is spelled `BlockBehaviour`; if you keep that name for correspondence, say so in
  its Javadoc and rename `CollisionBehavior` to match, so the two are never both present.
  `Refusal.UNMODELLED_*` is wire-adjacent; rename it now or never.
- **What "Stride" and "Nettarion" are.** Neither is introduced anywhere. The GitHub URL is
  `ball2jh`, the group is `com.nettarion.stride`. One sentence in README fixes this.
- **Whether `trace` is public API in 0.1.** Nothing in main uses it; it ships seven wire
  formats with no written spec; half of it is recorder/validation tooling. Recommendation:
  keep the read/compare surface public (`Trace`, `TraceCodec`, `TraceComparison`,
  `StateVector`, `StateField`, `Capture`, `WorldSnapshotCodec`, event traces) and move
  `ScheduledTrace`, `SimulationCheckpoint`, `BoundaryCodec`, `BlockStateCatalogCodec`,
  `CaptureFormat` to test scope or an explicitly unstable subpackage.
- **Python as a build dependency.** A zero-dependency Java library requires Python for two
  build steps that are trivially Gradle/Java. Port `verify-fixtures.py` to a Gradle task now;
  consider porting `generate.py` to Java or a template like `ShapeCollision`.

## 3. Ranked change list

### 3.1 Purge stale vocabulary and references (HIGH, mechanical)

Main-source Javadoc/comments referencing removed consumers or artifacts:
- `Simulator.java:20`, `server/ServerTick.java:108`: `live-powder-snow-crossing` capture is not bundled.
- `Simulator.java:54,151`, `SimulationState.java:12`, `Rollout.java:14,19`, `PlayerState.java:266,387`, `ServerPlayerState.java:410`, `Publisher.java:20`, `PlayerInput.java:30`, `DamageWrite.java:13`, `StateDigest.java:32`, `ServerWrite.java:7`, `ServerWriteTimeline.java:10`, `HurtMotion.java:49`, `HurtMotionWrite.java:8`, `PendingServerWriteException.java:16` ("plan"): planner/pilot/adapter/search/route-policy.
- `world/SharedPalette.java:17`, `world/FlatFloorView.java:16`, `world/BlockStateCatalog.java:17`, `world/WorldSnapshot.java:17`, `world/Section.java:12`, `world/WorldView.java:32,538,640`, `world/SnapshotView.java:1278,1375,1680,1831,1881,1920,2086`, `world/PlanningSnapshotRegion` (class name): pilot/adapter/planner/benchmarks/"validation".
- `tick/Move.java:206`, `tick/AiStep.java:202,377,433`, `geometry/CollisionBuffer.java:10`, `templates/ShapeCollision.java.template:98`: "build/audit-fixes2", "p3 workload", "validation's schedule", "the source project".
- `block/package-info.java:19` ("oracle pairing"), `block/UnmodelledBlock.java:13,17` ("second law", "contact log"), `block/CampfireBlock.java:10`, `MagmaBlock.java:10`, `SweetBerryBushBlock.java:15` ("decided by ServerTick" is stale; they call `hurtServer` directly).
- `MovementClasses.java:110-124` and `resources/stride/movement-classes.tsv:4,7,23,26,111`: a build gate, a `WorldCapture` class, "constraint 4", and "Parkour Spiral" that do not exist here.
- `trace/package-info.java:17-18,26-46`, `trace/CaptureFormat.java:13-31,64`, `trace/StateEventTrace.java:31-37`, `trace/TraceProducer.java:7-10`, `trace/ScheduledTrace.java:85-113`, `fields/generate.py:10` and generated `StateFields` Javadoc: recorder/validation/headless/planner.
- `Transition.java:77` mentions an `emittedFrom` parameter that does not exist. `Transition.java:30` cites a test class in API docs.
- `build.gradle:75`: "the upstream project measured".
- Undefined terms to define once in the root package-info or replace: "kernel" (29 main, 148 test uses), "pinned" (35), "admitted"/"admission", "slice", "corridor", "arena", "Two laws".

Tests citing tests that do not exist: `StateDigestCoverageTest.java:14` (`FutureIdentityTest`), `PublisherTest.java:17` (`PublicationParityVanillaTest`), `block/InsideBlockTraversalOrderTest.java:28` (`InsideBlockTraversalVanillaTest`), `server/ServerTickTransactionTest.java:35`, `server/ImpulseWriteTest.java:18` (three more). Plus "vanilla-oracle module", "fuzz suite", "PLAN 19.2", "twenty-nine captures" across `tick/` and `geometry/` tests (see 3.7).

### 3.2 Declare and shrink the public surface (HIGH)

- Add `module-info.java` (or at least `Automatic-Module-Name` in the manifest, which is currently `Manifest-Version: 1.0` only) exporting root, `world`, and the chosen `trace` subset. Reconcile README line 101 (`tick.ClientTick` as a stepping API) with root `package-info.java:38` (subpackages are mechanism).
- Package-private candidates with zero outside callers: `Transition` (root), `Simulator.step` (8 params, in-place), `geometry.ViewCells` (zero main callers anywhere), `world.Section`, `world.WorldIdentity` (or document its one purpose), `block.LayeredCauldronBlock`, `block.BubbleColumnBlock`, `block.InsideBlockTraversal.collidedWithShapeMovingFrom`, `block.InsideBlockEffectCollector.collectFireContact` (public only for one test), `server.ServerEntity`, `server.ServerTick.scratch()/emittedDamageCount()/enableWorldChanges`, `tick.PlayerTick.tick`, `geometry.RetainedSpan.retain/covers`, the `WRITES` string sets on six phase classes and `Survival`/`FoodData`/`BlockEffects`.
- `tick.Scratch`: 16 public mutable scalars (13 undocumented) plus 7 public final buffers, referenced by 28 files. Make intra-tick registers package-private with narrow accessors, or state at class level that all fields are implementation state.
- Dead public API (zero callers in main, tests, or examples): `ServerTick.advanceInPlace/landingHurts/safeFallDistance`, `Survival.calculateFallDamage(double)`, `InsideBlockTraversal.traverse`, `CollisionQuerySpan.fitsInside`, `CollisionBuffer.canonicalFullGroup`, `MovementClasses` (whole class), `SimulationState.viewClient()` (advertised in README, no callers, no test) and `serverDead()`, `StateDigest.chain(long,String)`, `PlayerState.raw(...)` x2, `UnpredictedServerWriteException(int,double)`, `WriteLedger.confirmVelocityCausally` (identical to `observeVelocity`), `TraceCodec.encodeState/decodeState`, `Capture.blockPaletteIndex/fluidPaletteIndex`, `ServerWriteTimeline.before/hurtMotionAfterAction`, `StateFields.*_UNREAD/*_PARTIAL` sets (and the TSV `future` column that feeds them), `ScheduledTrace.main` (a `main` method in a library class).
- Sealing: `PlayerState` should be `sealed permits ServerPlayerState`; `BlockBehaviour` is effectively sealed, declare it; `UnimplementedMechanicException` is the only non-final refusal, make it final and drop the relay constructors (or document who they serve).
- Subpackage types in root signatures contradict the package doc: `AABB.clipCell(..., tick.Scratch)`, `ServerPlayerState.addMovementThisTick(tick.Scratch, ...)`, `PlayerState.*PoseFitCertificate(world.WorldView, ...)`, `UnpredictedServerWriteException.correction()` returning `server.ServerTick.Corrected`.
- Package cycles: geometry imports tick.Scratch and world.WorldView; tick imports server; server imports tick. `geometry/package-info.java:16` claims "nothing here knows the player or the world", which is false.
- `Refuses.TRACE` is a public interface constant; move to a private static.

### 3.3 Consumer ergonomics (HIGH)

- `PlayerInput(boolean x7, float, float)`: keep the record (it is the wire shape) but add factories/withers (`PlayerInput.idle(yaw,pitch).forward().sprint()` or an `EnumSet<Key>` form) and use them in README and the example. Rename `shift` to `sneak` or document why the key name is kept. `yRot/xRot` vs README's "yaw/pitch": say once that these are the upstream field names.
- Exception hierarchy: three refusals extend `IllegalStateException`, one extends `RuntimeException`, while README says invalid state is a *separate* error from a refusal and `PlayerState.requireValidForTransition` throws ISE. Introduce one `abstract sealed class RefusalException extends RuntimeException implements ...` and make all four extend it. Rename `Refuses` (verb as interface name) and `Refusal` (the enum) to `Refusal`/`RefusalCause` or similar; `cause()` also collides with `Throwable.getCause()` semantics. Exception constructors have no Javadoc.
- `Refusal.java:22-24` says message text "is a stable part of the recorded evidence"; README says do not parse messages; eight server tests assert on message substrings. Pick the README's position and fix the tests to assert on `cause()`.
- World construction: five ways to build a `WorldSnapshot`; `BlockEntry` has 18/19/21-arg public constructors (the 19-arg one silently sets `LEGACY_GEOMETRY`, the 6-arg one silently sets `Suffocation.UNKNOWN`, which makes any wall refuse on first contact with a message a newcomer cannot diagnose); `new SnapshotView(snapshot)` yields a mutable view while `SnapshotView.compile` yields a frozen one and neither doc says so. Make the positional constructors private, make `SnapshotView`'s constructor private, promote `BlockEntry`/`FluidEntry`/`ShapeBox` to top level (20+ files write `WorldSnapshot.BlockEntry`; there are two nested `Builder`s), and document the suffocation default loudly. `GettingStarted.java:67` uses `.boxes(FULL_CUBE)` instead of `.fullCube()`, so the canonical example takes the slow general-shape path.
- `Simulator`: `start(...)` duplicates the `SimulationState` constructor; `run` takes `Optional<HurtCause>` as a parameter; `observed()` is an adjective-named factory for an undefined "free-running connection"; `FreezeWrite` is not a `ServerWrite`; `Run` copies states twice; `SimulationState` has three constructors with different null contracts for `pendingHurt`; `hasPendingEffect()` sits beside `pendingHurt()`.
- `ServerPlayerState extends PlayerState` creates overload traps: `copyInto(ServerPlayerState)` overloads rather than overrides, `Simulator.start/run(PlayerState, PlayerState)` resolve on static type and silently reset a server state to full health, `rawEquals` is hidden not overridden. Prefer composition or remove the `PlayerState`-typed server overloads.
- Direct field assignment on `ServerPlayerState` bypasses the dirty tracking that `setSprinting`/`setPose` perform; either compute dirtiness by diffing at sample time or document on each such field.
- `ScheduledSimulation`: 11 nested payload records whose names collide with root types (`Input`, `Health`, `Motion`, `Position`); a boxed `Boolean mayInteract` tri-state (also in `ServerTick.enableWorldChanges`, `ScheduledTrace`); `hurtAction = completedActions - 1` may be -1 at line 59 while `boundary()` clamps to 0 (line 262), an off-by-one at the start of a branch worth checking.
- `GettingStarted.java` is a self-check, not a tutorial: two of three sections verify library invariants and end in `throw`. Restructure as build world, walk and print per tick, sprint-jump, walk out of the region and classify the refusal; move parity checks to a JUnit test. Add a second example for reading a capture.

### 3.4 Naming (MEDIUM unless noted)

- Spelling split (HIGH, see decision above): behaviour 125 / behavior 94 in main; modelled 101 / modeled 0 in main vs README "modeled"; neighbour 15 / neighbor 2; centre/center, honour, licence/license, synchronised, recognised, normalised, quantise all mixed. Also "a implementation" x3 from a mechanical rename (`trace/StateField.java:82`, `StateVector.java:306`, `SharedFlagBits.java:50`).
- Upstream names as public type names: `ServerGamePacketListenerImpl` (the `Impl` is mapping noise; the class models one method), `Mth`, `AiStep`, `ServerEntity` (reads as "server's entity", is the tracker sample), `FoodData`. CONTRIBUTING sanctions upstream *mechanic* names; add one glossary line per type in the package docs, or rename the type and keep the upstream name in the first sentence. `xxa/yya/zza` fields need a plain-English lead-in.
- Overloaded terms: "boundary" (a SimulationState, a Refusal category, an int action index, a Rollout snapshot, a geometric edge); "publication/publish" (client to server, server to client, Rollout.boundary()); "copy" (copy() vs the server's copy of the player); "action/input/tick" for the same index (`HurtMotion.causeTick/writeAfterTick` vs `actionIndex` everywhere else); hit/damage/hurt/mark (`Transaction.hurt()`, `Survival.marked()`, `DamageEvent.marks()`, `HurtCause.marksHurt()`, `pendingHurt`, `hasPendingEffect`); `DamageEvent.refused()` means cooldown-blocked, colliding with project-wide "refusal"; `ServerTick.Cause` vs `HurtCause`; `ServerWriteTimeline` members are all named `event`; "vanilla"/"upstream"/"the source"/"the pinned source"/"native"/"Mojang" for one thing.
- Pseudo-formal cache vocabulary: `hasPoseFitCertificate`, `carryPoseFitCertificate`, `certifyPoseFit`, `certifyCurrentPoseAfterCollision`, "retained proofs" (`Simulator.carry`), "publication evidence" (`Simulator.Step`). These are a memoised collision result and a packet list. Rename to `poseFitCache*`; keep "evidence" only for `SimulationCheckpoint.Evidence`.
- World package: "identity" means three things (`WorldIdentity` counter, `CollisionShapeIdentity` provenance enum, "air identity" name); `OutsideRegion` is a policy not a region and `ROLLOUT_TERMINATING` leaks a runner name into world data; `SectionSummary` is a grid of all sections; `collisionSectionSizeX` names the whole section grid; `frozenFork()` returns `this`; `SupportCell.compareTo` is not Comparable; `Refusal.OUTSIDE_REGION` is used for a missing section inside the region; test classes `SnapshotWorld*Test` test `SnapshotView`.
- Trace package: `Trace` vs `ScheduledTrace` are unrelated; "Checkpoint" is a `byte[]` while the value record is `Evidence`; `BoundaryCodec` serialises a `SimulationState`; `BlockStateCatalogCodec` writes magic `stride-block-source` to a `.catalog` file; `getEnumOrdinal` returns the upstream id; `Capture.write(Path, Capture fixture)`.
- Block package: `UnknownStateBlock` vs `UnmodelledBlock` distinction is well explained but the names do not carry it (`UnrecordedStateBlock`); `MagmaBlock` is a stepOn body admitted via `Contact.HOT_FLOOR` while `SlimeBlock` uses `StepOn.SLIME`; `SweetBerryBushBlock.Age.GROWN` means age > 0; four words for "no body" (`INERT`, `ORDINARY`, `NONE`, `NONE`); upstream `InsideBlockEffectType` names exist only as prose and five booleans.
- Tick: one boolean is `descending`/`shiftDown`/`isShiftKeyDown` across layers; `Move.resolveOwned` collides with README's "owned"; `ViewCells` uses "view" for look direction, colliding with `WorldView`; "Scratch" vs "workspace", "span" vs "retention".
- `PlayerState.Pose.fromVanillaId` is the only "Vanilla" identifier. `Publisher.packetFor` locals `deltaMovementX` are position deltas.

### 3.5 Javadoc (HIGH for wrong facts, MEDIUM for style)

Wrong or stale facts:
- `tick/package-info.java:6-10` states the phase order as BaseTick, AiStep, Travel, Move, PlayerPose, Freezing, then block effects. `PlayerTick.java:82-105` runs BaseTick, AiStep, Travel(Move), BlockEffects, Freezing, tail, PlayerPose.
- `AiStep.java:30`, `BaseTick.java:338`, `PlayerPose.java:315` point at `ClientTick` for things that live in `PlayerTick`. `SupportingBlock.java:247` links `ServerTick`, which never calls it. `Travel.java:19-31` duplicates the echo-timing paragraph at 245-260.
- `block/CampfireBlock.java:11`, `MagmaBlock.java:10`, `SweetBerryBushBlock.java:15`, `UnmodelledBlock.java:17` use `{@link ServerTick}` without importing it; `SlimeBlock.java:15` `{@link Move}`. They pass only because those classes are package-private. `Clearance` is package-private but linked from public docs and renders dead.
- `block/package-info.java:4-7` says behaviours carry speed and jump hooks; they do not. `PowderSnowBlock.java:60-68` describes a stale visibility rule. `WebBlock.java:26-50` documents two classes and says "this is the client".
- `world/package-info.java` has an unused import after the package statement; `server/package-info.java` has three. `world/WorldSnapshot.java:667` references a `BlockBehaviour.StatePredicate` that does not exist. `SnapshotView.java:1800` Javadoc is attached to the wrong method. `world/WorldSnapshot.java:21` says coordinates are stored as raw bits (they are doubles).
- `Trace.java:18-24` documents versions 8 and 9 that the reader refuses. `SharedFlagBits.java:27` says bits 0 and 6 are unnamed two lines after naming them.
- `HealthWrite.applyToDigestedState` writes only `foodLevel`; nothing says the client copy has no health. `HurtMotion.equals` excludes `before*` while the doc says equality includes the binding.

Style:
- 58 Javadoc blocks over 15 lines, 9 over 30. Root `package-info` (74 lines, opens with a 300-word sentence chain, "Two laws bind everything here"), `ClientTick` (58), `ServerTick` (54, the transaction model as narrative), `trace/package-info` (46), `Simulator.step` (42), `ViewCells` (40). These are design essays; rewrite as what/entry points/ownership/refusals and move rationale to `@implNote` or the docs.
- 92 public methods (13%) have no Javadoc; `CheckApiDocs` only checks types and doclint runs with `-missing`. Worst: `CollisionBuffer` 11, `TickAuthority` 8, `Mth` 7 (`floor` is not `Math.floor`), `WorldEventTrace` 6, `StateVector` 6, all four exception constructors, `ServerTick` 4, `WriteLedger` 3. Undocumented public fields: `PlayerState.ticksFrozen`, `ServerPlayerState.health/tickTimer/movementThisTickPresent/awaitingPosition*/currentImpulseImpactPos*/lastKnownClientMovement*`, `Pose.id/width/height/eyeHeight`, `EntityDataWrite.FLAGS/POSE/FROZEN/MOVEMENT_SPEED`, 13 `Scratch` fields, `Section.EDGE/CELLS`, `WorldView.PROPERTY_*`.
- Multi-field declarations under one Javadoc (`ServerPlayerState.java:43,45`), Javadoc separated from its field by a blank line (`PlayerState.java:91-230`, nine sites; `Scratch.java:79`), Markdown `*emphasis*` inside Javadoc (`PlayerState.java:12,551`), `/* */` block comments containing `{@link}` (`PlayerState.java:60-75`), space-indented Javadoc bodies in tab files (`Simulator.java:153-165`, `ScheduledSimulation.java:274`, `ServerTick.java:229`, `ServerGamePacketListenerImpl.java:90`, `BoundaryCodec`, `SimulationCheckpoint`; 1,864 lines total), stray " ." before periods (`WorldSnapshot.java:669`, `SnapshotView.java:1279,1519`, `AiStep.java:187`, `Travel.java:208`), 20 hand-written lines over 120 columns (all comments; clang-format has `ReflowComments: false`).
- Signed-zero idioms `0.0 + x` / `deltaMovementX += 0.0` at `ScheduledSimulation.java:249`, `PlayerPositionWrite.java:19`, `ServerGamePacketListenerImpl.java:64`, `Travel.java:220,237,320,365` have no comment; `PlayerInput.java:41` has the right one. Add a named helper or the comment.
- Refusal conditions are not stated at class level for `Travel`, `AiStep`, `Freezing`, `EntityFluidInteraction` (CONTRIBUTING requires it). `EntityFluidInteraction`'s class doc is one line.

### 3.6 Code structure (MEDIUM)

- God classes: `SnapshotView` (2,587 lines, 105 field declarations, 54 public methods, 200-line constructor, Cursor3D ring walk copy-pasted three times at 947/1285/1509, `>> 4` 44 times while `Section.SHIFT` exists, `1.0E-7` 51 times unnamed); `WorldSnapshot` (1,188, six nested public types); `ServerTick` (723, four overlapping entry families, the "ClientTickEnd + throw on Corrected" block pasted three times at 344/414/456, `Corrected` record with 16 positional components built four times); `WorldView` (51 methods, 12 public bit constants, four telescoping `collectCollisionBoxes` overloads); `ServerGamePacketListenerImpl.handleMovePlayer` (155 lines, three separate rotation installs); `SimulationCheckpoint.value` (361-line instanceof chain that uses Java binary class names as wire tags, so any rename silently corrupts the format: HIGH).
- Parameter explosion: `InsideBlockTraversal.visitBoxInDirection` 21 params, `visit` 15, `CollisionCollector.findSupportingBlock` 15, `collect` 11, `Contact.apply` 13, `BlockBehaviour.of` 6-arg public overload; `BlockEffects.applyEffectsFromBlocks` smuggles the requested vector through `Scratch` fields it temporarily overwrites (lines 144-161).
- Duplicated predicate `world instanceof SnapshotView s && s.hasSourceInsideShapes() && scratch.authority.isServer()` at four sites (`BlockEffects.java:263,306`; `InsideBlockTraversal.java:144,413`) with no comment on why it disables ambiguity tracking. `markTickVisited`/`markSweepVisited` identical; `generalGroupIntersects` two near-identical bodies; `certifiedSpanRevision`/`certifiedSpanVersion` duplicate 12 lines with six `1.0E-7` literals; seven `ServerWrite` records hand-copy `afterConsuming`/`delayedBy` with three message styles; four copies of the all-zero `StateVector` helper and four copies of the reflection "mutate every field" switch in tests.
- Magic numbers: 140 (freeze cap) inline at `Simulator.java:305,372` and `InsideBlockEffectCollector.java:292` while `DEFAULT_TICKS_REQUIRED_TO_FREEZE` exists; 99 (movement tail cap) x3; shared-flag bits spelled three ways across `PlayerState`, `ServerPlayerState`, `EntityDataWrite`, `ServerEntity`; `9.9999994E-11F` at `BlockEffects.java:169` is `1.0E-5F * 1.0E-5F`; `0.9999900000002526^2` at `InsideBlockTraversal.java:187`; `MINIMUM_PACKET_SCALE = 3.051944088384301E-5` is `1.0 / PACKED_MAX`; `0.6F` default friction open-coded 8 times; vanilla constants (`4.0F`, `6.0F`, `80`, `18`, `20`, `40.0F`, `3.0E7`, `0.0045000000000000005`, `0.98F` x8) unnamed beside named siblings.
- Consistency: `ActionSchedule.Operation` mirrors `SimulationEvent.Phase` and converts by `valueOf(name())`; `Simulator.recordStep` takes both a `List` and a `Consumer` that is always `list::add`; `Publisher.inputChanged/sprintingChanged` are per-call outputs stored as hidden state; `FluidSample` has public fields *and* accessors; `ServerPlayerState.requireValid` mixes IAE and ISE; `TickAuthority.requireClient` throws IAE for a state condition; `Difficulty` uses `null` as the peaceful sentinel; two identical process-global `AtomicLong` counters; `WriteLedger` is the only `synchronized` class with no stated reason; `BlockBehaviour.of` couples `owners[]` index order to bit constants implicitly; `ServerTick.beginEvent` and `beginStep` apply different admission rules.
- Dead code and formatter artefacts: `Simulator.published` field used as a local; `ScheduledTrace.java:198-201` dead clears; unused `AABB` import in `BlockEffects.java:3`; `StateVector.java:4` imports `AABB` then uses the FQN; 15 wildcard imports confined to four `trace` files; duplicate identical imports in 7 test files; 67 of 206 files have unsorted imports and the com/java order is split 164/35 (`.clang-format` has `SortIncludes: Never`); `Foo.class ::isInstance` (20 sites) and `()\n-> x` lambda breaks are clang-format's Java output and read as typos; `AABB.java:18` `this.minX<other.maxX&& this.maxX> other.minX`; `DamageEvent.java:18` `public record\n DamageEvent(`; braceless multi-line `if` at `InsideBlockTraversal.java:144-146`; members declared out of order (`ServerTick.java:87` handler above constants, `InsideBlockEffectCollector` methods before constructor, `Rollout.DISCARD` last, `SnapshotView` constants mid-class at 1098/2017); fully-qualified `java.util.Objects/List/Optional` inline in ~15 files.
- `generate.py`: no argparse or `--help`, raw `KeyError`/`IndexError` on bad input with no row numbers, unused `server_again` variable, one 190-line f-string, no `encoding=`/`newline=` (CRLF output on Windows), single-letter names, TSV schema documented in two comment lines. Generated `StateFields` has 349-column lines and ships in the sources jar.

### 3.7 Tests (MEDIUM)

- Names: `CoordinateNumericDomainEvidenceTest`, `CoordinateSnapshotRegionEvidenceTest`, `ClientTickBrakingBoundaryEvidenceTest`, `WorldEvidenceAdmissionTest`, `ShapeCollisionProofTest`, `DryMovementIntervalSourceTest`, `MovementContactBoundaryMatrixSourceTest`, `ReachableYawSupportSourceTest`, `ClientTickSourceDivergenceTest`, `StateEncodingPinTest`, `PhaseContractTest`, `RefusalContractTest`, `*AdmissionTest` x3, and 63 test methods containing evidence/proof/witness/certif. `PlayerActionTest` tests `PlayerInput`; `PlayerStateAdmissionTest` vs `StateAdmissionTest` cover different things; `SnapshotWorld*Test` test `SnapshotView`. Rename to plain behaviour.
- Research prototypes: `DryMovementIntervalSourceTest` (723 lines, ~450 of them a test-only interval-arithmetic composer "under investigation" for a consumer feature) and `TranslationInvarianceTest` (a planner essay plus `System.out` tables around two good assertions). Move out or cut down.
- `MinorCollisionAngleTest` tests a *copy* of the predicate, not `Move.isHorizontalCollisionMinor`, and runs 6M assertions. `UpstreamBoundaryTest.java:95` reflects into a private method. `EchoPhaseMeasurementTest` silently `break`s on refusal for both scenarios and compares only a prefix. `ClientEchoDeliveryTest.java:53-56` asserts both lists are non-empty under the message "both schedules expect the same confirmations". `BlockBehaviourAdmissionTest.aBushOfUnrecordedAge...` asserts a `toString` and identity, not what its name says.
- Working-directory dependence: `RefusalContractTest` and `EchoPhaseMeasurementTest` and `BoundarySchemaTest` load files by relative filesystem path rather than classpath.
- Eight server tests assert on refusal message substrings (README says do not).
- Structure: `SimulatorTest` (607 lines) mixes `Simulator` and `WriteLedger` with no `WriteLedgerTest`; `ServerTickTransactionTest` (1,000 lines) repeats a floor-builder loop eight times; 14 tick tests each re-implement `CompleteWorldView` defaults (~250 lines); tests of root types live in `server/`; the 19-arg positional `BlockEntry` constructor is used in ~15 test files where the builder exists; `assertEquals(true, x)`, `assertEquals(null, x)`, `assertTrue(a == b)` sites; mixed `var`/`.5`/braceless style in `ScheduledSimulationTest`, `ScheduledTraceTest`, `BoundarySchemaTest`; 36 `class` vs 9 `final class`.
- Coverage gaps: `BlockStateCatalog` (zero tests anywhere), `SnapshotView.compile(snapshot, catalog, version)`, `viewClient()`, v11/v12 trace migration, `TraceComparison.Incomparable` variants, `BlockUpdateWrite`, `Difficulty.EASY/HARD` starvation, fire/drowning/freeze gamerules through `permittedBy`, sentinel block refusals (`UnmodelledBlock.stepOn/fallOn`), `SectionGrid`, `WorldSnapshot.crop` of a holed snapshot.

### 3.8 Repo, build, docs (HIGH for README and missing files)

- README: opens with jargon and a disclaimer, no "why you would want this", no glossary for admitted/boundary/refusal/publication/transport schedule/raw-bit trace codec, Stride and Nettarion never introduced, first sample is not self-contained and features the 7-boolean constructor, no CI badge, no versioning statement, no links to changelog/conduct/security/Javadoc. The units paragraph and the stepping-API table are excellent and should be promoted.
- Missing files: `CHANGELOG.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, `.github/ISSUE_TEMPLATE/`, `.github/PULL_REQUEST_TEMPLATE.md`, `.github/dependabot.yml`, `CODEOWNERS`, `captures/README.md`, a Javadoc `overview.html` (the landing page is a bare 7-package table with 77 types in the root listing, no version in the title).
- `LICENSE` copyright is "Stride Simulator contributors" on a single-author day-one project; POM lacks `developers` (Maven Central rejects it); no release/publish/signing workflow; CI is Ubuntu-only while README documents Windows; no `timeout-minutes`/`concurrency`; `python-version: '3.x'` is the one unpinned thing; `clean build` defeats the cache `setup-gradle` configures.
- `build.gradle` mixes 36 tab-led and 71 space-led lines against its own `.editorconfig`; `checkApiDocs` and `verifyFixtures` are `Exec` tasks with no inputs/outputs (never up-to-date, never cached) and resolve the launcher eagerly; the two generators lack `outputs.cacheIf`; toolchain pinned to exactly JDK 25 with no foojay resolver (JDK 26 is current on this machine); `-Werror -Xlint:all` also on tests; `org.gradle.warning.mode=fail` for every local build; comment lines at 161 and 143 columns; redundant `javaLauncher`/`dependsOn` on `runExample`.
- `.gitattributes` should mark `*.catalog` binary and the hashed fixtures `-text`. `.gitignore` lacks `out/`, `bin/`, `.settings/`, `.claude/`. `.editorconfig` lacks `max_line_length`.
- `tools/CheckApiDocs.java` reimplements two Checkstyle checks, swallows parse diagnostics (null listener), reports via stack trace, and skips `examples` and generated sources. `tools/verify-fixtures.py` ignores unlisted files and crashes on missing ones.
- `PROVENANCE.md` and `VALIDATION.md` read as internal memos (private commit hashes, "one diagnostic that previously suggested a command available only in Stride", "oracle"); `provenance.json` exposes private repo layout. Merge into one public "Origin and validation" page. CONTRIBUTING references an ecosystem ("Goal selection, route search ... belong in separate consumers") and has no PR workflow or single-test instructions.

### 3.9 Correctness smells to check before release (flagged, not proven)

- `SimulationCheckpoint.value` wire tags are Java class names (any rename corrupts archives silently).
- `BlockBehaviour.of` line 217: `UNMODELLED`/`UNKNOWN` sentinels refuse every hook family even when other families were captured as ordinary (e.g. landing on a sculk shrieker refuses with the wrong hook named).
- `FarmlandBlock.fallOn` classifies a certain trample (fallDistance >= 1.5) as `PENDING_SERVER_RANDOM`.
- `ScheduledSimulation` `hurtAction = -1` vs `boundary()` clamp to 0 at `completedActions == 0`.
- `RetainedSpan` stores version 0 for `movementFactsImmutable()` views and never rechecks; `SpanReuseTest:80` shows such a view can still be edited.
- `SnapshotView.airIn` refuses on a missing section instead of answering `UNKNOWN` as the contract defines; context-free `findSupportingBlock` substitutes defaults on scaffolding worlds while `collectCollisionBoxes` refuses.
- `ServerPlayerState.setIgnoreFallDamageFromCurrentImpulse(false, ...)` leaves `currentImpulseImpactPosPresent` true; `Math.max(grace, 40)` vs upstream assignment.
- `transact`'s `case AwaitingTeleport` is unreachable from the composed path; the two entry families disagree on whether a pending correction is modelled.
- `TraceCodec` v11/v12 migration is positional on the last four `StateField`s and has no test; `Integer.parseInt` unguarded; `StateVector.toPlayerState` restores pose from the label the class doc says is outside equality; `StateField.ALL` is a public mutable array; `Capture` compact constructor verifies the catalog before null-checking the world and runs verification twice.
- `WorldSnapshot.compose` reports a hole covered by a later part as an "overlap"; `BlockStateCatalog.withSuccessors` picks `getFirst()` arbitrarily; `SharedPalette.blocks()` re-copies after every intern.
- `ServerTick.deliverPublications` reads a tracker sample that may be stale after a refused step.

## 4. Preserve

- `Transition` as the single shared phase body with `Simulator` and `ScheduledSimulation` held bit-equal by test; `SimulationState.takeOwnership`; `Rollout` clearing `loaded` across a refused tick.
- Deferred-message refusals with stack traces gated on a system property; the `Refusal` taxonomy; `ServerWriteTimeline.validate`; digest-bound writes with `StaleServerWriteException`.
- Section sharing by reference with per-section revisions and copy-on-write forks; unknown space never treated as air; three-valued `airIn`; `FluidEntry.EMPTY` identity warning; `RESET_SESSION_DEPENDENT` handling of vanilla's session-cached water shape.
- Every strange literal explained in place (`(double) 0.98F`, `0.025999999F`, `Mth.cos(-a)` table asymmetry, the `acos` band argument, `getFurthestCorner` reproduced-not-repaired); declared write sets per phase plus `PhaseContractTest`; `VanillaCollisionReferenceTest` ULP sweeps; `LargeShapeNeighbourhoodTest` with vacuity controls; negative-control assertions in world tests.
- `BlockBehaviour.of` refusing conflicting fact tuples; sentinels that refuse on contact rather than at compile time; `InsideBlockEffectCollector.beginSweep` refusing ambiguous grouping.
- Raw-bit discipline throughout `trace`; `StateEncodingPinTest` and `StateDigestCoverageTest`; `WorldSnapshotCodec` overflow and trailing-data hardening; the template mechanism with unexpanded-marker failure.
- Zero TODO/suppressions/console output/static mutable state; 100% clang-format conformance; lowercase exception messages; bare-accessor record style; SHA-pinned CI; reproducible archives; hashed fixtures with no personal paths.
