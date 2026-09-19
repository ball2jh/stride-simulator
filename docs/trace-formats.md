# Trace file formats

Every file the `com.nettarion.stride.simulator.trace` package reads or writes, specified so
that a reader in another language needs nothing from this library. The column tables below
were generated from the code and checked against the two bundled captures under
`src/test/resources/captures` (`live-pose-echo-boundary.*`, `live-elytra-glide.*`), which are
the reference for the text formats.

Conventions shared by every format:

- Text formats are UTF-8, one record per line, `\n` line ends, cells separated by a single tab.
  Blank lines are ignored where a format says so and refused otherwise.
- **Raw bits.** Floating-point values are never rendered as decimals. A `hex64` cell is the
  16 lowercase hexadecimal digits of the IEEE 754 double's bit pattern (`0000000000000000` is
  `0.0`, `8000000000000000` is `-0.0`); a `hex32` cell is the 8 digits of a float's bits; a
  `hex8` cell is 2 digits of an unsigned byte. Readers compare bits, never values, so `-0.0`
  and NaN payloads survive a round trip.
- `int` cells are decimal, signed, 32-bit. `bit` cells are `0` or `1`. `enum` cells are the
  constant name exactly as listed.
- Binary formats use Java `DataOutput` encoding: big-endian, `int` four bytes, `long` eight,
  `boolean` one byte (0 or 1), `byte` one byte, and `utf` a two-byte length followed by that many
  bytes of modified UTF-8 (`writeUTF`).
- Every format opens with a magic and a version. A reader refuses any version it does not list.

## Capture bundle

A capture is a set of files behind one extension-free base path named
`<producer prefix>-<scenario>` (`Capture.basePath`). Producer prefixes: `live` (`LIVE_GAME`),
`headless` (`HEADLESS_VANILLA`), `kernel` (`PURE_KERNEL`).

| companion | format | required |
|-----------|--------|----------|
| `.tsv` | trace | yes |
| `.world` | world | no; required when `.events` is present |
| `.catalog` | block catalog | required by `Capture.read` when `.world` is present |
| `.events` | world events | no |
| `.state-events` | state events | no |

Cross-file rules a reader must check: every event tick names a tick the trace has; every
world event names a cell inside the world and a block/fluid state id that occurs exactly once in
the world's palette; the state-event scenario equals the trace scenario; the catalog's Minecraft
version equals the trace's and its entries contain every palette entry of the world.

## Trace (`.tsv`)

Read and written by `TraceCodec`. Version 13 only.

```
#stride-trace	13
#minecraft	<version string, e.g. 26.2>
#producer	<LIVE_GAME | HEADLESS_VANILLA | PURE_KERNEL>
#scenario	<scenario name>
tick	input	yRot	xRot	POS_X	...	FOOD_LEVEL
init	-	-	-	<67 state cells>
0	<hex8>	<hex32>	<hex32>	<67 state cells>
1	...
```

Header lines are fixed in this order. The column line names four action columns followed by
every state field in the order below; a reader refuses a column line that differs.

Exactly one row has `init` in the tick column and `-` in the three action cells: the state
before the first tick. Every other row is one tick: the zero-based tick number, the packed
input as `hex8`, and yaw and pitch (degrees) as `hex32` raw float bits. Blank lines are
ignored.

Packed input bits: `1` forward, `2` backward, `4` left, `8` right, `16` jump, `32` sneak,
`64` sprint. Bit 7 (`0x80`) must be clear.

State cell encoding by kind:

| kind | cell |
|------|------|
| `DOUBLE` | `hex64` |
| `FLOAT` | `hex32` |
| `BOOLEAN` | `0` or `1` |
| `INT` | decimal `int` |
| `BYTE_FLAGS` | `hex8` |
| `ENUM` | `<id>:<label>`, decimal numeric id, a colon, then a diagnostic label that is outside equality |

State columns, in order (column index in the row = 4 + this index):

