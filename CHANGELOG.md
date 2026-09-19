# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Before 1.0, minor versions may break the API;
every such change is listed under the release that made it.

## [Unreleased]

Preparation of the first public release. Everything below is relative to the extracted, unpublished code.

### Added

- `module-info.java`: the module `com.nettarion.stride.simulator` exports the root, `world`, `block`,
  `geometry` and `trace` packages; `tick` and `server` are internal.
- `RefusalException`, a sealed base for every refusal, with `cause()` returning a `RefusalCause`. The four
  refusals (`UnimplementedMechanicException`, `PendingServerWriteException`,
  `UnpredictedServerWriteException`, `StaleServerWriteException`) extend it.
- `PlayerInput.Key` and the factories `PlayerInput.idle`, `of`, `with`, `without`, `looking` and
  `ofPacked`, so an action is built by naming keys instead of positional booleans.
- `MovementCorrection` and `CorrectionReason` in the root package;
  `UnpredictedServerWriteException.correction()` returns the correction the server would issue.
- `OutsidePolicy.REFUSING` for space beyond a snapshot; the capture codec still reads the former
  `ROLLOUT_TERMINATING` spelling.
- Builders for `WorldSnapshot` and `BlockEntry`, including `BlockEntry.Builder.fullCube()` and `solid()`.
- `Interaction` (`ALLOWED`, `DENIED`, `UNDECLARED`) declares whether a scheduled simulation may change the
  world, replacing a boxed tri-state `Boolean`.
- `SharedFlag`, one holder for vanilla's shared-flags bit indices and masks.
- `ServerPlayerState.Difficulty.PEACEFUL`, which the food tick refuses as outside the admitted domain.
- `BlockBehavior.bubbleColumn`, `layeredCauldron` and `flattenShape` factories; the per-block classes
  behind them are package-private.
- `WorldView` extends the new `SpanQueries` interface; `CollisionContext` groups the context arguments of
  the collision and support queries.
- `docs/trace-formats.md`, a specification of every trace, capture and checkpoint format, and
  `src/main/fields/README.md` for the state-field generator.
- Tests: `BlockStateCatalogTest`, `BlockBehaviorRefusalTest`, `WriteLedgerTest`, `WriteStreamTest`,
  `SimulationCheckpointTest`, `BlockStateCatalogCodecTest`, `SectionGridTest`, and split server tests.
- A Javadoc overview page, a tutorial example (`GettingStarted`), a refusal-classification example
  (`HandlingRefusals`), and `CONTRIBUTING`, `SECURITY`, `CODE_OF_CONDUCT`, issue and pull request
  templates.

### Changed

- `BlockEntry`, `FluidEntry`, `ShapeBox`, `OutsidePolicy`, `Suffocation`, `FluidKind` and
  `ShapeProvenance` are top-level types in `world` instead of nested in `WorldSnapshot`.
- American spelling throughout: `BlockBehavior` (was `BlockBehaviour`), `RefusalCause.UNMODELED_*` (was
  `UNMODELLED_*`), `WorldView.behaviorAt`, and the same in prose.
- Renames for clarity: `RefusalCause` (was `Refusal`), `SnapshotBounds` (was `PlanningSnapshotRegion`),
  `ServerMovementListener` (was `ServerGamePacketListenerImpl`), `ScheduledRecording` (was
  `ScheduledTrace`), `UnmodeledBlock`, `Simulator.forObservedConnection()` (was `observed()`),
  `ServerWriteTimeline.writes()`, `Rollout.isLoaded()`, `SimulationState.hasPendingHurt()`,
  `SnapshotView.frozen()` (was `frozenFork()`), the pose-fit cache methods on `PlayerState`
  (`hasCachedPoseFit`, `cachePoseFit`, `carryPoseFitCache`, `clearPoseFitCache`), and
  `PlayerInput.sneak` (was `shift`).
- `Transition` is package-private; the stepping APIs are `Simulator`, `Rollout` and `ScheduledSimulation`.
- `Simulator.run` takes a `SimulationState` (or a client and server state) and a list of actions; the
  `Optional<HurtCause>` and two-`PlayerState` overloads are gone.
