package com.xencode.feature.workspace.domain

import com.xencode.core.util.BoundedLineReader
import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.domain.container.RemoteSshConnection
import com.xencode.feature.agent.domain.container.friendlySshError
import com.xencode.feature.workspace.data.repository.WorkspaceRepository
import com.xencode.feature.workspace.domain.WorkspacePathMapper.Companion.CONTAINER_ROOT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.xfer.FilePermission
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.file.Paths
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.util.EnumSet
import javax.inject.Inject

private const val TAG = "RemoteSftpFileAccess"

/** Buffer size for a single SFTP read/write. */
private const val IO_CHUNK = 32 * 1024

/** Size cap in bytes for remote files in `readFile` / `readBytes`: beyond it, throws [RemoteOutputTooLargeException] to avoid reading the whole thing into memory. */
private const val MAX_REMOTE_READ_BYTES = 8L * 1024 * 1024

/** Thrown when a remote file exceeds the cap: lets callers show a "content too large" message instead of misreading it as a missing file. */
class RemoteOutputTooLargeException(message: String) : IOException(message)

/**
 * Remote implementation of [FileAccessProvider]: reads/writes remote files over SFTP.
 *
 * **Dedicated channel**: SFTP runs on a second SSH transport established by [RemoteSshConnection.sftp], isolated from the
 * Bash/command exec channel — sshj's SFTP has an intermittent Buffer overflow (hierynomus/sshj#461), and sharing a transport means a crash
 * also takes down the command channel; separated, only file IO is affected, and a dead channel is dropped and rebuilt on the next call.
 *
 * Path mapping: the AI's `~/workspace/...` maps to the currently selected workspace's remote absolute path; other absolute paths (e.g. `/etc/...`)
 * are used directly as remote absolute paths. sshj's [SFTPClient] is not thread-safe, so all operations are serialized through [sftpMutex].
 */
