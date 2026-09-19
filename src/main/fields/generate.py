#!/usr/bin/env python3
"""Expand state-fields.tsv into the per-field plumbing of the three player copies.

One row per public field of the client, server and publisher copies, in
StateDigest order. From it this writes, into the directory given as the second
argument (the first argument names the field table):

  com/nettarion/stride/simulator/StateFields.java
      copy, raw equality, digest and the boundary wire for each copy, and the
      list of field names the planner's identity leaves out, so that adding a
      field is one row here and one declaration in its class.

The generated arithmetic is a transcription of the hand-written methods it
replaces: the digest folds each field in row order with the same byte forms,
and the wire writes each field in alphabetical order with the same labels and
widths. StateEncodingPinTest holds the output to the recorded bits.
"""
import sys
from pathlib import Path

WIRE_TYPE = {
    'double': ('double', 'out.writeLong(Double.doubleToRawLongBits(s.{n}))', 's.{n} = Double.longBitsToDouble(in.readLong())'),
    'float': ('float', 'out.writeInt(Float.floatToRawIntBits(s.{n}))', 's.{n} = Float.intBitsToFloat(in.readInt())'),
    'boolean': ('boolean', 'out.writeBoolean(s.{n})', 's.{n} = in.readBoolean()'),
    'int': ('int', 'out.writeInt(s.{n})', 's.{n} = in.readInt()'),
    'long': ('long', 'out.writeLong(s.{n})', 's.{n} = in.readLong()'),
    'byte': ('byte', 'out.writeByte(s.{n})', 's.{n} = in.readByte()'),
}

DIGEST = {
    'raw': {'double': 'hash = bits(hash, Double.doubleToRawLongBits(s.{n}));',
            'float': 'hash = bits(hash, Float.floatToRawIntBits(s.{n}));'},
    'bool': {'boolean': 'hash = bit(hash, s.{n});'},
    'int': {'int': 'hash = bits(hash, s.{n});'},
    'long': {'long': 'hash = bits(hash, s.{n});'},
    'unsigned': {'byte': 'hash = bits(hash, Byte.toUnsignedInt(s.{n}));'},
    'id': {'enum': 'hash = bits(hash, s.{n}.id);'},
    'ordinal': {'enum': 'hash = bits(hash, s.{n}.ordinal());'},
    'again': {'int': 'hash = bits(hash, s.{n});'},
}

RAW_EQUAL = {
    'double': 'Double.doubleToRawLongBits(a.{n}) == Double.doubleToRawLongBits(b.{n})',
    'float': 'Float.floatToRawIntBits(a.{n}) == Float.floatToRawIntBits(b.{n})',
    'enum': 'a.{n} == b.{n}',
}


def java_type(t):
    return 'enum' if '$' in t else t


def source_type(t):
    return t.replace('$', '.')


def read(table):
    rows = []
    for line in Path(table).read_text().splitlines():
        if not line or line.startswith('#') or line.startswith('owner\t'):
            continue
        owner, name, typ, digest, future, schema = line.split('\t')
        rows.append(dict(owner=owner, name=name, type=typ, digest=digest, future=future, schema=int(schema)))
    return rows


def copy_lines(rows, indent):
    out = []
    for r in rows:
        if r['type'] == 'tail':
            out.append(f"{indent}copyMovements(copy, from);")
        else:
            out.append(f"{indent}copy.{r['name']} = from.{r['name']};")
    return out


def equal_lines(rows, indent):
    out = []
    for r in rows:
        if r['type'] == 'tail':
            out.append(f"{indent}&& sameMovements(a, b)")
            continue
        form = RAW_EQUAL.get(java_type(r['type']), 'a.{n} == b.{n}')
        out.append(f"{indent}&& " + form.format(n=r['name']))
    return out


def digest_lines(rows, indent):
    out = []
    for r in rows:
        if r['digest'] == 'none':
            continue
        if r['type'] == 'tail':
            out.append(f"{indent}hash = movementsDigest(hash, s);")
            continue
        jt = java_type(r['type'])
        form = DIGEST[r['digest']][jt]
        out.append(indent + form.format(n=r['name']))
    return out


