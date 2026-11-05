# Installing Playwright Browser Automation

Once Chromium is installed in the container and the Playwright MCP server is registered, the AI can open pages, click elements, fill in forms, scrape page content, and take screenshots.

::: tip A Debian / Ubuntu container is required
Chrome for Testing binaries depend on glibc; the built-in Alpine image uses musl libc and cannot run them. First import a custom Debian or Ubuntu image as described in [Containers and images](/guide/container).
:::

## Environment baseline

| Item | Value |
| --- | --- |
| OS | Debian / Ubuntu, root by default inside the container |
| Architecture | aarch64 (arm64) or x86_64 |
| Browser | Chrome for Testing 153.0.8010.12, installed at `/opt/chrome` |
| MCP server | `@playwright/mcp`, fetched on demand by `npx` |

## 1. Install dependencies

```bash
apt-get update -qq && apt-get install -y -qq unzip fonts-noto-cjk
```

Without Chinese fonts, Chinese text in screenshots shows up as boxes.

## 2. Download and unpack Chromium

The example uses arm64; for x86_64 replace `linux-arm64` in the URL with `linux64` and the unpacked directory name with `chrome-linux64`.

```bash
mkdir -p /opt/chrome
curl -L -o /tmp/chrome.zip \
  https://cdn.npmmirror.com/binaries/chrome-for-testing/153.0.8010.12/linux-arm64/chrome-linux-arm64.zip
unzip -q /tmp/chrome.zip -d /opt/chrome
rm /tmp/chrome.zip

/opt/chrome/chrome-linux-arm64/chrome --version
# prints Google Chrome for Testing 153.0.8010.12
```

The commands above use the npmmirror mirror; the official source works too. If this version is taken down, just replace the version number in the URL:

```
https://storage.googleapis.com/chrome-for-testing-public/153.0.8010.12/linux-arm64/chrome-linux-arm64.zip
```

## 3. Register the MCP server

Open "Settings → MCP Servers → +", choose local stdio as the transport, fill in the fields below, and save; the app connects automatically.

| Field | Value |
| --- | --- |
| Name | `playwright` |
| Launch command | `npx` |
| Command arguments | `-y`, `@playwright/mcp@latest`, `--headless`, `--no-sandbox`, `--executable-path`, `/opt/chrome/chrome-linux-arm64/chrome` (add them one by one) |
| Scope | Global (shared by all projects) or Project (current workspace only) |

You can also edit the config file directly: the global one is `~/.xencode/mcp.json`, the project-level one is `<workspace>/.xencode/mcp.json`; changes take effect automatically within seconds of saving:

```json
{
    "mcpServers": {
        "playwright": {
            "command": "npx",
            "args": [
                "-y",
                "@playwright/mcp@latest",
                "--headless",
                "--no-sandbox",
                "--executable-path",
                "/opt/chrome/chrome-linux-arm64/chrome"
            ],
            "enabled": true
        }
    }
}
```

Of these, `--no-sandbox` is mandatory inside the container — without it the browser crashes at startup; `--executable-path` points at the installed Chromium so Playwright does not download it again.

By default the headless User-Agent carries the `HeadlessChrome` marker, which some sites block; append `--user-agent` to override it (add it as two separate parameter entries in the UI):

```
--user-agent
Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36
```

Screenshots and other output files default to `.playwright-mcp/` under the working directory — that is, the current workspace root — leaving untracked files in the repository. Append `--output-dir` to move it out of the workspace:

```
--output-dir
/root/playwright-output
```

An exception is when a filename is specified for the screenshot: relative paths are resolved against the workspace, bypassing `--output-dir`, so use an absolute path.

## 4. Verify

Open "Settings → MCP Servers"; the `playwright` row should show as connected with its tool count. If the connection fails, use the log button in the top-right corner to investigate.