| # | field | kind | # | field | kind |
|---|-------|------|---|-------|------|
| 0 | `POS_X` | DOUBLE | 34 | `CROUCHING` | BOOLEAN |
| 1 | `POS_Y` | DOUBLE | 35 | `INPUT_KEY_PRESSES` | BYTE_FLAGS |
| 2 | `POS_Z` | DOUBLE | 36 | `INPUT_MOVE_VECTOR_X` | FLOAT |
| 3 | `DELTA_X` | DOUBLE | 37 | `INPUT_MOVE_VECTOR_Y` | FLOAT |
| 4 | `DELTA_Y` | DOUBLE | 38 | `WATER_HEIGHT` | DOUBLE |
| 5 | `DELTA_Z` | DOUBLE | 39 | `LAVA_HEIGHT` | DOUBLE |
| 6 | `Y_ROT` | FLOAT | 40 | `EYE_IN_WATER` | BOOLEAN |
| 7 | `X_ROT` | FLOAT | 41 | `MAY_FLY` | BOOLEAN |
| 8 | `BB_MIN_X` | DOUBLE | 42 | `FLYING` | BOOLEAN |
| 9 | `BB_MIN_Y` | DOUBLE | 43 | `FLYING_SPEED` | FLOAT |
| 10 | `BB_MIN_Z` | DOUBLE | 44 | `FALL_FLY_TICKS` | INT |
| 11 | `BB_MAX_X` | DOUBLE | 45 | `GLIDER_USABLE` | BOOLEAN |
| 12 | `BB_MAX_Y` | DOUBLE | 46 | `STUCK_SPEED_X` | DOUBLE |
| 13 | `BB_MAX_Z` | DOUBLE | 47 | `STUCK_SPEED_Y` | DOUBLE |
| 14 | `ON_GROUND` | BOOLEAN | 48 | `STUCK_SPEED_Z` | DOUBLE |
| 15 | `HORIZONTAL_COLLISION` | BOOLEAN | 49 | `IN_POWDER_SNOW` | BOOLEAN |
| 16 | `VERTICAL_COLLISION` | BOOLEAN | 50 | `WAS_IN_POWDER_SNOW` | BOOLEAN |
| 17 | `VERTICAL_COLLISION_BELOW` | BOOLEAN | 51 | `TICKS_FROZEN` | INT |
| 18 | `MINOR_HORIZONTAL_COLLISION` | BOOLEAN | 52 | `CAN_FREEZE` | BOOLEAN |
| 19 | `FALL_DISTANCE` | DOUBLE | 53 | `FROST_SPEED_TICKS` | INT |
| 20 | `SPRINTING` | BOOLEAN | 54 | `SUPPORTING_BLOCK_PRESENT` | BOOLEAN |
| 21 | `SWIMMING` | BOOLEAN | 55 | `SUPPORTING_BLOCK_X` | INT |
| 22 | `FALL_FLYING` | BOOLEAN | 56 | `SUPPORTING_BLOCK_Y` | INT |
| 23 | `SHIFT_KEY_DOWN` | BOOLEAN | 57 | `SUPPORTING_BLOCK_Z` | INT |
| 24 | `SHARED_FLAGS_RESIDUAL` | BYTE_FLAGS | 58 | `ON_GROUND_NO_BLOCKS` | BOOLEAN |
| 25 | `POSE` | ENUM | 59 | `CAN_WALK_ON_POWDER_SNOW` | BOOLEAN |
| 26 | `XXA` | FLOAT | 60 | `SPRINT_WINDOW_TICKS` | INT |
| 27 | `YYA` | FLOAT | 61 | `AUTO_JUMP_ENABLED` | BOOLEAN |
| 28 | `ZZA` | FLOAT | 62 | `FAST_LAVA` | BOOLEAN |
| 29 | `JUMPING` | BOOLEAN | 63 | `MOVEMENT_SPEED_MULTIPLIER` | DOUBLE |
| 30 | `SPRINT_TRIGGER_TIME` | INT | 64 | `JUMP_BOOST_POWER` | FLOAT |
| 31 | `JUMP_TRIGGER_TIME` | INT | 65 | `SPRINTING_ATTRIBUTE` | BOOLEAN |
| 32 | `AUTO_JUMP_TIME` | INT | 66 | `FOOD_LEVEL` | INT |
| 33 | `NO_JUMP_DELAY` | INT | | | |

`POSE` ids are vanilla's: `0` STANDING, `1` FALL_FLYING, `3` SWIMMING, `5` CROUCHING; any other
id refuses when restored. `SHARED_FLAGS_RESIDUAL` carries vanilla shared-flag bits 0, 2, 5 and
6 only; bits 1, 3, 4 and 7 are the `SHIFT_KEY_DOWN`, `SPRINTING`, `SWIMMING` and `FALL_FLYING`
columns.

