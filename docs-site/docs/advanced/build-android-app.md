# Building Android Apps in a Container

With JDK and the Android SDK set up in XenCode's Linux container, you can build Android apps directly from source. This guide uses Debian 12 (bookworm) aarch64 as an example; Ubuntu, Arch, and other distros work the same way.

::: tip Use a custom Debian / Ubuntu image
The built-in Alpine container uses musl libc, so building Android apps under PRoot takes extra work (see the notes at the end of this page). To import a custom image, see "Custom container images".
:::

## Environment baseline

| Item | Value |
| --- | --- |
| OS | Debian GNU/Linux 12 (bookworm) aarch64 |
| JDK | OpenJDK 17 |
| Gradle | Bundled with the project wrapper; no system install needed |
| SDK | Android 36 / build-tools 35.0.0 |
| aapt2 | Statically built ARM64 35.0.2 (community build) |

## 1. Install base dependencies

```bash
apt update
apt install -y openjdk-17-jdk-headless curl wget unzip ripgrep
```

Verify the JDK:

```bash
/usr/lib/jvm/java-17-openjdk-arm64/bin/java -version
# should print openjdk version "17..."
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

yes | sdkmanager --install "platforms;android-36" "build-tools;35.0.0" "platform-tools"
```

::: tip Version notes
Choose component versions to match your target project's `compileSdk` and `buildToolsVersion`; this guide uses Android 36 / build-tools 35.0.0.
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

yes | sdkmanager --install "platforms;android-36" "build-tools;35.0.0" "platform-tools"
```

### Alternative: assemble the SDK by hand from mirrors

If the `SDK_TEST_BASE_URL` approach does not work, download the component zips from the Tencent Cloud mirror and assemble them by hand. You end up with the three directories `build-tools/`, `platforms/`, and `platform-tools/`, identical to what sdkmanager produces:

| Component | Mirror URL |
| --- | --- |
| commandlinetools | `https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-13114758_latest.zip` |
| platform-36 | `https://mirrors.cloud.tencent.com/AndroidSDK/platform-36_r01.zip` |
| build-tools 35.0.0 | `https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r35_linux.zip` |
| platform-tools | `https://mirrors.cloud.tencent.com/AndroidSDK/platform-tools_r35.0.2-linux.zip` |

```bash
cd /workspace
curl -L -O https://mirrors.cloud.tencent.com/AndroidSDK/platform-36_r01.zip
curl -L -O https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r35_linux.zip
curl -L -O https://mirrors.cloud.tencent.com/AndroidSDK/platform-tools_r35.0.2-linux.zip

# 1) build-tools: the zip unpacks into android-15/; move its contents into build-tools/35.0.0/
unzip build-tools_r35_linux.zip -d ~/android/sdk
mkdir -p ~/android/sdk/build-tools/35.0.0
mv ~/android/sdk/android-15/* ~/android/sdk/build-tools/35.0.0/

# 2) platform: unpacks into android-36/
unzip platform-36_r01.zip -d ~/android/sdk
mkdir -p ~/android/sdk/platforms
mv ~/android/sdk/android-36 ~/android/sdk/platforms/android-36

# 3) platform-tools: unpacks into platform-tools/ as is; no move needed
unzip platform-tools_r35.0.2-linux.zip -d ~/android/sdk

ls ~/android/sdk   # should show the three directories build-tools/ platforms/ platform-tools/
```

## 3. Replace the ARM64 native binaries

Google's official `aapt2`, `adb`, and other tools are compiled for x86_64 and cannot run on aarch64, so they must be replaced with community-maintained statically built ARM64 versions.

Download from `https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip`. If the download is slow or fails, prefix the original URL with a GitHub proxy (verified working, 2026-08):

| Accelerator | Usage |
| --- | --- |
| gh-proxy.com | `https://gh-proxy.com/https://github.com/...` |
| ghproxy.vip | `https://ghproxy.vip/https://github.com/...` |

```bash
# Example: accelerated download via gh-proxy.com
curl -L -O https://gh-proxy.com/https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip
# Or download directly if the container can reach GitHub
# curl -L -O https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip
unzip /workspace/android-sdk-tools-static-aarch64.zip -d ~/armtools35
```

Replace the binaries in the SDK:

```bash
cp -p ~/armtools35/build-tools/* ~/android/sdk/build-tools/35.0.0/
cp -p ~/armtools35/platform-tools/* ~/android/sdk/platform-tools/
```

Verify that `aapt2` runs:

```bash
~/android/sdk/build-tools/35.0.0/aapt2 version
# should print a version number without errors
```

## 4. Configuration

### local.properties (project root)

```bash
echo "sdk.dir=/root/android/sdk" > /workspace/local.properties
```

This file is usually already ignored by `.gitignore`, so it will not pollute the repository.

