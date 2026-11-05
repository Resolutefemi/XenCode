#!/usr/bin/env python3
"""Migration version reconciliation script.

Runs during CI builds and when locally merging PRs/branches that contain database migrations, verifying three invariants:

1. Continuity: version numbers in assets/migrations strictly increase, are contiguous, and have no duplicates
   (prevents gaps when migration files are added or removed, which would wipe some users' databases via fallback on upgrade);
2. Consistency: SCHEMA_VERSION in AgentDatabase.kt must equal the largest migration version;
3. Published freeze: a migration file whose version is <= the SCHEMA_VERSION on any v* tag
   must match that tag's copy byte for byte — tagged, published migrations must never be modified or reused.
   A violation means main failed to shift unpublished migrations after a hotfix merge, or someone tampered with a published migration.

Requires local git refs: CI just needs actions/checkout with fetch-depth: 0 (including tags); locally, run git fetch --tags first.
Usage: python3 scripts/check_migrations.py   (exit code 0 = pass, 1 = conflicts found)
"""

import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MIGRATIONS_REL = "app/src/main/assets/migrations"
DB_REL = "app/src/main/java/com/xencode/feature/agent/data/local/database/AgentDatabase.kt"
MIGRATIONS_DIR = os.path.join(ROOT, MIGRATIONS_REL)
DB_FILE = os.path.join(ROOT, DB_REL)

errors = []
notes = []


def git(args):
    """Returns (returncode, stdout_text); stdout is always decoded as UTF-8 to avoid locale effects."""
    proc = subprocess.run(["git", "-C", ROOT] + args, capture_output=True)
    return proc.returncode, proc.stdout.decode("utf-8", errors="replace")


def norm(text):
    return text.replace("\r\n", "\n").rstrip("\n")


# ---------- 1. Read local migration files ----------
versions = {}  # version -> file name
for name in sorted(os.listdir(MIGRATIONS_DIR)):
    m = re.match(r"(\d+)_", name)
    if not m or not name.endswith(".sql"):
        continue
    ver = int(m.group(1))
    if ver in versions:
        errors.append("Version number %d appears more than once: %s and %s" % (ver, versions[ver], name))
    versions[ver] = name

if versions:
    all_vers = sorted(versions)
    expected = list(range(all_vers[0], all_vers[-1] + 1))
    if all_vers != expected:
        missing = [v for v in expected if v not in set(all_vers)]
        errors.append("Migration versions are not contiguous: missing %s, have %s" % (missing, all_vers))
    notes.append("Local migration versions %d..%d, %d in total" % (all_vers[0], all_vers[-1], len(all_vers)))

# ---------- 2. SCHEMA_VERSION ----------
with open(DB_FILE, encoding="utf-8") as f:
    db_src = f.read()
m = re.search(r"const val SCHEMA_VERSION = (\d+)", db_src)
schema_ver = int(m.group(1)) if m else None
if schema_ver is None:
    errors.append("SCHEMA_VERSION not found in %s" % DB_REL)
elif versions and schema_ver != max(versions):
    errors.append("SCHEMA_VERSION=%d does not match the largest migration version %d" % (schema_ver, max(versions)))

# ---------- 3. Bridging AutoMigration and file-based migrations ----------
auto_migs = [
    (int(a), int(b))
    for a, b in re.findall(r"@AutoMigration\s*\(\s*from\s*=\s*(\d+)\s*,\s*to\s*=\s*(\d+)", db_src)
]
auto_migs.sort()
for frm, to in auto_migs:
    if to != schema_ver:
        errors.append("@AutoMigration(from=%d, to=%d): to does not match SCHEMA_VERSION=%d" % (frm, to, schema_ver))
    if to != frm + 1:
        errors.append("@AutoMigration(from=%d, to=%d) must satisfy to = from + 1" % (frm, to))
max_file = max(versions) if versions else None
if auto_migs and max_file is not None and auto_migs[0][0] != max_file:
    errors.append(
        "@AutoMigration start version %d must line up with the largest file-based migration version %d (a version can only use one of the two)"
        % (auto_migs[0][0], max_file)
    )

# ---------- 4. Published freeze check ----------
_, tags_out = git(["for-each-ref", "--sort=-creatordate", "--format=%(refname:short)", "refs/tags/v*"])
tags = [t.strip() for t in tags_out.splitlines() if t.strip()]
published_max = 0
tag_schema = {}  # tag -> schema version
for tag in tags:
    rc, content = git(["show", "%s:%s" % (tag, DB_REL)])
    if rc != 0:
        continue
    m = re.search(r"const val SCHEMA_VERSION = (\d+)", content)
    if m:
        tag_schema[tag] = int(m.group(1))
        published_max = max(published_max, int(m.group(1)))

if tags and not tag_schema:
    notes.append("Found v* tags but could not parse SCHEMA_VERSION from them; skipping the published freeze check")
elif versions and published_max > 0:
    published_content = {}  # version -> (tag, file_name, content)
    for tag in tags:  # Already sorted by creation date descending, so the first one written is the newest tag containing that version
        rc, listing = git(["ls-tree", "--name-only", tag, MIGRATIONS_REL + "/"])
        if rc != 0:
            continue
        for line in listing.splitlines():
            name = line.strip().split("/")[-1]
            m = re.match(r"(\d+)_", name)
            if not m:
                continue
            ver = int(m.group(1))
            if ver > published_max or ver in published_content:
                continue  # Only look at published version ranges, and only take content from the newest tag
            rc2, content = git(["show", "%s:%s/%s" % (tag, MIGRATIONS_REL, name)])
            if rc2 == 0:
                published_content[ver] = (tag, name, norm(content))
    for ver, name in versions.items():
        if ver in published_content:
            tag, tag_name, tag_content = published_content[ver]
            with open(os.path.join(MIGRATIONS_DIR, name), encoding="utf-8", errors="replace") as f:
                local_content = norm(f.read())
            if local_content != tag_content:
                errors.append(
                    "Version %d (%s) is already published (first seen in tag %s as file %s), but the local content differs: "
                    "published migrations cannot be modified or reused. If main was not shifted after the hotfix merge, "
                    "renumber this migration to %d+1 and the consecutive numbers after it, then commit." % (ver, name, tag, tag_name, published_max)
                )
    notes.append("Published version upper bound (max SCHEMA_VERSION across all tags) = %d" % published_max)

# ---------- Output ----------
if errors:
    print("[FAIL] Migration version reconciliation failed:")
    for e in errors:
        print("  - %s" % e)
    sys.exit(1)
print("[OK] Migration version reconciliation passed")
for n in notes:
    print("  - %s" % n)