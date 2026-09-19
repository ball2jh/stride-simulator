# State field table

`state-fields.tsv` declares every public field of the three player copies
(`PlayerState`, `ServerPlayerState`, `Publisher`). At build time `generate.py`
expands it, through `StateFields.java.template`, into
`build/generated/sources/stateFields/java/main/com/nettarion/stride/simulator/StateFields.java`:
the copy, raw-equality, digest and boundary-wire plumbing of all three copies.
Edit the table, never the generated file.

Run it by hand with `python3 generate.py state-fields.tsv <output dir>`; `--help`
describes the arguments. Every bad row is reported as `state-fields.tsv:<line>: <reason>`
and the script exits with status 1.

## Columns

Rows are tab-separated; lines starting with `#` are comments. Row order is digest
order: the digest folds fields top to bottom. The wire orders fields alphabetically
by name, so row order does not change the wire.

| column   | allowed values | meaning |
|----------|----------------|---------|
| `owner`  | `client`, `server`, `publisher` | Which copy declares the field. A `server` row names only a field the server copy adds; the server inherits every `client` row. |
| `name`   | a Java field name | The public field in the owner's class. |
| `type`   | `double`, `float`, `boolean`, `int`, `long`, `byte`, an enum's binary class name such as `com.nettarion.stride.simulator.PlayerState$Pose`, or `tail` | The field's Java type. `tail` is the server's retained movement list, copied and digested as a whole and absent from the wire. The type string is also the wire's type label, verbatim. |
| `digest` | `raw`, `bool`, `int`, `long`, `unsigned`, `id`, `ordinal`, `tail`, `again`, `none` | How `StateDigest` folds the field: raw float or double bits, a boolean byte, an int, a long, an unsigned byte, an enum's vanilla `id`, an enum's ordinal, the movement list, a second fold of an inherited client field into the server digest (the field is neither copied nor written again by the server), or not at all. Each value applies to one type; the generator refuses a mismatch. |
| `schema` | an integer, 1 or more | The first boundary schema that carries the field on the wire. Only `server` rows may be above 1; a gated field is written and read only when the stream's schema is at least this value. |

## Adding a field

A new field must be added in four places, or the build's reflection guards
(`StateDigestCoverageTest`, `PlayerStateCopyTest`, `BoundarySchemaTest`) fail:

1. A row in `state-fields.tsv`, in the digest position it should fold at, with the
   schema bumped if the boundary wire must stay readable by older readers.
2. The public field declaration in `PlayerState`, `ServerPlayerState` or `Publisher`.
3. A `StateField` constant in `com.nettarion.stride.simulator.trace`, appended in the
   position it should take as a trace column, together with a `Trace.FORMAT_VERSION`
   bump because the column set changed.
4. Its assignment in both directions of `StateVector.of` and `StateVector.toPlayerState`.

`StateEncodingStabilityTest` pins the digest and wire bytes of a fixed state. Adding a
field changes both; re-record its constants in the same change and say so in the
change description, since every recorded digest and boundary stream depends on them.
