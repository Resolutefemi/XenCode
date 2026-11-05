#!/usr/bin/env python3
"""Sync existing Release APK assets from GitHub to GitCode (idempotent, safe to re-run).

Usage:
  GITCODE_TOKEN=<token> python3 scripts/sync-gitcode-releases.py            # Sync the keep set
  GITCODE_TOKEN=<token> python3 scripts/sync-gitcode-releases.py --all      # Sync all GitHub Releases

Keep set (auto-derived when RELEASE_FILTER is unset):
  only the latest KEEP_STABLE stable releases (default 3), excluding RCs (GitCode cannot mark an RC as pre-release,
  and a lightweight sync already covers downloads from China); --all syncs every GitHub Release.

Optional environment variables:
  GH_REPO         GitHub repository, default jieapi/xencode
  GITCODE_OWNER   GitCode username, defaults to the same name as GH_REPO
  GITCODE_REPO    GitCode repository name, defaults to the same name as GH_REPO
  GITHUB_TOKEN    GitHub token (optional, raises API rate limits)
  RELEASE_FILTER  Python regex that overrides auto-derivation; only syncs Releases whose tag_name matches
  KEEP_STABLE     Number of stable releases to keep, default 3

Key differences from the Gitee version (verified against the GitCode API v5):
  - Auth: Authorization: Bearer <token> works too, not just the access_token query (see code)
  - Create Release: if the tag does not exist it is created automatically on target_commitish(main), no need to wait for the mirror;
    an existing tag returns 409 (idempotent reuse)
  - Upload attachment: first GET /releases/{tag}/upload_url?file_name= for the presigned URL,
    then PUT the binary (all returned headers must be sent back; they take part in OBS signature verification)
  - prerelease flag: has no effect via API create/update (web only), so GitHub's prerelease state cannot be synced
  - Delete Release: no dedicated API; DELETE /tags/{tag} deletes the release too (heavy side effects),
    and GitCode has published no attachment quota limits, so this script does not clean up automatically
"""

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request

UA = "xencode-gitcode-sync"

GH_REPO = os.environ.get("GH_REPO", "jieapi/xencode")
GITCODE_OWNER = os.environ.get("GITCODE_OWNER") or GH_REPO.split("/")[0]
GITCODE_REPO = os.environ.get("GITCODE_REPO") or GH_REPO.split("/")[1]
GITCODE_TOKEN = os.environ.get("GITCODE_TOKEN", "")
GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", "")
RELEASE_FILTER = os.environ.get("RELEASE_FILTER", "")
KEEP_STABLE = int(os.environ.get("KEEP_STABLE", "3"))
MAX_ATTACH = 2 * 1024 * 1024 * 1024  # GitCode has published no limit; 2GB fallback (GitHub per-attachment limit)

GITCODE_API = f"https://api.gitcode.com/api/v5/repos/{GITCODE_OWNER}/{GITCODE_REPO}"
GH_API = f"https://api.github.com/repos/{GH_REPO}"

TAG_RE = re.compile(r"^v(\d+)\.(\d+)\.(\d+)(?:-rc(\d+))?$")


def parse_tag(tag: str) -> tuple | None:
    m = TAG_RE.match(tag)
    if not m:
        return None
    return (int(m.group(1)), int(m.group(2)), int(m.group(3)), bool(m.group(4)))


def http_json(url: str, payload: dict | None = None, method: str | None = None,
              headers: dict | None = None, timeout: int = 120):
    hdrs = {"User-Agent": UA}
    if GITHUB_TOKEN and url.startswith(GH_API):
        hdrs["Authorization"] = f"Bearer {GITHUB_TOKEN}"
    if headers:
        hdrs.update(headers)
    if payload is None:
        req = urllib.request.Request(url, headers=hdrs, method=method)
    else:
        req = urllib.request.Request(
            url,
            data=json.dumps(payload).encode(),
            headers={**hdrs, "Content-Type": "application/json"},
            method=method or "POST",
        )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read().decode()
        if not body:  # Some endpoints (e.g. DELETE) return an empty body on success
            return None
        return json.loads(body)


def gc_get(path: str, params: dict | None = None):
    url = f"{GITCODE_API}{path}"
    if params:
        url += "?" + urllib.parse.urlencode(params)
    return http_json(url, headers={"Authorization": f"Bearer {GITCODE_TOKEN}"})


def gc_post(path: str, payload: dict):
    return http_json(
        f"{GITCODE_API}{path}",
        payload,
        headers={"Authorization": f"Bearer {GITCODE_TOKEN}"},
    )


def gh_get(path: str):
    return http_json(f"{GH_API}{path}")


def download(url: str, dest: str) -> bool:
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=300) as resp, open(dest, "wb") as f:
                shutil.copyfileobj(resp, f)
            return True
        except Exception as e:
            print(f"    Download failed (attempt {attempt + 1}): {e}")
            time.sleep(2 * (attempt + 1))
    return False


def upload(release_tag: str, path: str) -> bool:
    """GitCode attachment upload: GET upload_url for the presigned URL, then PUT the binary (send all headers)."""
    fname = os.path.basename(path)
    for attempt in range(3):
        try:
            up = gc_get(
                f"/releases/{urllib.parse.quote(release_tag)}/upload_url",
                {"file_name": fname},
            )
            if not isinstance(up, dict) or "url" not in up:
                print(f"    Failed to get the upload URL: {up}")
                time.sleep(2 * (attempt + 1))
                continue
            url = up["url"]
            headers = {k: str(v) for k, v in (up.get("headers") or {}).items()}
            with open(path, "rb") as f:
                data = f.read()
            req = urllib.request.Request(url, data=data, method="PUT", headers=headers)
            with urllib.request.urlopen(req, timeout=1800) as resp:
                resp.read()
            return True
        except Exception as e:
            print(f"    Upload failed (attempt {attempt + 1}): {e}")
            time.sleep(2 * (attempt + 1))
    return False


