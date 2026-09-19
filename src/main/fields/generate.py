#!/usr/bin/env python3
"""Expand state-fields.tsv into StateFields.java.

Input: a tab-separated table with a header row ``owner name type digest schema`` and one row
per public field of the client (``PlayerState``), server (``ServerPlayerState``) and publisher
(``Publisher``) copies, in digest order. Lines starting with ``#`` are comments. Columns:

  owner   ``client``, ``server`` or ``publisher``. Server rows name only the fields the server
          copy adds; it inherits every client row.
  name    the Java field name.
  type    ``double``, ``float``, ``boolean``, ``int``, ``long``, ``byte``, an enum's binary
          class name (containing ``$``), or ``tail`` for the retained movement list.
  digest  how StateDigest folds the field: ``raw`` (float/double bits), ``bool``, ``int``,
          ``long``, ``unsigned`` (byte), ``id`` (enum ``.id``), ``ordinal`` (enum ordinal),
          ``tail``, ``again`` (a second fold of an inherited client field into the server
          digest; the field is neither copied nor written again), or ``none``.
  schema  the first boundary schema that carries the field. Only server rows may be above 1;
          the client and publisher wires are not gated.

Output: ``com/nettarion/stride/simulator/StateFields.java`` under the output directory, from
``StateFields.java.template`` next to this script, with these rules:

  copy      one assignment per row in table order; ``tail`` copies the movement list.
  equality  raw-bit comparison for float and double, ``==`` otherwise.
  digest    one fold per row in table order, in the form the digest column names.
  wire      every non-tail field in alphabetical order as name, type label and value, where
            the label is the type column verbatim; server rows above schema 1 are written and
            read only under ``schema >= N``.

The wire and digest are pinned bit for bit by StateEncodingStabilityTest; a change to either
is a format change.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

OWNERS = ("client", "server", "publisher")
SCALAR_TYPES = ("double", "float", "boolean", "int", "long", "byte")
TAIL = "tail"
INDENT = "\t\t"

WIRE_FORMS: dict[str, tuple[str, str]] = {
    "double": ("out.writeLong(Double.doubleToRawLongBits(s.{n}))", "s.{n} = Double.longBitsToDouble(in.readLong())"),
    "float": ("out.writeInt(Float.floatToRawIntBits(s.{n}))", "s.{n} = Float.intBitsToFloat(in.readInt())"),
    "boolean": ("out.writeBoolean(s.{n})", "s.{n} = in.readBoolean()"),
    "int": ("out.writeInt(s.{n})", "s.{n} = in.readInt()"),
    "long": ("out.writeLong(s.{n})", "s.{n} = in.readLong()"),
    "byte": ("out.writeByte(s.{n})", "s.{n} = in.readByte()"),
}

DIGEST_FORMS: dict[str, dict[str, str]] = {
    "raw": {
        "double": "hash = bits(hash, Double.doubleToRawLongBits(s.{n}));",
        "float": "hash = bits(hash, Float.floatToRawIntBits(s.{n}));",
    },
    "bool": {"boolean": "hash = bit(hash, s.{n});"},
    "int": {"int": "hash = bits(hash, s.{n});"},
    "long": {"long": "hash = bits(hash, s.{n});"},
    "unsigned": {"byte": "hash = bits(hash, Byte.toUnsignedInt(s.{n}));"},
    "id": {"enum": "hash = bits(hash, s.{n}.id);"},
    "ordinal": {"enum": "hash = bits(hash, s.{n}.ordinal());"},
    "again": {"int": "hash = bits(hash, s.{n});"},
    "tail": {TAIL: "hash = movementsDigest(hash, s);"},
    "none": {},
}

RAW_EQUAL_FORMS: dict[str, str] = {
    "double": "Double.doubleToRawLongBits(a.{n}) == Double.doubleToRawLongBits(b.{n})",
    "float": "Float.floatToRawIntBits(a.{n}) == Float.floatToRawIntBits(b.{n})",
}


class TableError(Exception):
    """A table row that cannot be expanded, with its ``file:line:`` location."""


@dataclass(frozen=True)
class FieldRow:
    """One row of the table."""

    owner: str
    name: str
    type: str
    digest: str
    schema: int

    @property
    def java_type(self) -> str:
        """The type as the forms above key it: a scalar, ``enum`` or ``tail``."""
        return "enum" if "$" in self.type else self.type

    @property
    def source_type(self) -> str:
        """The type as Java source spells it."""
        return self.type.replace("$", ".")


def read_table(table: Path) -> list[FieldRow]:
    """Parse and validate the table, raising TableError at the first bad row."""
    rows: list[FieldRow] = []
    seen: dict[tuple[str, str], int] = {}
    header_seen = False
    for line_number, line in enumerate(table.read_text(encoding="utf-8").splitlines(), start=1):
        location = f"{table}:{line_number}:"
        if not line or line.startswith("#"):
            continue
        cells = line.split("\t")
        if not header_seen:
            if cells != ["owner", "name", "type", "digest", "schema"]:
                raise TableError(f"{location} expected header 'owner name type digest schema', got {cells}")
            header_seen = True
            continue
        if len(cells) != 5:
            raise TableError(f"{location} expected 5 tab-separated cells, got {len(cells)}")
        owner, name, type_name, digest, schema_text = cells
        if owner not in OWNERS:
            raise TableError(f"{location} unknown owner {owner!r}; expected one of {OWNERS}")
        if not re.fullmatch(r"[a-z][A-Za-z0-9]*", name):
            raise TableError(f"{location} {name!r} is not a Java field name")
        if type_name not in SCALAR_TYPES and type_name != TAIL and "$" not in type_name:
            raise TableError(f"{location} unknown type {type_name!r}; expected a scalar, an enum binary name or 'tail'")
        if digest not in DIGEST_FORMS:
            raise TableError(f"{location} unknown digest {digest!r}; expected one of {sorted(DIGEST_FORMS)}")
        row = FieldRow(owner, name, type_name, digest, 0)
        if digest != "none" and row.java_type not in DIGEST_FORMS[digest]:
            raise TableError(f"{location} digest {digest!r} does not apply to type {type_name!r}")
        if (type_name == TAIL) != (digest == TAIL):
            raise TableError(f"{location} type 'tail' and digest 'tail' go together")
        if type_name == TAIL and owner != "server":
            raise TableError(f"{location} only the server copy has a tail")
        if digest == "again" and owner != "server":
            raise TableError(f"{location} digest 'again' is for server rows refolding a client field")
        try:
            schema = int(schema_text)
        except ValueError:
            raise TableError(f"{location} schema {schema_text!r} is not an integer") from None
        if schema < 1:
            raise TableError(f"{location} schema must be at least 1")
        if owner != "server" and schema != 1:
            raise TableError(f"{location} only server rows may be gated by a schema above 1")
        key = (owner, name)
        if key in seen:
            raise TableError(f"{location} duplicate {owner} field {name!r} (first at line {seen[key]})")
        seen[key] = line_number
        rows.append(FieldRow(owner, name, type_name, digest, schema))
    if not header_seen:
        raise TableError(f"{table}:1: the table has no header row")
    client_names = {row.name for row in rows if row.owner == "client"}
    for row in rows:
        if row.owner == "server" and row.digest == "again" and row.name not in client_names:
            raise TableError(f"{table}: server field {row.name!r} is digested 'again' but no client row declares it")
        if row.owner == "server" and row.digest != "again" and row.name in client_names:
            raise TableError(f"{table}: server field {row.name!r} shadows a client row; digest it 'again'")
    return rows


def copy_lines(rows: list[FieldRow]) -> str:
    lines = []
    for row in rows:
        if row.type == TAIL:
            lines.append(f"{INDENT}copyMovements(copy, from);")
        else:
            lines.append(f"{INDENT}copy.{row.name} = from.{row.name};")
    return "\n".join(lines)


def equality_lines(rows: list[FieldRow]) -> str:
    lines = []
    for row in rows:
        if row.type == TAIL:
            lines.append(f"{INDENT}\t&& sameMovements(a, b)")
            continue
        form = RAW_EQUAL_FORMS.get(row.java_type, "a.{n} == b.{n}")
        lines.append(f"{INDENT}\t&& " + form.format(n=row.name))
    return "\n".join(lines)


def digest_lines(rows: list[FieldRow]) -> str:
    lines = []
    for row in rows:
        if row.digest == "none":
            continue
        lines.append(INDENT + DIGEST_FORMS[row.digest][row.java_type].format(n=row.name))
    return "\n".join(lines)


def wire_lines(rows: list[FieldRow], gated: bool) -> tuple[str, str]:
    """The write and read bodies: alphabetical, the wire's own order; gated rows only under their schema."""
    write: list[str] = []
    read: list[str] = []
    for row in sorted(rows, key=lambda r: r.name):
        if row.type == TAIL:
            continue
        if row.java_type == "enum":
            label = row.type
            write_form = f"out.writeUTF(s.{row.name}.name())"
            read_form = f"s.{row.name} = {row.source_type}.valueOf(in.readUTF())"
        else:
            label = row.type
            write_form, read_form = (form.format(n=row.name) for form in WIRE_FORMS[row.java_type])
        write_statements = [f'out.writeUTF("{row.name}");', f'out.writeUTF("{label}");', write_form + ";"]
        read_statements = [f'field(in, "{row.name}", "{label}");', read_form + ";"]
        if gated and row.schema > 1:
            write.append(f"{INDENT}if (schema >= {row.schema}) {{")
            write.extend(f"{INDENT}\t{statement}" for statement in write_statements)
            write.append(f"{INDENT}}}")
            read.append(f"{INDENT}if (schema >= {row.schema}) {{")
            read.extend(f"{INDENT}\t{statement}" for statement in read_statements)
            read.append(f"{INDENT}}}")
        else:
            write.extend(f"{INDENT}{statement}" for statement in write_statements)
            read.extend(f"{INDENT}{statement}" for statement in read_statements)
    return "\n".join(write), "\n".join(read)


