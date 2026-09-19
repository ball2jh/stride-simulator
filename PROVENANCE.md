# Provenance and fidelity

This repository starts with the simulator module of Stride at commit
`e6a9a080094b341b1197e6b7b14bb0de06b57019`. It has a fresh Git history and
contains no planner, pilot, adapter, experiment suite, or native Minecraft
validation implementation. Java package names remain
`com.nettarion.stride.simulator` to preserve API identity.

The implementation targets Minecraft Java Edition **26.2**. Its movement
arithmetic and phase ordering were developed by inspecting the pinned game's
mapped Java source. It is a behavioral transcription organized around flat
state, phase functions, and explicit client/server composition; it is not a
clean-room implementation. Upstream class and method names in code comments
identify the correspondence. This provenance statement is not a legal review
of the source-derived portions.

The extraction preserves the simulator arithmetic and wire schemas. Cleanup
changes documentation, formatting, imports, build wiring, and one diagnostic
that previously suggested a command available only in Stride. Tests formerly
reading a sibling module now read bundled fixtures.

`provenance.json` records the extraction revision and the original paths and
SHA-256 digests of each imported test fixture. The fixtures are small generated
world descriptions and observations used by existing simulator regressions:

- Pose-echo and elytra captures check the recorded client trajectory and the
  arrival phase of entity-data publications. They cover those scenarios only.
- The schema-1 boundary checks migration to default server damage rules.

These are observations, not game executables or source archives. Their original
bytes are retained. No experiment runs, private maps, credentials, machine paths,
or prior Git history are needed to build this repository.

The standalone test suite checks consistency and recorded regressions. The
broader native oracle, fuzz, and live validation tools remain in Stride; they
are not implied by a green build here. See [VALIDATION.md](VALIDATION.md).