## World (`.world`)

Read and written by `WorldSnapshotCodec`. Writes version 15; reads 13 through 15.

```
#stride-world	15
#origin	<x>	<y>	<z>
#size	<sx>	<sy>	<sz>
#outside	<SEALED | REFUSING>
#palette	<n>
p	0	...              (n rows)
#fluid-palette	<m>
f	0	...              (m rows, m >= 1)
c	<localY>	<localZ>	<sx comma-separated ints>   (sy * sz rows)
#fluid-cells	<0 | 1>
fc	<localY>	<localZ>	<sx comma-separated ints>  (sy * sz rows, only when #fluid-cells is 1)
```

Origin and size are block coordinates and counts, all sizes positive, `sx*sy*sz` within a
signed 32-bit int and `origin + size` within int range on each axis. `#outside` also accepts the
pre-0.1 label `ROLLOUT_TERMINATING` for `REFUSING`; the bundled captures carry it.

Palette rows (`p`) are numbered `0..n-1` in order. Columns by index in a version-15 row:

| # | column | encoding | values / notes |
|---|--------|----------|----------------|
| 0 | tag | literal | `p` |
| 1 | index | int | equals the row position |
| 2 | block state id | int | vanilla block state id |
| 3 | name | string | e.g. `minecraft:stone` |
| 4 | friction | hex32 | |
| 5 | speed factor | hex32 | |
| 6 | jump factor | hex32 | |
| 7 | moving piston | bit | |
| 8 | suppresses supporting speed factor | bit | |
| 9 | bubble column mode | enum | `NONE`, `DRAG_DOWN`, `PUSH_UP` |
| 10 | fall-distance resetting | bit | |
| 11 | collision behavior | enum | `ORDINARY`, `SCAFFOLDING_SUPPORTED`, `SCAFFOLDING_UNSTABLE_BOTTOM`, `POWDER_SNOW_NO_BOOTS`, `SERVER_MUTABLE_SUPPORT` |
| 12 | suffocation | enum | `UNKNOWN`, `NO`, `YES` |
| 13 | inside effect | enum | `NONE`, `COBWEB`, `SWEET_BERRY_BUSH`, `HONEY`, `LAVA_CAULDRON` |
| 14 | bounce restitution | hex32 | |
| 15 | suppresses bounce | bit | |
| 16 | step on | enum | `NONE`, `SLIME` |
| 17 | retains support pos | bit | |
| 18 | climbability | enum | `NONE`, `CLIMBABLE`, `GLIDE_THROUGH`, `LADDER_NORTH`, `LADDER_EAST`, `LADDER_SOUTH`, `LADDER_WEST`, `OPEN_TRAPDOOR_NORTH`, `OPEN_TRAPDOOR_EAST`, `OPEN_TRAPDOOR_SOUTH`, `OPEN_TRAPDOOR_WEST` |
| 19 | shape provenance | enum | since 14: `GENERAL`, `CANONICAL_FULL`, `LEGACY_GEOMETRY`; absent in 13, read as `LEGACY_GEOMETRY` |
| 20 | contact | enum | since 15: `NONE`, `CACTUS`, `SWEET_BERRY_BUSH`, `HOT_FLOOR`, `CAMPFIRE`, `SOUL_CAMPFIRE`, `FIRE`, `SOUL_FIRE`, `LAVA_CAULDRON`, `UNMODELED`, `UNRECORDED` (`UNKNOWN` is read as `UNRECORDED`); absent before 15, derived from the name |
| 21 | landing | enum | since 15: `ORDINARY`, `HAY`, `HONEY`, `SLIME`, `BED`, `STALAGMITE`, `FARMLAND`, `POWDER_SNOW`, `UNMODELED`, `UNRECORDED` (`UNKNOWN` is read as `UNRECORDED`); absent before 15, derived from the name |
| 22 | box count | int | `k`; the row ends after `k` more cells |
| 23.. | box | 6 hex64 joined by `,` | `minX,minY,minZ,maxX,maxY,maxZ` in block-local coordinates |

In a version-13 row columns 19 through 21 are absent, so the box count is column 19; in a
version-14 row contact and landing are absent, so the box count is column 20. `CANONICAL_FULL`
requires exactly one box equal to the unit cube. The `PaletteColumn` enum in
`WorldSnapshotCodec` is this table in code.

