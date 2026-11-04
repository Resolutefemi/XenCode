# CI Signing Setup

For CI builds and artifacts, see the "CI Automated Builds" and "Known Limitations" sections of the root [README.md](../../README.md). This file covers one thing the main README does not: **how to hand your local release signing credentials to GitHub Actions**.

## Required GitHub Secrets

In `Settings → Secrets and variables → Actions → New repository secret`, add these 4:

| Secret name | Value |
|---|---|
| `AICODE_KEYSTORE_BASE64` | base64 encoding of the `app/aicode.jks` file (see below) |
| `AICODE_KEYSTORE_PASSWORD` | the keystore's `storePassword` |
| `AICODE_KEY_ALIAS` | the signing key's `keyAlias` |
| `AICODE_KEY_PASSWORD` | the key's `keyPassword` (usually the same as the keystore passphrase) |

`GITHUB_TOKEN` is injected by Actions automatically; no manual setup needed.

## Generating the keystore base64

From the repo root, copy to the clipboard with PowerShell:

```powershell
[System.Convert]::ToBase64String([System.IO.File]::ReadAllBytes("app\aicode.jks")) | Set-Clipboard
```

Paste it into `AICODE_KEYSTORE_BASE64`.

If you do not have a keystore yet, create one with keytool first (pick your own alias/passphrase, then fill the secrets in):

```powershell
keytool -genkeypair -v -keystore app\aicode.jks -alias aicode -keyalg RSA -keysize 2048 -validity 10000
```

> Both `aicode.jks` and `keystore.properties` are in `.gitignore`, never committed, and local signing keeps working as before.
