# Building Flutter Apps in a Container

With JDK, the Android SDK, and the Flutter SDK set up in XenCode's Linux container (PRoot), you can build a Flutter app's Android APK directly from source. This guide uses Debian 12 (bookworm) aarch64 as an example; building a debug APK has been verified inside a PRoot container.

::: tip Supported scope
The ARM64 container only supports building **debug APKs** (JIT mode, natively ARM64). **Release / profile builds cannot be produced locally**: Google ships gen_snapshot for Android AOT only for linux-x64, so AOT compilation cannot run on an ARM64 host. For release builds, use a remote build service such as GitHub Actions.
:::

::: tip Mirrors and network notes
This guide installs and builds with Chinese mirrors by default (faster and steadier downloads). Artifacts from the mirrors are fully equivalent to the official ones: even if your network can reach `dl.google.com` and other official sources directly, there is no need to switch back to the official configuration.
:::

## Environment baseline

| Item | Value |
| --- | --- |
| OS | Debian GNU/Linux 12 (bookworm) aarch64 (PRoot) |
| JDK | OpenJDK 17 (headless) |
| Flutter | 3.27.4 stable (git clone of the pinned tag) |
| Dart | 3.6.2 (linux-arm64) |
| Gradle | Bundled with the project wrapper (8.3); no system install needed |
| SDK | platforms android-35, build-tools 33.0.1 + 35.0.0, platform-tools |
| aapt2 | Statically built ARM64 build (lzhiyong/android-sdk-tools) |

## 1. Install base dependencies

```bash
apt update
apt install -y openjdk-17-jdk-headless curl unzip zip git
```

Verify the JDK:

```bash
java -version
# openjdk version "17..."
```

## 2. Install the Android SDK

By default, components are installed from Chinese mirrors (faster and steadier downloads). Apart from sdkmanager itself (cmdline-tools), which must be downloaded and unpacked by hand, everything else is installed in a single command by overriding sdkmanager's default repository root URL with the `SDK_TEST_BASE_URL` environment variable (it must end with `/`, and the mirror must serve `repository2-3.xml`); both `--list` and `--install` have been verified to work:

```bash
# The only manual step: fetch sdkmanager itself (downloaded from the mirror)
mkdir -p ~/android/sdk && cd ~/android/sdk
curl -fL -C - -o clt.zip https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-13114758_latest.zip
unzip -q clt.zip
mkdir -p cmdline-tools/latest && mv cmdline-tools/* cmdline-tools/latest/

# All components installed from the mirror (directory layout and license acceptance handled automatically)
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=$HOME/android/sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
export SDK_TEST_BASE_URL=https://mirrors.cloud.tencent.com/AndroidSDK/

yes | sdkmanager --install "platforms;android-35" "build-tools;33.0.1" "build-tools;35.0.0" "platform-tools"
```

::: tip Version notes
The Flutter 3.27 template uses `compileSdk` 35 (which requires `platforms;android-35`), and the default `buildToolsVersion` is **33.0.1** (if it is missing you get `Failed to find Build Tools revision 33.0.1`). build-tools 35.0.0 is used for the aapt2 override in section 5 — optional but recommended.
:::

::: tip Mirrors vs. the official source
`SDK_TEST_BASE_URL` was designed for testing (it is not an officially documented setting), but mirror-based setups use it widely. An SDK installed from a mirror is fully equivalent to an official install: even if your network can reach `dl.google.com` directly, there is no need to switch back to the official procedure. If this approach fails in a particular scenario, fall back to the official source (see below) or assemble the SDK by hand.
:::

### Optional: install from the official source

If your network can reach `dl.google.com` directly, the standard official procedure works too:

```bash
mkdir -p ~/android/sdk && cd ~/android/sdk
curl -O https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q commandlinetools-linux-*.zip
mkdir -p cmdline-tools/latest && mv cmdline-tools/* cmdline-tools/latest/

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=$HOME/android/sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

yes | sdkmanager --install "platforms;android-35" "build-tools;33.0.1" "build-tools;35.0.0" "platform-tools"
```

### Alternative: assemble the SDK by hand from mirrors

If the `SDK_TEST_BASE_URL` approach does not work, download the component zips from the Tencent Cloud mirror and assemble them by hand. You end up with the four directories `build-tools/`, `platforms/`, `platform-tools/`, and `cmdline-tools/`, identical to what sdkmanager produces:

| Component | Mirror URL |
| --- | --- |
| commandlinetools | `https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-13114758_latest.zip` |
| platform-35 | `https://mirrors.cloud.tencent.com/AndroidSDK/platform-35_r02.zip` |
| build-tools 33.0.1 | `https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r33.0.1-linux.zip` |
| build-tools 35.0.0 | `https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r35_linux.zip` |
| platform-tools | `https://mirrors.cloud.tencent.com/AndroidSDK/platform-tools_r35.0.2-linux.zip` |

Large-file downloads from the mirror tend to drop connections; use `curl -fL -C -` to resume, combined with retries in a loop. Assemble the directories:

```bash
cd ~/android/sdk
# cmdline-tools: unpacks into cmdline-tools/; rename to latest
unzip clt.zip && mv cmdline-tools cmdline-tools/latest   # actual layout depends on the zip
# platform-35: unpacks into android-35/; move into platforms/
mkdir -p platforms && mv android-35 platforms/android-35
# build-tools: the zip unpacks into android-13/ (33.0.1) or android-15/ (35.0.0); move into the matching version directory
mkdir -p build-tools/33.0.1 build-tools/35.0.0
mv android-13/* build-tools/33.0.1/ && mv android-15/* build-tools/35.0.0/
# platform-tools: unpacks into platform-tools/ as is; no move needed
```

## 3. Replace the ARM64 native binaries

Google's official `aapt2`, `aidl`, `zipalign`, `adb`, and other tools are compiled for x86_64 and cannot run on aarch64 (an architecture limitation, nothing to do with networking), so they must be replaced with the community's statically built ARM64 versions. Download from `https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip`; if the download is slow or fails, prefix the original URL with a GitHub proxy: `https://gh-proxy.com/https://github.com/...`

```bash
curl -L -O https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip
unzip android-sdk-tools-static-aarch64.zip -d ~/armtools35
# Overwrite build-tools (both 33.0.1 and 35.0.0) and platform-tools
cp -p ~/armtools35/build-tools/* ~/android/sdk/build-tools/35.0.0/
cp -p ~/armtools35/build-tools/* ~/android/sdk/build-tools/33.0.1/
cp -p ~/armtools35/platform-tools/* ~/android/sdk/platform-tools/
chmod +x ~/android/sdk/build-tools/*/* ~/android/sdk/platform-tools/*
```

Verify:

```bash
~/android/sdk/build-tools/35.0.0/aapt2 version   # printing a version number means it works
~/android/sdk/build-tools/35.0.0/zipalign        # a usage message means it works
```

## 4. Install the Flutter SDK

Clone the pinned tag with git:

```bash
git clone --depth 1 --branch 3.27.4 https://github.com/flutter/flutter.git ~/.local/flutter-3.27.4
export PATH=$PATH:~/.local/flutter-3.27.4/bin

flutter --version   # the first run automatically downloads the dart-sdk and engine artifacts
```

::: tip Handling slow dart-sdk setup under PRoot
The first run of `flutter --version` automatically downloads the dart-sdk (about 210MB) and unpacks it. Under PRoot this step can hang for a long time (slow download, slow unzip, lock waits). If it gets stuck, finish the installation manually to bypass the bootstrap:

```bash
cd ~/.local/flutter-3.27.4/bin/cache
EV=$(cat ../internal/engine.version)     # e.g. 82bd5b7209295a5b7ff8cae0df96e7870171e3a5
curl -fL -o dart-sdk-linux-arm64.zip \
  "https://storage.flutter-io.cn/flutter_infra_release/flutter/$EV/dart-sdk-linux-arm64.zip"
# if reachable, the official source works too: https://storage.googleapis.com/flutter_infra_release/flutter/$EV/dart-sdk-linux-arm64.zip
unzip -q dart-sdk-linux-arm64.zip -d .   # unpacks into dart-sdk/
rm -f dart-sdk-linux-arm64.zip
echo "$EV" > engine-dart-sdk.stamp       # write the stamp so flutter skips the automatic download
```

Verify:

```bash
~/.local/flutter-3.27.4/bin/cache/dart-sdk/bin/dart --version
# Dart SDK version: 3.6.2 ... on "linux_arm64"
```
:::

## 5. Configuration

### local.properties

`flutter create` generates it automatically (with `sdk.dir` and `flutter.sdk`), so there is no need to write it by hand — just confirm the contents are correct:

```bash
cat ~/workspace/<project>/android/local.properties
# sdk.dir=/root/android/sdk
# flutter.sdk=/root/.local/flutter-3.27.4
```

### Global Gradle configuration

The aapt2 override should be written to the global file, not into the project's `gradle.properties`:

```bash
mkdir -p ~/.gradle
echo "android.aapt2FromMavenOverride=/root/android/sdk/build-tools/35.0.0/aapt2" > ~/.gradle/gradle.properties
```

The container has roughly 4G of usable memory; the default `-Xmx4G` in the project's `android/gradle.properties` can trigger OOM, so lowering it is recommended:

```properties
org.gradle.jvmargs=-Xmx2G -XX:MaxMetaspaceSize=1G -XX:+HeapDumpOnOutOfMemoryError
```

### Mirror configuration (default)

When Gradle resolves dependencies, it hangs for a long time on connection timeouts if the repository list contains `google()` (which points to dl.google.com) and the network cannot reach it. Using mirror repositories directly is the recommended default (faster builds); there is no need to revert the settings below even when the official sources are reachable:

1. **Gradle wrapper mirror**: the `distributionUrl` in the project's `android/gradle/wrapper/gradle-wrapper.properties` points to `services.gradle.org` by default; you can switch it to a Chinese mirror:

```
distributionUrl=https\://mirrors.huaweicloud.com/gradle/gradle-8.3-all.zip
```