Fluid palette rows (`f`), numbered `0..m-1`; index 0 must be the empty fluid:

| # | column | encoding |
|---|--------|----------|
| 0 | tag | `f` |
| 1 | index | int |
| 2 | fluid state id | int |
| 3 | name | string, e.g. `minecraft:flowing_water` |
| 4 | kind | `EMPTY`, `WATER`, `LAVA` |
| 5 | height | hex64 |
| 6 | flow x | hex64 |
| 7 | flow y | hex64 |
| 8 | flow z | hex64 |
| 9 | source | bit |

Cell rows: one `c` row per `(localY, localZ)` pair, each with exactly `sx` comma-separated
palette indices for `localX = 0..sx-1`; the cell at local `(x, y, z)` is index
`(y*sz + z)*sx + x`. Rows may appear in any order but each pair exactly once. `fc` rows are the
same shape over the fluid palette and appear only when `#fluid-cells` is `1`. Anything after
the last row other than blank lines is refused.

## World events (`.events`)

Read and written by `WorldEventTrace`. Writes version 3; reads 1 through 3.

Version 3:

```
#stride-world-events	3
#block-events	<n>
tick	x	y	z	state_id	fluid_state_id
<n rows of six ints>
#fluid-events	<m>
tick	x	y	z	fluid_state_id	fluid_name	fluid_kind	height_bits	flow_x_bits	flow_y_bits	flow_z_bits	source
<m rows>
```

Block rows: tick (zero-based client tick before which the change is visible), block
coordinates, the new block state id, and the new fluid state id or `-1` to keep the cell's
fluid. Fluid rows: tick, coordinates, then the resolved fluid exactly as a fluid palette row
(`hex64` heights and flows, `true`/`false` source). Within each section ticks are nondecreasing;
a tick's fluid events apply after its block events. Only blank lines may follow the last row.

Versions 1 and 2 have no section headers: the magic line, then one column line, then block
rows until end of file. Version 1 columns are `tick x y z state_id` (fluid kept); version 2 adds
`fluid_state_id`.

## State events (`.state-events`)

Read and written by `StateEventTrace`. Version 2 only.

```
#stride-state-events	2
#scenario	<scenario name>
tick	phase	field	raw_value
38	PRE	SWIMMING	0000000000000000
```

Each row is one server-written value: the zero-based client tick, `PRE` (takes effect before
that tick) or `POST` (after), a state field name from the trace table above, and its value as
16 hex digits of the field's raw-bit encoding: all 64 bits for `DOUBLE`; the low 32 bits for
`FLOAT`, `INT` and `ENUM` (numeric id) and the low 8 for `BYTE_FLAGS`, with the high bits zero;
`0` or `1` for `BOOLEAN`. Rows are in nondecreasing tick order, `PRE` before `POST` within a
tick, and no `(tick, phase, field)` repeats.

Only these fields may appear: `POS_X`, `POS_Y`, `POS_Z`, `DELTA_X`, `DELTA_Y`, `DELTA_Z`,
`SPRINTING`, `SPRINTING_ATTRIBUTE`, `FOOD_LEVEL`, `SWIMMING`, `FALL_FLYING`, `SHIFT_KEY_DOWN`,
`SHARED_FLAGS_RESIDUAL`, `POSE`, `MAY_FLY`, `FLYING`, `FLYING_SPEED`, `TICKS_FROZEN`,
`FROST_SPEED_TICKS`, `CAN_FREEZE`, `GLIDER_USABLE`, `CAN_WALK_ON_POWDER_SNOW`,
`SPRINT_WINDOW_TICKS`, `AUTO_JUMP_ENABLED`, `FAST_LAVA`, `FALL_DISTANCE`.

## Simulation state (boundary), binary

Written and read by `SimulationStateCodec`; embedded in checkpoints and recordings, and the
bundled `schema-1-boundary.bin` is one on its own. Writes schema 2 (or 1 on request); reads 1
and 2.

```
int   schema                      1 or 2
copy  client                      66 fields
copy  publisher                   10 fields
copy  server                      132 fields at schema 1, 136 at schema 2
int   retained movement count     0..99
      movement * count            8 longs each: fromX fromY fromZ toX toY toZ requestedX requestedZ (raw double bits)
int   completed actions
utf   pending hurt                a HurtCause name, or "" for none
```

