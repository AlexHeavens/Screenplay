#!/usr/bin/env python3
"""Fails when a schema changed in a way that stops it accepting data the last released schema accepted.

Usage: schema-diff.py OLD.json NEW.json            compare two files
       schema-diff.py [--base REF] [schemas-dir]   compare every schemas/<name>/vN.json with the same file at git REF

REF defaults to the newest tag `v<digit>...` (an app release), or origin/main while there is none (the `catalogue` tag is data, not a release).
A file that does not exist at REF is a new version and is not compared: a new version may break the old one. Every other change must be
additive. The rule: the new schema accepts every document the old one accepted. That fails on
  a removed property (also one dropped from `x-column-order`), a changed `type`, a narrower range (`minimum` up, `maximum` down, the same for
  exclusive bounds, lengths and item counts), an `enum` that lost a value, a changed `const` or `pattern`, a property that became `required`,
  `additionalProperties` that became stricter, an `x-column-order` that does not keep the old columns first and in order,
  an `x-header-lines` that changed, and any other keyword that differs (so the check cannot miss something it does not understand).
It passes on a new optional property, a new column appended to `x-column-order`, a wider range, a new `enum` value and changed descriptions.
Standard library only.
"""
import argparse
import importlib.util
import json
import os
import subprocess
import sys

_spec = importlib.util.spec_from_file_location("schemalib", os.path.join(os.path.dirname(os.path.abspath(__file__)), "schemalib.py"))
assert _spec is not None and _spec.loader is not None
schemalib = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(schemalib)

LOWER = ("minimum", "exclusiveMinimum", "minLength", "minItems")  # a larger new value is narrower
UPPER = ("maximum", "exclusiveMaximum", "maxLength", "maxItems")  # a smaller new value is narrower
HANDLED = {"type", "enum", "const", "pattern", "required", "properties", "additionalProperties", "items", "anyOf", "oneOf", "allOf", "$ref", "$defs",
           "x-column-order", "x-header-lines"} | set(LOWER) | set(UPPER)


def as_list(types):
    return [types] if isinstance(types, str) else list(types)


def compare(old, new, old_root, new_root, where="#"):
    """The ways [new] accepts less than [old], as messages."""
    old, new = schemalib.resolve(old, old_root), schemalib.resolve(new, new_root)
    if old is True or new is True:
        return [] if new is True else ["%s: the schema now rejects values it accepted" % where]
    if old is False:
        return []
    if new is False:
        return ["%s: the schema now rejects every value" % where]
    out = []
    if as_list(old.get("type", [])) != as_list(new.get("type", [])):
        out.append("%s: type changed from %s to %s" % (where, old.get("type"), new.get("type")))
    for key in LOWER:
        if key in new and (key not in old or new[key] > old[key]):
            out.append("%s: %s is narrower (%s -> %s)" % (where, key, old.get(key, "none"), new[key]))
    for key in UPPER:
        if key in new and (key not in old or new[key] < old[key]):
            out.append("%s: %s is narrower (%s -> %s)" % (where, key, old.get(key, "none"), new[key]))
    if "enum" in new:
        lost = [v for v in old["enum"] if v not in new["enum"]] if "enum" in old else None
        if lost is None:
            out.append("%s: an enum was added, so values are now rejected" % where)
        elif lost:
            out.append("%s: enum lost %s" % (where, lost))
    for key in ("const", "pattern"):
        if old.get(key) != new.get(key):
            out.append("%s: %s changed (%s -> %s)" % (where, key, old.get(key), new.get(key)))
    for name in new.get("required", []):
        if name not in old.get("required", []):
            out.append("%s: %r is now required" % (where, name))
    old_props, new_props = old.get("properties", {}), new.get("properties", {})
    for name in old_props:
        if name not in new_props:
            out.append("%s: property %r was removed" % (where, name))
        else:
            out += compare(old_props[name], new_props[name], old_root, new_root, "%s.%s" % (where, name))
    old_extra, new_extra = old.get("additionalProperties", True), new.get("additionalProperties", True)
    if old_extra != new_extra:
        out += compare(old_extra, new_extra, old_root, new_root, where + ".additionalProperties")
    if "items" in old or "items" in new:
        out += compare(old.get("items", True), new.get("items", True), old_root, new_root, where + "[]")
    for key in ("anyOf", "oneOf", "allOf"):
        olds, news = old.get(key, []), new.get(key, [])
        if key == "anyOf":
            # An alternative may be added, never dropped or narrowed: each old alternative must still be there, in place.
            if len(news) < len(olds):
                out.append("%s: anyOf lost an alternative" % where)
        elif len(news) != len(olds):
            out.append("%s: %s changed from %d to %d alternatives" % (where, key, len(olds), len(news)))
        for i, (o, n) in enumerate(zip(olds, news, strict=False)):
            out += compare(o, n, old_root, new_root, "%s/%s/%d" % (where, key, i))
    old_cols, new_cols = old.get("x-column-order"), new.get("x-column-order")
    if old_cols is not None and (new_cols is None or new_cols[:len(old_cols)] != old_cols):
        out.append("%s: x-column-order must keep %s first and in order, got %s" % (where, old_cols, new_cols))
    if old.get("x-header-lines") != new.get("x-header-lines"):
        out.append("%s: x-header-lines changed" % where)
    for key in set(old) | set(new):
        if key in HANDLED or key in schemalib.ANNOTATIONS or key.startswith("x-"):
            continue
        if old.get(key) != new.get(key):
            out.append("%s: %s changed, and this check does not know whether that is safe" % (where, key))
    return out


def git_show(ref, path):
    done = subprocess.run(["git", "show", "%s:%s" % (ref, path)], capture_output=True, text=True, check=False)
    return done.stdout if done.returncode == 0 else None


def default_base():
    tags = subprocess.run(["git", "tag", "--list", "v[0-9]*", "--sort=-v:refname"], capture_output=True, text=True, check=False).stdout.split()
    return tags[0] if tags else "origin/main"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("paths", nargs="*", help="OLD.json NEW.json, or a schemas directory (default: schemas)")
    parser.add_argument("--base", help="git ref of the last release (default: newest v<digit> tag, else origin/main)")
    args = parser.parse_args()
    pairs = []  # (path of the new schema, old schema)
    if len(args.paths) == 2 and args.paths[0].endswith(".json"):
        pairs.append((args.paths[1], schemalib.load(args.paths[0])))
    else:
        base = args.base or default_base()
        root = args.paths[0] if args.paths else "schemas"
        print("comparing %s with the schemas at %s" % (root, base))
        for dirpath, _, files in sorted(os.walk(root)):
            for name in sorted(files):
                path = os.path.join(dirpath, name)
                if not name.endswith(".json"):
                    continue
                text = git_show(base, os.path.relpath(path))
                if text is None:
                    print("new, not compared: " + path)
                else:
                    pairs.append((path, json.loads(text)))
    problems = {}
    for path, old in pairs:
        new = schemalib.load(path)
        try:
            schemalib.check_schema(old)
            schemalib.check_schema(new)
            found = compare(old, new, old, new)
        except schemalib.SchemaError as e:
            found = [str(e)]
        if found:
            problems[path] = found
        else:
            print("compatible: " + path)
    for path, found in problems.items():
        print("BREAKING CHANGE in %s:" % path, file=sys.stderr)
        for message in found:
            print("  " + message, file=sys.stderr)
    if problems:
        print("A change that breaks old data needs a new version file (vN+1.json), a migration and a new golden folder: see docs/data-specs.md", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
