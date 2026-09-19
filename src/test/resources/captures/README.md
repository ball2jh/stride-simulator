# Bundled captures

Recordings used by the tests. Their bytes are fixed: `provenance.json` at the repository root records each
file's SHA-256 digest and the `verifyFixtures` task fails the build if one changes or an unlisted file
appears. Read a capture with `Capture.read(Path)` from the `trace` package, giving the base path without an
extension (for example `captures/live-pose-echo-boundary`).

## Scenarios

`live-pose-echo-boundary` (Minecraft 26.2, producer `LIVE_GAME`): a real client sprints into water, swims,
and stands up again over about 130 ticks. `EchoPhaseMeasurementTest` replays its inputs and checks the
client trajectory tick for tick and the arrival tick of every sprinting, swimming and pose echo the server
sent back. Its `.state-events` sidecar records one server-written field value the live client held before tick 38.

`live-elytra-glide` (Minecraft 26.2, producer `LIVE_GAME`): a real client starts an elytra glide from a
height and flies for about 110 ticks. The same test replays it until the elytra's durability write, which
the library does not model, and checks trajectory, glide flag and echo arrivals up to that point.

`schema-1-boundary.bin`: one server boundary written under wire schema 1, before the server carried its
damage-rule flags. `BoundarySchemaTest` reads it and checks that vanilla's default damage rules are filled
in, so recordings made before the schema change stay readable.

## File extensions

- `.tsv`: the trace. Header lines (`#stride-trace 13`, `#minecraft`, `#producer`, `#scenario`) followed by
  one tab-separated row per tick: the action (packed keys, yaw, pitch) and every state field as raw bits.
- `.world`: the world snapshot (`#stride-world 15`): origin, size, outside policy, the block and fluid
  palettes with their facts and shapes, then the cells.
- `.catalog`: the block-state catalog (`stride-block-source`): independently recorded block facts the world
  is verified against when the capture is read.
- `.entity-data`: the server's entity-data echoes as the live client received them
  (`#stride-entity-data-echo 2`): the client tick at arrival, and the pose or shared flags carried.
- `.state-events`: server-written client fields observed before or after a named client tick
  (`#stride-state-events 2`): tick, phase, field and raw value. A replay installs them instead of computing them.
- `.bin`: a binary boundary written by `BoundaryCodec`.