A `copy` is `int` field count, then per field `utf name`, `utf type label`, value. Fields are in
ascending order of name (Java `String` order). The type label is an opaque tag; a reader must
match it exactly. Value encodings by label:

| label | value |
|-------|-------|
| `double` | long, raw bits |
| `float` | int, raw bits |
| `int` | int |
| `long` | long |
| `boolean` | boolean |
| `byte` | byte |
| any other label | utf, an enum constant name |

The enum labels in use are `com.nettarion.stride.simulator.PlayerState$Pose` (`STANDING`,
`FALL_FLYING`, `SWIMMING`, `CROUCHING`) and
`com.nettarion.stride.simulator.ServerPlayerState$Difficulty` (`EASY`, `NORMAL`, `HARD`). They
happen to be Java binary class names because the bundled fixture stores them; they are tags,
not a promise about class names.

Client fields (66): `autoJumpEnabled` boolean, `autoJumpTime` int, `boundingBoxMaxX` double,
`boundingBoxMaxY` double, `boundingBoxMaxZ` double, `boundingBoxMinX` double, `boundingBoxMinY`
double, `boundingBoxMinZ` double, `canFreeze` boolean, `canWalkOnPowderSnow` boolean,
`crouching` boolean, `deltaMovementX` double, `deltaMovementY` double, `deltaMovementZ` double,
`eyeInWater` boolean, `fallDistance` double, `fallFlyTicks` int, `fallFlying` boolean,
`fastLava` boolean, `flying` boolean, `flyingSpeed` float, `foodLevel` int, `frostSpeedTicks`
int, `gliderUsable` boolean, `horizontalCollision` boolean, `inputKeyPresses` byte,
`inputMoveVectorX` float, `inputMoveVectorY` float, `isInPowderSnow` boolean, `jumpBoostPower`
float, `jumpTriggerTime` int, `jumping` boolean, `lavaHeight` double,
`mainSupportingBlockPosPresent` boolean, `mainSupportingBlockPosX` int,
`mainSupportingBlockPosY` int, `mainSupportingBlockPosZ` int, `mayfly` boolean,
`minorHorizontalCollision` boolean, `movementSpeedMultiplier` double, `noJumpDelay` int,
`onGround` boolean, `onGroundNoBlocks` boolean, `pose` Pose, `sharedFlagsResidual` byte,
`sprintTriggerTime` int, `sprintWindowTicks` int, `sprinting` boolean, `sprintingAttribute`
boolean, `stuckSpeedMultiplierX` double, `stuckSpeedMultiplierY` double,
`stuckSpeedMultiplierZ` double, `swimming` boolean, `ticksFrozen` int, `verticalCollision`
boolean, `verticalCollisionBelow` boolean, `wasInPowderSnow` boolean, `waterHeight` double, `x`
double, `xRot` float, `xxa` float, `y` double, `yRot` float, `yya` float, `z` double, `zza`
float.

Publisher fields (10): `lastHorizontalCollision` boolean, `lastOnGround` boolean,
`lastSentInput` int, `positionReminder` int, `wasSprinting` boolean, `xLast` double, `xRotLast`
float, `yLast` double, `yRotLast` float, `zLast` double.

