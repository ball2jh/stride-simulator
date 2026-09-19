# Contributing

## Workflow

Fork the repository, branch from `main`, and open a pull request against `main`. Before you push:

```sh
./gradlew build
```

That compiles with warnings as errors, runs the tests, builds the Javadoc with doclint, checks that every
public type is documented, verifies the bundled capture fixtures, and runs the examples. You need JDK 25 and
Python 3; Python is used only by the state-field generator. To run one test class or one method:

```sh
./gradlew test --tests 'com.nettarion.stride.simulator.SimulatorTest'
./gradlew test --tests 'com.nettarion.stride.simulator.SimulatorTest.advancingPreservesItsInputAcrossOrdinaryAndPendingHurtBoundaries'
```

The pull request template has a short checklist; fill it in. Add a line to the `Unreleased` section of
[CHANGELOG.md](CHANGELOG.md) for anything a user of the library would notice.

## Style

- Tabs for indentation, 120 columns, braces on every `if`, `for` and `while` body, one field per
  declaration, one blank line between members.
- Explicit imports only, sorted `com.nettarion` first, then `java`, `javax`, `org`; static imports first in
  tests. No wildcard imports and no fully qualified `java.util.X` inline where an import would do.
- `.clang-format` encodes the layout. Running `clang-format -i` on the files you change is optional, but the
  repository is kept conformant, so a reviewer may ask for it.
- American spelling in identifiers and prose (behavior, modeled, neighbor, center, license). Vanilla's own
  names keep vanilla's spelling where they are quoted.
- Do not write `Foo.class::isInstance` or `Foo.class::cast`; clang-format mangles them. Write a lambda or a
  pattern `instanceof` instead.
- Name a magic number that recurs or that mirrors a vanilla constant.

## Documentation

- Every public type, constructor, method and field has Javadoc. The first sentence is short, standalone, and
  useful to someone who has never seen the code.
- State units (blocks, blocks per tick, degrees, ticks), who owns and may mutate a value, thread-safety, and
  the conditions under which a method refuses.
- Every `{@link}` must resolve. Do not link to package-private types from public documentation; use
  `{@code}`.
- Comments that refer to Minecraft's own code use vanilla's Mojang-mapped names in `{@code Class.member}`
  form and the word "vanilla".
- Use the terms defined in the README's Concepts section and no others for the library's own ideas. Do not
  import vocabulary from the projects that consume the simulator; write "the caller" or "a consumer", or
  state the concrete engineering reason.
- Keep a Javadoc block under about fifteen lines; move rationale into an `@implNote` or a `//` comment.

## Generated code

Two generators run before compilation. Edit their inputs, never `build/generated`:

- `src/main/templates/ShapeCollision.java.template` holds the one collision rule that Gradle expands into
  three axis orientations.
- `src/main/fields/state-fields.tsv` with `src/main/fields/generate.py` holds the field table that expands
  into copy, equality, digest and wire plumbing for the player states.

Both inputs ship in the sources JAR so the origin of generated code is inspectable.

## Evidence

Never update a digest, an expected hash, or a fixture byte to make a failing test pass. The raw-bit pins
(`StateEncodingStabilityTest`, `StateDigestCoverageTest`, `TraceCodecTest`, and the fixtures hashed in
`provenance.json`) are the evidence that a change did not alter behavior. If a change legitimately alters
a recorded value, explain the new behavior in the pull request, keep the previous evidence, and establish
the new value independently. See [VALIDATION.md](VALIDATION.md) for what the suite proves.

When changing a mechanic, preserve arithmetic order, rounding points, signed-zero handling and phase order
exactly as vanilla has them, keep the comments that explain why an expression is written the way it is, and
add a focused regression test.

## Scope

In scope: the mechanics of player movement and the server behavior that affects it, the world description
and capture formats they read, and the tooling to compare implementations. Out of scope: pathfinding, route
search, bots, mods, launchers, world-save importers and network clients. Those belong in separate projects
that depend on this one.

## Reporting bugs and security issues

Use the issue templates for bugs and feature requests. For anything that could be a security issue, follow
[SECURITY.md](SECURITY.md) rather than opening a public issue.

## License and conduct

Contributions are licensed under the [MIT license](LICENSE). By submitting a change you certify that you
have the right to contribute it under that license, in the sense of the Developer Certificate of Origin;
signing off commits (`git commit -s`) is welcome but not required. Preserve third-party notices and make
the origin of any new material clear. This project follows the [Code of Conduct](CODE_OF_CONDUCT.md).
