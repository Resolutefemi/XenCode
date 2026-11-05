package com.xencode.feature.workspace.domain

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

/**
 * Resolves an SAF tree uri (returned by the system directory picker) into a real host path.
 *
 * Only the well-defined docId forms of the Android external storage provider are supported:
 * - `primary:<relative path>` -> `<primary storage root>/<relative path>` (the root comes from `Environment.getExternalStorageDirectory()`, which is not a fixed `/storage/emulated/0` under multi-user/work profiles)
 * - `<volume UUID>:<relative path>` -> `/storage/<volume UUID>/<relative path>` (e.g. `XXXX-XXXX:Download`)
 *
 * Custom docIds from other providers return null, so provider-defined opaque IDs are never mistaken for host paths.
 */
object UriPathResolver {

    /** tree uri -> real host directory path; null when unresolvable. */
    fun toFilePath(context: Context, uri: Uri): String? {
        if (!DocumentsContract.isTreeUri(uri)) return null
        if (uri.authority != "com.android.externalstorage.documents") return null
        val primaryRoot = runCatching { Environment.getExternalStorageDirectory().absolutePath }
            .getOrDefault("/storage/emulated/0")
        return resolveDocId(DocumentsContract.getTreeDocumentId(uri), primaryRoot)
    }

    /** Pure function: externalstorage docId -> real host directory path; null when unresolvable.
     *  [primaryRoot] is the primary storage root the primary: branch maps to, defaulting to the common main-user path; injectable for tests. */
    fun resolveDocId(docId: String, primaryRoot: String = "/storage/emulated/0"): String? {
        if (docId.startsWith("primary:")) {
            val sub = docId.substringAfter("primary:", "")
            if (!isSafeSubpath(sub)) return null
            return primaryRoot.trimEnd('/') + "/" + sub.trimStart('/')
        }
        val separator = docId.indexOf(':')
        if (separator <= 0) return null
        val volume = docId.substring(0, separator)
        val sub = docId.substring(separator + 1)
        if (!volume.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) || !isSafeSubpath(sub)) return null
        return "/storage/$volume/" + sub.trimStart('/')
    }

    private fun isSafeSubpath(subpath: String): Boolean =
        subpath.trimStart('/').split('/').none { it == ".." }
}
