package com.xencode.feature.workspace.domain

import com.xencode.core.util.FileLogger
import com.xencode.core.util.boundedLines
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local implementation of [FileAccessProvider]: wraps a [WorkspacePathMapper] and goes through java.io.File directly.
 *
 * Zero behavior change for file tools in local mode — it merely gathers the pathMapper.toHostFile + File operations
 * that used to be scattered across tools into one place, called uniformly through the interface.
 */
@Singleton
class LocalFileAccess @Inject constructor(
    private val pathMapper: WorkspacePathMapper
) : FileAccessProvider {

    private fun resolve(path: String): File = pathMapper.toHostFile(path)

    override fun readFile(path: String): String {
        val file = resolve(path)
        if (!file.exists()) throw NoSuchFileException(file)
        return file.readText()
    }

    override fun readLines(path: String): Sequence<String> {
        val file = resolve(path)
        if (!file.exists()) throw NoSuchFileException(file)
        // Lazy Sequence: each iteration reads the next line, so large files are never loaded whole; the reader closes via use after iteration.
        // Lines are capped at 64K characters so one pathological line cannot blow up memory.
        return boundedLines { file.bufferedReader() }
    }

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) {
        val file = resolve(path)
        if (file.exists() && !overwrite) throw FileAlreadyExistsException(file)
        file.parentFile?.mkdirs()
        val bytes = content.toByteArray(encoding)
        // fsync + read-back length check: writeText only hits the page cache, and write-back errors under delayed allocation
        // (disk-full ENOSPC, storage faults) are never reported to the caller — it becomes "tool says success but the file did not change".
        // sync surfaces the error early as an IOException so the tool reports a real failure instead of silently dropping the change.
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        val actual = file.length()
        if (actual != bytes.size.toLong()) {
            throw IOException("write verification failed: ${file.absolutePath} expected ${bytes.size} bytes, found $actual")
        }
        FileLogger.i(TAG, "Write: '$path' -> ${file.absolutePath} (${bytes.size} bytes)")
    }

    override fun exists(path: String): Boolean = resolve(path).exists()

    override fun isDirectory(path: String): Boolean = resolve(path).isDirectory

    override fun isFile(path: String): Boolean = resolve(path).isFile

    override fun fileSize(path: String): Long = resolve(path).length()

    override fun lastModified(path: String): Long = resolve(path).lastModified()

    override fun permissions(path: String): String {
        val file = resolve(path)
        val read = if (file.canRead()) "r" else "-"
        val write = if (file.canWrite()) "w" else "-"
        val execute = if (file.canExecute() || file.isDirectory) "x" else "-"
        return read + write + execute
    }

    override fun listFiles(path: String): List<FileEntry> {
        val dir = resolve(path)
        return dir.listFiles().orEmpty().map { child ->
            FileEntry(
                name = child.name,
                isDirectory = child.isDirectory,
                size = child.length(),
                lastModified = child.lastModified(),
                localFile = child,
                permissions = run {
                    val r = if (child.canRead()) "r" else "-"
                    val w = if (child.canWrite()) "w" else "-"
                    val x = if (child.canExecute() || child.isDirectory) "x" else "-"
                    r + w + x
                }
            )
        }
    }

    override fun readBytes(path: String): ByteArray {
        val file = resolve(path)
        if (!file.exists()) throw NoSuchFileException(file)
        return file.readBytes()
    }

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> {
        val dir = resolve(path)
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown().maxDepth(maxDepth)
            .filter { it.isFile }
            .map { it.relativeTo(dir).invariantSeparatorsPath }
            .toList()
    }

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
        val file = resolve(path)
        if (file.exists() && !overwrite) throw FileAlreadyExistsException(file)
        file.parentFile?.mkdirs()
        // Same fsync + read-back length check as writeFile, avoiding "reports success but the file is incomplete".
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        val actual = file.length()
        if (actual != bytes.size.toLong()) {
            throw IOException("write verification failed: ${file.absolutePath} expected ${bytes.size} bytes, found $actual")
        }
        FileLogger.i(TAG, "Write: '$path' -> ${file.absolutePath} (${bytes.size} bytes)")
    }

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long {
        val file = resolve(path)
        if (file.exists() && !overwrite) throw FileAlreadyExistsException(file)
        file.parentFile?.mkdirs()
        return try {
            FileOutputStream(file).use { out -> input.copyTo(out) }
        } catch (e: Exception) {
            // A mid-write failure (source stream read error, no space left) leaves a partial file; delete it then rethrow
            file.delete()
            throw e
        }
    }

    override fun copyToLocal(path: String): File = resolve(path)

    override fun delete(path: String) {
        resolve(path).delete()
    }

    override fun deleteRecursively(path: String) {
        val file = resolve(path)
        if (!file.exists()) return
        if (!file.deleteRecursively()) throw IOException("delete failed: ${file.absolutePath}")
    }

    override fun rename(path: String, newPath: String) {
        val source = resolve(path)
        val target = resolve(newPath)
        if (!source.exists()) throw NoSuchFileException(source)
        if (target.exists()) throw FileAlreadyExistsException(target)
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            throw IOException("rename failed: ${source.absolutePath} -> ${target.absolutePath}")
        }
    }

    override fun copy(path: String, newPath: String, overwrite: Boolean) {
        val source = resolve(path)
        val target = resolve(newPath)
        if (!source.exists()) throw NoSuchFileException(source)
        val sourcePath = source.canonicalFile.toPath()
        val targetPath = target.canonicalFile.toPath()
        if (targetPath == sourcePath || (source.isDirectory && targetPath.startsWith(sourcePath))) {
            throw IOException("destination is the source or its descendant: $newPath")
        }
        if (target.exists()) {
            if (!overwrite) throw FileAlreadyExistsException(target)
            target.deleteRecursively()
        }
        target.parentFile?.mkdirs()
        if (source.isDirectory) {
            source.copyRecursively(target, overwrite = false)
        } else {
            source.copyTo(target, overwrite = false)
        }
    }

    override fun move(path: String, newPath: String, overwrite: Boolean) {
        val source = resolve(path)
        val target = resolve(newPath)
        if (!source.exists()) throw NoSuchFileException(source)
        val sourcePath = source.canonicalFile.toPath()
        val targetPath = target.canonicalFile.toPath()
        if (targetPath == sourcePath || (source.isDirectory && targetPath.startsWith(sourcePath))) {
            throw IOException("destination is the source or its descendant: $newPath")
        }
        if (target.exists()) {
            if (!overwrite) throw FileAlreadyExistsException(target)
            target.deleteRecursively()
        }
        target.parentFile?.mkdirs()
        val moved = try {
            source.renameTo(target)
        } catch (e: SecurityException) {
            false
        }
        if (moved) return
        // Fallback: copy then delete the source (if the copy is interrupted the source survives, safe to retry later).
        if (source.isDirectory) {
            source.copyRecursively(target, overwrite = false)
        } else {
            source.copyTo(target, overwrite = false)
        }
        if (!source.deleteRecursively()) throw IOException("delete-after-copy failed: ${source.absolutePath}")
    }

    override fun mkdirs(path: String) {
        resolve(path).mkdirs()
    }

    override fun parentPath(path: String): String? {
        val parent = resolve(path).parentFile ?: return null
        return pathMapper.toContainerPath(parent.absolutePath)
    }

    override fun toDisplayPath(path: String): String {
        return pathMapper.toContainerPath(resolve(path).absolutePath)
    }

    private companion object {
        const val TAG = "LocalFileAccess"
    }
}