def list_gitcode_releases() -> list:
    """GitCode list endpoint; if the pagination params are ignored it returns everything, so stop based on the row count."""
    out = []
    page = 1
    while True:
        batch = gc_get("/releases", {"per_page": 100, "page": page})
        if not isinstance(batch, list):
            raise RuntimeError(f"GitCode releases list returned unexpected data: {batch}")
        out.extend(batch)
        if len(batch) < 100:
            break
        page += 1
    return out


def derive_keep_set(releases: list) -> set:
    """Auto-derive the keep set: only the latest KEEP_STABLE stable releases (no RCs)."""
    parsed = [(parse_tag(r["tag_name"]), r["tag_name"]) for r in releases]
    stable = sorted((p for p, _ in parsed if p and not p[3]), reverse=True)
    keep_main = {s[:3] for s in stable[:KEEP_STABLE]}
    return {t for p, t in parsed if p and not p[3] and p[:3] in keep_main}


def main() -> int:
    if not GITCODE_TOKEN:
        print("Error: please provide GITCODE_TOKEN via an environment variable (GitCode personal access token)")
        return 1
    print(f"Source: GitHub {GH_REPO} -> Target: GitCode {GITCODE_OWNER}/{GITCODE_REPO}")

    # Verify the GitCode token and repo are reachable, so auth errors are not misjudged as other problems
    try:
        gc_get("")
    except Exception as e:
        print(f"Error: cannot access the GitCode repo {GITCODE_OWNER}/{GITCODE_REPO}: {e}")
        return 1

    # Fetch all GitHub releases (paginated)
    gh_releases = []
    page = 1
    while True:
        batch = gh_get(f"/releases?per_page=100&page={page}")
        if not isinstance(batch, list):
            raise RuntimeError(f"GitHub releases list returned unexpected data: {batch}")
        gh_releases.extend(batch)
        if len(batch) < 100:
            break
        page += 1

    # Keep set: the regex when RELEASE_FILTER overrides, otherwise auto-derived; --all keeps everything
    if "--all" in sys.argv:
        keep = {r["tag_name"] for r in gh_releases}
        print("Sync mode: all releases")
    elif RELEASE_FILTER:
        pattern = re.compile(RELEASE_FILTER)
        keep = {r["tag_name"] for r in gh_releases if pattern.match(r["tag_name"])}
        print(f"Tag filter (regex): {RELEASE_FILTER}")
    else:
        keep = derive_keep_set(gh_releases)
        print(f"Keep policy: only the latest {KEEP_STABLE} stable releases (no RCs)")
    gh_releases = [r for r in gh_releases if r["tag_name"] in keep]
    print(f"{len(gh_releases)} GitHub Releases to keep")

    # Fetch existing GitCode releases (for idempotent comparison)
    gc_releases = {r["tag_name"]: r for r in list_gitcode_releases()}
    print(f"GitCode already has {len(gc_releases)} Releases")

    ok = skipped = failed = 0
    tmpdir = tempfile.mkdtemp(prefix="gitcode-sync-")
    try:
        # Process oldest first, by creation time
        for rel in sorted(gh_releases, key=lambda r: r["created_at"]):
            tag = rel["tag_name"]
            existing = gc_releases.get(tag)
            attach_names = set()
            if existing is not None:
                # The GitCode list endpoint includes assets directly (including platform-generated source packages, ignored)
                attach_names = {
                    a.get("name") for a in existing.get("assets", [])
                    if a.get("name", "").endswith(".apk")
                }
                print(f"[Skip create] {tag} (already exists, {len(attach_names)} APK attachments)")
            else:
                print(f"[Create] {tag} ...")
                try:
                    created = gc_post("/releases", {
                        "tag_name": tag,
                        "name": rel.get("name") or f"Release {tag}",
                        "body": rel.get("body") or "",
                        "target_commitish": "main",
                    })
                    if isinstance(created, dict) and created.get("tag_name"):
                        attach_names = set()
                    else:
                        raise RuntimeError(f"Unexpected create response: {created}")
                except Exception as e:
                    print(f"  Error: failed to create the Release: {e}")
                    failed += 1
                    continue

            for asset in rel.get("assets", []):
                name = asset["name"]
                size = asset["size"]
                if size > MAX_ATTACH:
                    print(f"  [Skip] {name} ({size / 1048576:.1f}MB exceeds the {MAX_ATTACH // 1048576}MB limit)")
                    skipped += 1
                    continue
                if name in attach_names:
                    print(f"  [Exists] {name}")
                    skipped += 1
                    continue
                dest = os.path.join(tmpdir, name)
                print(f"  Downloading {name} ({size / 1048576:.1f}MB) ...")
                if not download(asset["browser_download_url"], dest):
                    print(f"  [Failed] {name} download failed")
                    failed += 1
                    continue
                if upload(tag, dest):
                    print(f"  [OK] {name} -> GitCode Release {tag}")
                    ok += 1
                else:
                    print(f"  [Failed] {name} upload failed")
                    failed += 1
                os.remove(dest)
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

    print(f"\nDone: {ok} succeeded, {skipped} skipped, {failed} failed")
    if failed:
        print("Some items failed; re-run this script to resume (already-succeeded items are skipped).")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