class RemoteSftpFileAccess @Inject constructor(
    private val connection: RemoteSshConnection,
    private val workspaceRepository: WorkspaceRepository
) : FileAccessProvider {

    private val sftpMutex = Mutex()

    /** Real path of the currently selected workspace on the remote server (e.g. /data/.../test/111). */
    private fun currentWorkspaceRoot(): String {
        val cfg = connection.config ?: throw IllegalStateException("SSH is not connected")
        // In remote mode currentPath() returns the selected workspace's remote absolute path; falls back to remoteWorkspacePath when nothing is selected
        val path = workspaceRepository.currentPath()
        return if (path.isNotBlank() && path != "/") path else cfg.remoteWorkspacePath.trimEnd('/')
    }

    /** Maps an AI path to the real path on the remote server. */
    private fun toRemotePath(path: String): String =
        remotePathFor(path, currentWorkspaceRoot(), connection.remoteHome)

    /** Restores a remote path back to the AI-facing container path (for display). */
    private fun toDisplayPathFromRemote(remotePath: String): String =
        displayPathFor(remotePath, currentWorkspaceRoot())

    /**
     * Runs [block] serialized on the dedicated SFTP channel. On a transport-layer error the current channel is dropped (auto-rebuilt on the next call) and the exception rethrown as-is;
     * business errors (missing/already-exists files, SFTP status-code errors) do not rebuild. No automatic retry — retrying a write could land it twice.
     */
    private fun <T> withSftp(block: (SFTPClient) -> T): T = runBlocking {
        withContext(Dispatchers.IO) {
            sftpMutex.withLock {
                val sftp = try {
                    connection.sftp()
                } catch (e: Exception) {
                    throw IOException(friendlySshError(e), e)
                }
                guarded { block(sftp) }
            }
        }
    }

    /**
     * Runs one operation on the already-open SFTP channel (without re-fetching the client). For [readLines]' lazy iteration: each read holds
     * the lock only once, `yield` happens outside the lock, so a caller abandoning iteration midway never leaves [sftpMutex] held forever.
     */
    private fun <T> onSftp(block: () -> T): T = runBlocking {
        withContext(Dispatchers.IO) { sftpMutex.withLock { guarded(block) } }
    }

    /** On a transport-layer error, drops the SFTP channel (auto-rebuilt on the next call) and rethrows as-is; business errors do not rebuild. */
    private suspend fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        if (e !is SFTPException && e !is NoSuchFileException && e !is FileAlreadyExistsException) {
            runCatching { connection.invalidateSftp() }
        }
        throw e
    }

    override fun readFile(path: String): String = String(readAll(toRemotePath(path)), Charsets.UTF_8)

    /**
     * Lazy line-by-line read: only the current line is materialized (capped at 64K characters per line), consume as you go, the whole file never enters memory.
     * The sequence is re-iterable (each iteration reopens the remote file); callers must iterate on an IO thread.
     */
    override fun readLines(path: String): Sequence<String> {
        val remote = toRemotePath(path)
        return sequence {
            val reader = withSftp { sftp ->
                val attrs = sftp.statExistence(remote) ?: throw NoSuchFileException(File(remote))
                if (attrs.type == FileMode.Type.DIRECTORY) throw IOException("Is a directory; cannot read it as a file: $remote")
                BoundedLineReader(InputStreamReader(RemoteFileInputStream(sftp.open(remote))))
            }
            try {
                while (true) {
                    val line = onSftp { reader.readLine() } ?: break
                    yield(line.text)
                }
            } finally {
                runCatching { onSftp { reader.close() } }
            }
        }
    }

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) =
        writeBytes(path, content.toByteArray(encoding), overwrite)

    override fun exists(path: String): Boolean = withSftp { it.statExistence(toRemotePath(path)) != null }

    override fun isDirectory(path: String): Boolean =
        withSftp { it.statExistence(toRemotePath(path))?.type == FileMode.Type.DIRECTORY }

    override fun isFile(path: String): Boolean =
        withSftp { it.statExistence(toRemotePath(path))?.type == FileMode.Type.REGULAR }

    override fun fileSize(path: String): Long =
        withSftp { it.statExistence(toRemotePath(path))?.size ?: 0L }

    override fun lastModified(path: String): Long =
        withSftp { (it.statExistence(toRemotePath(path))?.mtime ?: 0L) * 1000L }

    override fun permissions(path: String): String =
        withSftp { it.statExistence(toRemotePath(path))?.permissions?.let(::formatPermissions) ?: "---" }

    override fun listFiles(path: String): List<FileEntry> {
        val remote = toRemotePath(path)
        return withSftp { sftp ->
            sftp.ls(remote).mapNotNull { info ->
                val name = info.name
                if (name == "." || name == "..") return@mapNotNull null
                FileEntry(
                    name = name,
                    isDirectory = info.isDirectory,
                    size = info.attributes.size,
                    lastModified = info.attributes.mtime * 1000L,
                    localFile = null,
                    permissions = formatPermissions(info.attributes.permissions)
                )
            }
        }
    }

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> {
        val remote = toRemotePath(path)
        return withSftp { sftp ->
            val result = mutableListOf<String>()
            fun walk(dir: String, rel: String, depth: Int) {
                val entries = runCatching { sftp.ls(dir) }.getOrNull() ?: return
                for (info in entries) {
                    val name = info.name
                    if (name == "." || name == "..") continue
                    val childRel = if (rel.isEmpty()) name else "$rel/$name"
                    if (info.isDirectory) {
                        if (depth < maxDepth) walk(info.path, childRel, depth + 1)
                    } else {
                        result += childRel
                    }
                }
            }
            walk(remote, "", 1)
            result
        }
    }

    override fun readBytes(path: String): ByteArray = readAll(toRemotePath(path))

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
        val remote = toRemotePath(path)
        withSftp { sftp ->
            if (sftp.statExistence(remote) != null && !overwrite) throw FileAlreadyExistsException(File(remote))
            ensureParent(sftp, remote)
            sftp.open(remote, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { rf ->
                writeAll(rf, bytes)
            }
        }
    }

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long {
        val remote = toRemotePath(path)
        // Land on .xencode-part first then rename: a mid-transfer disconnect leaves no partial file at the target
        val tmp = "$remote.xencode-part"
        return withSftp { sftp ->
            if (sftp.statExistence(remote) != null && !overwrite) throw FileAlreadyExistsException(File(remote))
            ensureParent(sftp, remote)
            val written = try {
                sftp.open(tmp, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { rf ->
                    val buf = ByteArray(IO_CHUNK)
                    var offset = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        rf.write(offset, buf, 0, n)
                        offset += n
                    }
                    offset
                }
            } catch (e: Exception) {
                runCatching { sftp.rm(tmp) }
                throw e
            }
            // If the target exists, delete before renaming (SFTP v3 has no cross-platform reliable "overwriting rename")
            if (sftp.statExistence(remote) != null) runCatching { sftp.rm(remote) }
            sftp.rename(tmp, remote)
            written
        }
    }

    override fun copyToLocal(path: String): File {
        val remote = toRemotePath(path)
        val tempFile = File.createTempFile("xencode_remote_", ".copy").apply { deleteOnExit() }
        return try {
            withSftp { sftp ->
                if (sftp.statExistence(remote) == null) throw NoSuchFileException(File(remote))
                sftp.get(remote, tempFile.absolutePath)
            }
            tempFile
        } catch (e: Exception) {
            // Delete as soon as done: temp files do not rely on deleteOnExit (only cleaned at process exit); the failure path also reclaims it, avoiding buildup over long sessions.
            runCatching { tempFile.delete() }
            if (e is NoSuchFileException) throw e
            FileLogger.e(TAG, "copyToLocal failed: $remote", e)
            throw NoSuchFileException(File(remote))
        }
    }

    override fun delete(path: String) {
        val remote = toRemotePath(path)
        // Interface contract: delete only removes files or empty directories; a non-empty directory fails rmdir -> throws (aligned with local File.delete()).
        withSftp { sftp ->
            val attrs = sftp.statExistence(remote) ?: return@withSftp
            if (attrs.type == FileMode.Type.DIRECTORY) sftp.rmdir(remote) else sftp.rm(remote)
        }
    }

    override fun deleteRecursively(path: String) {
        val remote = toRemotePath(path)
        withSftp { sftp -> deleteRecursive(sftp, remote) }
    }

    override fun rename(path: String, newPath: String) {
        val from = toRemotePath(path)
        val to = toRemotePath(newPath)
        withSftp { sftp ->
            if (sftp.statExistence(from) == null) throw NoSuchFileException(File(from))
            if (sftp.statExistence(to) != null) throw FileAlreadyExistsException(File(to))
            sftp.rename(from, to)
        }
    }

    override fun copy(path: String, newPath: String, overwrite: Boolean) {
        val from = Paths.get(toRemotePath(path)).normalize().toString()
        val to = Paths.get(toRemotePath(newPath)).normalize().toString()
        withSftp { sftp ->
            val source = sftp.statExistence(from) ?: throw NoSuchFileException(File(from))
            if (to == from || (source.type == FileMode.Type.DIRECTORY && Paths.get(to).startsWith(Paths.get(from)))) {
                throw IOException("destination is the source or its descendant: $newPath")
            }
            if (sftp.statExistence(to) != null) {
                if (!overwrite) throw FileAlreadyExistsException(File(to))
                deleteRecursive(sftp, to)
            }
            copyRecursive(sftp, from, to)
        }
    }

    override fun move(path: String, newPath: String, overwrite: Boolean) {
        val from = Paths.get(toRemotePath(path)).normalize().toString()
        val to = Paths.get(toRemotePath(newPath)).normalize().toString()
        withSftp { sftp ->
            val source = sftp.statExistence(from) ?: throw NoSuchFileException(File(from))
            if (to == from || (source.type == FileMode.Type.DIRECTORY && Paths.get(to).startsWith(Paths.get(from)))) {
                throw IOException("destination is the source or its descendant: $newPath")
            }
            if (sftp.statExistence(to) != null) {
                if (!overwrite) throw FileAlreadyExistsException(File(to))
                deleteRecursive(sftp, to)
            }
            sftp.rename(from, to)
        }
    }

    override fun mkdirs(path: String) {
        val remote = toRemotePath(path)
        withSftp { it.mkdirs(remote) }
    }

    override fun parentPath(path: String): String? {
        val remote = toRemotePath(path)
        val parent = remote.substringBeforeLast('/', "")
        if (parent.isEmpty()) return null
        return toDisplayPathFromRemote(parent)
    }

    override fun toDisplayPath(path: String): String = toDisplayPathFromRemote(toRemotePath(path))

    /** Reads all bytes of a remote file; throws [NoSuchFileException] when missing and [RemoteOutputTooLargeException] over [MAX_REMOTE_READ_BYTES]. */
    private fun readAll(remote: String): ByteArray = withSftp { sftp ->
        val attrs = sftp.statExistence(remote) ?: throw NoSuchFileException(File(remote))
        if (attrs.type == FileMode.Type.DIRECTORY) throw IOException("Is a directory; cannot read it as a file: $remote")
        if (attrs.size > MAX_REMOTE_READ_BYTES) {
            throw RemoteOutputTooLargeException(
                "Remote file exceeds ${MAX_REMOTE_READ_BYTES / 1024 / 1024}MB; use start_line/end_line to read it in segments"
            )
        }
        sftp.open(remote).use { rf -> readFully(rf) }
    }

    private fun readFully(rf: RemoteFile): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(IO_CHUNK)
        var offset = 0L
        while (true) {
            val n = rf.read(offset, buf, 0, buf.size)
            if (n <= 0) break
            out.write(buf, 0, n)
            offset += n
        }
        return out.toByteArray()
    }

    private fun writeAll(rf: RemoteFile, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val n = minOf(IO_CHUNK, bytes.size - offset)
            rf.write(offset.toLong(), bytes, offset, n)
            offset += n
        }
    }

    /** Recursive delete (SFTP has no recursive-delete primitive): post-order traversal, directories are rmdir'd after their children are gone. */
    private fun deleteRecursive(sftp: SFTPClient, remote: String) {
        val attrs = sftp.statExistence(remote) ?: return
        if (attrs.type == FileMode.Type.DIRECTORY) {
            for (info in sftp.ls(remote)) {
                val name = info.name
                if (name == "." || name == "..") continue
                deleteRecursive(sftp, info.path)
            }
            sftp.rmdir(remote)
        } else {
            sftp.rm(remote)
        }
    }

    /** Recursive copy (SFTP has no server-side copy primitive): directories are created then copied item by item, files are copied streamingly. */
    private fun copyRecursive(sftp: SFTPClient, from: String, to: String) {
        val attrs = sftp.statExistence(from) ?: throw NoSuchFileException(File(from))
        if (attrs.type == FileMode.Type.DIRECTORY) {
            sftp.mkdirs(to)
            for (info in sftp.ls(from)) {
                val name = info.name
                if (name == "." || name == "..") continue
                copyRecursive(sftp, info.path, "$to/$name")
            }
        } else {
            sftp.open(from).use { src ->
                sftp.open(to, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { dst ->
                    val buf = ByteArray(IO_CHUNK)
                    var offset = 0L
                    while (true) {
                        val n = src.read(offset, buf, 0, buf.size)
                        if (n <= 0) break
                        dst.write(offset, buf, 0, n)
                        offset += n
                    }
                }
            }
        }
    }

    private fun ensureParent(sftp: SFTPClient, remote: String) {
        val parent = remote.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) sftp.mkdirs(parent)
    }
}

