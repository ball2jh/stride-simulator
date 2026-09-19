# Stride Simulator

[![Build](https://github.com/ball2jh/stride-simulator/actions/workflows/build.yml/badge.svg)](https://github.com/ball2jh/stride-simulator/actions/workflows/build.yml)

Deterministic Minecraft Java Edition 26.2 player movement, and the server mechanics that affect it, as a
dependency-free Java library.

Stride Simulator advances an explicit player state through an explicit description of the world, one client
tick at a time, reproducing vanilla's movement arithmetic and phase order bit for bit. It models collision
and stepping, jumping and sprinting, swimming and crawling poses, water and lava, climbables, block contact
effects, gliding, and the server side of movement: packet admission and corrections, damage, food and
health. It runs without Minecraft, Fabric or any other dependency, and it never guesses: a state, world or
mechanic it does not model is refused with a typed exception rather than answered approximately.

Use it to verify a route or a tool-assisted run against the real physics, to research movement (how far
does a sprint-jump from this block carry, at this angle?), to test movement logic headlessly in CI, or as
the physics core beneath a bot or route-search tool that you write. The library is the simulator alone: it contains
no pathfinder, bot, mod, launcher or world-save importer, and those belong in separate consumers built on
top of it. It was extracted from Stride, the private movement-planning project it grew up in; Nettarion is
the author's namespace, which is why the Maven group is `com.nettarion.stride`.

## Status

Pre-1.0: the API may change between minor versions. A green build proves that the modeled mechanics reproduce vanilla on the scenarios the
tests cover, and that the two bundled captures of a live client replay bit for bit (the elytra capture up
to the durability write the library does not model). It does not prove equivalence for every Minecraft state,
server or network schedule: a returned state is a result under the facts you supplied, and a refusal means
those facts left what the library models, not that the movement is impossible.

## Quick start

You need JDK 25. The Gradle wrapper downloads Gradle itself and can download the JDK through the foojay
toolchain resolver; Python 3 is needed only for the state-field generator that runs during the build.

```sh
git clone https://github.com/ball2jh/stride-simulator.git
cd stride-simulator
./gradlew build         # compiles, tests, builds Javadoc, checks docs and fixtures, runs the examples
./gradlew runExample    # the tutorial below, with per-tick output
```

On Windows use `gradlew.bat`; if Python is installed as `python` rather than `python3`, add
`-PpythonExecutable=python`.

The build writes `build/libs/stride-simulator-0.1.0-SNAPSHOT.jar` with its `-sources` and `-javadoc`
JARs, the API reference at `build/docs/javadoc/index.html`, and test reports under `build/reports/tests/`.
No Maven Central artifact is published yet; install to your local repository and depend on it:

```sh
./gradlew publishToMavenLocal
```

```groovy
repositories { mavenLocal() }
dependencies { implementation 'com.nettarion.stride:stride-simulator:0.1.0-SNAPSHOT' }
```

## First example

A flat stone floor, a player walking forward for twenty ticks, and the refusal that ends the walk at the
edge of the described region. Every type is in `com.nettarion.stride.simulator` or its `world` subpackage.

```java
// 1. Describe the world: a 16 x 12 x 32 block region whose lowest corner is (-8, -2, -8), with a stone
//    floor at y = 0. Air is declared like any other block; undeclared space is never treated as air.
BlockEntry air = BlockEntry.builder(0, "minecraft:air").build();
BlockEntry stone = BlockEntry.builder(1, "minecraft:stone")
    .fullCube()                    // the collision shape of an ordinary solid block
    .suffocation(Suffocation.YES)  // the default, UNKNOWN, refuses once the body reaches the block
    .build();
WorldSnapshot.Builder builder = WorldSnapshot.builder(OutsidePolicy.REFUSING, -8, -2, -8, 16, 12, 32)
    .palette(air, stone);          // cells refer to palette indices: 0 is air, 1 is stone
for (int x = -8; x < 8; x++) {
    for (int z = -8; z < 24; z++) {
        builder.set(x, 0, z, 1);
    }
}
SnapshotView world = SnapshotView.compile(builder.build()); // frozen, safe to share between threads

// 2. Place the player. x and z are the center of the body and y the feet, in blocks.
PlayerState client = new PlayerState();
client.placeAt(0.5, 1.0, 0.5);
client.onGround = true;            // must agree with the world
client.requireValidForTransition();
SimulationState state = new SimulationState(client, ServerPlayerState.atBoundary(client), 0);

// 3. Walk forward for 20 ticks. Yaw 0 looks along +z.
Simulator simulator = new Simulator();
PlayerInput forward = PlayerInput.of(0.0F, 0.0F, PlayerInput.Key.FORWARD);
for (int tick = 0; tick < 20; tick++) {
    state = simulator.advance(state, forward, world).state();
}
PlayerState after = state.clientState();
System.out.printf("after %d ticks: z=%.6f onGround=%b%n", state.completedActions(), after.z, after.onGround);

// 4. Keep walking until a collision query leaves the region.
try {
    while (true) {
        state = simulator.advance(state, forward, world).state();
    }
} catch (RefusalException refusal) {
    System.out.println(refusal.cause()); // OUTSIDE_REGION; `state` is still the last valid boundary
}
```

`./gradlew runExample` runs the longer version of this in
[`GettingStarted.java`](examples/src/main/java/com/nettarion/stride/examples/GettingStarted.java), which
also sprint-jumps, prints velocities and forks a scheduled simulation;
[`HandlingRefusals.java`](examples/src/main/java/com/nettarion/stride/examples/HandlingRefusals.java)
classifies every refusal cause.

## Concepts

- **Vanilla**: Minecraft Java Edition 26.2's own implementation, the reference for every mechanic here.
  Comments name the vanilla class and member each part corresponds to.
- **Admitted domain**: the states, worlds and mechanics the simulator computes. A bare survival player at the
  normal tick rate, on declared blocks, with declared food and difficulty facts, is admitted; unsupported
  equipment, undeclared actors, evolving fluids, creative flight packets and death are not.
- **Refusal**: a transition the simulator declines to compute because something left the admitted domain.
  Every refusal is a `RefusalException` carrying one stable `RefusalCause`.
- **Boundary**: a `SimulationState`, the immutable state between two actions: the client's `PlayerState`,
  the server's `ServerPlayerState`, the client's `Publisher` and the packets in flight.
- **Action, step, transition, tick**: an action is one `PlayerInput`; the action index counts completed
  actions. A step is one `Simulator.advance`. The transition is the composed sequence of client and server
  phases one step performs. A tick is one client or server game tick.
- **Publish and deliver**: the client publishes its movement packet to the server; the server delivers
  writes (a `ServerWrite`: health, velocity, position, entity data, block updates) back to the client.
- **Snapshot, view, palette, catalog**: a `WorldSnapshot` is a finite region of cells indexing a palette of
  `BlockEntry` facts (shape, friction, suffocation, contact behavior); a `SnapshotView` is the compiled,
  frozen form the simulator queries; a `BlockStateCatalog` holds independently recorded block facts that a
  captured world is verified against.
- **Capture and trace**: a capture is a bundle of trace files recorded from a client, the simulator or another
  implementation: a per-tick state trace, the world, and event sidecars. Traces are bit-exact, so two
  implementations can be compared field by field.
- **Digest**: the raw-bit hash of a state, used to compare states and to detect a write applied to a state
  it was not derived from.

### Units and conventions

Positions and collision boxes are in blocks; velocity is in blocks per tick. Yaw and pitch are in degrees
with vanilla's convention: yaw 0 faces +z, yaw 90 faces -x, and a negative pitch looks upward. One step is
one client tick at 20 Hz, a twentieth of a second; there is no variable timestep or wall clock. Field
names on `PlayerState`, `ServerPlayerState` and `PlayerInput` (`yRot`, `xRot`, `deltaMovementX`, `xxa`,
`zza`) are vanilla's own, so a reader can find the corresponding member.

## Choosing a stepping API

| API | Use it for | Ownership |
| --- | --- | --- |
| `Simulator.advance` | One action at a time, keeping every boundary. | Copies its input and returns a new `SimulationState` with the writes and confirmations of that step. One `Simulator` per thread. |
| `Rollout` | Many short sequences with few allocations. | Steps one owned working copy in place; `boundary()` publishes a copy. Reload after a refusal. |
| `ScheduledSimulation` + `ActionSchedule` | Explicit client and server ticks with FIFO packet delivery. | Owns its queues and both world overlays. `fork()` before speculating and discard a refused branch. |

All three run the same transition, so they agree bit for bit; choosing a schedule is part of defining the
problem, and none of them infers network latency. The client tick beneath them lives in the `tick` package,
which is internal to the module.

## Speed

One thread simulates a few million steps per second. `./gradlew runThroughput` walks and sprint-jumps a
player across a flat stone floor, twenty steps per run, and prints nanoseconds per step for both fixed
stepping APIs. On a Ryzen 9 9950X3D with OpenJDK 25, after warm-up:

| API | Per step | Steps per second | Game time per real second |
| --- | --- | --- | --- |
| `Simulator.advance` | about 405 ns | about 2.5 million | about 34 hours |
| `Rollout.tick` | about 325 ns | about 3.1 million | about 43 hours |

A step is one game tick, so the last column is how much play one thread covers per second. The scenario
exercises floor collision, jumping, and the composed server tick, but no fluids, block effects, or hits;
denser terrain and contact effects cost more per step. Stepping objects are confined to one thread and
share nothing, so throughput scales with cores by giving each thread its own `Simulator` or `Rollout` over
the same compiled `SnapshotView`. Treat the figures as an order of magnitude, not a benchmark suite.

## Describing the world

Build a `WorldSnapshot` with `WorldSnapshot.builder(outside, originX, originY, originZ, sizeX, sizeY,
sizeZ)`, declare its palette with `BlockEntry.builder(id, name)`, set cells, and `SnapshotView.compile`
the result. The region must contain the player's body and every cell the collision queries around it
reach; `OutsidePolicy.REFUSING` refuses a query that leaves it and `SEALED` treats the outside as solid.

Two rules catch newcomers. First, `BlockEntry.Builder` defaults suffocation to `Suffocation.UNKNOWN`,
which refuses the first time the player's body reaches such a block: declare `YES` for ordinary opaque
blocks and `NO` for blocks like glass and leaves. Second, unknown space is never air: an undeclared cell, a
missing section, or a query outside the region refuses instead of passing.

For a world observed from a real client, read it with the `trace` package's codecs and compile it against
a `BlockStateCatalog`, which verifies each block's facts against independently recorded ones. The codecs
read this library's own capture format; they do not read Minecraft save files.

## Handling refusals

A refusal is an outcome, not a fault. `RefusalException` is sealed with four subclasses:
`UnimplementedMechanicException` (a mechanic, block or world fact the library does not model),
`PendingServerWriteException` (the server's outcome depends on a fact you did not supply),
`UnpredictedServerWriteException` (the server would correct the client; see `correction()`), and
`StaleServerWriteException` (a write met a state it was not derived from). Classify on `cause()`, a
`RefusalCause`, never on the message: messages explain the particular call and may change. Refusals record
no stack trace unless the JVM runs with `-Dstride.refusal.trace=true`, because they are thrown on hot paths.

Structurally invalid arguments are programming errors and throw `IllegalArgumentException` or
`IllegalStateException` instead. After a refused `Rollout.tick`, reload a boundary; after a refused
scheduled event, discard that branch. `Simulator.advance` leaves its input untouched.

## Scope and limits

Modeled: collision resolution and stepping, jumping and sprinting, crawling and swimming poses, water and
lava travel, climbables, the contact and landing effects of specific blocks, gliding under declared
capabilities, and the server's movement admission, damage, food, health and echo writes. Each mechanic's
exact conditions are in its Javadoc. Not modeled, and refused when their facts are required: arbitrary
equipment and enchantments, actors without a declared schedule, fluid flow, world mutation beyond the
declared block updates, paused tick rates, creative flight packets, death and respawn.

## Validation

[VALIDATION.md](VALIDATION.md) describes how fidelity is checked, what the two bundled captures establish,
and what a green build does and does not prove.

## Repository layout

| Package under `com.nettarion.stride.simulator` | Exported | Responsibility |
| --- | --- | --- |
| root | yes | States, actions, the three stepping APIs, server writes and refusals. Start here. |
| `world` | yes | Snapshots, palettes, sections, compiled views and catalogs. |
| `block` | yes | Palette-keyed block behaviors and the traversal that applies them. |
| `geometry` | yes | Boxes, vanilla's math helpers and collision buffers. |
| `trace` | yes | State vectors, trace and capture codecs, and bit-exact trace comparison. |
| `tick` | no | The player tick as a phase pipeline. |
| `server` | no | Server authority, movement admission, survival and the write stream. |

Generator inputs live in `src/main/fields` and `src/main/templates`; generated Java is written to
`build/generated` and never edited by hand. Tests and the bundled captures live under `src/test`;
runnable examples under `examples/`.

## Contributing and license

Run `./gradlew build` before opening a pull request; it compiles with warnings as errors, runs the tests,
checks the API documentation, verifies the bundled captures, and runs the examples. Edit the generator
inputs under `src/main/fields` and `src/main/templates` rather than `build/generated`, and never update a
pinned digest to silence a failing test; see [VALIDATION.md](VALIDATION.md). The code is licensed under
[MIT](LICENSE); see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for what is not covered.

Minecraft is a trademark of Mojang and Microsoft. This project is not an official Minecraft product and is
not affiliated with or endorsed by Mojang or Microsoft.
