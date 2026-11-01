"""Re-derive every committed native in cache/ from its upstream jar and diff the result.

Run with the relocation venv so lief is importable:
    .venv/Scripts/python.exe verify_natives.py        (Windows)
    .venv/bin/python verify_natives.py                (nix)

Exits non-zero if any native cannot be reproduced.
"""
import argparse
import hashlib
import io
import os
import subprocess
import sys
import zipfile
from pathlib import Path

try:
    import lief
except ImportError:
    sys.exit("lief is not installed; run prepare.ps1 / prepare.sh and use the .venv interpreter.")

ROOT = Path(__file__).resolve().parent
CACHE = ROOT / "cache"

# Mirrors the NativeTransformer blocks in buildSrc/src/main/groovy/dh-loader.gradle.
RULES = [
    {
        "name": "zstd-jni",
        "group": "com.github.luben",
        "artifact": "zstd-jni",
        "version_property": "zstd_version",
        "owns": lambda p: "libzstd-jni_dh" in p,
        "to_jar_entry": lambda p: p.replace("libzstd-jni_dh", "libzstd-jni"),
        "replacements": [("com/github/luben", "dhcomgithubluben"), ("com_github_luben", "dhcomgithubluben")],
    },
    {
        "name": "sqlite-jdbc",
        "group": "org.xerial",
        "artifact": "sqlite-jdbc",
        "version_property": "sqlite_jdbc_version",
        "owns": lambda p: p.startswith("dh_sqlite/"),
        "to_jar_entry": lambda p: "org/sqlite/" + p[len("dh_sqlite/"):],
        "replacements": [("org/sqlite", "dh_sqlite"), ("org_sqlite", "dh_1sqlite")],
    },
]


def replace_in_null_terminated_strings(buf, target, replacement):
    """Port of NativeRelocator.replaceInNullTerminatedStrings, including its scan-position quirks."""
    target_bytes = target.encode("ascii")
    replacement_bytes = replacement.encode("ascii")
    if len(target_bytes) < len(replacement_bytes):
        raise ValueError("Replacement must be the same length or shorter than the target.")

    end_pos = 0
    limit = len(buf) - len(target_bytes) - 1
    while end_pos < limit:
        start_pos = end_pos
        target_pos = 0
        while target_pos < len(target_bytes) and buf[end_pos] == target_bytes[target_pos]:
            target_pos += 1
            end_pos += 1

        if target_pos == len(target_bytes):
            buf[start_pos:start_pos + len(replacement_bytes)] = replacement_bytes
            start_pos += len(replacement_bytes)
            # a shorter replacement shifts the rest of the string down and re-terminates it
            while buf[end_pos] != 0:
                buf[start_pos] = buf[end_pos]
                end_pos += 1
                start_pos += 1
            buf[start_pos] = 0

        end_pos += 1
    return buf


def read_gradle_properties():
    properties = {}
    for line in (ROOT.parent / "gradle.properties").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, _, value = line.partition("=")
            properties[key.strip()] = value.strip()
    return properties


def find_jar(rule, version, overrides):
    if rule["name"] in overrides:
        return Path(overrides[rule["name"]])

    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    search_root = gradle_home / "caches" / "modules-2" / "files-2.1" / rule["group"] / rule["artifact"] / version
    matches = sorted(search_root.glob(f"*/{rule['artifact']}-{version}.jar"))
    return matches[0] if matches else None


def exported_symbols(data):
    binary = lief.parse(io.BytesIO(bytes(data)))
    return sorted(symbol.name for symbol in binary.symbols if symbol.has_export_info)


def verify(rule, jar_path, cache_files, output_dir):
    identical = equivalent = failed = 0
    with zipfile.ZipFile(jar_path) as jar:
        entries = set(jar.namelist())
        for cache_file in cache_files:
            relative = cache_file.relative_to(CACHE).as_posix()
            entry = rule["to_jar_entry"](relative)
            if entry not in entries:
                print(f"  NOT IN JAR   {relative}  (looked for {entry})")
                failed += 1
                continue

            content = bytearray(jar.read(entry))
            for target, replacement in rule["replacements"]:
                replace_in_null_terminated_strings(content, target, replacement)

            produced_path = output_dir / relative.replace("/", "_")
            result = subprocess.run(
                [sys.executable, "./fix_modified_binary.py", str(produced_path)],
                input=bytes(content), cwd=str(ROOT), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            if result.returncode != 0:
                print(f"  SCRIPT FAIL  {relative}  {result.stderr.decode(errors='replace').strip()[:160]}")
                failed += 1
                continue

            produced = produced_path.read_bytes()
            committed = cache_file.read_bytes()
            if hashlib.sha256(produced).digest() == hashlib.sha256(committed).digest():
                print(f"  IDENTICAL    {relative}")
                identical += 1
            elif exported_symbols(produced) == exported_symbols(committed):
                # Mach-O gets an ad-hoc signature that is not reproducible, so compare the export trie instead
                print(f"  EQUIVALENT   {relative}  (same exports, differing signature)")
                equivalent += 1
            else:
                print(f"  MISMATCH     {relative}  produced={len(produced)} committed={len(committed)}")
                failed += 1
    return identical, equivalent, failed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", action="append", default=[], metavar="NAME=PATH",
                        help="override the jar for a library, e.g. --jar zstd-jni=C:/path/zstd-jni-1.5.7-12.jar")
    args = parser.parse_args()
    overrides = dict(pair.split("=", 1) for pair in args.jar)

    properties = read_gradle_properties()
    output_dir = ROOT / "build" / "verify"
    output_dir.mkdir(parents=True, exist_ok=True)

    all_cache_files = [p for p in CACHE.rglob("*") if p.is_file()]
    totals = [0, 0, 0]
    unclaimed = list(all_cache_files)

    for rule in RULES:
        version = properties.get(rule["version_property"])
        cache_files = sorted(p for p in all_cache_files if rule["owns"](p.relative_to(CACHE).as_posix()))
        for p in cache_files:
            if p in unclaimed:
                unclaimed.remove(p)

        print(f"\n{rule['name']} {version}  ({len(cache_files)} cached natives)")
        if not version:
            print(f"  SKIPPED: {rule['version_property']} not found in gradle.properties")
            totals[2] += len(cache_files)
            continue

        jar_path = find_jar(rule, version, overrides)
        if jar_path is None or not jar_path.exists():
            print(f"  SKIPPED: jar not found; build once to populate the Gradle cache, or pass "
                  f"--jar {rule['name']}=<path>")
            totals[2] += len(cache_files)
            continue

        print(f"  jar: {jar_path}")
        result = verify(rule, jar_path, cache_files, output_dir)
        totals = [a + b for a, b in zip(totals, result)]

    for leftover in unclaimed:
        print(f"\n  UNCLAIMED    {leftover.relative_to(CACHE).as_posix()}  (no rule owns this cached file)")
        totals[2] += 1

    print(f"\nidentical={totals[0]}  equivalent={totals[1]}  failed={totals[2]}")
    return 1 if totals[2] else 0


if __name__ == "__main__":
    sys.exit(main())
