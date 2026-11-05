# Containers & Images

In XenCode, the environment where the AI executes commands is called a "container"; the entry point is under "Settings → Containers & Images". The list is single-select: the selected container is the environment for the AI's command execution, file read/write, and the terminal.

There are two kinds of backends, and one of the two must be chosen: **local containers** (a Linux system running on the phone) and **remote SSH** (running on a remote server; see [Remote SSH mode](/guide/remote-ssh)).

## Container List

Each row is a container configuration, with a source tag after the name: built-in, custom, or SSH.

Three actions apply to every kind of container:

- **Edit**: the pencil icon at the end of the row.
- **Reset**: the refresh icon in the top-right corner of the edit dialog. This is a destructive operation: it deletes the tools and configuration already installed inside the container and requires a second confirmation; once confirmed, running AI sessions and all terminal tabs are closed. Resetting a large container with a lot of content can take tens of seconds; "Cleaning container data" and a count of the cleaned items are shown during the process, and it closes automatically when done without blocking the UI.
- **Delete**: swipe the row left to reveal the red delete button. Deleting a local image also cleans up its rootfs to free space (with the same progress display); deleting a remote SSH entry only removes the configuration and doesn't touch data on the server.

The built-in Alpine can be deleted too. When everything is deleted, the list shows an empty state where the built-in Alpine can be restored with one tap—no need to worry about accidental deletion.

Once a container has run, the app automatically identifies its system type and shows the matching icon on the left of the list row (Alpine, Ubuntu, Debian, and CentOS are recognized); a default icon is shown when it can't be identified.

## Built-in Alpine Container

The app ships with an Alpine image that is automatically added to the list and selected on first launch, with the shell path already configured—no extra setup needed.

::: tip Enter the terminal once to finish initialization
For local containers, first-time environment preparation (extracting the rootfs and installing basic tools) is triggered when you enter the **terminal page** for the first time. On first use, enter the terminal to complete initialization; after that, AI command execution, Git management, and local MCP work normally.
:::

## The Initialization Menu on First Terminal Entry

Whether it's the built-in Alpine or a self-imported image, the first time you enter the terminal after the rootfs has been extracted, an initialization menu with four options appears:

**1. Automatically install dependencies (recommended)**

Lists what will be installed (basic tools such as bash, curl, ripgrep, and git, plus Node.js and Python 3); enter `y` to confirm, then choose a mirror and installation begins.

**2. Environment install**

Opens the "Environment install" menu, where you pick a development environment by scenario (see the next section). A scenario is a bundle of runtimes; confirm the list after choosing and installation begins. The menu also offers "Custom install", where you can check items one by one and specify major versions.

