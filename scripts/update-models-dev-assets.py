#!/usr/bin/env python3
"""Run manually before a release: update the built-in models.dev model metadata snapshot (app/src/main/assets/api.official.json).

Structure of api.official.json:
- Keep only providers already present in the built-in snapshot; no new providers are introduced. New models under those providers can be added directly.
- Besides models, each provider node also gets name / type / baseUrl fields, used for display and prefill in the "Add from preset library" panel.
- On network fetch or parse failure, print the error and exit non-zero; the existing file is never touched.
Usage: python3 scripts/update-models-dev-assets.py
"""

import json
import os
import sys
import urllib.request

MODELS_DEV_URL = "https://models.dev/api.json"
ASSET_PATH = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "api.official.json")
)
UA = "xencode-assets-updater"

# Default Base URLs for official providers (fallback when models.dev's api field is missing).
DEFAULT_BASE_URL = {
    "openai": "https://api.openai.com/",
    "anthropic": "https://api.anthropic.com/",
    "google": "https://generativelanguage.googleapis.com/",
}

# Per-provider overrides: models.dev's api/npm fields sometimes do not match the protocol/endpoint the app expects;
# they are forcibly overridden when generating presets (baseUrl is used as-is, skipping normalize_base_url's /v1 trimming).
#   - xai: models.dev's api defaults to the OpenAI official address, but the xAI official endpoint is correct.
#   - minimax/minimax-cn family: npm being anthropic is just a compatibility variant offered by MiniMax;
#     they still connect via the OpenAI-compatible endpoint by default (the app appends /v1/chat/completions).
PROVIDER_OVERRIDES = {
    "xai": {"baseUrl": "https://api.x.ai/"},
    "minimax": {"type": "OPENAI", "baseUrl": "https://api.minimax.io/"},
    "minimax-cn": {"type": "OPENAI", "baseUrl": "https://api.minimaxi.com/"},
    "minimax-coding-plan": {"type": "OPENAI", "baseUrl": "https://api.minimax.io/"},
    "minimax-cn-coding-plan": {"type": "OPENAI", "baseUrl": "https://api.minimaxi.com/"},
}


def trim_model(mv: dict) -> dict:
    out = {}
    for key in ("id", "name", "tool_call", "temperature", "reasoning", "reasoning_options", "limit"):
        if key in mv:
            out[key] = mv[key]
    modalities = mv.get("modalities", {})
    if isinstance(modalities, dict) and "input" in modalities:
        out["modalities"] = {"input": modalities["input"]}
    if "cost" in mv:
        out["cost"] = mv["cost"]
    return out


def map_type(npm: str) -> str:
    """Map models.dev's npm field to a protocol type (OPENAI / ANTHROPIC / GEMINI)."""
    if not npm:
        return "OPENAI"
    n = npm.lower()
    if "anthropic" in n:
        return "ANTHROPIC"
    if "google" in n:
        return "GEMINI"
    return "OPENAI"


def normalize_base_url(raw: str, pid: str) -> str:
    """Normalize a Base URL: strip the trailing slash; if an OpenAI-compatible path ends with /v1, drop it,
    because the app appends v1/chat/completions itself, which would produce /v1/v1/...."""
    if not raw:
        return DEFAULT_BASE_URL.get(pid, "https://api.openai.com/")
    url = raw.strip().rstrip("/")
    if url.endswith("/v1"):
        url = url[: -len("/v1")].rstrip("/")
    return url


def build_provider_meta(pid: str, pv: dict) -> dict:
    """Compute display metadata (name / type / baseUrl) for a single provider, written to its api.official.json node."""
    npm = pv.get("npm")
    override = PROVIDER_OVERRIDES.get(pid, {})
    base_url = override.get("baseUrl") if "baseUrl" in override else normalize_base_url(pv.get("api"), pid)
    return {
        "name": pv.get("name") or pid,
        "type": override.get("type", map_type(npm)),
        "baseUrl": base_url,
    }


def fetch_remote() -> dict:
    print(f"Fetching {MODELS_DEV_URL} ...")
    req = urllib.request.Request(MODELS_DEV_URL, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main() -> int:
    if not os.path.isfile(ASSET_PATH):
        print(f"Error: built-in snapshot not found at {ASSET_PATH}")
        return 1

    with open(ASSET_PATH, encoding="utf-8") as f:
        current = json.load(f)
    providers = list(current.keys())

    try:
        remote = fetch_remote()
    except Exception as e:
        print(f"Fetch/parse failed ({e}); skipping the update and keeping the existing file")
        return 1

    if not isinstance(remote, dict):
        print("Remote data has an unexpected format; skipping the update and keeping the existing file")
        return 1

    # 1) Update api.official.json (keep only existing providers, merge new models, and write provider display metadata)
    merged = {}
    for pid in providers:
        net_models = remote.get(pid, {}).get("models", {})
        models = {
            mid: trim_model(mv)
            for mid, mv in net_models.items()
            if isinstance(mv, dict)
        }
        for mid, mv in current.get(pid, {}).get("models", {}).items():
            if mid not in models:
                models[mid] = mv
        remote_pv = remote.get(pid, {})
        meta = build_provider_meta(pid, remote_pv) if isinstance(remote_pv, dict) else {}
        merged[pid] = {**meta, "models": models}

    old_total = sum(len(p.get("models", {})) for p in current.values())
    new_total = sum(len(p.get("models", {})) for p in merged.values())
    if new_total == 0:
        print("Merge result is empty; aborting the update")
        return 1

    with open(ASSET_PATH, "w", encoding="utf-8") as f:
        json.dump(merged, f, ensure_ascii=False, separators=(",", ":"))
    print(f"Updated {ASSET_PATH}")
    for pid in providers:
        old = len(current.get(pid, {}).get("models", {}))
        new = len(merged.get(pid, {}).get("models", {}))
        flag = " (new models)" if new > old else ""
        print(f"  {pid}: {old} -> {new} {flag}")
    print(f"Total: {old_total} -> {new_total} models")
    return 0


if __name__ == "__main__":
    sys.exit(main())
