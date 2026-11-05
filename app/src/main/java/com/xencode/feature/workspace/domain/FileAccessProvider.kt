package com.xencode.feature.workspace.domain

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

/** Directory entry info, returned by [FileAccessProvider.listFiles]. */
data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    /** File size in bytes; may be 0 for directories. */
    val size: Long,
    /** Last modified time (epoch milliseconds). */
    val lastModified: Long,
    /** In local mode may carry the host [File] for callers needing a local path (e.g. [BitmapFactory]); null in remote mode. */
    val localFile: File? = null,
    /** Read/write/execute permission bits (an rwx string such as "rwx"); parsed from SFTP permissions remotely, with a sensible default when unavailable. */
    val permissions: String = "---"
)

/**
 * Abstraction for the file read/write backend: decouples "where files are read/written" from hardcoded local java.io.File.
 *
 * Two implementations:
 * - `LocalFileAccess`: wraps a [WorkspacePathMapper] and goes through java.io.File directly (local-mode behavior unchanged);
 * - `RemoteSftpFileAccess`: reads/writes remote files over SFTP.
 *
 * Path parameters are uniformly the "container paths" the AI provides (`~/workspace/...`); each implementation maps them internally to local host paths or remote paths.
 * The tool layer (FileTools/ImageTools/ListFilesTool/EditFileTool) depends on this interface, not a concrete implementation,
 * and DI injects the right instance for the current execution mode.
 *
 * Capabilities that need a local file path (e.g. [android.graphics.BitmapFactory.decodeFile]) should use [readBytes] or
 * [copyToLocal] to obtain a local temp file first — in remote mode this SFTP-downloads into a temp directory.
 */
interface FileAccessProvider {

    /**
     * Reads a file's full text. Throws [NoSuchFileException] when the file does not exist.
     * In remote mode, files over the implementation limit throw [RemoteOutputTooLargeException] to avoid reading the whole thing into memory.
     */
    fun readFile(path: String): String

    /**
     * Lazily reads a file line by line, for [ReadFileTool]'s line-windowed reads: consume as you go, the whole file never enters memory;
     * lines over the cap (64K characters by default) are truncated.
     * The sequence is re-iterable (each iteration reopens the underlying read); callers must iterate on an IO thread; throws [NoSuchFileException] when the file does not exist.
     */
    fun readLines(path: String): Sequence<String>

    /**
     * Writes a file's full content. Parent directories are created automatically. When [overwrite] is false and the file exists, throws [FileAlreadyExistsException].
     * [encoding] selects the on-disk charset; default is UTF-8. Callers should pass back the "encoding detected at read time" as-is,
     * so non-UTF-8 files like GBK are not silently transcoded and corrupted on save.
     */
    fun writeFile(path: String, content: String, overwrite: Boolean = true, encoding: Charset = Charsets.UTF_8)

    /** Whether the file exists. */
    fun exists(path: String): Boolean

    /** Whether the path is a directory. */
    fun isDirectory(path: String): Boolean

    /** Whether the path is a regular file. */
    fun isFile(path: String): Boolean

    /** File size in bytes. */
    fun fileSize(path: String): Long

    /** Last modified time (epoch milliseconds). */
    fun lastModified(path: String): Long

    /** Permission bit string (e.g. "rwx"). */
    fun permissions(path: String): String

    /**
     * Lists entries under a directory. The local implementation uses [File.listFiles]; the remote one uses SFTP ls.
     * Excludes `.` and `..`. The returned [FileEntry.localFile] is null in remote mode.
     */
    fun listFiles(path: String): List<FileEntry>

    /**
     * Recursively lists all regular files under [path] up to [maxDepth] (direct children of [path] count as level 1).
     * Returns paths relative to [path] (`/`-separated); an empty list when the directory does not exist.
     * Used for skill/sub-agent directory scans.
     */
    fun listFilesRecursive(path: String, maxDepth: Int): List<String>