def wire_lines(rows, indent, gated):
    """Alphabetical, the wire's own order; gated rows write only under a schema that carries them."""
    write, read = [], []
    for r in sorted(rows, key=lambda r: r['name']):
        if r['type'] == 'tail':
            continue
        jt = java_type(r['type'])
        if jt == 'enum':
            label = r['type']
            w = f'out.writeUTF(s.{r["name"]}.name())'
            rd = f's.{r["name"]} = {source_type(r["type"])}.valueOf(in.readUTF())'
        else:
            label, w, rd = WIRE_TYPE[jt]
            w = w.format(n=r['name'])
            rd = rd.format(n=r['name'])
        lines_w = [f'out.writeUTF("{r["name"]}");', f'out.writeUTF("{label}");', w + ';']
        lines_r = [f'field(in, "{r["name"]}", "{label}");', rd + ';']
        if gated and r['schema'] > 1:
            write.append(f"{indent}if (schema >= {r['schema']}) {{")
            write += [f"{indent}\t{l}" for l in lines_w]
            write.append(f"{indent}}}")
            read.append(f"{indent}if (schema >= {r['schema']}) {{")
            read += [f"{indent}\t{l}" for l in lines_r]
            read.append(f"{indent}}}")
        else:
            write += [f"{indent}{l}" for l in lines_w]
            read += [f"{indent}{l}" for l in lines_r]
    return write, read


def wire_count(rows, schema):
    return sum(1 for r in rows if r['type'] != 'tail' and r['schema'] <= schema)


def names(rows, future):
    return ', '.join(f'"{r["name"]}"' for r in rows if r['future'] == future and r['type'] != 'tail')


