"""Tests for schema-diff.py, validate-schema.py and schemalib.py. Run: cd scripts && python3 -m unittest test_schema_diff"""
import copy
import importlib.util
import json
import lzma
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, file))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


diff = load("schema_diff", "schema-diff.py")
validate = load("validate_schema", "validate-schema.py")
schemalib = diff.schemalib

BASE = {
    "type": "object",
    "required": ["id"],
    "properties": {
        "id": {"type": "integer", "minimum": 1, "maximum": 100},
        "kind": {"type": "string", "enum": ["a", "b"]},
        "tags": {"type": "array", "items": {"type": "integer"}, "maxItems": 5},
    },
    "additionalProperties": True,
}


def changed(edit):
    new = copy.deepcopy(BASE)
    edit(new)
    return diff.compare(BASE, new, BASE, new)


class SchemaDiffPasses(unittest.TestCase):
    def test_same_schema(self):
        self.assertEqual([], changed(lambda s: None))

    def test_new_optional_property(self):
        self.assertEqual([], changed(lambda s: s["properties"].update(extra={"type": "string"})))

    def test_wider_range_and_enum_and_description(self):
        def edit(s):
            s["properties"]["id"]["maximum"] = 200
            s["properties"]["id"]["minimum"] = 0
            s["properties"]["kind"]["enum"].append("c")
            s["properties"]["tags"]["maxItems"] = 9
            s["properties"]["kind"]["description"] = "reworded"
        self.assertEqual([], changed(edit))

    def test_column_appended(self):
        old = {"type": "object", "properties": {"a": {"type": "string"}}, "x-column-order": ["a"]}
        new = {"type": "object", "properties": {"a": {"type": "string"}, "b": {"type": "string"}}, "x-column-order": ["a", "b"]}
        self.assertEqual([], diff.compare(old, new, old, new))

    def test_anyof_alternative_added(self):
        old = {"anyOf": [{"type": "string"}]}
        new = {"anyOf": [{"type": "string"}, {"type": "null"}]}
        self.assertEqual([], diff.compare(old, new, old, new))


class SchemaDiffFails(unittest.TestCase):
    def assert_fails(self, edit, text):
        found = changed(edit)
        self.assertTrue(any(text in m for m in found), found)

    def test_removed_property(self):
        self.assert_fails(lambda s: s["properties"].pop("kind"), "'kind' was removed")

    def test_changed_type(self):
        self.assert_fails(lambda s: s["properties"]["id"].update(type="string"), "type changed")

    def test_narrower_minimum(self):
        self.assert_fails(lambda s: s["properties"]["id"].update(minimum=2), "minimum is narrower")

    def test_narrower_maximum(self):
        self.assert_fails(lambda s: s["properties"]["id"].update(maximum=50), "maximum is narrower")

    def test_narrower_items(self):
        self.assert_fails(lambda s: s["properties"]["tags"].update(maxItems=2), "maxItems is narrower")

    def test_range_added_where_there_was_none(self):
        self.assert_fails(lambda s: s["properties"]["tags"]["items"].update(minimum=0), "minimum is narrower")

    def test_enum_loses_value(self):
        self.assert_fails(lambda s: s["properties"]["kind"].update(enum=["a"]), "enum lost ['b']")

    def test_new_required(self):
        self.assert_fails(lambda s: s.update(required=["id", "kind"]), "'kind' is now required")

    def test_additional_properties_closed(self):
        self.assert_fails(lambda s: s.update(additionalProperties=False), "rejects")

    def test_nested_change_is_found(self):
        self.assert_fails(lambda s: s["properties"]["tags"]["items"].update(type="string"), "tags[]: type changed")

    def test_unknown_keyword_difference(self):
        self.assert_fails(lambda s: s["properties"]["id"].update(multipleOf=5), "multipleOf changed")

    def test_column_dropped_or_reordered(self):
        old = {"type": "object", "properties": {"a": {}, "b": {}}, "x-column-order": ["a", "b"]}
        for cols in (["a"], ["b", "a"], ["a", "c", "b"]):
            new = {"type": "object", "properties": {"a": {}, "b": {}}, "x-column-order": cols}
            self.assertTrue(diff.compare(old, new, old, new), cols)

    def test_anyof_alternative_dropped(self):
        old = {"anyOf": [{"type": "string"}, {"type": "null"}]}
        new = {"anyOf": [{"type": "string"}]}
        self.assertTrue(diff.compare(old, new, old, new))

    def test_ref_is_followed(self):
        old = {"$defs": {"n": {"type": "integer", "minimum": 0}}, "properties": {"a": {"$ref": "#/$defs/n"}}}
        new = {"$defs": {"n": {"type": "integer", "minimum": 5}}, "properties": {"a": {"$ref": "#/$defs/n"}}}
        self.assertTrue(diff.compare(old, new, old, new))


class Validate(unittest.TestCase):
    def test_unsupported_keyword_is_an_error(self):
        with self.assertRaises(schemalib.SchemaError):
            schemalib.check_schema({"type": "string", "format": "date"})

    def test_value_checks(self):
        self.assertEqual([], schemalib.errors({"id": 5}, BASE))
        self.assertTrue(schemalib.errors({"id": 0}, BASE))
        self.assertTrue(schemalib.errors({}, BASE))
        self.assertTrue(schemalib.errors({"id": 1, "kind": "z"}, BASE))
        self.assertTrue(schemalib.errors({"id": True}, BASE))
        self.assertTrue(schemalib.errors({"id": 1, "tags": [1, 2, 3, 4, 5, 6]}, BASE))

    def test_tsv_rows(self):
        schema = {
            "type": "object", "required": ["id", "name", "n"], "x-column-order": ["id", "name", "n"], "x-header-lines": ["^head 1$"],
            "properties": {"id": {"type": "integer", "minimum": 1}, "name": {"type": "string"}, "n": {"type": ["integer", "null"]}},
        }
        with tempfile.TemporaryDirectory() as tmp:
            good, bad = os.path.join(tmp, "good.tsv.xz"), os.path.join(tmp, "bad.tsv")
            with lzma.open(good, "wt", encoding="utf-8") as f:
                f.write("head 1\n1\tone\t\n2\ttwo\t7\n")
            self.assertEqual(([], 2), validate.validate(schema, good))
            with open(bad, "w", encoding="utf-8") as f:
                f.write("head 2\n0\tone\t\nx\ttwo\t7\n3\tshort\n")
            problems, _ = validate.validate(schema, bad)
            self.assertEqual(4, len(problems), problems)

    def test_json_document(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "d.json")
            with open(path, "w") as f:
                json.dump({"id": 3}, f)
            self.assertEqual(([], 1), validate.validate(BASE, path))
            with open(path, "w") as f:
                json.dump({"id": "x"}, f)
            self.assertTrue(validate.validate(BASE, path)[0])


if __name__ == "__main__":
    unittest.main()
