package com.xencode.feature.editor.presentation.component

import android.net.Uri
import android.view.ContextThemeWrapper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.xencode.R
import com.xencode.core.util.FileLogger
import com.xencode.feature.editor.presentation.MarkdownPreviewViewModel

/**
 * Markdown preview screen: renders full Markdown (including embedded HTML) in a WebView, reproducing the GitHub look.
 *
 * Local file links navigate in place like a browser (Markdown targets are rendered into a new preview page by [MarkdownPreviewViewModel]);
 * Back first goes back through WebView history, then exits the preview; external links do not navigate.
 *
 * This and the chat area's Compose rendering are two independent paths and do not affect each other.
 */
@Composable
internal fun MarkdownPreviewWebView(
    text: String,
    path: String,
    onExitPreview: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MarkdownPreviewViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bgColor = if (dark) BG_DARK else BG_LIGHT
    val exitPreview by rememberUpdatedState(onExitPreview)

    val webView = remember {
        val themedContext = ContextThemeWrapper(context, webViewThemeRes(dark))
        WebView(themedContext).apply {
            settings.javaScriptEnabled = true
            // The page only fetches resources through assetLoader; the local file system stays unexposed
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            setBackgroundColor(bgColor)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val response = viewModel.assetLoader.shouldInterceptRequest(request.url)
                    FileLogger.v(TAG, "Preview resource request: ${request.url} -> ${if (response == null) "network" else "intercepted"}")
                    return response
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url
                    // In-page anchors (#xxx): only the fragment changes and the path matches the current page; allow and let WebView scroll
                    if (url.fragment != null && url.path == view.url?.let { Uri.parse(it).path }) return false
                    // Allow file links pointing into the workspace so WebView navigates in place (Markdown is rendered into a preview page by assetLoader)
                    if (viewModel.containerPathForUrl(url) != null) return false
                    // Block everything else (external links etc.)
                    return true
                }
            }
        }
    }

    // WebView's isLightTheme derives from the context theme; update it on dark-mode switches so scrollbar and other controls match.
    LaunchedEffect(dark) {
        (webView.context as? ContextThemeWrapper)?.setTheme(webViewThemeRes(dark))
        webView.setBackgroundColor(bgColor)
    }

    DisposableEffect(webView) {
        onDispose { webView.destroy() }
    }

    LaunchedEffect(text, path, dark) {
        val html = viewModel.buildHtml(text, dark)
        webView.loadDataWithBaseURL(viewModel.baseUrl(path), html, "text/html", "utf-8", null)
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else exitPreview()
    }

    AndroidView(factory = { webView }, modifier = modifier)
}

private fun webViewThemeRes(dark: Boolean): Int =
    if (dark) R.style.Theme_XenCode_WebView_Dark else R.style.Theme_XenCode_WebView

private const val BG_DARK = 0xFF0D1117.toInt()
private const val BG_LIGHT = 0xFFFFFFFF.toInt()
private const val TAG = "MarkdownPreview"
