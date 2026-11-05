package com.xencode.feature.editor.presentation

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceResponse
import androidx.lifecycle.ViewModel
import androidx.webkit.WebViewAssetLoader
import com.xencode.core.util.FileLogger
import com.xencode.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.file.Paths
import javax.inject.Inject

/**
 * Backend of the Markdown preview screen (WebView rendering): assembles the rendered HTML and serves its images.
 *
 * Preview resources load over [WebViewAssetLoader] via two routes:
 * - `/assets/` uses the built-in [WebViewAssetLoader.AssetsPathHandler] to serve the rendering JS/CSS;
 * - `/local/` uses [LocalAssetPathHandler] to read via [FileAccessProvider] (direct read locally, SFTP in remote mode),
 *   so relative-path images in documents like READMEs display in both execution modes.
 *
 * The path after `/local/` is relative to the workspace root (assembled by [baseUrl] from the previewed
 * file's directory); `~` stays out of URLs - some WebView versions treat it specially and break relative resolution.
 */
@HiltViewModel
class MarkdownPreviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileAccess: FileAccessProvider
) : ViewModel() {

    companion object {
        /** Default virtual host of [WebViewAssetLoader]. */
        const val ASSET_HOST = "appassets.androidplatform.net"

        private const val ASSET_BASE = "https://$ASSET_HOST/assets/markdown/"
        private const val LOCAL_PREFIX = "/local/"
        private const val TAG = "MarkdownPreview"

        /** Root path of the workspace inside the container, matching [com.xencode.feature.workspace.domain.WorkspacePathMapper.CONTAINER_ROOT]. */
        private const val WS_ROOT = "~/workspace"

        /** Page background in dark mode, matching the github-markdown-css dark theme. */
        private const val BG_DARK = "#0d1117"
        private const val BG_LIGHT = "#ffffff"
    }

    private val template: String by lazy {
        context.assets.open("markdown/preview.html").bufferedReader().use { it.readText() }
    }

    /** Light/dark mode of the most recent render, reused by [LocalAssetPathHandler] when rendering a navigated-to Markdown. */
    @Volatile
    private var currentDark: Boolean = false

    val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler(LOCAL_PREFIX, LocalAssetPathHandler())
        .build()

    /** Injects raw markdown into the template to produce preview HTML; [dark] picks the light/dark GitHub stylesheet. */
    suspend fun buildHtml(text: String, dark: Boolean): String = withContext(Dispatchers.IO) {
        buildHtmlInternal(text, dark)
    }

    private fun buildHtmlInternal(text: String, dark: Boolean): String {
        currentDark = dark
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return template
            .replace("__ASSET_BASE__", ASSET_BASE)
            .replace("__MD_CSS__", if (dark) "github-markdown-dark.css" else "github-markdown-light.css")
            .replace("__HL_CSS__", if (dark) "highlight-github-dark.min.css" else "highlight-github.min.css")
            .replace("__BG__", if (dark) BG_DARK else BG_LIGHT)
            .replace("__MD_B64__", b64)
    }

    /**
     * Preview page baseUrl: based on the previewed file's directory, relative image paths in
     * markdown (e.g. `docs/a.png`) resolve to `/local/<dir relative to workspace root>/docs/a.png`, which [containerPathOf] maps back to a container path to read.
     */
    fun baseUrl(path: String): String {
        val dir = relativeDirOf(path)
        val encoded = Uri.encode(dir, "/")
        return if (encoded.isEmpty()) {
            "https://$ASSET_HOST$LOCAL_PREFIX"
        } else {
            "https://$ASSET_HOST$LOCAL_PREFIX$encoded/"
        }
    }

    /**
     * Maps the relative path after `/local/` (relative to the workspace root) back to a container path; returns null when nothing can be resolved.
     * The input is the suffix path after WebViewAssetLoader strips the registered prefix, without `/local/`.
     * Normalization keeps `..` within the root to prevent requests escaping the workspace.
     */
    fun containerPathOf(relativePath: String): String? {
        val decoded = runCatching { URLDecoder.decode(relativePath, "UTF-8") }.getOrNull() ?: return null
        if (decoded.isEmpty()) return null
        val normalized = runCatching { Paths.get("/$decoded").normalize().toString() }.getOrNull() ?: return null
        val trimmed = normalized.trimStart('/')
        if (trimmed.isEmpty()) return null
        return "$WS_ROOT/$trimmed"
    }

    /** Returns the container path when a URL in the preview page points to a workspace file; null for non-local URLs. */
    fun containerPathForUrl(url: Uri): String? {
        if (url.host != ASSET_HOST) return null
        val path = url.path ?: return null
        if (!path.startsWith(LOCAL_PREFIX)) return null
        return containerPathOf(path.removePrefix(LOCAL_PREFIX))
    }

    /** Path of the previewed file's directory relative to the workspace root (no leading/trailing slashes); falls back to the workspace root when outside it. */
    private fun relativeDirOf(path: String): String {
        val parent = fileAccess.parentPath(path)?.trimEnd('/') ?: return ""
        return when {
            parent == WS_ROOT -> ""
            parent.startsWith("$WS_ROOT/") -> parent.removePrefix("$WS_ROOT/")
            else -> ""
        }
    }

    private inner class LocalAssetPathHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val containerPath = containerPathOf(path)
            FileLogger.v(TAG, "Preview local resource: $path -> ${containerPath ?: "null"}")
            if (containerPath == null) {
                FileLogger.w(TAG, "Preview resource path failed to resolve: $path")
                return notFound()
            }
            val bytes = runCatching { fileAccess.readBytes(containerPath) }
                .onFailure { FileLogger.w(TAG, "Failed to read preview resource: $containerPath", it) }
                .getOrNull()
            if (bytes == null) return notFound()
            // When a local Markdown link is tapped in the preview, render the target file as a preview page (browser-style navigation)
            if (isMarkdown(containerPath)) {
                val html = buildHtmlInternal(bytes.toString(Charsets.UTF_8), currentDark)
                return WebResourceResponse(
                    "text/html", "utf-8", 200, "OK", emptyMap(),
                    ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
                )
            }
            return WebResourceResponse(mimeOf(containerPath), null, ByteArrayInputStream(bytes))
        }
    }

    private fun isMarkdown(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")

    /** Matched `/local/` but content unavailable: return 404 rather than null, so WebView does not treat it as un-intercepted and fall back to the network. */
    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun mimeOf(path: String): String = when (val ext = path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        // Text-like files (including extension-less LICENSE / README) are served as plain text so they display inline in the preview instead of being treated as downloads
        "txt", "text", "log", "json", "jsonc", "yml", "yaml", "toml", "ini", "conf", "cfg", "properties",
        "xml", "html", "htm", "css", "js", "mjs", "ts", "tsx", "jsx", "kt", "kts", "java", "py", "rb",
        "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "swift", "sh", "bash", "zsh", "sql", "gradle",
        "gitignore", "env", "csv" -> "text/plain"
        else -> if (ext.isEmpty()) "text/plain" else "application/octet-stream"
    }
}
