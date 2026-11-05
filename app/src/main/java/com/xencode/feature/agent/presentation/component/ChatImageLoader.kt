package com.xencode.feature.agent.presentation.component

import androidx.compose.ui.graphics.ImageBitmap
import com.xencode.core.ui.ImageSource
import com.xencode.core.ui.ImageViewerRequest
import com.xencode.core.ui.decodeSampledBitmap
import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.presentation.AgentAttachment
import com.xencode.feature.workspace.domain.FileAccessProvider
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ChatImageLoader"

/**
 * Chat image loader: materializes an [ImageSource] into a bitmap for the fullscreen viewer. This is the project's single decoding strategy.
 *
 * [ImageSource.ContainerPath] lands as a host file via [FileAccessProvider.copyToLocal] — in local PRoot mode it is
 * just a path mapping with no copy; in remote SSH mode the file is pulled back as base64 and written to a temp file.
 */
internal fun chatImageLoader(
    fileAccess: FileAccessProvider,
    maxEdge: Int,
    maxPixels: Long
): suspend (ImageSource) -> ImageBitmap? = { source ->
    withContext(Dispatchers.IO) {
        try {
            when (source) {
                is ImageSource.LocalFile ->
                    decodeExistingFile(File(source.path), maxEdge, maxPixels)

                is ImageSource.ContainerPath ->
                    decodeExistingFile(fileAccess.copyToLocal(source.path), maxEdge, maxPixels)

                is ImageSource.Base64 ->
                    decodeSampledBitmap(Base64.getDecoder().decode(source.data), maxEdge, maxPixels)
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "Image loading failed: $source", e)
            null
        }
    }
}

private fun decodeExistingFile(file: File, maxEdge: Int, maxPixels: Long): ImageBitmap? {
    if (!file.isFile || file.length() <= 0L) return null
    return decodeSampledBitmap(file.absolutePath, maxEdge, maxPixels)
}

/**
 * Viewer request for an image attachment pending send.
 *
 * `localPath` first, base64 as fallback: `decodeByteArray` needs the full byte[] resident, and on top of that the base64
 * string already held in memory (actually twice as large as UTF-16), a 5MB image can transiently cost a dozen-plus MB extra; decoding from disk has no such overhead.
 */
internal fun PendingUploadAttachment.toViewerRequest(): ImageViewerRequest = ImageViewerRequest(
    sources = listOfNotNull(
        localPath.takeIf { it.isNotBlank() }?.let { ImageSource.LocalFile(it) },
        image?.base64Data?.takeIf { it.isNotBlank() }?.let { ImageSource.Base64(it) }
    ),
    title = fileName
)

/**
 * Viewer request for a sent attachment.
 *
 * Includes `containerPath` as a fallback: in remote mode the `localPath` recorded by `sendFile` comes from `createTempFile` +
 * `deleteOnExit`, so the file is gone once the process restarts or the cache is cleared; in that case we must fall back to the container path and pull it again.
 */
internal fun AgentAttachment.toViewerRequest(): ImageViewerRequest = ImageViewerRequest(
    sources = listOfNotNull(
        localPath.takeIf { it.isNotBlank() }?.let { ImageSource.LocalFile(it) },
        containerPath.takeIf { it.isNotBlank() }?.let { ImageSource.ContainerPath(it) }
    ),
    title = fileName
)

/**
 * Viewer request for an inline Markdown image: the link is the container path from the AI's perspective.
 *
 * The library hands alt straight to Image as contentDescription without going through the transformer, so it is unavailable here;
 * fall back to the file name at the end of the path as the title.
 */
internal fun markdownViewerRequest(link: String): ImageViewerRequest = ImageViewerRequest(
    sources = listOf(ImageSource.ContainerPath(link)),
    title = link.trimEnd('/').substringAfterLast('/')
)
