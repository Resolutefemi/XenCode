#!/usr/bin/env python3
"""Run on a CI schedule: fetch full model metadata from models.dev and save it to data/models.json.

Only fetches and saves the full metadata; never touches the app's built-in api.official.json.
Exits non-zero on network or parse failure, without overwriting the old file.
"""

import json
import os
import sys
import urllib.request

MODELS_DEV_URL = "https://models.dev/api.json"
TARGET_PATH = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "data", "models.json")
)
UA = "xencode-models-sync"


def fetch_remote() -> dict:
    print(f"Fetching {MODELS_DEV_URL} ...")
    req = urllib.request.Request(MODELS_DEV_URL, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main() -> int:
    try:
        remote = fetch_remote()
    except Exception as e:
        print(f"Failed to fetch or parse models.dev: {e}")
        return 1

    if not isinstance(remote, dict) or not remote:
        print("Remote returned a non-dict or empty payload; aborting the write")
        return 1

    os.makedirs(os.path.dirname(TARGET_PATH), exist_ok=True)
    with open(TARGET_PATH, "w", encoding="utf-8") as f:
        json.dump(remote, f, ensure_ascii=False, separators=(",", ":"))

    total_models = sum(
        len(v.get("models", {}))
        for v in remote.values()
        if isinstance(v, dict)
    )
    print(f"Full metadata synced to {TARGET_PATH}")
    print(f"{len(remote)} providers with {total_models} models in total")
    return 0


if __name__ == "__main__":
    sys.exit(main())
