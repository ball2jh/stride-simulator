# Validation and origin

## What the library implements

The implementation reimplements the mechanics of Minecraft Java Edition 26.2. Comments name the vanilla
classes and methods each part corresponds to so the correspondence can be checked. Movement arithmetic and
the order of the tick's phases follow vanilla exactly, including where values widen from float to double,
where they round, and how signed zero is handled, because the library's purpose is bit-for-bit agreement
with a real client. Anything outside that is refused rather than approximated.

## How fidelity is checked

`./gradlew build` runs the following. No game installation is needed; the first build downloads Gradle,
JUnit and, if necessary, a JDK.

| Task | What it checks |
| --- | --- |
| `test` | Movement, collision, survival, schedules, refusals, state copies, codecs, raw-bit pins and the bundled captures. |
| `javadoc` | The API documentation builds without malformed markup or unresolved links; warnings fail it. |
| `checkApiDocs` | Every public type in the library and the examples has a Javadoc description and every package a `package-info.java`. |
| `verifyFixtures` | Every bundled capture file matches the SHA-256 digest recorded in `provenance.json`, and no unlisted file is present. |
| `runExample`, `runHandlingRefusals` | The examples run to completion; `GettingStarted` also checks that fixed and in-place stepping agree at every tick. |

The tests fall into these categories:

| Category | Examples | What it establishes |
| --- | --- | --- |
| Transcription | `VanillaMathTest`, `VanillaCollisionReferenceTest`, `PinnedMathTest` | The scalar math and collision resolution reproduce vanilla's results on reference values, swept to the last bit. |
| Phase order and write sets | `ClientTickOrderingTest`, `PhaseWriteSetTest`, `ServerTickTransactionTest` | Each phase runs in vanilla's order and writes only the fields it declares. |
| Mechanics | `ClientTick*Test`, `Server*Test`, the `block` tests | Each modeled mechanic behaves as vanilla does on constructed worlds. |
| Guarantees by reflection | `StateDigestCoverageTest`, `PlayerStateCopyTest`, `SimulationStateEqualityTest`, `RefusalTest` | Every field participates in copy, equality, digest and wire encoding; every refusal carries a cause. |
| Raw-bit pins | `StateEncodingStabilityTest`, `TraceCodecTest`, `BoundarySchemaTest` | The wire encodings, digests and format migrations are unchanged. |
| Live captures | `EchoPhaseMeasurementTest` | Two recordings of a real client replay bit for bit, including the tick at which each server echo reaches the client. |

### The bundled captures

`src/test/resources/captures/` holds two recordings of a live Minecraft 26.2 client and one binary
fixture; the directory's README describes each file.

- `live-pose-echo-boundary`: a player who sprints into water, swims, and stands up again. Replaying its
  inputs through the composed simulator must reproduce the recorded position, sprint flag and pose on
  every tick, and the server's entity-data echoes (sprinting, swimming, pose) must reach the client at the
  tick the live client recorded in the `.entity-data` file. This is what pins the transport phase: when a
  server write lands relative to the client tick that caused it.
- `live-elytra-glide`: a player who starts gliding from a height. The replay must reproduce the recorded
  trajectory and glide flags until it reaches the elytra's durability write, which the library does not
  model. The replay stops at that refusal, and agreement is asserted on everything before it.
- `schema-1-boundary.bin`: a boundary written under the first wire schema. It must still read, with
  vanilla's default damage rules filled in, so old recordings stay readable.

The capture bytes are verified against `provenance.json` on every build. Their original bytes are
retained exactly; nothing in them is regenerated.

## What a green build proves

The tests exercise the modeled mechanics and hold selected exact results. They prove that the arithmetic
matches vanilla on the covered scenarios and that two live recordings replay exactly. They do not prove
equivalence for every Minecraft state, every network schedule or every custom server. A returned state is a
result under the supplied facts and chosen schedule. A refusal says those facts left what the library
models; it is not an approximation and not a proof that a movement is impossible.

A replay of the simulator against its own recording checks internal consistency, not fidelity. Only an
independently observed client establishes fidelity, which is why the two live captures matter.

## Changing mechanics

Preserve arithmetic order, rounding points, signed-zero handling, phase order and the comments that explain
them. Add a focused regression test for the changed behavior. A claim of broader Minecraft equivalence needs
independent recordings of a real client at the target version; record where they came from.

Never update an expected digest, hash or fixture byte to silence a failure. Explain the changed behavior,
keep the previous evidence, and establish the new value independently. For a schema change, keep the
migration and refusal tests and update the documented format.

## Origin

This library was extracted from a private movement-planning project. `provenance.json` records the
extraction revision and, for each bundled fixture, its original path and SHA-256 digest; those paths refer
to the private repository. Nothing else from it is needed: no experiment runs, maps, credentials, machine
paths or prior Git history are required to build, test or use this repository.