Then start a new session (the tool list is fixed when the session starts) and have the AI open a page and take a screenshot; getting the image back means the whole chain works. The first connection waits for `npx` to download `@playwright/mcp` — measured at 3 to 4 minutes.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `Target closed` or a crash right at startup | Missing `--no-sandbox` | Add the argument and reconnect |
| Running chrome directly in a terminal reports a `/dev/shm` FATAL | The container has no `/dev/shm` and `/dev` is not writable | Add `--disable-dev-shm-usage`; launches by Playwright are unaffected |
| `chrome: not found` or the binary will not execute | The container is Alpine (musl), or the package does not match the architecture | Switch to a Debian / Ubuntu image and check the arm64 vs x64 packages |
| Chinese text in screenshots shows as boxes | Missing Chinese fonts | Run `apt-get install -y fonts-noto-cjk` |
| Screenshot is a blank page or fully transparent | Rendering occasionally fails without a GPU | Retry once; if still blank, switch to JPEG |
| The first call waits several minutes | `npx` is downloading `@playwright/mcp` | Wait it out; afterwards the local cache is used. You can also point the npm registry at `https://registry.npmmirror.com` |
| `playwright` missing from the MCP list | Config not applied, or the JSON has a syntax error | Confirm `mcp.json` parses, then click "Reconnect" in the top-right corner |

## Appendix: one-click install script

The script below performs steps 1 through 3, with automatic download-source failover, zip verification, skipping the download when already installed, and backing up `mcp.json` before writing. Save it as `/tmp/install-playwright.sh` and run `bash /tmp/install-playwright.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

VERSION="${CHROME_VERSION:-153.0.8010.12}"
DIR=/opt/chrome
ZIP=/tmp/chrome-$VERSION.zip

case "$(uname -m)" in
  aarch64|arm64) SUB=linux-arm64; NAME=chrome-linux-arm64 ;;
  x86_64)        SUB=linux64;     NAME=chrome-linux64 ;;
  *) echo "Unsupported architecture: $(uname -m)"; exit 1 ;;
esac
BIN="$DIR/$NAME/chrome"

[ "$(id -u)" = 0 ] || { echo "Please run as root"; exit 1; }
command -v python3 >/dev/null || { echo "python3 is missing"; exit 1; }
command -v npx >/dev/null || echo "[Warning] npx not found; the MCP server cannot start"

need=()
command -v unzip >/dev/null || need+=(unzip)
fc-list :lang=zh 2>/dev/null | grep -q . || need+=(fonts-noto-cjk)
if [ ${#need[@]} -gt 0 ]; then
  echo "Installing dependencies: ${need[*]}"
  apt-get update -qq && apt-get install -y -qq "${need[@]}"
fi

if [ -x "$BIN" ]; then
  echo "Already installed, skipping download: $BIN"
else
  for url in \
    "https://cdn.npmmirror.com/binaries/chrome-for-testing/$VERSION/$SUB/$NAME.zip" \
    "https://storage.googleapis.com/chrome-for-testing-public/$VERSION/$SUB/$NAME.zip"
  do
    echo "Downloading: $url"
    curl -fL --retry 3 --retry-delay 2 -o "$ZIP" "$url" && break
    echo "[Warning] this source is unavailable, trying the next"
  done
  [ -s "$ZIP" ] || { echo "All download sources failed"; exit 1; }

  mkdir -p "$DIR"
  unzip -tq "$ZIP" && unzip -q "$ZIP" -d "$DIR"
  rm -f "$ZIP"
fi

"$BIN" --version

BIN="$BIN" python3 - <<'PY'
import json, os, shutil
p = os.path.expanduser("~/.xencode/mcp.json")
os.makedirs(os.path.dirname(p), exist_ok=True)
data = {}
if os.path.exists(p):
    shutil.copy(p, p + ".bak")
    with open(p, encoding="utf-8") as f:
        data = json.load(f)   # fail loudly on syntax errors; do not overwrite
data.setdefault("mcpServers", {})["playwright"] = {
    "command": "npx",
    "args": ["-y", "@playwright/mcp@latest", "--headless", "--no-sandbox",
             "--executable-path", os.environ["BIN"]],
    "enabled": True,
}
with open(p, "w", encoding="utf-8") as f:
    json.dump(data, f, indent=4, ensure_ascii=False)
print("playwright config written")
PY

echo "Install complete: $BIN"
```

`CHROME_VERSION` can select another version. After installation, return to step 4 to verify.
