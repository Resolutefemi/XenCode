import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")

    kotlin("plugin.compose")
    kotlin("plugin.serialization") version "2.2.21"
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// Read the release signing credentials from the local keystore.properties (gitignored, not committed).
// If the file is missing (e.g. on CI), skip signing and produce an unsigned release build.
val keystorePropertiesFile = file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

// gitVersionName resolves the version from git tags, eliminating the "hand-edited build.gradle.kts drifting out of sync with the tag" problem at release time.
// Rules:
//   1. If the current commit has a tag (e.g. v1.7.0 or v1.7.0-rc1), use it directly as "1.7.0" or "1.7.0-rc1";
//   2. If the current commit is N commits past the last tag (e.g. v1.7.0-2-g04bc2fa), resolve to "1.7.0-dev.2+g04bc2fa";
//   3. Without a git environment or on error, fall back to the default "1.7.0-dev".
// Use providers.exec instead of Runtime.exec: the configuration cache requires external processes started during
// configuration to be registered as provider inputs; a plain fork invalidates the cache entry (external process started).
val gitDescribeOutput = providers.exec {
    workingDir = rootProject.projectDir
    commandLine("git", "describe", "--tags", "--always", "--dirty")
    isIgnoreExitValue = true
}.standardOutput.asText

fun gitVersionName(): String = try {
    val raw = gitDescribeOutput.get().trim()
    if (raw.startsWith("v")) {
        val version = raw.substring(1) // strip the leading 'v'
        // e.g. "1.7.0", "1.7.0-rc1", or "1.7.0-2-g04bc2fa"
        // Convert git describe output "1.7.0-2-g04bc2fa" into the canonical semver form "1.7.0-dev.2+g04bc2fa"
        val devRegex = Regex("""^(\d+\.\d+\.\d+(?:-[a-zA-Z0-9]+)?)-(\d+)-g([0-9a-f]+)(.*)$""")
        val match = devRegex.matchEntire(version)
        if (match != null) {
            val (base, count, hash, dirty) = match.destructured
            "$base-dev.$count+$hash$dirty"
        } else {
            version
        }
    } else if (raw.isNotEmpty()) {
        "1.7.0-dev+$raw"
    } else {
        "1.7.0-dev"
    }
} catch (e: Exception) {
    "1.7.0-dev"
}