    /**
     * Batch-reads multiple files in full, returning `path -> content`; entries that fail to read are absent (no throw).
     *
     * The default implementation calls [readFile] one by one. Channels where "one call = one cross-process or cross-network round-trip"
     * such as device ROOT / SFTP **must** override this to fetch the batch in one round-trip: skill scans and sub-agent scans read a hundred files at a time,
     * and reading them one by one degenerates into a hundred subprocess forks (tens to hundreds of ms each), dragging the caller straight into ANR.
     */
    fun readFiles(paths: List<String>): Map<String, String> {
        if (paths.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>(paths.size)
        for (p in paths) runCatching { readFile(p) }.onSuccess { out[p] = it }
        return out
    }

    /**
     * Batch-reads the first [maxBytes] characters of multiple files (truncated by characters, not guaranteed to align to UTF-8 boundaries —
     * only for scenarios like frontmatter parsing that care about file heads).
     *
     * The default implementation = [readFiles] then per-entry truncation; channels where "one call = one cross-process or cross-network round-trip"
     * such as device ROOT / SFTP **must** override this to fetch the batch in one round-trip: locating skills by name reads the heads of a hundred SKILL.md files,
     * and doing it one by one degenerates into a hundred subprocess forks (tens to hundreds of ms each), dragging loadSkill straight into ANR.
     * Same return convention as [readFiles]: failed entries are absent, no throw.
     */
    fun readFileHeads(paths: List<String>, maxBytes: Int): Map<String, String> {
        if (paths.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>(paths.size)
        for (p in paths) runCatching { readFile(p) }.onSuccess { out[p] = it.take(maxBytes) }
        return out
    }

    /**
     * Reads a file's raw bytes. For tools like [ViewImageTool] that need binary data.
     * In remote mode, if the caller needs a local file path, use [copyToLocal] instead; oversized files throw [RemoteOutputTooLargeException].
     */
    fun readBytes(path: String): ByteArray

    /**
     * Writes a file's raw bytes. Parent directories are created automatically. When [overwrite] is false and the file exists, throws [FileAlreadyExistsException].
     * For tools like [GenerateImageTool] that persist binary data (images etc.), and also for binary writes such as attachment uploads;
     * text content still goes through [writeFile].
     */
    fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean = true)

    /**
     * Streams [input] into a file, returning the number of bytes written; content is never held in memory as a whole.
     * Parent directories are created automatically. When [overwrite] is false and the file exists, throws [FileAlreadyExistsException].
     * For scenarios like uploading large attachments.
     */
    fun writeStream(path: String, input: InputStream, overwrite: Boolean = true): Long

    /**
     * Copies the file to a local temp file and returns its [File].
     * Local mode directly returns the mapped host [File] (no copy);
     * remote mode SFTP-downloads into the cache directory and returns a local temp file.
     * Used by [ViewImageTool] (which needs a local path for BitmapFactory) and friends.
     */
    fun copyToLocal(path: String): File

    /** Deletes a file or empty directory. */
    fun delete(path: String)

    /** Recursively deletes a file or directory (non-empty directories go with their contents). Silently returns when the target is missing. */
    fun deleteRecursively(path: String)

    /**
     * Rename / move. Throws [FileAlreadyExistsException] when [newPath] already exists,
     * [NoSuchFileException] when [path] is missing, and [java.io.IOException] for other failures.
     */
    fun rename(path: String, newPath: String)

    /**
     * Copies a file or directory (with contents) to [newPath]. Throws [NoSuchFileException] when [path] is missing,
     * and [java.io.IOException] for other failures. When [overwrite] is false and [newPath] exists, throws
     * [FileAlreadyExistsException]; when true, it overwrites.
     */
    fun copy(path: String, newPath: String, overwrite: Boolean = false)

    /**
     * Moves (cuts) [path] to [newPath]. Throws [NoSuchFileException] when [path] is missing,
     * and [java.io.IOException] for other failures. When [overwrite] is false and [newPath] exists, throws
     * [FileAlreadyExistsException]; when true, it overwrites.
     * Implementations may use rename or "copy + delete source".
     */
    fun move(path: String, newPath: String, overwrite: Boolean = false)

    /** Creates a directory (with parents). */
    fun mkdirs(path: String)

    /** Returns the parent directory path (in container-path form). */
    fun parentPath(path: String): String?

    /** Restores an internal real path back to the AI-facing container path (for display). */
    fun toDisplayPath(path: String): String
}

/**
 * Whether a single directory entry name is legal: non-empty, no path separators, not `.` or `..`.
 * Blocks inputs like `../` that would escape the current directory; used by file browsing for create/rename.
 */
fun isValidFileEntryName(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isNotEmpty() &&
        !trimmed.contains('/') &&
        !trimmed.contains('\\') &&
        trimmed != "." &&
        trimmed != ".."
}