def main(table, out_dir):
    rows = read(table)
    client = [r for r in rows if r['owner'] == 'client']
    server = [r for r in rows if r['owner'] == 'server' and r['digest'] != 'again']
    server_again = [r for r in rows if r['owner'] == 'server' and r['digest'] == 'again']
    publisher = [r for r in rows if r['owner'] == 'publisher']
    server_all = client + server  # the wire lists every field of the server copy, inherited included
    max_schema = max(r['schema'] for r in rows)

    cw, cr = wire_lines(client, '\t\t', False)
    pw, pr = wire_lines(publisher, '\t\t', False)
    sw, sr = wire_lines(server_all, '\t\t', True)
    server_digest = [r for r in rows if r['owner'] == 'server']

    java = f'''package com.nettarion.stride.simulator;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;

/**
 * The per-field plumbing of the three player copies, expanded at build time
 * from {{@code src/main/fields/state-fields.tsv}}: copy, raw equality, digest,
 * the boundary wire, and the names the planner's identity leaves out.
 *
 * <p>Generated; edit the table. Each method is a transcription of the
 * hand-written one it replaced, field for field and byte for byte, which
 * {{@code StateEncodingPinTest}} holds to the recorded bits.
 */
public final class StateFields {{
	/** Client fields the next tick overwrites before reading. */
	public static final Set<String> CLIENT_UNREAD = Set.of({names(client, 'unread')});
	/** Client fields compared through a derived value the identity names. */
	public static final Set<String> CLIENT_PARTIAL = Set.of({names(client, 'partial')});
	/** Server-only fields no surviving node reads relative to itself. */
	public static final Set<String> SERVER_UNREAD = Set.of({names(server, 'unread')});
	/** Server-only fields compared through what the next step reads of them. */
	public static final Set<String> SERVER_PARTIAL = Set.of({names(server, 'partial')});
	/** Publisher fields implied by the compared copies. */
	public static final Set<String> PUBLISHER_UNREAD = Set.of({names(publisher, 'unread')});

	/** The newest boundary schema, the one every field is carried by. */
	public static final int SCHEMA = {max_schema};

	private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
	private static final long FNV_PRIME = 0x100000001b3L;

	private StateFields() {{
	}}

	// ---- copy

	static void copy(final PlayerState copy, final PlayerState from) {{
{chr(10).join(copy_lines(client, '		'))}
	}}

	static void copy(final ServerPlayerState copy, final ServerPlayerState from) {{
{chr(10).join(copy_lines(server, '		'))}
	}}

	static void copy(final Publisher copy, final Publisher from) {{
{chr(10).join(copy_lines(publisher, '		'))}
	}}

	private static void copyMovements(final ServerPlayerState copy, final ServerPlayerState from) {{
		if (from.additionalMovements == null || from.additionalMovements.isEmpty()) {{
			if (copy.additionalMovements != null) copy.additionalMovements.clear();
		}} else {{
			if (copy.additionalMovements == null) copy.additionalMovements = new ArrayList<>();
			else copy.additionalMovements.clear();
			copy.additionalMovements.addAll(from.additionalMovements);
		}}
	}}

	// ---- raw equality

	static boolean rawEquals(final PlayerState a, final PlayerState b) {{
		return true
{chr(10).join(equal_lines(client, '			'))};
	}}

	static boolean rawEqualsOwn(final ServerPlayerState a, final ServerPlayerState b) {{
		return true
{chr(10).join(equal_lines(server, '			'))};
	}}

	static boolean rawEquals(final Publisher a, final Publisher b) {{
		return true
{chr(10).join(equal_lines(publisher, '			'))};
	}}

	private static boolean sameMovements(final ServerPlayerState a, final ServerPlayerState b) {{
		return ServerPlayerState.sameMovements(a, b);
	}}

	// ---- digest

	static long digest(long hash, final PlayerState s) {{
{chr(10).join(digest_lines(client, '		'))}
		return hash;
	}}

	static long digestOwn(long hash, final ServerPlayerState s) {{
{chr(10).join(digest_lines(server_digest, '		'))}
		return hash;
	}}

	static long digest(long hash, final Publisher s) {{
{chr(10).join(digest_lines(publisher, '		'))}
		return hash;
	}}

	private static long movementsDigest(long hash, final ServerPlayerState s) {{
		hash = bits(hash, s.additionalMovements == null ? 0 : s.additionalMovements.size());
		if (s.additionalMovements != null) for (ServerPlayerState.Movement movement : s.additionalMovements) {{
			hash = bits(hash, Double.doubleToRawLongBits(movement.fromX()));
			hash = bits(hash, Double.doubleToRawLongBits(movement.fromY()));
			hash = bits(hash, Double.doubleToRawLongBits(movement.fromZ()));
			hash = bits(hash, Double.doubleToRawLongBits(movement.toX()));
			hash = bits(hash, Double.doubleToRawLongBits(movement.toY()));
			hash = bits(hash, Double.doubleToRawLongBits(movement.toZ()));
			hash = bits(hash, Double.doubleToRawLongBits(movement.requestedX()));
			hash = bits(hash, Double.doubleToRawLongBits(movement.requestedZ()));
		}}
		return hash;
	}}

	static long bit(final long hash, final boolean value) {{
		return byteValue(hash, value ? 1 : 0);
	}}

	static long bits(long hash, final int value) {{
		for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {{
			hash = byteValue(hash, value >>> shift);
		}}
		return hash;
	}}

	static long bits(long hash, final long value) {{
		for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {{
			hash = byteValue(hash, value >>> shift);
		}}
		return hash;
	}}

	private static long byteValue(final long hash, final long value) {{
		return (hash ^ (value & 0xffL)) * FNV_PRIME;
	}}

	// ---- boundary wire, alphabetical, one label and one type name per field

	/** Fields the client wire carries. */
	public static int wireCount(final PlayerState s) {{
		return {wire_count(client, max_schema)};
	}}

	/** Fields the publisher wire carries. */
	public static int wireCount(final Publisher s) {{
		return {wire_count(publisher, max_schema)};
	}}

	/** Fields the server wire carries under {{@code schema}}, inherited included. */
	public static int wireCount(final ServerPlayerState s, final int schema) {{
		return switch (schema) {{
{chr(10).join(f"			case {k} -> {wire_count(server_all, k)};" for k in range(1, max_schema + 1))}
			default -> throw new IllegalArgumentException("unsupported boundary schema " + schema);
		}};
	}}

	/** Writes the pinned scalar schema for {{@code PlayerState}}; the caller owns the stream. */
	public static void write(final PlayerState s, final DataOutput out) throws IOException {{
{chr(10).join(cw)}
	}}

	/** Reads the pinned scalar schema for {{@code PlayerState}}; the caller owns the stream. */
	public static void read(final PlayerState s, final DataInput in) throws IOException {{
{chr(10).join(cr)}
	}}

	/** Writes the pinned scalar schema for {{@code Publisher}}; the caller owns the stream. */
	public static void write(final Publisher s, final DataOutput out) throws IOException {{
{chr(10).join(pw)}
	}}

	/** Reads the pinned scalar schema for {{@code Publisher}}; the caller owns the stream. */
	public static void read(final Publisher s, final DataInput in) throws IOException {{
{chr(10).join(pr)}
	}}

	/** Writes the pinned scalar schema for {{@code ServerPlayerState}}; the caller owns the stream. */
	public static void write(final ServerPlayerState s, final DataOutput out, final int schema) throws IOException {{
{chr(10).join(sw)}
	}}

	/** Reads the pinned scalar schema for {{@code ServerPlayerState}}; the caller owns the stream. */
	public static void read(final ServerPlayerState s, final DataInput in, final int schema) throws IOException {{
{chr(10).join(sr)}
	}}

	private static void field(final DataInput in, final String name, final String type) throws IOException {{
		if (!in.readUTF().equals(name) || !in.readUTF().equals(type)) {{
			throw new IOException("boundary field differs at " + name);
		}}
	}}
}}
'''
    target = Path(out_dir) / 'com/nettarion/stride/simulator/StateFields.java'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(java)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