// versionCode is generated from the git commit count: it grows monotonically with each commit and needs manual upkeep,
// preventing the "bumped versionName but forgot versionCode" mistake that breaks upgrade detection.
// The working directory is rootProject.projectDir (repo root); without git (e.g. a downloaded zip source), it falls back to 1.
// CI additionally verifies versionCode monotonicity (see .github/workflows/android-release.yml) so rebases/squashes cannot roll it back.
val gitCommitCountOutput = providers.exec {
    workingDir = rootProject.projectDir
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText

fun gitCommitCount(): Int = try {
    gitCommitCountOutput.get().trim().toIntOrNull() ?: 1
} catch (e: Exception) {
    1
}

// The AI's built-in docs (~/.aicode/docs) are sourced from docs-site/docs at the repo root: the same Markdown builds the VitePress
// site and is also copied into assets/docs to ship inside the APK, so what the site shows and what the AI reads always match.
// Implemented as a plain Gradle Copy so APK builds need no Node — CI and F-Droid only require JDK + Gradle.
val aiDocsGeneratedDir = layout.buildDirectory.dir("generated/aiDocs")
val syncAiDocs = tasks.register<Sync>("syncAiDocs") {
    description = "Copy the user docs from docs-site/docs into assets/docs for the AI to read inside the container"
    from(rootProject.layout.projectDirectory.dir("docs-site/docs")) {
        include("**/*.md")
        // index.md is the docs-site landing page (hero-layout frontmatter); it is meaningless to the AI
        exclude("index.md")
    }
    into(layout.buildDirectory.dir("generated/aiDocs/docs"))
}

android {
    namespace = "com.aicode"
    // compileSdk 36→37: PrismalAGSL (Liquid Glass) declares minCompileSdk=37 in its aar-metadata;
    // below that AGP fails at dependency resolution/resource merging with "requires compileSdk 37". The android-37.0 SDK is installed.
    // Note: targetSdk stays locked at 28 (reasons in the defaultConfig block); compileSdk is independent and does not conflict.
    compileSdk = 37
    // Bump build-tools accordingly: with compileSdk 37, AGP enforces at least 36.0.0 and silently ignores lower values.
    // The build-tools 35.0.0 aidl workaround (see the comment at the end of this file) is applied again on 36.0.0.
    buildToolsVersion = "36.0.0"

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
            // AGP disables v1 (JAR) signing by default when minSdk >= 24, leaving v2 only.
            // Some OEM installers and tools (e.g. MT Manager) require the v1 signature, so
            // force v1+v2+v3 to maximize install compatibility across devices.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    defaultConfig {
        applicationId = "com.aicode"
        minSdk = 26
        // targetSdk stays at 28, but PRoot is no longer the blocker: the full proot set is now shipped via jniLibs into nativeLibraryDir
        // (see the sourceSets and packaging.jniLibs comments), so W^X no longer blocks container startup. Before upgrading, still to resolve:
        //   1. The external workspace (EXTERNAL_LOCAL) reads /storage/emulated/0 directly via the File API; on API 29+ this must move
        //      MANAGE_EXTERNAL_STORAGE；
        //   2. TerminalKeepaliveService's dataSync foreground service is capped at 6 hours per 24 hours on API 35,
        //      so keep-alive would need the specialUse type.
        // Trade-off: ineligible for Google Play.
        targetSdk = 28
        versionCode = gitCommitCount()
        versionName = gitVersionName()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Split APKs by container image: universal carries both arm and x86 Alpine rootfs/proot sets (works everywhere but large),
    // armsolo carries only arm (arm64-v8a) and x86solo only x86 (x86_64)
    // — each single-arch APK is roughly half the size. assets/container/... is provided by each flavor's sourceSet;
    // ContainerInstaller.ASSET_DIR picks one by device ABI on universal, and on solo builds only one set remains so it always matches.
    //
    // Note: defaultConfig no longer pins abiFilters; the flavor dimension decides instead. universal keeps all ABIs
    // its dependencies need (arm64-v8a + x86_64); single-arch packages converge to one ABI so a mismatched device cannot load the wrong image.
    flavorDimensions += "container"
    productFlavors {
        create("universal") {
            dimension = "container"
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        create("armsolo") {
            dimension = "container"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("x86solo") {
            dimension = "container"
            ndk { abiFilters += "x86_64" }
        }
    }

    // Container images share sourceSets across flavors:
    //   _armAssets holds the single physical copy of the arm image, shared by universal + armsolo
    //   _x86Assets holds the single physical copy of the x86 image, shared by universal + x86solo
    // This way single-arch packages naturally contain one image (AGP merges sourceSets as a union: unreferenced dirs stay out),
    // while universal contains both; each image binary also exists exactly once in the repo, with no duplication.
    // Dropped ignoreAssetsPattern=dir:x86: verified in practice it does not work on the container/x86 tree (proven in rc1).
    // The full proot set (proot itself + two loaders + libtalloc/libandroid-shmem) goes through jniLibs instead of assets:
    // the installer only extracts lib/<abi>/lib*.so from the APK into nativeLibraryDir, which is the only location inside the app directory
    // where execve is allowed (SELinux label apk_data_file). The rootfs still ships via assets into filesDir —
    // guest binaries are never execve'd by the kernel; proot's loader maps them into memory with mmap(PROT_EXEC),
    // which W^X does not constrain. The files must therefore be named lib*.so (libproot-loader32.so is actually a 32-bit ELF and works the same way).
    sourceSets {
        getByName("universal") { assets.srcDir("src/_armAssets") }
        getByName("universal") { assets.srcDir("src/_x86Assets") }
        getByName("armsolo") { assets.srcDir("src/_armAssets") }
        getByName("x86solo") { assets.srcDir("src/_x86Assets") }
        getByName("universal") { jniLibs.srcDirs("src/_armJniLibs", "src/_x86JniLibs") }
        getByName("armsolo") { jniLibs.srcDir("src/_armJniLibs") }
        getByName("x86solo") { jniLibs.srcDir("src/_x86JniLibs") }
        // Docs do not live under app/src/main/assets; syncAiDocs generates them from docs-site/docs and merges them in
        getByName("main") { assets.srcDir(aiDocsGeneratedDir) }
    }

    buildTypes {
        // debug appends the .debug suffix → applicationId becomes com.aicode.debug, so it can coexist on one device
        // with the release build (com.aicode) without overwriting it. The IDE no longer uninstalls the installed release when launching debug due to signature mismatch.
        // Note: with a different applicationId, the debug variant uses /data/data/com.aicode.debug/ as its private directory,
        // so the rootfs extracted by release and release workspace projects are invisible to debug (they must be re-extracted/re-cloned) — expected isolation.
        debug {
            applicationIdSuffix = ".debug"
            // Performance key 1: debug skips R8 by default, so code runs interpreted + device-side JIT; hot paths like the
            // giant AIChatPanel composables get compiled on first run and lack method inlining, systematically inflating the frame budget.
            // (cont.) Enabling R8 code optimization (no obfuscation, proguard-android-optimize.txt) moves inlining and
            // dead-code elimination to build time.
            isMinifyEnabled = true
            // Performance key 2 (more fundamental than R8): ART forces debuggable apps down to interpreter-tier dexopt —
            // Only verify/quicken levels are allowed and AOT compilation to machine code is disabled. On-device base.odex comparison:
            // as reference, WeChat is 707MB, Alipay 543MB, Chrome 177MB, Bilibili 51MB, ChatGPT 32MB,
            // while this app is only 213KB — three orders of magnitude apart. Other apps effectively run machine code,
            // we run interpreted bytecode with just-in-time compilation. That is the fundamental reason a small app can still feel janky:
            // games feel smooth precisely because they are AOT-compiled native machine code.
            // Turning off debuggable lets ART restore speed-level AOT. Flip it back to true temporarily when attaching a breakpoint debugger.
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Reproducible builds: AGP 8.3+ writes version-control-info.textproto into the APK by default;
            // its local_root_path holds the build machine's absolute path, which necessarily differs between GitHub CI and F-Droid build servers,
            // making the two APKs byte-different and breaking reproducibility checks. Disable it (version provenance comes from git tags/CI).
            vcsInfo {
                include = false
            }
        }
        // beta test build: inherits all release configuration (signing/R8/resource shrinking/proguard) and only appends the .beta suffix
        // → applicationId becomes com.aicode.beta, coexisting with the release build without overwriting it.
        // Built and uploaded to Artifacts by .github/workflows/beta.yml on push to main, for verification on test devices.
        create("beta") {
            initWith(getByName("release"))
            applicationIdSuffix = ".beta"
            // :terminal-emulator / :terminal-view have no beta variant; dependency resolution falls back to their release variant.
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // sora-editor's language-textmate requires core library desugaring on devices below API 33 (hence minSdk 26 needs it enabled).
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // The Shizuku UserService uses an AIDL cross-process interface (app/src/main/aidl); enable it explicitly rather than relying on AGP defaults.
        aidl = true
    }



    packaging {
        jniLibs {
            // Must be true: with the default (false), .so files are mapped straight from the APK and never extracted, leaving nativeLibraryDir empty,
            // so execve on libproot.so returns ENOENT. Trade-off: one extra extracted copy on disk (the proot set is ~300KB).
            useLegacyPackaging = true
            // The proot set is an externally pre-built artifact and must not be post-processed by the build: the current NDK's strip cannot recognize it and
            // skips it; declaring that explicitly guards against AGP/NDK upgrades where strip starts to apply and would alter the bytes.
            keepDebugSymbols += setOf(
                "**/libproot.so",
                "**/libproot-loader.so",
                "**/libproot-loader32.so",
                "**/libtalloc.so",
                "**/libandroid-shmem.so"
            )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/INDEX.LIST"
            excludes += "/sshj.properties"
            excludes += "/kotlin-tooling-metadata.json"
            excludes += "/DebugProbesKt.bin"
            // Both bcprov-jdk18on 1.78.1 and jspecify ship that multi-release OSGi metadata file, colliding at packaging time;
            // it is only an OSGi MANIFEST, so excluding it is safe.
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    // targetSdk is intentionally locked at 28 (reasons in the defaultConfig comments); the cost is no Google Play — so disable Play's outdated-targetSdk check.
    // Also disable lint for release builds: this repo ships only via GitHub Release, not Play,
    // and lintVital consumes extra CPU/memory during R8/packaging (easy OOM on a 2-core 7GB runner) and its findings do not block releases anyway.
    lint {
        disable += "ExpiredTargetSdkVersion"
        checkReleaseBuilds = false
        abortOnError = false
    }

    // Do not mock android.jar in unit tests: let Log and friends return defaults instead of throwing "not mocked",
    // otherwise incidental FileLogger calls in code under test would crash plain-JVM unit tests.
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric (migration tests) needs real Android resources
        unitTests.isIncludeAndroidResources = true
    }

    // Migration tests (MigrationTestHelper) read the Room-exported schema from merged assets under Robolectric,
    // at <databaseClass FQN>/<version>.json; AGP does not merge the unit test sourceSet's assets
    // into Robolectric's assets (test_config.properties points at mergeUniversalDebugAssets),
    // so the schema is attached to the debug variant: migration tests only run on universalDebug and release builds never carry the schema.
    sourceSets {
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }
}

// Disable the lintVital<Flavor>Release tasks entirely (one per flavor),
// keeping them out of the assembleRelease task graph — cheaper on build time and memory than lint.checkReleaseBuilds=false.
// Disable only before the release task graph runs, so dev-time debug lint is unaffected.
gradle.projectsEvaluated {
    tasks.matching { it.name.startsWith("lintVital") }.configureEach { enabled = false }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2026.01.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.animation:animation")

    // Liquid Glass (top title bar / bottom input area / todo overlay / dialogs).
    // Coordinates match JitPack (com.github.styropyr0:PrismalAGSL:v1.0.4), but resolution happens from the
    // <root>/prismalrepo local Maven repo — JitPack builds on demand, and Gradle direct hits occasionally failed
    // to resolve (the same URL fetched the AAR fine via curl). The local mirror drops the upstream POM's version-less
    // Compose dependency and the conflicting compose-bom:2026.06.01; the Compose version is governed by the BOM above.
    // Coordinate caveat: the four-segment form in the README (com.github.styropyr0:PrismalAGSL:Prismal:TAG) is wrong;
    // Gradle only accepts three; the real artifacts are groupId=com.github.styropyr0 / artifactId=PrismalAGSL.
    // Compatibility: aar-metadata declares minCompileSdk=37 (hence compileSdk 37) and minSdk 25 (ours is 26);
    // class Kotlin metadata is mv=[2,2,0], directly readable by this project's Kotlin 2.2.21;
    // the library ships three fallback tiers: frosted on API 25-30 / RenderEffect blur on 31-32 / full AGSL refraction on 33+,
    // and we never branch on API level ourselves — the library downgrades on its own.
    implementation("com.github.styropyr0:PrismalAGSL:v1.0.4")
    // The AAR's AndroidManifest declares androidx.startup.InitializationProvider;
    // AGSL shaders (assets/agsl/*.agsl) load at startup via PrismalAgslInitializer; without this dependency startup
    // throws ClassNotFoundException. The upstream local POM omits it, so add it explicitly here.
    implementation("androidx.startup:startup-runtime:1.2.0")

    // Vico charts (token usage trends)
    implementation("com.patrykandpatrick.vico:compose-m3:2.4.4")

    // Lifecycle + ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.1")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // Hilt dependency injection
    implementation("com.google.dagger:hilt-android:2.56.1")
    ksp("com.google.dagger:hilt-compiler:2.56.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room database
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.4")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // HTML parsing and sanitizing (for WebFetchTool)
    implementation("org.jsoup:jsoup:1.18.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Kotlin serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // YAML parsing (for skill frontmatter)
    implementation("org.yaml:snakeyaml:2.2")

    // Remote sync (SFTP/FTP) and the built-in FTP server
    implementation("com.hierynomus:sshj:0.38.0")
    // BouncyCastle: sshj 0.38.0 uses X25519 key exchange, which Android's stripped-down BC provider lacks,
    // so pull in the full version and register it as a replacement (see AIEditorApp.registerBouncyCastle).
    // 1.75 hits CVE-2024-30172 (Ed25519 verification infinite loop DoS), fixed in 1.78; use 1.78.1 to match sshj 0.39.0.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("commons-net:commons-net:3.10.0")
    implementation("org.apache.ftpserver:ftpserver-core:1.2.0")
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // Container: extract the Alpine rootfs tar.gz (correct symlink/hardlink/permission handling)
    implementation("org.apache.commons:commons-compress:1.26.2")
    // xz support: commons-compress's XZCompressorInputStream depends on this library (for user-imported .tar.xz images)
    implementation("org.tukaani:xz:1.10")

    // Termux open-source terminal components: terminal-emulator handles VT100/ANSI parsing and the PTY (ships its own native .so),
    // terminal-view is the rendering Android View. Distributed via JitPack (com.github.<user>.<repo> coordinates),
    // avoiding a hand-rolled terminal emulator.
    implementation(project(":terminal-emulator"))
    implementation(project(":terminal-view"))

    // Material Icons
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    // Lucide Icons
    implementation("br.com.devsrsouza.compose.icons:feather:1.1.1")

    // Draggable sortable list (long-press drag gesture; used for provider ordering)
    implementation("sh.calvin.reorderable:reorderable:3.1.0")

    // Markdown Renderer
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.41.0")
    // Markdown Renderer — Code Syntax Highlighting
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.41.0")
    // Syntax-highlighting engine (brought in transitively by markdown-renderer-code; declared explicitly for direct use in the diff view)
    implementation("dev.snipme:highlights-jvm:1.1.0")
    // LaTeX math rendering (pure JVM, no .so; jlatexmath-android bundles the font resources)
    implementation("ru.noties:jlatexmath-android:0.2.0")

    // Code editor (standalone editor page). Only the pure-JVM modules: language-treesitter and oniguruma-native carry .so files,
    // which would conflict with ABI flavor splits and F-Droid reproducible builds — so they are not included.
    implementation(platform("io.github.rosemoe:editor-bom:0.24.6"))
    implementation("io.github.rosemoe:editor")
    implementation("io.github.rosemoe:language-textmate")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // WorkManager — keep-alive fallback: periodically check TerminalKeepaliveService liveness and restart it (KeepaliveWorker)
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.hilt:hilt-work:1.2.0")

    // Android WebKit extensions (modern dark-mode WebSettingsCompat / ForceDark support)
    implementation("androidx.webkit:webkit:1.12.1")

    // Shizuku: run commands as the adb shell user (uid 2000). The api artifact provides the Shizuku class and UserService binding;
    // the provider artifact registers ShizukuProvider (see AndroidManifest) to obtain the binder across processes.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Core Android
    implementation("androidx.core:core:1.16.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    // Migration tests: MigrationTestHelper + Robolectric (run Room migrations on the JVM; needs real resources)
    testImplementation("androidx.room:room-testing:2.7.1")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.1.5")
    // Coroutine tests: runTest virtual time (instead of runBlocking) + Dispatchers.setMain (ViewModel test infrastructure)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // MockK: native Kotlin mocking (coEvery/coVerify coroutine support) for stubbing ViewModel/Repository dependencies
    testImplementation("io.mockk:mockk:1.14.11")
    // XML layout renderer tests: XmlPullParserFactory needs a real implementation on the JVM (android.jar ships stubs only)
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Migration version reconciliation: run `./gradlew checkMigrations` manually locally (CI runs the script directly in the workflow).
// Verifies consecutive migration numbering, SCHEMA_VERSION consistency, and that published migrations are untouched and not reused (see scripts/check_migrations.py).
tasks.register<Exec>("checkMigrations") {
    commandLine("python3", "scripts/check_migrations.py")
    workingDir(rootProject.projectDir)
}

// Docs must be generated before assets merge; otherwise the first build (or a build after clean) ships an APK without docs/.
tasks.named("preBuild") { dependsOn(syncAiDocs) }

// ── Fix javac failures caused by generated AIDL files (Windows) ───────────────────────────────────────────────
// The build-tools 35.0.0 aidl writes the full command line into the generated .java's header comment:
//   /* This file is auto-generated. DO NOT MODIFY.
//    * Using: C:\...\aidl.exe ... -I...\app\src\universal\aidl ...
//    */
// The flavor source dir path contains `\universal`, and `\u` is rejected by javac as an illegal Unicode escape
// (on both JDK 17 and 21), failing :app:compile<Variant>JavaWithJavac outright; all three flavors are affected
// because AGP passes every flavor's aidl directory as -I inputs.
// The clean fix is renaming the `universal` flavor so it does not start with 'u' (which would also change CI and artifact names); for now we
// take the cheapest workaround: after the AIDL task runs, swap the backslash in that comment line for a forward slash. Comment-only,
// so compiled output semantics are untouched; on macOS/Linux there is no backslash in the first place, making it a no-op.
tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Aidl") }.configureEach {
    doLast {
        val root = File(project.layout.buildDirectory.get().asFile, "generated/aidl_source_output_dir")
        if (!root.isDirectory) return@doLast
        root.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { f ->
            val lines = f.readLines()
            var changed = false
            val fixed = lines.map { line ->
                if (line.startsWith(" * Using: ") && line.contains('\\')) {
                    changed = true
                    line.replace('\\', '/')
                } else {
                    line
                }
            }
            if (changed) f.writeText(fixed.joinToString("\n"))
        }
    }
}
