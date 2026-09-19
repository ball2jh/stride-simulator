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
- Builders for `WorldSnapshot` and `BlockEntry`, including `BlockEntry.Builder.fullCube()`.
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

[Unreleased]: https://github.com/ball2jh/stride-simulator/commits/main
