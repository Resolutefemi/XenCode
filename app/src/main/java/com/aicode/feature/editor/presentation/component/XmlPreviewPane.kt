package com.aicode.feature.editor.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.ImageSource
import com.aicode.core.ui.ImageViewerRequest
import com.aicode.core.ui.LocalImageViewer
import com.aicode.core.xmlrender.RenderOutcome
import com.aicode.core.xmlrender.RenderStatus
import com.aicode.feature.editor.presentation.CodeEditorViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertTriangle
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Smartphone
import compose.icons.feathericons.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * In-editor XML layout preview: renders the current editor text (unsaved draft included)
 * through [CodeEditorViewModel.renderLayoutPreview] and shows the snapshot inside a phone
 * frame. Re-renders with a short debounce while typing; the refresh button forces an
 * immediate re-read; tapping the phone opens the snapshot full screen in the shared viewer.
 */
@Composable
internal fun XmlPreviewPane(
    path: String,
    editorTextProvider: () -> String,
    viewModel: CodeEditorViewModel,
    onExitPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dark = isSystemInDarkTheme()
    var text by remember { mutableStateOf(editorTextProvider()) }
    var outcome by remember { mutableStateOf<RenderOutcome?>(null) }
    var rendering by remember { mutableStateOf(true) }

    LaunchedEffect(text, dark) {
        rendering = true
        delay(RENDER_DEBOUNCE_MS)
        outcome = viewModel.renderLayoutPreview(layoutXml = text, path = path, dark = dark)
        rendering = false
    }

    val context = LocalContext.current
    val viewer = LocalImageViewer.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        PreviewHeader(
            fileName = path.substringAfterLast('/'),
            approximateCount = outcome
                ?.takeIf { it.status == RenderStatus.APPROXIMATE }
                ?.warnings
                ?.size
                ?: 0,
            onRefresh = { text = editorTextProvider() },
            onClose = onExitPreview
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            val current = outcome
            val bitmap = current?.bitmap
            when {
                bitmap != null -> {
                    val fileName = path.substringAfterLast('/')
                    PhoneFrame(
                        image = bitmap.asImageBitmap(),
                        contentDescription = fileName,
                        onOpenFull = {
                            scope.launch {
                                val file = withContext(Dispatchers.IO) {
                                    File(context.cacheDir, XML_PREVIEW_FULL_FILE).apply {
                                        outputStream().use { stream ->
                                            bitmap.compress(
                                                android.graphics.Bitmap.CompressFormat.PNG,
                                                100,
                                                stream
                                            )
                                        }
                                    }
                                }
                                viewer.show(
                                    ImageViewerRequest(
                                        sources = listOf(ImageSource.LocalFile(file.absolutePath)),
                                        title = fileName
                                    )
                                )
                            }
                        }
                    )
                }
                rendering -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5f.dp)
                    Text(
                        text = stringResource(R.string.editor_xml_rendering),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> PreviewErrorColumn(outcome = current)
            }
        }
    }
}

private const val RENDER_DEBOUNCE_MS = 700L
private const val XML_PREVIEW_FULL_FILE = "xml-preview-full.png"

@Composable
private fun PreviewHeader(
    fileName: String,
    approximateCount: Int,
    onRefresh: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(
            FeatherIcons.Smartphone,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = fileName,
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (approximateCount > 0) {
            Text(
                text = stringResource(R.string.editor_xml_render_approximate, approximateCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onRefresh) {
            Icon(
                FeatherIcons.RefreshCw,
                contentDescription = stringResource(R.string.editor_xml_render_refresh),
                modifier = Modifier.size(17.dp)
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                FeatherIcons.X,
                contentDescription = stringResource(R.string.common_close),
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

/** Phone-shaped frame with the snapshot stretched to the 9:16 viewport. */
@Composable
private fun PhoneFrame(
    image: androidx.compose.ui.graphics.ImageBitmap,
    contentDescription: String,
    onOpenFull: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(Radius.xl),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxHeight(0.88f)
            .aspectRatio(9f / 16f)
            .clickable(onClick = onOpenFull)
    ) {
        Image(
            bitmap = image,
            contentDescription = contentDescription,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(Radius.xl))
        )
    }
}

@Composable
private fun PreviewErrorColumn(outcome: RenderOutcome?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.lg)
    ) {
        Icon(
            FeatherIcons.AlertTriangle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = stringResource(R.string.editor_xml_render_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        outcome?.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