2. **Dependency repository mirrors**: remove `google()` / `mavenCentral()` / `gradlePluginPortal()` from all repositories, keeping only the Aliyun mirrors:

```groovy
maven { url 'https://maven.aliyun.com/repository/google' }
maven { url 'https://maven.aliyun.com/repository/central' }
maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }   // add when the plugin repository is needed
```

There are 5 places to modify:

| File | Purpose |
| --- | --- |
| Project `android/settings.gradle` | pluginManagement.repositories |
| Project `android/build.gradle` | allprojects.repositories |
| `flutter SDK/packages/flutter_tools/gradle/settings.gradle.kts` | :gradle subproject repositories (if left unmodified, AGP 7.3.0 connects to dl.google.com) |
| `flutter SDK/packages/flutter_tools/gradle/resolve_dependencies.gradle` | Dependency pre-resolution |
| `flutter SDK/packages/flutter_tools/gradle/src/main/kotlin/dependency_version_checker.gradle.kts` | Version-check buildscript |

::: warning Note
The flutter engine artifact repository (`download.flutter.io`) is injected automatically by the flutter plugin and its address is assembled from `FLUTTER_STORAGE_BASE_URL`; do not remove it. Of the 5 places above, only the google/mavenCentral entries need handling. Files inside the SDK are overwritten when flutter upgrades, so the changes must be reapplied.
:::

3. **Engine artifacts and dependency download sources**: set environment variables at build time so flutter's dart-sdk, engine artifacts, and pub packages go through the Chinese mirrors or the official sources:

```bash
export FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn   # engine artifacts etc. via the Chinese mirror
unset PUB_HOSTED_URL    # use the official pub.dev (usually reachable); pub.flutter-io.cn has had content-hash verification failures
```

## 6. Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export PATH=$PATH:~/.local/flutter-3.27.4/bin
export FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn   # engine artifacts etc. via the Chinese mirror (recommended default)
unset PUB_HOSTED_URL    # use the official pub.dev (recommended default, usually reachable)

cd ~/workspace/<project>
flutter build apk --debug --target-platform android-arm64
```

The environment variables above are the recommended defaults (explained in section 5); you can keep them even when the official sources are reachable — no need to revert.

The first build takes about 3 to 10 minutes (downloading the Gradle distribution and dependencies, then compiling, depending on the network); later incremental builds take about 1 to 2 minutes. The Gradle daemon works fine under PRoot; if the daemon exits abnormally and leaves conflicting lock files, add `--no-daemon` or delete `~/.gradle/caches/*.lock`.

After a successful build the APK is output at:

```
build/app/outputs/flutter-apk/app-debug.apk
```

Verify with `aapt2 dump badging`:

```bash
~/android/sdk/build-tools/35.0.0/aapt2 dump badging build/app/outputs/flutter-apk/app-debug.apk | head -3
```

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `Connect to dl.google.com timed out` (hangs for a long time) | Repositories contain `google()`/`mavenCentral()` and dl.google.com is unreachable | Use the mirror repositories per section 5 (the default configuration) |
| `Could not resolve io.flutter:flutter_embedding_debug` | Same as above — flutter engine dependency resolution hits dl.google.com | Same as above |
| `Failed to find Build Tools revision 33.0.1` | The Flutter template defaults to buildTools 33.0.1, which the SDK is missing | Install `build-tools;33.0.1` as well (and replace the ARM64 binaries) |
| `aapt2 ... cannot execute binary / syntax error` | The x86 build of aapt2 is in use | Make sure `aapt2FromMavenOverride` in `~/.gradle/gradle.properties` points to the ARM64 build |
| flutter bootstrap stalls downloading dart-sdk / repeated lock waits | Automatic download and unzip are unstable under PRoot | Install the dart-sdk manually per section 4 and write the stamp |
| `Downloaded archive for xxx had wrong content-hash` (pub retries repeatedly) | The pub mirror returned a corrupted package | `unset PUB_HOSTED_URL` to switch to the official pub.dev, and clear `~/.pub-cache` |
| `flutter doctor` Android toolchain check times out | doctor verifies dl.google.com online via sdkmanager internally | Normal when dl.google.com is unreachable; does not affect builds |
| Gradle lock file conflicts | The previous build did not exit cleanly | Delete `~/.gradle/caches/*.lock` or add `--no-daemon` |
| No progress visible when the build command is piped | `\| tail` buffers the output | Run the build command directly without tail; failure details appear within the first 1 to 3 minutes during dependency resolution |

## Limitations and tips

- **release/profile cannot be built on ARM64**: the Android AOT gen_snapshot only ships for linux-x64. For release builds, use GitHub Actions (run `flutter build apk --release` on x64) or another remote build method.
- The `flutter create` template uses `compileSdk=35` and `buildToolsVersion=33.0.1`; before modifying the template, confirm the corresponding SDK components are installed.
- Extracting many small files under PRoot (e.g. dart-sdk, dependency caches) is naturally slow — do not mistake it for a hang.
- If dependency resolution keeps failing, first verify each repository URL with `curl -sI`, then check the repository order.
