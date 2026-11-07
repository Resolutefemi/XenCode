# CI Overview

Workflow map for the PC line (the mobile app now lives in [Resolutefemi/XenAI](https://github.com/Resolutefemi/XenAI)).

| Workflow | Trigger | Produces |
|---|---|---|
| `pc.yml` | push/PR touching `core/**` `cli/**` `desktop/**` `rust/**` | PC tests + CLI dists (linux/windows/macos) + desktop packages (deb+rpm / msi+exe / dmg) + Rust build |
| `pc-release.yml` | tag `pc-v*` or manual | all PC artifacts attached to one GitHub Release |
| `docs-deploy.yml` | tag push | docs site (Vercel) |
| `sync-gitcode.yml` | schedule | repo + release mirror to GitCode |

`GITHUB_TOKEN` is injected by Actions automatically; no manual setup needed.
The Android signing secrets (keystore, alias, passwords) moved with the mobile app to the XenAI repository.
