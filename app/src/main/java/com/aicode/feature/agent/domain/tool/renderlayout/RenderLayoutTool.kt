package com.aicode.feature.agent.domain.tool.renderlayout

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.core.xmlrender.RenderOutcome
import com.aicode.core.xmlrender.RenderStatus
import com.aicode.core.xmlrender.XmlRenderEngine
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * Renders an Android XML layout file into a PNG snapshot the user can open in chat.
 *
 * The renderer is an approximate, whitelist-based engine (framework widgets only):
 * custom views, fragments and unsupported attributes become labeled placeholders, and
 * every degradation is reported as a warning so the model knows the preview is
 * approximate. Screenshots land in ~/workspace/.aicode/previews/ (one PNG per layout,
 * latest render wins). Read-only against the workspace besides that output folder.
 */
class RenderLayoutTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileAccess: FileAccessProvider
) : AgentTool() {

    override val name = "renderLayout"

    override val description get() =
        "Render an Android XML layout (res/layout/*.xml) to a PNG preview shown in chat and saved under .aicode/previews/. Coverage is broad but approximate: standard framework widgets (Text/EditText/Button/ImageView/Spinner/RatingBar/TableLayout and all base layouts) render faithfully; Material cards, FABs, tab bars, bottom bars and list containers render as close lookalikes; ConstraintLayout parent and sibling constraints are translated into RelativeLayout rules; shapes, layer-lists, radial/sweep gradients, dashed strokes and Material theme colors are supported. Custom view classes, fragments, constraint chains/bias and a few exotic attributes become labeled placeholders with warnings. Use after writing or editing a layout so the user can see the current look."

    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE

    override val parameters get() = mapOf(
        "path" to ToolParameter(
            name = "path",
            type = ParameterType.STRING,
            description = "Layout XML path (workspace-relative or absolute), must live under a res/ folder so resources can be resolved",
            required = true
        ),
        "theme" to ToolParameter(
            name = "theme",
            type = ParameterType.STRING,
            description = "Render theme: light (default) or dark",
            required = false,
            enum = listOf("light", "dark")
        ),
        "width_dp" to ToolParameter(
            name = "width_dp",
            type = ParameterType.INTEGER,
            description = "Viewport width in dp (default $DEFAULT_WIDTH_DP, range $MIN_SIZE_DP-$MAX_SIZE_DP)",
            required = false
        ),
        "height_dp" to ToolParameter(
            name = "height_dp",
            type = ParameterType.INTEGER,
            description = "Viewport height in dp (default $DEFAULT_HEIGHT_DP, range $MIN_SIZE_DP-$MAX_SIZE_DP)",
            required = false
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val path = args.string("path")?.trim().orEmpty()
        if (path.isEmpty()) {
            return ToolResult.Error("Missing required parameter: path", "MISSING_PATH")
        }
        if (!path.substringAfterLast('/').endsWith(".xml", ignoreCase = true)) {
            return ToolResult.Error("Layout path must point to an .xml file: $path", "NOT_XML")
        }
        val dark = args.string("theme")?.lowercase() == "dark"
        val widthDp = args.int("width_dp") ?: XmlRenderEngine.DEFAULT_WIDTH_DP
        val heightDp = args.int("height_dp") ?: XmlRenderEngine.DEFAULT_HEIGHT_DP

        FileLogger.i(TAG, "renderLayout start: $path theme=${if (dark) "dark" else "light"} ${widthDp}x${heightDp}dp")

        val outcome = XmlRenderEngine.render(
            context = context,
            fileAccess = fileAccess,
            layoutPath = path,
            widthDp = widthDp,
            heightDp = heightDp,
            dark = dark
        )
        if (outcome.status == RenderStatus.FAILED || outcome.bitmap == null) {
            FileLogger.w(TAG, "renderLayout failed: ${outcome.errorMessage}")
            return ToolResult.Error(
                "Render failed: ${outcome.errorMessage ?: "unknown error"}" +
                    warningsSuffix(outcome),
                "RENDER_FAILED"
            )
        }

        val pngName = path.substringAfterLast('/').removeSuffix(".xml") + ".png"
        val previewPath = "$PREVIEW_DIR/$pngName"
        val saved = XmlRenderEngine.savePng(fileAccess, previewPath, outcome.bitmap)
        if (!saved) {
            return ToolResult.Error(
                "Rendered but could not save $previewPath; check workspace write access",
                "RENDER_SAVE_FAILED"
            )
        }

        val statusText = if (outcome.status == RenderStatus.SUCCESS) "success" else "approximate"
        FileLogger.i(TAG, "renderLayout done: $previewPath status=$statusText warnings=${outcome.warnings.size}")

        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "content" to JsonPrimitive(
                        buildString {
                            append("Rendered $path at ${widthDp}x${heightDp}dp (${if (dark) "dark" else "light"}) ")
                            append("→ saved to $previewPath. Status: $statusText.")
                            append(warningsSuffix(outcome))
                        }
                    ),
                    "status" to JsonPrimitive(statusText),
                    "render" to JsonObject(
                        mapOf(
                            "name" to JsonPrimitive(pngName),
                            "container_path" to JsonPrimitive(previewPath),
                            "source_path" to JsonPrimitive(path),
                            "mime_type" to JsonPrimitive("image/png"),
                            "width" to JsonPrimitive(outcome.widthPx),
                            "height" to JsonPrimitive(outcome.heightPx),
                            "theme" to JsonPrimitive(if (dark) "dark" else "light")
                        )
                    ),
                    "warnings" to JsonArray(outcome.warnings.map { JsonPrimitive(it) })
                )
            )
        )
    }

    private fun warningsSuffix(outcome: RenderOutcome): String {
        if (outcome.warnings.isEmpty()) return ""
        val shown = outcome.warnings.take(MAX_WARNINGS_IN_RESULT)
        val more = outcome.warnings.size - shown.size
        return buildString {
            append('\n')
            shown.forEach { append("- ").append(it).append('\n') }
            if (more > 0) append("- …and $more more")
        }
    }

    private fun Map<String, JsonElement>.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun Map<String, JsonElement>.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()

    private companion object {
        const val TAG = "RenderLayoutTool"
        const val PREVIEW_DIR = "~/workspace/.aicode/previews"
        const val DEFAULT_WIDTH_DP = XmlRenderEngine.DEFAULT_WIDTH_DP
        const val DEFAULT_HEIGHT_DP = XmlRenderEngine.DEFAULT_HEIGHT_DP
        const val MIN_SIZE_DP = XmlRenderEngine.MIN_SIZE_DP
        const val MAX_SIZE_DP = XmlRenderEngine.MAX_SIZE_DP
        const val MAX_WARNINGS_IN_RESULT = 12
    }
}
