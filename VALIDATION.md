# Validation

Run `./gradlew clean check` from a fresh checkout with JDK 25 and Python 3.
No game installation or sibling repository is needed. The first build downloads
Gradle and JUnit from their configured public repositories.

The verification tasks cover:

| Task | What it checks |
| --- | --- |
| `test` | Movement, collision, survival, schedules, refusals, state copies, codecs, raw-bit pins, and bundled observation regressions. |
| `javadoc` | API documentation builds without malformed markup or unresolved links; warnings fail the task. |
| `checkApiDocs` | Every publicly accessible declared Java type has Javadoc and each source package has a package description. |
| `verifyFixtures` | Bundled observation bytes still match their provenance hashes. |
| `runExample` | Fixed and in-place stepping agree on a synthetic flat-world route; an explicit schedule can be forked and replayed deterministically. |

`build` includes these checks and produces the library, source, and Javadoc JARs.
Reports are local build output: `build/reports/tests/test/index.html` and
`build/docs/javadoc/index.html`.

## What a passing build establishes

The tests exercise the admitted mechanisms and preserve selected exact results.
They do not prove equivalence for every Minecraft state, every network schedule,
or every custom server. A returned state is a result under the supplied facts
and chosen schedule. A typed refusal says those facts left the model's admitted
scope; it is not a numerical approximation or a proof that a route is impossible.

The bundled native observations check two small recorded scenarios. In particular,
the elytra replay stops at its explicitly unsupported durability transition.
A same-simulator replay verifies internal consistency, not independent fidelity.

## Changing mechanics

Preserve the arithmetic order, signed-zero behavior, phase ordering, and causal
state. Add a focused regression that exercises the changed boundary. A broader
Minecraft-equivalence claim also needs independent observations or a native
comparison at the target version. This repository does not ship the recorder or
native oracle; obtain that evidence separately and record its provenance.

Do not update an expected digest just to silence a failure. Explain the changed
behavior, preserve the previous evidence, and establish the new value independently.
For a schema change, keep migration/refusal tests and update the documented format.
