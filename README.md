# Stride Simulator

A deterministic Java library for Minecraft Java Edition **26.2** player movement
and the server mechanics that affect it. It advances explicit player state
through explicit world facts, preserving the arithmetic and ordering of the
modeled mechanics.

The library has **no runtime dependencies** and runs without Minecraft or Fabric.
It includes client/server stepping, collision and block effects, survival and
packet-publication state, explicit transport schedules, and raw-bit trace codecs.
It does not include a pathfinder, bot, mod, game launcher, or world-save importer.

**Status:** experimental, pre-1.0. Unsupported mechanics and missing facts produce
typed refusals. A successful step is conditional on the admitted state and chosen
schedule; it is not a claim of universal Minecraft equivalence.

## Build and try it

Install **JDK 25** and **Python 3**, then run:

```sh
git clone https://github.com/ball2jh/stride-simulator.git
cd stride-simulator
./gradlew clean build
./gradlew runExample
```

On Windows, use `gradlew.bat` and make Python available as `python3` (or set
`-PpythonExecutable=python` on Gradle commands). The wrapper downloads its pinned,
checksum-verified Gradle distribution; no system Gradle is needed. Python is
used only to generate state plumbing and verify fixtures during the build.

The example creates a finite flat world, walks forward, checks fixed/in-place
stepping agreement, and forks an explicit transport simulation. Read its
[complete source](examples/src/main/java/com/nettarion/stride/examples/GettingStarted.java).

Build output:

- `build/libs/stride-simulator-0.1.0-SNAPSHOT.jar`: the library.
- Adjacent `-sources.jar` and `-javadoc.jar`: source and API documentation.
- `build/docs/javadoc/index.html`: browsable API reference.
- `build/reports/tests/test/index.html`: regression results.

No Maven Central artifact is published. To use a locally built version:

```sh
./gradlew publishToMavenLocal
```

In a consuming Gradle project running Java 25 or newer:

```groovy
repositories { mavenLocal() }
dependencies {
    implementation 'com.nettarion.stride:stride-simulator:0.1.0-SNAPSHOT'
}
```

You can also use an included Gradle build or depend directly on the built JAR.
The build has no remote artifact publishing configuration.

## Start with the composed simulator

Given a compiled `SnapshotView world` with declared supporting geometry:

```java
PlayerState player = new PlayerState();
player.placeAt(0.5, 1.0, 0.5); // x/z center and feet height, in blocks
player.onGround = true;       // must agree with the actual world
player.requireValidForTransition();

SimulationState start = new SimulationState(
    player, ServerPlayerState.atBoundary(player), 0);
PlayerInput forward = new PlayerInput(
    true, false, false, false, false, false, false, 0.0F, 0.0F);

Simulator simulator = new Simulator();
Simulator.Step step = simulator.advance(start, forward, world);
PlayerState after = step.state().clientState();
```

The example above uses the classes in `com.nettarion.stride.simulator`.
`ServerPlayerState.atBoundary` deliberately constructs fresh server facts,
including full health, default food, and reset listener state. It does **not**
reconstruct an arbitrary live player's history. For a captured boundary, supply
the observed client, publisher, server, action count, and pending effect.

Positions and collision boxes use blocks; velocity uses blocks per tick.
Yaw and pitch use degrees: yaw zero faces positive Z, positive ninety faces
negative X, and negative pitch looks upward. One fixed step advances one client
action under the declared schedule, nominally one twentieth of a second at the
normal game tick rate. There is no variable timestep or wall-clock simulation.

## Choose a stepping API

| API | Use it for | Ownership |
| --- | --- | --- |
| `Simulator.advance` | Keeping a successor boundary after each action. | Copies the input boundary; returns state and publication evidence. One simulator per thread. |
| `Rollout` | Repeated short sequences with fewer allocations. | Mutates owned working state; reload after a refusal. Call `boundary()` to retain a snapshot. |
| `ScheduledSimulation` + `ActionSchedule` | Explicit client/server ticks and FIFO packet delivery. | Owns queues and world overlays. Fork the runner before speculation; discard a refused branch. |
| `tick.ClientTick` | Studying client kinematics alone. | Mutates a client state. It omits composed server effects and is not a substitute for a full transition. |