### Global Gradle configuration

Write the aapt2 override into the global file, not into the project's `gradle.properties`:

```bash
mkdir -p ~/.gradle
echo "android.aapt2FromMavenOverride=/root/android/sdk/build-tools/35.0.0/aapt2" > ~/.gradle/gradle.properties
```

### apt sources (optional)

The container may not reach the official sources by default; switching to a Chinese mirror is recommended:

```
URIs: http://mirrors.ustc.edu.cn/debian
URIs: http://mirrors.ustc.edu.cn/debian-security
```

### Download a Gradle distribution (optional)

Projects using the Gradle wrapper need no system Gradle, but bootstrapping a new project from scratch requires a distribution first. If the official source `https://services.gradle.org/distributions/gradle-8.11.1-bin.zip` downloads slowly, use a mirror:

- Huawei Cloud: `https://mirrors.huaweicloud.com/gradle/gradle-8.11.1-bin.zip`
- Tencent Cloud: `https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip`

```bash
curl -L -o /workspace/gradle.zip https://mirrors.huaweicloud.com/gradle/gradle-8.11.1-bin.zip
unzip gradle.zip -d /workspace/tools
/workspace/tools/gradle-8.11.1/bin/gradle --version   # use it to run builds or generate the wrapper
```

## 5. Build

```bash
cd /workspace
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 \
ANDROID_HOME=/root/android/sdk \
./gradlew assembleDebug
```

If dependency downloads are slow, switch `dependencyResolutionManagement.repositories` in `settings.gradle.kts` to the Aliyun mirrors (AGP pulls dependencies from `google()` / `mavenCentral()` by default):

```kotlin
maven { url = uri("https://maven.aliyun.com/repository/google") }
maven { url = uri("https://maven.aliyun.com/repository/central") }
maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
```

The first build takes about 10 to 15 minutes (downloading dependencies plus Kotlin compilation); later incremental builds take 1 to 2 minutes. The Gradle daemon works fine under PRoot and speeds up incremental builds; if the daemon exits abnormally and leaves conflicting lock files, just add `--no-daemon` for that run.

On success, the APK output path depends on the project configuration, typically:

```
app/build/outputs/apk/<flavor>/debug/app-<flavor>-debug.apk
```

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `JAVA_HOME is not set` | Environment variable not set | Add `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64` on the command line |
| `SDK location not found` | Missing local.properties | Create `local.properties` containing `sdk.dir=/root/android/sdk` |
| `aapt2 syntax error` | The x86 build of aapt2 is in use | Make sure `aapt2FromMavenOverride` in `~/.gradle/gradle.properties` points to the ARM build |
| `Failed to install build-tools` | AGP's online verification failed | Make sure the build-tools version the project needs is installed locally |
| apt downloads return 403 | Mirror temporarily unavailable | Switch mirrors (TUNA → USTC) |
| Gradle lock file conflicts | The previous build did not exit cleanly | Delete `~/.gradle/caches/*.lock`, or add `--no-daemon` |

## Notes: fixing the built-in Alpine container

Building Android apps in the built-in Alpine container may fail with `Failed to delete /tmp/tempdir_*`. The cause: `TemporaryFile` in AGP's apkzlib deletes temporary directories via `File.delete()`, an operation that can fail under PRoot. Custom Debian images are unaffected.

::: warning Version-specific
The code below targets apkzlib in Gradle 8.11.1 / AGP 8.x (package `com.android.tools.build.apkzlib.bytestorage.TemporaryFile`). Package paths and internals may differ in other versions, so adjust to the decompiled structure of the jar you actually use.
:::

Replace `File.delete()` with `Files.delete()` (goes through unlinkat to work around the failing underlying delete):

```java
package com.android.tools.build.apkzlib.bytestorage;

import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;

public class TemporaryFile implements Closeable {
    private boolean deleted;
    private final File file;

    public TemporaryFile(File file) {
        this.deleted = false;
        this.file = file;
    }

    public File getFile() {
        Preconditions.checkState(!this.deleted, "File already deleted");
        return this.file;
    }

    @Override
    public void close() throws IOException {
        if (this.deleted) {
            return;
        }
        this.deleted = true;
        deleteFile(this.file);
    }

    private void deleteFile(File f) throws IOException {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    deleteFile(kid);
                }
            }
        }
        if (f.exists()) {
            try {
                Files.delete(f.toPath());
            } catch (NoSuchFileException e) {
                // ignore if already deleted
            } catch (IOException e) {
                if (!f.delete()) {
                    throw new IOException("Failed to delete '" + f.getAbsolutePath() + "'", e);
                }
            }
        }
    }
}
```

Compile this source and replace the `.class` files back into the `instrumented-apkzlib-*.jar` in the Gradle cache.
