# Contributing

Use JDK 25, Python 3, and the checked-in Gradle wrapper. Before submitting a change:

```sh
./gradlew clean build
```

Keep changes scoped to the simulator. Goal selection, route search, game-client
integration, and native validation harnesses belong in separate consumers.

## Source layout

The normal library and tests live under `src/main` and `src/test`. Two generators
run before compilation:

- `src/main/templates/ShapeCollision.java.template` owns one collision rule,
  expanded into three axis orientations by Gradle.
- `src/main/fields/state-fields.tsv` and its adjacent `generate.py` own field
  copy, equality, digest, and binary encoding plumbing.

Edit those inputs rather than `build/generated`. Generated Java is included in
the sources JAR along with the ordinary sources. The generator inputs are also
included so the generated code's origin is inspectable.

## Code and documentation

Use the existing upstream mechanic names where they make the correspondence
clear. Keep the tick as a phase pipeline, block effects as palette behaviors,
and server effects on the ordered server-write stream. Do not duplicate movement
arithmetic in another runner or put networking bookkeeping on `PlayerState`.

Java uses tabs of width four and a 120-column formatting target. `.clang-format`
provides the formatting configuration; `clang-format -i PATH.java` is optional
local tooling. Preserve comments explaining arithmetic order and upstream rules.
The build requires warning-free Java compilation.

Every public type needs a useful first Javadoc sentence and every package needs
a `package-info.java`. Document mutation/ownership, units, refusal conditions,
thread safety, and any supported-domain assumptions that affect callers. Ordinary
parameter/return tags are optional when prose already states the contract;
Javadoc syntax and links are checked. Examples must be runnable and checked by
`runExample`.

## Evidence and API changes

See [VALIDATION.md](VALIDATION.md). Preserve raw fixture bytes and provenance.
Never silently treat unknown world cells as air or unsupported mechanics as
ordinary blocks. A refused in-place transition has no usable successor.

This is an experimental pre-1.0 API. Describe source/API or schema compatibility
changes explicitly. The Maven publication is local-only by default: the build
has no remote publishing repository or credentials.

Contributions are made under the [MIT license](LICENSE). Preserve applicable
third-party notices and make the origin of any new material clear.