- `PlayerState` is sealed to `ServerPlayerState`; `ServerPlayerState.copyInto` is a true override and
  `atBoundary` copies only the movement half of a server state passed as the client.
- `Survival` is `ServerDamage` and `TickAuthority.survival()` is `damage()`; `HurtCause.permittedBy` is
  `allowedByGameRules`; `DamageEvent.refused()` is `blockedByCooldown()`; `UnrecordedStateBlock` (was
  `UnknownStateBlock`) and `WorldView.Contact.UNRECORDED` / `Landing.UNRECORDED` (were `UNKNOWN`; the
  world codec reads the old label); `WorldSnapshot.toDenseCells()` (was `cells()`);
  `SupportCell.compareCell` (was `compareTo`); `SimulationStateCodec` (was `BoundaryCodec`);
  `SimulationCheckpoint.Checkpoint` (was `Evidence`); `StateVector.getEnumId` (was `getEnumOrdinal`);
  `WorldEventTrace.BlockCellEvent` (was `CellStateEvent`); `HurtMotion.applyAfterAction`.
- `ServerWrite` provides `afterConsuming` and `delayedBy` from one abstract `withActionIndex`, returning
  `Optional<ServerWrite>`; `applyUnchecked` replaces `applyToDigestedState` on the writes that skip the
  digest guard.
- `MovementCorrection` groups its components into `Target`, `Resolved` and `Teleport` records.
- `ServerTick.observed()` replaces the `deliverEchoes` and `hurtMotionAfterNextPacket` setters; one
  admission rule runs at the start of every server transaction and clears the tracker sample.
- `FarmlandBlock` refuses a certain trample as `UNMODELED_WORLD_WRITE` and only an uncertain one as
  `PENDING_SERVER_RANDOM`; sentinel behaviors refuse only the hook family whose fact is missing.
- A missing section inside a snapshot's bounds refuses as `UNDECLARED_WORLD_FACT` (was `OUTSIDE_REGION`),
  and `airIn` answers `UNKNOWN` across it; the context-free `findSupportingBlock` refuses on
  context-sensitive worlds as `collectCollisionBoxes` does.
- Scheduled recordings use stable wire tags and natural integer widths (container version 3); traces before
  format 13 are refused instead of migrated.
- The build treats every lint category except `exports` as an error, checks Javadoc on every public member
  of the exported packages, and runs on Linux, Windows and macOS.
- `SnapshotView` is obtained through `SnapshotView.compile`, which returns a frozen view.
- Refusals no longer extend `IllegalStateException`; structurally invalid arguments still throw
  `IllegalArgumentException` or `IllegalStateException`.
- The build no longer needs Python to verify fixtures; `verifyFixtures` is a Gradle task. Python is used only
  by the state-field generator.

### Removed

- The `Refuses` interface and `RefusalCause.UNCLASSIFIED`; every refusal carries a specific cause.
- The relay constructors on the refusal exceptions.
- `Simulator.start(...)` (construct a `SimulationState` directly), `SimulationState.viewClient()` and
  `serverDead()`, and the `MovementClasses` class (the TSV it read remains as documentation).
- The positional `WorldSnapshot` and `BlockEntry` constructors from the public API; use the builders.
- The public `SnapshotView` constructor; use `SnapshotView.compile`.
- Dead or test-only public API: `ServerTick.advanceInPlace`, `landingHurts`, `safeFallDistance`,
  `InsideBlockTraversal.traverse`, `CollisionQuerySpan.fitsInside`, `geometry.ViewCells`,
  `TraceCodec.encodeState`/`decodeState`, `Capture.blockPaletteIndex`/`fluidPaletteIndex`,
  `ServerWriteTimeline.before`/`hurtMotionAfterAction`, `WriteLedger.reset`/`confirmVelocityByCause`/
  `confirmVelocityCausally`, `StateDigest.chain(long, String)`, `ScheduledRecording.main`, and the
  generated `StateFields.*_UNREAD`/`*_PARTIAL` sets.
- `WriteLedger` is no longer `synchronized`; like every other stepping object it is confined to one thread.

[Unreleased]: https://github.com/ball2jh/stride-simulator/commits/main
