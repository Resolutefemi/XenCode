package com.xencode.feature.agent.domain.container

import android.content.Context
import android.system.Os
import com.xencode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installs the Alpine rootfs and PRoot binaries bundled in assets into the app's private directory.
 *
 * Loads the matching resources automatically based on device architecture (ARM / x86). targetSdk is pinned to 28 so files in the data directory stay executable (see build.gradle.kts).
 */
@Singleton
class ContainerInstaller @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerOsDetector: ContainerOsDetector
) {
    companion object {
        private const val TAG = "ContainerInstaller"
        @Volatile private var docsExtractedSession = false

        /**
         * Extracts docs from assets to ~/.xencode/docs (built-in usage guides), supporting category subdirectories like guide/ and advanced/.
         *
         * The whole directory is deleted before extraction: docs are categorized by function, and category changes between versions
         * move file paths, so overwriting without cleaning would leave same-named stale files from the previous version at other paths, where the AI might read outdated content.
         */
        fun extractDocs(context: Context) {
            if (docsExtractedSession) return
            val destDir = File(File(context.filesDir, "xencode"), "docs")
            runCatching {
                destDir.deleteRecursively()
                destDir.mkdirs()
                extractAssetsRecursive(context, "docs", destDir)
                docsExtractedSession = true
            }.onFailure {
                FileLogger.w(TAG, "Failed to extract built-in docs: ${it.message}", it)
            }
        }

        /**
         * Extracts built-in prompts from assets to ~/.xencode/prompts/, fully overwriting on every launch so prompts update with app upgrades.
         *
         * User overrides live in ~/.xencode/prompts.custom/ (same name wins); this method never touches that directory,
         * so user-rewritten snippets survive upgrades. See [com.xencode.feature.agent.domain.prompt.SystemPromptProvider].
         */
        fun extractPrompts(context: Context) {
            val destDir = File(File(context.filesDir, "xencode"), "prompts")
            destDir.mkdirs()
            runCatching {
                extractAssetsRecursive(context, "prompts", destDir)
            }.onFailure {
                FileLogger.w(TAG, "Failed to extract built-in prompts: ${it.message}", it)
            }
        }

        /** Recursively copies an assets directory to a destination directory, supporting subdirectories (e.g. prompts/agent/, docs/guide/). */
        private fun extractAssetsRecursive(context: Context, assetDir: String, destDir: File) {
            val entries = context.assets.list(assetDir) ?: return
            destDir.mkdirs()
            for (entry in entries) {
                val assetPath = "$assetDir/$entry"
                val destFile = File(destDir, entry)
                try {
                    context.assets.open(assetPath).use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: IOException) {
                    // Directory entry: assets.open throws IOException for directories; recurse
                    extractAssetsRecursive(context, assetPath, destFile)
                }
            }
        }

        /**
         * Extracts built-in subagent definitions (e.g. Explore) from assets to ~/.xencode/agents/.
         * Existing files are not overwritten, preserving user edits and their choice to rebuild after deletion.
         */
        fun extractAgents(context: Context) {
            val destDir = File(File(context.filesDir, "xencode"), "agents")
            destDir.mkdirs()
            runCatching {
                val entries = context.assets.list("agents") ?: return@runCatching
                for (entry in entries) {
                    val destFile = File(destDir, entry)
                    if (!destFile.exists()) {
                        context.assets.open("agents/$entry").use { input ->
                            destFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }.onFailure {
                FileLogger.w(TAG, "Failed to extract built-in subagent definitions: ${it.message}", it)
            }
        }

        /**
         * Extracts built-in scripts (e.g. the panel demo_balance.py) from assets to ~/.xencode/scripts/.
         * Existing files are not overwritten, preserving user edits.
         */
        fun extractScripts(context: Context) {
            val destDir = File(File(context.filesDir, "xencode"), "scripts")
            destDir.mkdirs()
            runCatching {
                val entries = context.assets.list("xencode/scripts") ?: return@runCatching
                for (entry in entries) {
                    val destFile = File(destDir, entry)
                    if (!destFile.exists()) {
                        context.assets.open("xencode/scripts/$entry").use { input ->
                            destFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        destFile.setExecutable(true, false)
                    }
                }
            }.onFailure {
                FileLogger.w(TAG, "Failed to extract built-in scripts: ${it.message}", it)
            }
        }

        /**
         * Extracts the custom git credential helper from assets to ~/.xencode/git-credential-xencode and sets the executable bit.
         *
         * Bound via [LinuxContainerEngine]'s -b to /root/.xencode/git-credential-xencode inside the container,
         * registered by the container provisioning menu (provision.sh) in `.gitconfig` as a second credential.helper,
         * after `store`, as a fallback when not logged in (belt and braces). See assets/xencode/git-credential-xencode for the helper's behavior.
         *
         * Extracted at startup, independent of provisioning success: if provisioning fails git is not installed, so a missing helper config does not matter;
         * once git is installed and the config registered, the helper works immediately. Extraction failure only logs a warning (a missing helper merely means no popup when not logged in;
         * git still runs bare and reports the auth failure — not fatal).
         */
        fun extractCredentialHelper(context: Context) {
            val dest = File(File(context.filesDir, "xencode"), "git-credential-xencode")
            runCatching {
                dest.parentFile?.mkdirs()
                context.assets.open("xencode/git-credential-xencode").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                // Set the executable bit for all users (the proot process runs as the app uid; mirrors [copyAsset]'s 0o111 pattern).
                if (!dest.setExecutable(true, false)) {
                    FileLogger.w(TAG, "setExecutable returned false: ${dest.absolutePath}")
                }
            }.onFailure {
                FileLogger.w(TAG, "Failed to extract git credential helper: ${it.message}", it)
            }
        }

        /**
         * Extracts the container dependency-install script from assets to ~/.xencode/provision.sh and sets the executable bit.
         *
         * Bound via [LinuxContainerEngine]'s -b to /root/.xencode/provision.sh inside the container, run with `sh` by
         * the provisioning menu on first entry to the terminal — the script installs base packages per package manager
         * (apk/apt-get/dnf/yum/pacman), with the package list maintained in the script rather than in code.
         * Extracted at startup, overwritten every time (the script updates with app versions). Extraction failure only logs a warning:
         * without the script the provisioning menu simply does not appear, and the user can install base tools manually.
         */
        fun extractProvisionScript(context: Context) {
            val dest = File(File(context.filesDir, "xencode"), "provision.sh")
            runCatching {
                dest.parentFile?.mkdirs()
                context.assets.open("xencode/provision.sh").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                if (!dest.setExecutable(true, false)) {
                    FileLogger.w(TAG, "setExecutable returned false: ${dest.absolutePath}")
                }
            }.onFailure {
                FileLogger.w(TAG, "Failed to extract provision script: ${it.message}", it)
            }
        }

        /**
         * Extracts the environment tooling (the `xencode` command + shared libs in lib/) from assets to ~/.xencode/bin and ~/.xencode/lib, setting the executable bit.
         *
         * Bound via [LinuxContainerEngine]'s -b to /root/.xencode/bin/xencode inside the container (on PATH, see
         * buildContainerEnv) and /root/.xencode/lib/ (env-common.sh, android-sdk.sh, and the scenarios/ subdirectory),
         * letting the user run `xencode` any time to install dev environments (scenario-based). Overwritten every time (updates with app versions); extraction failure only logs
         * a warning: without the tool the container provisioning menu (provision.sh) reports not ready, and restarting the app re-extracts it.
         */
        fun extractEnvTool(context: Context) {
            runCatching {
                extractDirOverwrite(context, "xencode/bin", File(File(context.filesDir, "xencode"), "bin"), executable = true)
                extractDirOverwrite(context, "xencode/lib", File(File(context.filesDir, "xencode"), "lib"), executable = false)
            }.onFailure {
                FileLogger.w(TAG, "Failed to extract environment tooling: ${it.message}", it)
            }
        }

        /** Recursively copies an assets directory to [destDir] (overwrites every time, supports subdirectories like lib/scenarios/); when [executable], sets the executable bit on each file. */
        private fun extractDirOverwrite(context: Context, assetDir: String, destDir: File, executable: Boolean) {
            val entries = context.assets.list(assetDir) ?: return
            destDir.mkdirs()
            for (entry in entries) {
                val assetPath = "$assetDir/$entry"
                val dest = File(destDir, entry)
                try {
                    context.assets.open(assetPath).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (executable && !dest.setExecutable(true, false)) {
                        FileLogger.w(TAG, "setExecutable returned false: ${dest.absolutePath}")
                    }
                } catch (e: IOException) {
                    // Directory entry: assets.open throws IOException for directories; recurse (e.g. lib/scenarios/)
                    extractDirOverwrite(context, assetPath, dest, executable)
                }
            }
        }

        /**
         * The apk branch matching the alpine-rootfs version in assets, used to build mirror URLs.
         * Pinned to v3.21: consistent with [INSTALL_VERSION] (alpine-3.21.3); that apk-tools 2.14 is reliable under proot.
         */
        /**
         * Installed version. Bump +1 when swapping rootfs / proot or changing install logic, to trigger re-extraction.
         * Keep it in sync with the Alpine version actually shipped in assets for troubleshooting.
         */
        private const val INSTALL_VERSION = "alpine-3.21.3-v6"

        /** Reports progress every this-many deleted entries when purging a rootfs (any denser would flood the UI state flow). */
        private const val DELETE_PROGRESS_STEP = 2000
    }

    /** Architecture-specific directory inside assets */
    val ASSET_DIR: String
        get() {
            // Prefer the image directory matching the device ABI.
            // But release builds are split by flavor, so single-architecture APKs carry only one image set (armsolo→arm, x86solo→x86):
            // if that set is absent from assets (e.g. an armsolo build installed on an x86-only device), we fall back
            // to whichever set actually exists, avoiding an open() crash — whether proot can truly run is decided by the device ABI,
            // but at least the asset lookup layer will not break.
            val preferX86 = android.os.Build.SUPPORTED_ABIS.any { it.contains("x86") }
            val first = if (preferX86) "container/x86" else "container/arm"
            val fallback = if (preferX86) "container/arm" else "container/x86"
            return if (assetExists(first)) first else fallback
        }

    /** Lightweight probe for whether an asset path is bundled in the current APK (used for [ASSET_DIR]'s fallback logic) */
    private fun assetExists(path: String): Boolean =
        context.assets.list(path.substringBeforeLast('/'))?.any { it == path.substringAfterLast('/') } == true

    // Deliberately uses a neutral .bin suffix: AGP's asset merging auto-extracts and renames .tar.gz/.tgz as archives,
    // so open("...tar.gz") would fail at runtime. .bin makes it pack verbatim as a plain binary.
    val ASSET_ROOTFS: String get() = "$ASSET_DIR/alpine-rootfs.bin"

    /** Root directory for rootfs extraction */
    val rootfsDir: File
        get() = File(context.filesDir, "rootfs")

    /**
     * Root directory for AI config data (skill instructions + MCP configs), pinned to the app's private filesDir.
     *
     * Deliberately **independent of [rootfsDir]**: the rootfs is wiped and reinstalled on container version upgrades (see [installRootfsIfNeed]),
     * while this directory holds user data and must survive upgrades. It is bound by [com.xencode.feature.agent.domain.container.LinuxContainerEngine]
     * to `/root/.xencode` inside the container, so the `/root/.xencode` seen by AI / terminal actually lands here.
     */
    val xencodeDir: File
        get() = File(context.filesDir, "xencode")

    /**
     * Directory holding the full proot set: `lib/<abi>/lib*.so` inside the APK is extracted here by the installer (see build.gradle.kts's
     * jniLibs sourceSet and useLegacyPackaging). **Must not move back to filesDir** — this is the only location in the app
     * directory where execve is allowed; files under filesDir are refused execution by W^X on targetSdk 29+.
     */
    private val nativeLibDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    /** PRoot executable (Termux build, with statx, dynamically linked against libtalloc/libandroid-shmem) */
    val prootBin: File
        get() = File(nativeLibDir, "libproot.so")

    /** PRoot's 64/32-bit loaders (Termux separates proot loaders; pointed to by PROOT_LOADER/_32). */
    val prootLoader: File
        get() = File(nativeLibDir, "libproot-loader.so")
    val prootLoader32: File
        get() = File(nativeLibDir, "libproot-loader32.so")

    /** proot's dynamic dependency library directory (libtalloc.so / libandroid-shmem.so), pointed to by LD_LIBRARY_PATH. */
    val prootLibDir: File
        get() = nativeLibDir

    /**
     * PRoot temp directory for the built-in container (Android has no /tmp). **Private**: container startup always goes through [prootTmpDirFor],
     * so other containers never get pointed into the built-in rootfs.
     * Lives at the rootfs's /tmp (host filesDir/rootfs/tmp): cache directories get cleaned by the system (after a cache purge proot
     * cannot find its temp dir and reports can't canonicalize), while the files directory is stable.
     */
    private val prootTmpDir: File
        get() = File(rootfsDir, "tmp")

    /**
     * The /tmp inside [profile]'s own rootfs, i.e. that container's `PROOT_TMP_DIR`.
     *
     * Must be taken per profile: this is a host path and each container's rootfs directory is isolated; sharing the built-in container's tmp would make other containers
     * report can't canonicalize once the built-in rootfs is reset and deleted (it only worked while the built-in rootfs existed by coincidence).
     */
    fun prootTmpDirFor(profile: ContainerProfile): File = File(rootfsDirFor(profile), "tmp")

    /** Marker file whose content is the installed version */
    private val installedMarker: File
        get() = File(rootfsDir, ".installed")

    /** Checks whether the rootfs has been extracted at the current version (proot ships with the APK, not part of install state) */
    fun isInstalled(): Boolean {
        if (!rootfsDir.isDirectory) return false
        val marker = installedMarker
        return marker.exists() && marker.readText().trim() == INSTALL_VERSION
    }

    /**
     * Extracts and installs from assets if not installed (or version mismatch). Idempotent; safe to call before every command.
     *
     * [onProgress] is invoked at each stage of an actual extraction to update [ContainerInitState]; the already-installed fast path never calls it.
     */
    suspend fun installRootfsIfNeed(
        onProgress: (ContainerInitState) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (isInstalled()) {
            FileLogger.d(TAG, "rootfs already at current version ($INSTALL_VERSION), skipping extraction")
            return@withContext
        }

        val startedAt = System.currentTimeMillis()
        FileLogger.i(
            TAG,
            "Installing container rootfs (version $INSTALL_VERSION, asset=$ASSET_ROOTFS, target=${rootfsDir.absolutePath}, " +
                "parent usable space=${usableSpaceMb(rootfsDir.parentFile)}MB)"
        )
        try {
            // On version mismatch, purge the old one for a clean install (deleting a large rootfs is slow; report progress to the UI)
            purgeRootfs(rootfsDir, installedMarker) { onProgress(ContainerInitState.CleaningOldRootfs(it)) }
            rootfsDir.mkdirs()
            if (!rootfsDir.isDirectory) FileLogger.e(TAG, "Failed to create rootfs directory: ${rootfsDir.absolutePath}")

            val entries = extractRootfs(onProgress)
            configureResolvConf()
            prootTmpDir.mkdirs()
            installedMarker.writeText(INSTALL_VERSION)
            FileLogger.i(
                TAG,
                "Container rootfs install completed: $entries entries, took ${System.currentTimeMillis() - startedAt}ms, " +
                    "marker=${installedMarker.absolutePath}"
            )
        } catch (e: Exception) {
            FileLogger.e(
                TAG,
                "Container rootfs install failed (took ${System.currentTimeMillis() - startedAt}ms, " +
                    "remaining space=${usableSpaceMb(rootfsDir.parentFile)}MB)",
                e
            )
            throw e
        }
    }

    /** Usable space of a directory in MB, to check for low disk on extraction failure; returns -1 when the path is unavailable. */
    private fun usableSpaceMb(dir: File?): Long =
        runCatching { (dir ?: return -1).usableSpace / (1024 * 1024) }.getOrDefault(-1)

    /**
     * Returns the rootfs directory for [profile]: the built-in one stays [rootfsDir] (untouched), custom local images use filesDir/rootfs_<id>.
     * Remote SSH profiles have no local rootfs and get a placeholder directory (never used/created).
     * Directories are isolated — built-in and custom never share or delete each other; switching back to built-in leaves its rootfs intact.
     */
    fun rootfsDirFor(profile: ContainerProfile): File =
        if (profile.isBuiltin) rootfsDir
        else File(context.filesDir, "rootfs_${profile.id}")

    /** Installed marker for custom images (separate from the built-in .installed, to avoid confusion). */
    private fun customInstalledMarker(profile: ContainerProfile): File =
        File(rootfsDirFor(profile), ".installed_custom")

    /** Returns whether [profile] is installed and ready: built-in uses the existing version check, custom local checks directory and marker, remote SSH is always ready. */
    fun isInstalledFor(profile: ContainerProfile): Boolean =
        when {
            profile.isBuiltin -> isInstalled()
            profile.rootfsSource is RootfsSource.RemoteSsh -> true
            else -> rootfsDirFor(profile).isDirectory && customInstalledMarker(profile).exists()
        }

    /**
     * Extracts and installs the rootfs for [profile]. The built-in path runs the full assets flow (rootfs/resolv/apk source); custom local images only extract
     * the tar.gz + write DNS (no apk source). Neither auto-installs packages — base tools are offered
     * by the provisioning menu (assets/xencode/provision.sh) on first entry to the terminal. Remote SSH profiles have no local rootfs and return directly
     * (command execution goes through [RemoteSshEngine], no local rootfs needed).
     */
    suspend fun installRootfsIfNeed(
        profile: ContainerProfile,
        onProgress: (ContainerInitState) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (isInstalledFor(profile)) return@withContext

        // The rootfs will be re-extracted and the container system may change; clear the old detection cache so the next run re-detects.
        containerOsDetector.clear(profile.id)

        if (profile.isBuiltin) {
            installRootfsIfNeed(onProgress)
            return@withContext
        }

        // Remote SSH profile: no local rootfs to extract; treat as ready.
        if (profile.rootfsSource is RootfsSource.RemoteSsh) return@withContext

        val dest = rootfsDirFor(profile)
        FileLogger.i(TAG, "Installing custom container rootfs: ${profile.id} -> ${dest.absolutePath}")
        purgeRootfs(dest, customInstalledMarker(profile)) { onProgress(ContainerInitState.CleaningOldRootfs(it)) }
        dest.mkdirs()

        when (val src = profile.rootfsSource) {
            is RootfsSource.Asset -> context.assets.open("${ASSET_DIR}/${src.path}").use {
                extractRootfsTo(dest, it, CompressedFormat.GZIP, onProgress)
            }
            is RootfsSource.LocalFile -> {
                val uri = android.net.Uri.parse(src.uri)
                val format = if (src.uri.endsWith(".xz") || src.uri.endsWith(".txz"))
                    CompressedFormat.XZ else CompressedFormat.GZIP
                context.contentResolver.openInputStream(uri)?.use {
                    extractRootfsTo(dest, it, format, onProgress)
                } ?: FileLogger.w(TAG, "Failed to open imported rootfs uri: ${src.uri}")
            }
            is RootfsSource.RemoteSsh -> { /* No local rootfs; already returned early above */ }
        }
        configureResolvConf(dest)
        prootTmpDirFor(profile).mkdirs()
        repairRootfsCompatibility(dest)
        customInstalledMarker(profile).writeText("custom")
        FileLogger.i(TAG, "Custom container rootfs install completed: ${profile.id}")
    }

    /**
     * rootfs compatibility inspection (idempotent, silent on failure): fixes known Ubuntu 25.10+ layout issues.
     * Called uniformly for all containers; the scope is narrowed by internal existence checks — untouched containers stay unchanged.
     */
    fun repairRootfsCompatibility(rootfs: File) {
        fixCoreutilsPermissions(rootfs)
        fixNodeGlobEntry(rootfs)
    }

    private fun fixCoreutilsPermissions(rootfs: File) {
        val coreutilsDir = File(rootfs, "usr/lib/cargo/bin/coreutils")
        if (!coreutilsDir.isDirectory) return
        val target = PosixFilePermissions.fromString("rwxr-xr-x")
        var fixed = 0
        fun fix(path: File) {
            runCatching {
                val p = path.toPath()
                if (Files.getPosixFilePermissions(p) != target) {
                    Files.setPosixFilePermissions(p, target)
                    fixed++
                }
            }.onFailure { FileLogger.w(TAG, "Failed to fix coreutils permissions: ${path.absolutePath}", it) }
        }
        fix(File(rootfs, "usr/lib/cargo"))
        fix(File(rootfs, "usr/lib/cargo/bin"))
        fix(coreutilsDir)
        coreutilsDir.listFiles()?.forEach { fix(it) }
        if (fixed > 0) {
            FileLogger.i(TAG, "Fixed container coreutils permissions: $fixed files (${coreutilsDir.absolutePath})")
        }
    }

    /**
     * Fixes an Ubuntu 26.04 repo packaging bug: node-glob 10.3.6 flattens the package using a `dist/cjs/src -> .` self-referencing
     * symlink; node 22+'s realpath reports ELOOP, so npm fails at startup with MODULE_NOT_FOUND (unrelated to PRoot —
     * native Ubuntu 26.04 is affected too). Fix on the real-device layout: repoint the package.json entry from dist/cjs/src/
     * to dist/cjs/ where the flattened files actually live. Idempotent: leaves a healthy entry alone.
     */
    private fun fixNodeGlobEntry(rootfs: File) {
        val candidates = listOf(
            File(rootfs, "usr/share/nodejs/glob/package.json"),
            File(rootfs, "usr/share/node_modules/glob/package.json")
        )
        for (f in candidates) {
            if (!f.isFile) continue
            runCatching {
                val text = f.readText()
                if (text.contains("dist/cjs/src/")) {
                    f.writeText(text.replace("dist/cjs/src/", "dist/cjs/"))
                    FileLogger.i(TAG, "Fixed node-glob entry: ${f.absolutePath}")
                }
            }.onFailure { FileLogger.w(TAG, "Failed to fix node-glob entry: ${f.absolutePath}", it) }
        }
    }

    /**
     * Deletes a custom profile's rootfs directory (called when deleting the profile). The built-in rootfs cannot be deleted; remote SSH has no rootfs to delete.
     * [onProgress] reports the deleted entry count for the UI progress display.
     */
    suspend fun deleteCustomRootfs(
        profile: ContainerProfile,
        onProgress: (Int) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        // The profile was deleted or its image switched; the cache is now stale (re-detect on the next run).
        containerOsDetector.clear(profile.id)
        if (profile.isBuiltin) return@withContext
        if (profile.rootfsSource is RootfsSource.RemoteSsh) return@withContext
        purgeRootfs(rootfsDirFor(profile), customInstalledMarker(profile), onProgress)
    }

    /**
     * Resets the built-in Alpine container: deletes its rootfs directory (including the .installed / .provisioned markers),
     * and the next [ensureInstalled] re-extracts; the provisioning menu re-guides installation on terminal entry. Invoked by the built-in image's "Reset" button.
     */
    suspend fun resetBuiltinRootfs(onProgress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        purgeRootfs(rootfsDir, installedMarker, onProgress)
    }

    /** Uniformly resets the rootfs for [profile]: built-in goes through [resetBuiltinRootfs], custom local deletes its rootfs directory, remote SSH has no local data and does nothing. */
    suspend fun resetRootfs(profile: ContainerProfile, onProgress: (Int) -> Unit = {}) {
        if (profile.isBuiltin) {
            resetBuiltinRootfs(onProgress)
            return
        }
        deleteCustomRootfs(profile, onProgress)
    }

    /**
     * Empties a rootfs directory; [onProgress] reports the cumulative deleted entry count.
     *
     * Deletes [marker] first: a fully provisioned rootfs has hundreds of thousands of inodes and takes tens of seconds to purge; if the process is killed midway the marker
     * is already gone, so the leftover directory is no longer treated by [isInstalledFor] as an installed container, and the next entry purges it clean before re-extracting.
     */
    private fun purgeRootfs(dir: File, marker: File, onProgress: (Int) -> Unit) {
        if (!dir.exists()) return
        marker.delete()
        val count = deleteTree(dir, 0, onProgress)
        FileLogger.i(TAG, "Deleted rootfs ${dir.name}, $count entries in total")
    }

    /**
     * Recursively deletes [root], returning the cumulative deleted entry count starting from [startCount], invoking the callback every [DELETE_PROGRESS_STEP] entries.
     *
     * Avoids [File.deleteRecursively]: it decides via `File.isDirectory` and follows symlinks down — a rootfs's /bin
     * and /usr/lib are full of symlinks; following them needlessly walks link targets (several times slower on large containers), and when a link points at its own ancestor it
     * keeps descending until the path exceeds length limits, let alone deleting data elsewhere when a link points outside the rootfs. Here directories are
     * decided with NOFOLLOW_LINKS, and symlinks are always unlinked as ordinary entries.
     */
    private fun deleteTree(root: File, startCount: Int, onProgress: (Int) -> Unit): Int {
        var count = startCount
        val pending = ArrayDeque<File>()
        val dirs = ArrayDeque<File>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val entry = pending.removeLast()
            if (isRealDirectory(entry)) {
                dirs.addLast(entry)
                entry.listFiles()?.forEach { pending.addLast(it) }
            } else if (entry.delete()) {
                count++
                if (count % DELETE_PROGRESS_STEP == 0) onProgress(count)
            }
        }
        // A directory can only be deleted once empty: dirs are enqueued in shallow-to-deep discovery order, so deleting from the tail back is deep-to-shallow.
        while (dirs.isNotEmpty()) {
            if (dirs.removeLast().delete()) count++
        }
        onProgress(count)
        return count
    }

    /** Whether it is a real directory: symlinks return false even when pointing at directories, preventing deletion from following links down. */
    private fun isRealDirectory(file: File): Boolean = runCatching {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isDirectory
    }.getOrDefault(false)

    init {
        CoroutineScope(Dispatchers.IO).launch {
            extractDocs(context)
            extractCredentialHelper(context)
            extractProvisionScript(context)
            extractEnvTool(context)
        }
    }

    /** Extracts docs from assets to ~/.xencode/docs (built-in usage guides) */
    fun extractDocs() = extractDocs(context)

    /** Extracts the git credential helper from assets to ~/.xencode/git-credential-xencode and sets the executable bit. */
    fun extractCredentialHelper() = extractCredentialHelper(context)

    /** Extracts the container dependency-install script from assets to ~/.xencode/provision.sh and sets the executable bit. */
    fun extractProvisionScript() = extractProvisionScript(context)

    /** Extracts the environment tooling (xencode command + shared libs) from assets to ~/.xencode/{bin,lib} and sets the executable bit. */
    fun extractEnvTool() = extractEnvTool(context)

    /** Extracts alpine-minirootfs.tar.gz, handling directories/files/symlinks/hardlinks and permission bits correctly; returns the entry count extracted. */
    private fun extractRootfs(onProgress: (ContainerInitState) -> Unit): Int {
        context.assets.open(ASSET_ROOTFS).use { rawIn ->
            return extractRootfsTo(rootfsDir, rawIn, CompressedFormat.GZIP, onProgress)
        }
    }

    /** Image compression format: built-in Alpine uses gzip; user-imported ones may be gzip or xz. */
    enum class CompressedFormat { GZIP, XZ }

    /**
     * Extracts a tar.gz / tar.xz stream into [destDir], handling directories/files/symlinks/hardlinks and permission bits correctly.
     * Shared by the built-in Alpine ([extractRootfs] passes an assets stream) and user custom images ([installRootfsIfNeed] passes a content uri stream).
     */
    fun extractRootfsTo(
        destDir: File,
        input: java.io.InputStream,
        format: CompressedFormat = CompressedFormat.GZIP,
        onProgress: (ContainerInitState) -> Unit = {}
    ): Int {
        var processed = 0
        val startedAt = System.currentTimeMillis()
        val decompressed = when (format) {
            CompressedFormat.GZIP -> GZIPInputStream(input)
            CompressedFormat.XZ -> XZCompressorInputStream(input)
        }
        decompressed.use { decompIn ->
            TarArchiveInputStream(decompIn).use { tarIn ->
                var entry: TarArchiveEntry? = tarIn.nextEntry
                while (entry != null) {
                    extractEntry(destDir, tarIn, entry)
                    processed++
                    onProgress(ContainerInitState.ExtractingRootfs(processed))
                    entry = tarIn.nextEntry
                }
            }
        }
        FileLogger.i(
            TAG,
            "Extraction to ${destDir.absolutePath} completed: $processed entries, took ${System.currentTimeMillis() - startedAt}ms"
        )
        return processed
    }

    private fun extractEntry(
        destDir: File,
        tarIn: TarArchiveInputStream,
        entry: TarArchiveEntry
    ) {
        val outFile = File(destDir, entry.name)

        // Zip-slip protection: ensure extraction targets stay inside destDir
        val canonicalRoot = destDir.canonicalPath
        if (!outFile.canonicalPath.startsWith(canonicalRoot + File.separator) &&
            outFile.canonicalPath != canonicalRoot
        ) {
            FileLogger.w(TAG, "Skipping out-of-bounds entry: ${entry.name}")
            return
        }

        when {
            entry.isDirectory -> outFile.mkdirs()

            entry.isSymbolicLink -> {
                outFile.parentFile?.mkdirs()
                // A symlink's target may be relative/absolute; create it verbatim (resolved by proot inside the container)
                if (outFile.exists()) outFile.delete()
                runCatching { Os.symlink(entry.linkName, outFile.absolutePath) }
                    .onFailure { FileLogger.w(TAG, "symlink failed ${entry.name} -> ${entry.linkName}: ${it.message}") }
            }

            entry.isLink -> {
                // Hard link: linkName points at another file already extracted from the tar
                outFile.parentFile?.mkdirs()
                val target = File(destDir, entry.linkName)
                if (outFile.exists()) outFile.delete()
                runCatching { Os.link(target.absolutePath, outFile.absolutePath) }
                    .onFailure {
                        // Degrade to a copy so the file exists
                        FileLogger.w(TAG, "hardlink failed ${entry.name} -> ${entry.linkName}, falling back to copy: ${it.message}")
                        runCatching { target.copyTo(outFile, overwrite = true) }
                    }
            }

            entry.isFile -> {
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { tarIn.copyTo(it) }
                applyMode(outFile, entry.mode)
            }

            else -> FileLogger.d(TAG, "Ignoring unsupported entry type: ${entry.name}")
        }
    }

    /** Sets the executable bit per the tar entry's mode (if the owner has x, execution is granted to everyone) */
    private fun applyMode(file: File, mode: Int) {
        val ownerExecutable = (mode and 0b001_000_000) != 0 // 0100
        if (ownerExecutable) {
            file.setExecutable(true, false)
        }
        file.setReadable(true, false)
        // owner write bit
        if ((mode and 0b010_000_000) != 0) file.setWritable(true, false)
    }

    /**
     * Writes the in-container DNS; otherwise network operations like apk/npm fail on unresolvable domains.
     * Uses AliDNS public DNS: faster/more reliable resolution in China; 8.8.8.8 gets blocked in some network environments.
     * Leaves a valid config (a regular file containing nameserver) untouched, respecting existing in-container settings;
     * empty files or broken links (Ubuntu/Debian bases often link resolv.conf to the systemd-resolved stub, unusable under PRoot) are deleted then rewritten.
     */
    private fun configureResolvConf(rootfs: File = rootfsDir) {
        val etc = File(rootfs, "etc").apply { mkdirs() }
        val conf = File(etc, "resolv.conf")
        if (conf.isFile) {
            runCatching {
                if (conf.readText().contains("nameserver")) {
                    FileLogger.d(TAG, "resolv.conf already has a valid config, leaving untouched: ${conf.absolutePath}")
                    return
                }
            }
        }
        conf.delete()
        conf.writeText("nameserver 223.5.5.5\nnameserver 223.6.6.6\n")
        FileLogger.i(TAG, "Wrote container DNS config: ${conf.absolutePath}")
    }
}