`SimulationState` constructors copy their inputs. Normal state accessors return
copies; `viewClient()` is a performance-oriented **borrowed read-only reference**.
Mutating that reference violates the boundary contract. `PlayerState`,
`ServerPlayerState`, `Publisher`, and rollout working views are mutable and must
not be shared between stepping threads.

The fixed and scheduled runners use the same phase arithmetic. Choosing a
schedule is part of defining the problem. Neither runner infers actual network
latency or an unknown server's event order.

## Supply complete world facts

Use `WorldSnapshot.builder` to define a finite region and its palette, then
`SnapshotView.compile` to prepare queries. The runnable example shows a synthetic
air-and-stone palette. Shapes, coefficients, fluid data, and block behavior must
be mutually consistent. A block's name alone is not a complete world description.

For imported observations, use a `BlockStateCatalog` with independently recorded
block facts and the verified capture-reading APIs. Synthetic snapshots are useful
for controlled tests but do not independently establish native-game fidelity.
The trace codecs store simulator observations; they do not read Minecraft Anvil
save files. Unknown cells and out-of-region queries are not silently treated as
air. Choose bounds large enough for the player's body and surrounding queries.

## Handle refusals as outcomes

A refused transition has no valid successor. The main categories are
`UnimplementedMechanicException`, `PendingServerWriteException`,
`UnpredictedServerWriteException`, and `StaleServerWriteException`. They implement
`Refuses`, whose `cause()` supplies a stable `Refusal` enum for classification.
Messages provide detail; do not parse them to identify the category. Stack traces
are disabled by default for these hot-path outcomes; enable them with
`-Dstride.refusal.trace=true` while debugging.

Malformed arguments and structurally invalid state are separate programming or
input errors. Do not broadly swallow every exception as an ordinary search miss.
After a refused in-place step, reload a known boundary; after a refused scheduled
event, discard that branch. No rollback is implied.

## Scope and limits

Implemented mechanisms include collision resolution and stepping, jumping and
sprinting, crawling/swimming poses, water and lava travel, climbables, selected
surface and contact effects, gliding under declared capabilities, and admitted
server movement validation, damage, food, health, and publication effects.
Their exact admission conditions live in the package and type Javadocs and the
[movement-hook inventory](src/main/resources/stride/movement-classes.tsv).
An implemented client mechanism does not imply every server or equipment variant
is supported.

The composed survival slice assumes declared non-peaceful difficulty and food
facts. Arbitrary equipment/enchantments, undisclosed actors, evolving fluids,
unknown world mutations, paused tick rates, and session-ending transitions are
outside the general contract. Some operations model narrower declared variants;
unsupported cases refuse when their facts are required. There is no general
creative-mode, multiplayer-latency, or custom-server equivalence guarantee.

See [VALIDATION.md](VALIDATION.md) for what the tests establish and what still
requires independent native evidence.

## Find your way around the code

| Location under `src/main/java/com/nettarion/stride/simulator` | Responsibility |
| --- | --- |
| Root package | State, actions, runners, schedules, writes, and refusals. Start here as a consumer. |
| `world` | Captured cells, palettes, sections, compiled queries, and explicit world overlays. |
| `tick` | The ordered player tick phases. |
| `block` | Palette-keyed block behaviors and contact traversal. |
| `server` | Server authority, movement admission, survival, and publication. |
| `geometry` | Pinned math, collision buffers, and axis-oriented resolution. |
| `trace` | State vectors, capture/checkpoint codecs, and raw-bit comparisons. |

Each package has an API overview. Generator inputs live in `src/main/fields`
and `src/main/templates`; generated Java belongs in `build/generated` and is
never edited by hand. Tests and their small observation fixtures live under
`src/test`. This repository preserves the simulator's original package names
without depending on the rest of Stride.

## Contributing and license

Read [CONTRIBUTING.md](CONTRIBUTING.md) and [PROVENANCE.md](PROVENANCE.md) before
changing mechanics or making fidelity claims. Project code is licensed under
[MIT](LICENSE); see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for exclusions
and notices. This is not an official Minecraft product.