**3. Manual install (don't ask again)**

Skips installation; the menu won't pop up again when entering the terminal, and you install tools yourself. To bring the menu back, reset the container.

**4. Exit**

Installs nothing this time; you'll be prompted again the next time you enter the terminal.

A failed installation doesn't block entry to the shell; re-enter the terminal to try again.

### Choosing a Mirror

When switching mirrors, you can choose:

- `1) Auto-detect (recommended)`: probes each mirror's reachability under the current package manager and automatically skips dead or blocked ones.
- `2~6)` specify a mirror: Huawei Cloud, Tsinghua TUNA, USTC, Tencent Cloud, Aliyun.
- `0)` don't switch; keep the default.

The original configuration is backed up before switching and restored automatically on failure, so later installs are unaffected. The built-in Alpine rootfs keeps the official mirrors; switch in this menu if you need mirrors hosted in China.

## Installing Development Dependencies Anytime (xencode)

The initialization menu appears only once, on first entry to the terminal. To install other runtimes later (Java or Go, for example), run the command `xencode` in the terminal to open the "Environment install" menu.

`xencode` installs by scenario: pick one and confirm the list, that's all:

| Scenario | What gets installed |
| --- | --- |
| General development | Basic tools + Node.js + Python 3 |
| Python development | Basic tools + Python 3 + pip |
| Node.js / frontend development | Basic tools + Node.js + npm |
| Kotlin / Android development | Basic tools + JDK 17 + Android SDK |
| Flutter development | Basic tools + JDK + Android SDK + Flutter SDK |
| Java development | Basic tools + JDK |
| Go development | Basic tools + Go |
| Rust development | Basic tools + rustc + cargo |
| PHP development | Basic tools + PHP + Composer |
| Basic tools only | bash, curl, ripgrep, git |

The basic tools (bash, curl, ripgrep, git) are installed in every scenario. Scenarios install the latest major version from the software repositories by default; to pin a version, use "Custom install" in the menu and check items one by one. The menu also supports switching mirrors separately and viewing installed runtimes.

"Kotlin / Android development" and "Flutter development" are heavy scenarios: the JDK, the Android SDK (including build-tools and platform-tools), and the Flutter SDK are downloaded from mirrors hosted in China, which takes a while and uses a fair amount of storage. Installing in a Debian / Ubuntu image is recommended (the built-in Alpine needs extra handling because of musl libc); on ARM64 devices, native binaries such as aapt2 are automatically replaced with executable ones. Note that ARM64 containers can only build Flutter debug APKs; release builds must be done via remote builds. After installation, newly opened terminals automatically carry environment variables such as `JAVA_HOME` and `ANDROID_HOME`. For the detailed build steps, see "Building Android apps in the container" and "Building Flutter apps in the container" in the Advanced Tutorials.

The settings icon in the top-right corner of the terminal opens the terminal settings panel, where you can adjust the color theme, font size, font, and cursor style.

## Custom Images

The built-in Alpine covers most scenarios. If you need Debian / Ubuntu, or an environment with a specific toolchain preinstalled, you can add your own image in two ways.

### Option 1: built-in image download (recommended)

1. Open "Settings → Containers & Images" and tap the download icon in the top-right corner.
2. The page lists downloadable images (Alpine, Ubuntu, Debian, etc.); tap a row to see details and download.
3. The top-right corner switches the download source (official / Huawei Cloud / Aliyun / Tencent Cloud); on networks in China, a China-based source is recommended.
4. When the download finishes, tap "Import"; the image joins the container list—select it to use it.

Downloaded images can be imported repeatedly; swipe left to delete a downloaded image file.

### Option 2: manually import an image file

When you need a specific version or distribution, prepare a rootfs image yourself and import it.

**Image requirements**:

- Compression format: `.tar.gz`, `.tgz`, `.tar.xz`, `.txz`
- The contents must be a complete Linux rootfs (a filesystem root, including `/bin/sh` or another shell)
- The architecture must match the device: ARM64 devices use aarch64 images, x86 devices use x86_64 images

**Where to get images**:

- Alpine (small, fast to start): `https://alpine.linuxhub.cn/alpine/edge/releases/`, pick `alpine-minirootfs-*-aarch64.tar.gz` in the aarch64 directory
- Ubuntu: `https://cdimage.ubuntu.com/ubuntu-base/releases/`, pick `ubuntu-base-*-base-arm64.tar.gz`
- Debian: export from the official Docker Hub image (e.g. `arm64v8/debian`)

With Docker available, you can export a rootfs from any image:

```bash
docker pull debian:bookworm
docker create --name temp-rootfs debian:bookworm
docker export temp-rootfs | gzip > debian-rootfs.tar.gz
docker rm temp-rootfs
```

You can also build one yourself in an existing Linux environment with `debootstrap` (Debian / Ubuntu) or `alpine-make-rootfs` (Alpine).

**Import steps**:

1. Open "Settings → Containers & Images", tap + in the top-right corner, and choose "Local image".
2. Fill in the configuration:
   - **Name**: an alias for this image configuration.
   - **Shell path**: the shell inside the container, e.g. `/bin/sh` or `/bin/bash`.
   - **Image file**: pick the prepared rootfs file. On save, a copy is placed in the app's private directory; subsequent container resets always re-extract from that copy, so the original file isn't needed. The copy is cleaned up when the image is deleted.
   - **Mounts** (optional): mount phone directories into the container; see "Mounting phone directories for the AI" below.
   - **proot arguments** (optional): add items one by one; they are appended verbatim to the PRoot launch arguments.
   - **Environment variables** (optional): key-value pairs injected into processes inside the container.
3. After saving, select the configuration in the list to switch to the custom container.

The first time you use a custom image, the app extracts the rootfs into its private directory; how long that takes depends on the image size. Custom images don't configure China-based software mirrors by default; switch them in the initialization menu on first entry to the terminal.

### Troubleshooting

**Errors installing packages**: caused by hard links being unavailable inside the container. Edit the image → proot arguments → add `--link2symlink` → save, then re-enter the terminal. Images imported through the built-in download already carry this argument by default.

**Arrow keys show `^[[A`**: the current shell is `sh`, which doesn't support line editing. Edit the container and set the shell to `/bin/bash` to get arrow keys, history, and completion.

## Mounting Phone Directories for the AI

The AI runs inside the container and by default can only see the workspace, not the phone's shared storage. To grant access, mount a phone directory into the container:

1. Make sure the storage permission has been granted. The permission request appears on first use; if you declined it before, go to system Settings → Apps → XenCode → Permissions → Storage and enable it.
2. Open "Settings → Containers & Images" and edit the container currently in use.
3. Add an entry under "Mounts": **Local directory** is the path on the phone (e.g. `/sdcard/Download`); **Container directory** is the path inside the container (e.g. `/mnt/Download`).
4. Save and restart the terminal session; the AI can then access it via the in-container path.

::: warning Mount only what you need
Mount only the subdirectories you need—do not mount all of `/sdcard` directly. That would expose every file on the phone to the AI, risking privacy leaks.
:::

Example: to let the AI look at the app logs, mount the local directory `/storage/emulated/0/Android/data/com.xencode/` to the container path `/mnt/xencode`; the logs under `/mnt/xencode/files/logs/` then become readable. Note that under `Android/data/` only XenCode's own directory is accessible—other apps' directories are off-limits due to scoped-storage restrictions.

If it's just a single file you need to work with, the simpler way is to copy it into the workspace, which the AI can access by default.

## Behavior After Deleting Everything

Even if you delete every container (including the built-in Alpine), the app keeps working—when the AI runs commands, it falls back to the built-in Alpine as the default execution environment. Reopen the "Containers & Images" page and the empty state lets you restore the built-in Alpine with one tap or add a new image.