/** Adapts an SFTP [RemoteFile]'s random reads into a sequential [InputStream] for [BoundedLineReader]'s lazy line-by-line reads. */
private class RemoteFileInputStream(private val file: RemoteFile) : InputStream() {
    private var offset = 0L
    private val single = ByteArray(1)

    override fun read(): Int {
        val n = file.read(offset, single, 0, 1)
        if (n <= 0) return -1
        offset += 1
        return single[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = file.read(offset, b, off, len)
        if (n <= 0) return -1
        offset += n
        return n
    }

    override fun close() = file.close()
}

/** SFTP permission set -> a 9-character rwx string (e.g. `rw-r--r--`). */
internal fun formatPermissions(perms: Set<FilePermission>): String {
    fun bit(p: FilePermission, c: Char) = if (p in perms) c else '-'
    return buildString {
        append(bit(FilePermission.USR_R, 'r'))
        append(bit(FilePermission.USR_W, 'w'))
        append(bit(FilePermission.USR_X, 'x'))
        append(bit(FilePermission.GRP_R, 'r'))
        append(bit(FilePermission.GRP_W, 'w'))
        append(bit(FilePermission.GRP_X, 'x'))
        append(bit(FilePermission.OTH_R, 'r'))
        append(bit(FilePermission.OTH_W, 'w'))
        append(bit(FilePermission.OTH_X, 'x'))
    }
}

/** AI path -> real remote path: the `~/workspace` prefix maps to [workspaceRoot]; other absolute paths are used as-is. */
internal fun remotePathFor(path: String, workspaceRoot: String, remoteHome: String?): String {
    val root = workspaceRoot.trimEnd('/')
    // CONTAINER_ROOT is ~/workspace; expand ~ first, then do prefix matching
    val wsRoot = (remoteHome ?: "~").trimEnd('/') + "/workspace"
    val p = path.trim().let {
        if (it.startsWith("~/")) {
            val home = remoteHome
            if (home != null) home.trimEnd('/') + "/" + it.removePrefix("~/") else it
        } else it
    }
    return when {
        p == wsRoot || p == "$wsRoot/" || p == CONTAINER_ROOT || p == "$CONTAINER_ROOT/" -> root
        p.startsWith("$wsRoot/") ->
            root + "/" + p.removePrefix("$wsRoot/")
        p.startsWith("/") -> p
        else -> root + "/" + p
    }
}

/** Real remote path -> the AI-facing container path (for display). */
internal fun displayPathFor(remotePath: String, workspaceRoot: String): String {
    val root = workspaceRoot.trimEnd('/')
    return when {
        remotePath == root -> CONTAINER_ROOT
        remotePath.startsWith("$root/") -> CONTAINER_ROOT + "/" + remotePath.removePrefix("$root/")
        else -> remotePath
    }
}