Server fields: every client field above plus, merged into the same alphabetical order,
`aboveGroundTickCount` int, `absorption` float, `airSupply` int, `allowFlight` boolean,
`attachedRockets` int, `awaitingPositionX` double, `awaitingPositionY` double,
`awaitingPositionZ` double, `awaitingTeleport` int, `awaitingTeleportTime` int,
`clientIsFloating` boolean, `connectionTickCount` int, `correctionPending` boolean,
`currentImpulseContextResetGraceTime` int, `currentImpulseImpactPosPresent` boolean,
`currentImpulseImpactPosX` double, `currentImpulseImpactPosY` double, `currentImpulseImpactPosZ`
double, `difficulty` Difficulty, `elytraMovementCheck` boolean, `entityDataDirty` int,
`exhaustionLevel` float, `firstGoodX` double, `firstGoodY` double, `firstGoodZ` double,
`floatingUnknown` boolean, `health` float, `invulnerableTime` int, `knownMovePacketCount` int,
`lastFoodSaturationZero` boolean, `lastGoodX` double, `lastGoodY` double, `lastGoodZ` double,
`lastHurt` float, `lastKnownClientMovementX` double, `lastKnownClientMovementY` double,
`lastKnownClientMovementZ` double, `lastSentFood` int, `lastSentFrostSpeedTicks` int,
`lastSentHealth` float, `lastSentPose` Pose, `lastSentSharedFlags` int, `lastSentTicksFrozen`
int, `maximumHealth` float, `movementAxisDependent` boolean, `movementFromX` double,
`movementFromY` double, `movementFromZ` double, `movementRequestedX` double,
`movementRequestedZ` double, `movementSpeedAttributeDirty` boolean, `movementThisTickPresent`
boolean, `movementToX` double, `movementToY` double, `movementToZ` double,
`naturalRegeneration` boolean, `playerMovementCheck` boolean, `publicationScheduleKnown`
boolean, `receivedMovePacketCount` int, `remainingFireTicks` int, `saturationLevel` float,
`sharedFlagOnFire` boolean, `shiftKeyDown` boolean, `singleplayerOwner` boolean, `tickCount`
long, `tickTimer` int; and, **schema 2 only**, `drowningDamage`, `fallDamage`, `fireDamage`,
`freezeDamage`, all boolean. A schema-1 reader takes the four as `true`.

The source of truth for this inventory is `src/main/fields/state-fields.tsv`; the alphabetical
wire order and labels are generated from it.

## Block catalog (`.catalog`), binary

Written and read by `BlockStateCatalogCodec`. Version 1 only.

```
utf   magic                 "stride-block-source" (fixed by the bundled captures)
int   version               1
utf   minecraft version
int   palette text length   <= 64 MiB
bytes palette text          a complete world file (format above) with #origin 0 0 0,
                            #size 1 1 1, one cell 0, whose #palette rows are the catalog entries
int   cauldron count        <= 100000
      per cauldron: int block state id, int successor state id, boxes
int   inside shape count    <= 100000
      per shape, ascending by id: int block state id, boolean canonical full, boxes
```

`boxes` is `int` count (<= 100000) then per box six longs of raw double bits
`minX minY minZ maxX maxY maxZ`. The palette text is embedded rather than re-specified because
the world codec already owns the palette row encoding; a reader reuses its world parser. The
bundled catalogs embed `#outside ROLLOUT_TERMINATING`, so re-encoding them does not reproduce
their bytes.

## Simulation checkpoint, binary

Produced by `SimulationCheckpoint.Checkpoint.encode()`; stored inside scheduled recordings.

```
boundary        a simulation state stream (above), pending hurt written as ""
value           the transport record
value           the list of server writes
int + bytes     client world: length, then a complete world file as UTF-8 text
int + bytes     server world: same
```

A `value` is `utf tag` followed by a body whose shape the tag decides. Scalar and list tags:

| tag | body |
|-----|------|
| `null` | nothing |
| `list` | int count, then that many values |
| `d` | long, raw double bits |
| `f` | int, raw float bits |
| `i` | int |
| `l` | long |
| `b` | byte |
| `z` | boolean |
| `s` | utf |
| `external-actor-kind` | utf: `EXPLOSION`, `FIREWORK_ROCKET` |
| `hurt-cause` | utf: `FALL`, `STALAGMITE`, `FLY_INTO_WALL`, `CACTUS`, `SWEET_BERRY_BUSH`, `HOT_FLOOR`, `CAMPFIRE`, `IN_FIRE`, `ON_FIRE`, `LAVA`, `DROWN`, `FREEZE`, `IN_WALL`, `FELL_OUT_OF_WORLD`, `STARVE` |
| `impulse-operation` | utf: `ADD`, `REPLACE`, `MOVE` |
| `impulse-phase` | utf: `PACKET`, `ACTOR_TICK` |
| `movement-packet` | utf: `NONE`, `STATUS_ONLY`, `ROT`, `POS`, `POS_ROT` |
| `pose` | utf: `STANDING`, `FALL_FLYING`, `SWIMMING`, `CROUCHING` |

Record tags: the body is, for each field in the listed order, `utf field name` then a value.