def wire_count(rows: list[FieldRow], schema: int) -> int:
    return sum(1 for row in rows if row.type != TAIL and row.schema <= schema)


def expand(template: str, rows: list[FieldRow]) -> str:
    """Substitute every placeholder of the template from the rows."""
    client = [row for row in rows if row.owner == "client"]
    # The server copy inherits every client field, so its copy and equality cover only its own
    # rows and its wire lists every field; an 'again' row is a digest-only refold.
    server_own = [row for row in rows if row.owner == "server" and row.digest != "again"]
    server_digest = [row for row in rows if row.owner == "server"]
    publisher = [row for row in rows if row.owner == "publisher"]
    server_wire = client + server_own
    newest_schema = max(row.schema for row in rows)

    client_write, client_read = wire_lines(client, gated=False)
    publisher_write, publisher_read = wire_lines(publisher, gated=False)
    server_write, server_read = wire_lines(server_wire, gated=True)
    server_wire_counts = "\n".join(
        f"{INDENT}\tcase {schema} -> {wire_count(server_wire, schema)};" for schema in range(1, newest_schema + 1)
    )

    substitutions = {
        "@SCHEMA@": str(newest_schema),
        "@CLIENT_COPY@": copy_lines(client),
        "@SERVER_COPY@": copy_lines(server_own),
        "@PUBLISHER_COPY@": copy_lines(publisher),
        "@CLIENT_EQUALS@": equality_lines(client),
        "@SERVER_EQUALS@": equality_lines(server_own),
        "@PUBLISHER_EQUALS@": equality_lines(publisher),
        "@CLIENT_DIGEST@": digest_lines(client),
        "@SERVER_DIGEST@": digest_lines(server_digest),
        "@PUBLISHER_DIGEST@": digest_lines(publisher),
        "@CLIENT_WIRE_COUNT@": str(wire_count(client, newest_schema)),
        "@PUBLISHER_WIRE_COUNT@": str(wire_count(publisher, newest_schema)),
        "@SERVER_WIRE_COUNTS@": server_wire_counts,
        "@CLIENT_WRITE@": client_write,
        "@CLIENT_READ@": client_read,
        "@PUBLISHER_WRITE@": publisher_write,
        "@PUBLISHER_READ@": publisher_read,
        "@SERVER_WRITE@": server_write,
        "@SERVER_READ@": server_read,
    }
    source = template
    for placeholder, text in substitutions.items():
        if placeholder not in source:
            raise TableError(f"template lacks the {placeholder} placeholder")
        source = source.replace(placeholder, text)
    unexpanded = re.search(r"@[A-Z_]+@", source)
    if unexpanded:
        raise TableError(f"template contains an unexpanded placeholder {unexpanded.group(0)}")
    return source


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Expand the state field table into StateFields.java.")
    parser.add_argument("table", type=Path, help="the state-fields.tsv to expand")
    parser.add_argument("output_dir", type=Path, help="the generated-sources root to write under")
    arguments = parser.parse_args(argv)
    template_path = Path(__file__).with_name("StateFields.java.template")
    target = arguments.output_dir / "com/nettarion/stride/simulator/StateFields.java"
    try:
        rows = read_table(arguments.table)
        source = expand(template_path.read_text(encoding="utf-8"), rows)
    except (TableError, OSError) as failure:
        print(f"generate.py: {failure}", file=sys.stderr)
        return 1
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("w", encoding="utf-8", newline="\n") as output:
        output.write(source)
    print(f"wrote {target} ({len(rows)} fields)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
