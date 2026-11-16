#!/usr/bin/env python3
"""Checks that a data file follows a JSON Schema from schemas/<name>/vN.json. Exit status 1 and a list of the first errors when it does not.

Usage: validate-schema.py SCHEMA DATAFILE [--max-errors N]

DATAFILE is one of
  *.json, *.json.xz   one JSON document, validated whole
  *.jsonl             one JSON document per line, each validated against SCHEMA
  *.tsv, *.tsv.xz     tab-separated rows. SCHEMA describes ONE ROW as an object; its `x-column-order` lists the property names in column
                      order. Cells become the types the row's properties ask for: integer and number from text, an array of integers from
                      "1,2,3" (an empty cell is []), and an empty cell is null where the property allows null. Other columns are strings.
                      `x-header-lines` (optional) is a list of regular expressions, one per leading line that is not a row.
                      A row with a different number of columns than `x-column-order` is an error.
The schema may use only the keywords scripts/schemalib.py lists. Standard library only.
"""
import argparse
import importlib.util
import json
import lzma
import os
import re
import sys

_spec = importlib.util.spec_from_file_location("schemalib", os.path.join(os.path.dirname(os.path.abspath(__file__)), "schemalib.py"))
schemalib = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(schemalib)


def open_text(path):
    return lzma.open(path, "rt", encoding="utf-8", newline="\n") if path.endswith(".xz") else open(path, encoding="utf-8", newline="\n")


def cell(text, prop, root):
    """The cell's text as the type the row property asks for; text that cannot be that type stays text, so the schema reports it."""
    prop = schemalib.resolve(prop, root)
    types = prop.get("type", [])
    types = [types] if isinstance(types, str) else types
    if text == "" and "null" in types:
        return None
    if "array" in types:
        item_types = schemalib.resolve(prop.get("items", {}), root).get("type", "string")
        if text == "":
            return []
        return [coerce(part, item_types) for part in text.split(",")]
    return coerce(text, types)


def coerce(text, types):
    types = [types] if isinstance(types, str) else types
    for t in types:
        try:
            if t == "integer":
                return int(text)
            if t == "number":
                return float(text)
        except ValueError:
            pass
    return text


def rows(path, schema):
    """(line number, object or error text) for every row of a TSV file."""
    columns = schema.get("x-column-order")
    if not columns:
        raise schemalib.SchemaError("a TSV file needs `x-column-order` in the schema")
    headers = schema.get("x-header-lines", [])
    with open_text(path) as f:
        for number, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if number <= len(headers):
                if not re.search(headers[number - 1], line):
                    yield number, "header line %d %s does not match %s" % (number, json.dumps(line[:60]), headers[number - 1])
                continue
            fields = line.split("\t")
            if len(fields) != len(columns):
                yield number, "%d columns, expected %d" % (len(fields), len(columns))
                continue
            yield number, {name: cell(text, schema.get("properties", {}).get(name, {}), schema) for name, text in zip(columns, fields)}


def validate(schema, path, max_errors=20):
    """The error messages for the file at [path]; empty when it is valid."""
    schemalib.check_schema(schema)
    found = []
    count = 0
    if ".tsv" in path:
        for number, row in rows(path, schema):
            count += 1
            problems = [row] if isinstance(row, str) else schemalib.errors(row, schema)
            found += ["line %d: %s" % (number, p) for p in problems]
            if len(found) >= max_errors:
                break
    elif path.endswith(".jsonl"):
        with open_text(path) as f:
            for number, line in enumerate(f, 1):
                if line.strip():
                    count += 1
                    found += ["line %d: %s" % (number, p) for p in schemalib.errors(json.loads(line), schema)]
                if len(found) >= max_errors:
                    break
    else:
        with open_text(path) as f:
            count = 1
            found = schemalib.errors(json.load(f), schema)
    if count == 0:
        found.append("the file holds no data")
    return found[:max_errors], count


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("schema")
    parser.add_argument("datafile")
    parser.add_argument("--max-errors", type=int, default=20)
    args = parser.parse_args()
    try:
        problems, count = validate(schemalib.load(args.schema), args.datafile, args.max_errors)
    except (schemalib.SchemaError, OSError, ValueError, lzma.LZMAError) as e:
        sys.exit("%s: %s" % (args.datafile, e))
    if problems:
        print("%s does not follow %s (first %d errors):" % (args.datafile, args.schema, len(problems)), file=sys.stderr)
        for p in problems:
            print("  " + p, file=sys.stderr)
        sys.exit(1)
    print("%s follows %s (%d %s)" % (args.datafile, args.schema, count, "rows" if ".tsv" in args.datafile or args.datafile.endswith(".jsonl") else "document"))


if __name__ == "__main__":
    main()
