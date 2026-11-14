"""The run file every engine writes, so any two runs can be scored and compared.

{"name", "engine", "settings", "host", "commit", "date",
 "cases": {case: {"pages", "render_s", "recognise_s", "layout_ms", "text"}}}
Times are per page. "text" is what the app would speak for that case (for ML Kit, after Layout).
"""
import datetime
import json
import os
import platform
import subprocess

from corpus import HERE

RESULTS = os.path.join(HERE, "results")


def commit():
    return subprocess.run(["git", "-C", HERE, "rev-parse", "--short", "HEAD"], capture_output=True, text=True, check=False).stdout.strip()


def write(name, engine, settings, host, cases, out_dir=RESULTS):
    os.makedirs(out_dir, exist_ok=True)
    run = {"name": name, "engine": engine, "settings": settings, "host": host, "commit": commit(),
           "date": datetime.date.today().isoformat(), "cases": cases}
    path = os.path.join(out_dir, f"{name}.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(run, f, ensure_ascii=False, indent=1)
    return path


def read(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def desktop_host():
    return f"desktop {platform.machine()} {os.cpu_count()} cores"
