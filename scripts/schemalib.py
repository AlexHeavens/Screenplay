"""The JSON Schema subset the data specs use (draft 2020-12), shared by validate-schema.py and schema-diff.py.

Standard library only, so CI and the publisher need no `pip install`. A schema that uses a keyword outside the subset is an error, never
silently ignored: a check that skips a keyword would pass data the schema was written to reject.

Supported: type, enum, const, minimum, maximum, exclusiveMinimum, exclusiveMaximum, minLength, maxLength, pattern, required, properties,
additionalProperties (boolean or schema), items (one schema), minItems, maxItems, anyOf, oneOf, allOf, local `$ref` ("#/$defs/name"), `$defs`.
Annotations that change nothing: $schema, $id, $comment, title, description, examples, default, deprecated. Any `x-` keyword is ignored here;
the tools that read one say so (x-column-order, x-header-lines).
"""
import json
import re

ANNOTATIONS = {"$schema", "$id", "$comment", "title", "description", "examples", "default", "deprecated"}
KEYWORDS = {
    "type", "enum", "const", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength", "pattern", "required",
    "properties", "additionalProperties", "items", "minItems", "maxItems", "anyOf", "oneOf", "allOf", "$ref", "$defs",
}
TYPES = {"string", "integer", "number", "boolean", "null", "array", "object"}


class SchemaError(Exception):
    """The schema itself is outside the supported subset or malformed."""


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def check_schema(schema, where="#"):
    """Raises SchemaError for a keyword outside the subset, anywhere in [schema]."""
    if isinstance(schema, bool):
        return
    if not isinstance(schema, dict):
        raise SchemaError("%s: a schema is an object or a boolean" % where)
    for key, value in schema.items():
        if key in ANNOTATIONS or key.startswith("x-"):
            continue
        if key not in KEYWORDS:
            raise SchemaError("%s: unsupported keyword %r (see scripts/schemalib.py)" % (where, key))
        if key == "type":
            for t in [value] if isinstance(value, str) else value:
                if t not in TYPES:
                    raise SchemaError("%s: unknown type %r" % (where, t))
        elif key in ("properties", "$defs"):
            for name, sub in value.items():
                check_schema(sub, "%s/%s/%s" % (where, key, name))
        elif key in ("items", "additionalProperties"):
            check_schema(value, "%s/%s" % (where, key))
        elif key in ("anyOf", "oneOf", "allOf"):
            for i, sub in enumerate(value):
                check_schema(sub, "%s/%s/%d" % (where, key, i))
        elif key == "$ref" and not value.startswith("#/$defs/"):
            raise SchemaError("%s: only local $ref to #/$defs/<name> is supported, got %r" % (where, value))


def resolve(schema, root):
    """The schema a `$ref` points at (following chains); any other schema is returned unchanged."""
    seen = set()
    while isinstance(schema, dict) and "$ref" in schema:
        ref = schema["$ref"]
        if ref in seen:
            raise SchemaError("circular $ref %s" % ref)
        seen.add(ref)
        try:
            schema = root["$defs"][ref[len("#/$defs/"):]]
        except KeyError:
            raise SchemaError("unresolved $ref %s" % ref) from None
    return schema


def _type_ok(value, name):
    if name == "string":
        return isinstance(value, str)
    if name == "boolean":
        return isinstance(value, bool)
    if name == "null":
        return value is None
    if name == "integer":
        return (isinstance(value, int) and not isinstance(value, bool)) or (isinstance(value, float) and value.is_integer())
    if name == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if name == "array":
        return isinstance(value, list)
    return isinstance(value, dict)


def errors(value, schema, root=None, where="$"):
    """Every way [value] breaks [schema], as "path: message" strings; empty when it is valid."""
    root = schema if root is None else root
    schema = resolve(schema, root)
    if schema is True:
        return []
    if schema is False:
        return ["%s: no value is allowed here" % where]
    out = []
    if "type" in schema:
        types = [schema["type"]] if isinstance(schema["type"], str) else schema["type"]
        if not any(_type_ok(value, t) for t in types):
            return ["%s: expected %s, got %s" % (where, "/".join(types), json.dumps(value)[:60])]
    if "enum" in schema and value not in schema["enum"]:
        out.append("%s: %s is not one of %s" % (where, json.dumps(value)[:60], json.dumps(schema["enum"])))
    if "const" in schema and value != schema["const"]:
        out.append("%s: %s is not %s" % (where, json.dumps(value)[:60], json.dumps(schema["const"])))
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if "minimum" in schema and value < schema["minimum"]:
            out.append("%s: %s is below the minimum %s" % (where, value, schema["minimum"]))
        if "maximum" in schema and value > schema["maximum"]:
            out.append("%s: %s is above the maximum %s" % (where, value, schema["maximum"]))
        if "exclusiveMinimum" in schema and value <= schema["exclusiveMinimum"]:
            out.append("%s: %s is not above %s" % (where, value, schema["exclusiveMinimum"]))
        if "exclusiveMaximum" in schema and value >= schema["exclusiveMaximum"]:
            out.append("%s: %s is not below %s" % (where, value, schema["exclusiveMaximum"]))
    if isinstance(value, str):
        if "minLength" in schema and len(value) < schema["minLength"]:
            out.append("%s: shorter than %d characters" % (where, schema["minLength"]))
        if "maxLength" in schema and len(value) > schema["maxLength"]:
            out.append("%s: longer than %d characters" % (where, schema["maxLength"]))
        if "pattern" in schema and not re.search(schema["pattern"], value):
            out.append("%s: %s does not match %s" % (where, json.dumps(value)[:60], schema["pattern"]))
    if isinstance(value, list):
        if "minItems" in schema and len(value) < schema["minItems"]:
            out.append("%s: fewer than %d items" % (where, schema["minItems"]))
        if "maxItems" in schema and len(value) > schema["maxItems"]:
            out.append("%s: more than %d items" % (where, schema["maxItems"]))
        if "items" in schema:
            for i, item in enumerate(value):
                out += errors(item, schema["items"], root, "%s[%d]" % (where, i))
    if isinstance(value, dict):
        for name in schema.get("required", []):
            if name not in value:
                out.append("%s: missing required %r" % (where, name))
        props = schema.get("properties", {})
        for name, item in value.items():
            if name in props:
                out += errors(item, props[name], root, "%s.%s" % (where, name))
            elif "additionalProperties" in schema:
                out += errors(item, schema["additionalProperties"], root, "%s.%s" % (where, name))
    for key in ("allOf", "anyOf", "oneOf"):
        if key not in schema:
            continue
        results = [errors(value, sub, root, where) for sub in schema[key]]
        passed = sum(1 for r in results if not r)
        if key == "allOf":
            out += [e for r in results for e in r]
        elif key == "anyOf" and passed == 0:
            out.append("%s: matches none of the %d alternatives (%s)" % (where, len(results), "; ".join(r[0] for r in results)))
        elif key == "oneOf" and passed != 1:
            out.append("%s: matches %d of the %d alternatives, not exactly one" % (where, passed, len(results)))
    return out