| tag | fields |
|-----|--------|
| `transport` | `serverbound` list, `clientbound` list, `pendingHurt` hurt-cause or null, `hurtAction` i, `mayInteract` z or null, `receivedMovementThisTick` z |
| `input` | `input` player-input |
| `sprint` | `sprinting` z |
| `glide` | none |
| `accept-teleport` | `id` i |
| `move-packet` | `form` movement-packet, `x` d, `y` d, `z` d, `yRot` f, `xRot` f, `onGround` z, `horizontalCollision` z |
| `client-tick-end` | none |
| `block-update` | `write` block-update-write |
| `data` | `value` entity-data-write |
| `health` | `health` f, `food` i, `saturation` f |
| `motion` | `cause` hurt-cause, `action` i, `x` d, `y` d, `z` d |
| `position` | `id` i, `x` d, `y` d, `z` d, `yRot` f, `xRot` f |
| `player-input` | `forward` z, `backward` z, `left` z, `right` z, `jump` z, `sneak` z, `sprint` z, `yRot` f, `xRot` f |
| `block-update-write` | `actionIndex` i, `x` i, `y` i, `z` i, `paletteIndex` i |
| `damage-event` | `cause` hurt-cause, `attempted` f, `absorbed` f, `healthDamage` f, `healthAfter` f, `full` z |
| `damage-write` | `actionIndex` i, `event` damage-event |
| `entity-data-write` | `actionIndex` i, `beforeDigest` l, `dirty` i, `sharedFlags` i, `pose` pose or null, `ticksFrozen` i, `frostSpeedTicks` i, `sprintingAttribute` z |
| `external-actor` | `kind` external-actor-kind, `entityId` i |
| `health-write` | `actionIndex` i, `beforeDigest` l, `health` f, `foodLevel` i, `saturationLevel` f |
| `hurt-motion` | `cause` hurt-cause, `causeAction` i, `expectedStateDigest` l, `writeAfterAction` i, `writeX` d, `writeY` d, `writeZ` d |
| `hurt-motion-write` | `actionIndex` i, `event` hurt-motion |
| `impulse-write` | `actionIndex` i, `actor` external-actor, `sequence` l, `phase` impulse-phase, `operation` impulse-operation, `x` d, `y` d, `z` d |
| `player-position-write` | `actionIndex` i, `beforeDigest` l, `teleportId` i, `x` d, `y` d, `z` d, `yRot` f, `xRot` f |

The transport's `serverbound` list holds `input`, `sprint`, `glide`, `accept-teleport`,
`move-packet` and `client-tick-end` values; `clientbound` holds `data`, `health`, `motion`,
`position` and `block-update`. The server-write list holds `block-update-write`,
`damage-write`, `entity-data-write`, `health-write`, `hurt-motion-write`, `impulse-write` and
`player-position-write`. The `Tag` enum in `SimulationCheckpoint` is this table in code.

## Scheduled recording, gzip container

Written and read by `ScheduledRecording`. Version 3 only. The whole file is one gzip stream
whose decompressed content is:

```
utf   magic                 "stride-scheduled-recording"
int   version               3
utf   minecraft version
bool  verified              whether a block catalog fingerprint follows
blob  catalog fingerprint   SHA-256 of the catalog's .catalog bytes, or empty when unverified
utf   interaction           ALLOWED, DENIED or UNDECLARED
      initial state         a simulation state stream (above)
blob  world                 a complete world file as UTF-8 text
int   event count           <= 1000000
utf   producer 0            LIVE_GAME, HEADLESS_VANILLA or PURE_KERNEL
blob  checkpoint 0          a simulation checkpoint (above), after no events
      per event i (1..count):
        event               see below
        utf producer i
        blob checkpoint i   the checkpoint after event i
```

A `blob` is `int` length (<= 256 MiB) then that many bytes. An event is `utf` name: one of the
phases `LEVEL_TICK`, `CONNECTION_TICK`, `PUBLISH_SERVER_ENTITY`, `DELIVER_SERVERBOUND`,
`DELIVER_CLIENTBOUND` with no body, or `CLIENT_TICK` followed by `byte` packed input (bits as
in the trace format), `int` yaw raw float bits, `int` pitch raw float bits. No bytes may follow
the last checkpoint.

Version history: version 2 stored the interaction as a byte (`-1` undeclared, `0`, `1`), used
the magic `stride-scheduled-trace`, and tagged checkpoint values with Java class names and
8-byte ints; no version-2 files are bundled and the reader refuses them.
