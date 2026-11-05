package com.xencode.feature.settings.data.remote

import android.content.Context
import com.xencode.R
import com.xencode.feature.settings.data.repository.UpdateChannel
import com.xencode.feature.settings.presentation.component.compareVersions
import com.xencode.feature.settings.presentation.component.parseVersionTag
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Changelog for a single version. */
data class VersionUpdate(
    val tag: String,
    val changelog: String
)

/** Fetched update info: latest version tag + changelog from the current version to the latest. */
data class UpdateInfo(
    val latestTag: String,
    val changelog: String,
    val updates: List<VersionUpdate>
)

/** Update check result. */
sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class NewVersion(val info: UpdateInfo) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/**
 * Fetches version info from GitHub Releases and builds the changelog.
 *
 * The stable channel filters out prereleases (RC) while the latest channel includes them; only releases strictly newer than the current version are counted;
 * the changelog is assembled from highest to lowest version (tag + body, with light cleanup of markdown headings/bold).
 * Results are not persisted; callers display them as needed.
 */
@Singleton
class UpdateCheckService @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    suspend fun checkForUpdate(currentVersion: String, channel: UpdateChannel): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = okhttp3.Request.Builder()
                    .url("$GITHUB_RELEASES_API?per_page=50")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                SHARED_CLIENT.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@use UpdateCheckResult.Error("HTTP ${resp.code}")
                    }
                    val body = resp.body?.string().orEmpty()
                    val array = runCatching { JsonParser.parseString(body).asJsonArray }
                        .getOrNull()
                        ?: return@use UpdateCheckResult.Error(context.getString(R.string.about_parse_version_failed))

                    val releases = array.mapNotNull { el ->
                        val obj = el.asJsonObject
                        val tag = obj.get("tag_name")?.asString ?: return@mapNotNull null
                        val version = parseVersionTag(tag) ?: return@mapNotNull null
                        ReleaseInfo(
                            version = version,
                            rawTag = tag,
                            notes = obj.get("body")?.asString.orEmpty(),
                            prerelease = obj.get("prerelease")?.asBoolean ?: false
                        )
                    }

                    val updates = releases
                        .filter { if (channel == UpdateChannel.STABLE) !it.prerelease else true }
                        .filter { compareVersions(it.version, currentVersion) > 0 }
                        .sortedWith { a, b -> compareVersions(b.version, a.version) }

                    if (updates.isEmpty()) {
                        UpdateCheckResult.UpToDate
                    } else {
                        val versionUpdates = updates.map { toVersionUpdate(it) }
                        UpdateCheckResult.NewVersion(
                            UpdateInfo(
                                latestTag = versionUpdates.first().tag,
                                changelog = versionUpdates.joinToString("\n\n") { "${it.tag}\n${it.changelog}" },
                                updates = versionUpdates
                            )
                        )
                    }
                }
            }.getOrElse { UpdateCheckResult.Error(it.message ?: context.getString(R.string.about_network_error)) }
        }

    private fun toVersionUpdate(release: ReleaseInfo): VersionUpdate {
        val notes = release.notes.trim()
        return VersionUpdate(
            tag = release.rawTag,
            changelog = if (notes.isEmpty()) {
                context.getString(R.string.about_no_changelog)
            } else {
                cleanMarkdown(notes)
            }
        )
    }

    /** Light markdown cleanup: strips leading heading markers and bold markers, and removes GitHub-generated heading/link lines, keeping plain text for easy reading. */
    private fun cleanMarkdown(text: String): String = text
        .lines()
        .map { it.trimEnd().replace("**", "") }
        .map { it.replace(Regex("^#{1,6}\\s+"), "") }
        .filterNot { line ->
            val t = line.trim()
            t.startsWith("What's Changed") ||
                t.startsWith("What's New") ||
                t.startsWith("Full Changelog:")
        }
        .joinToString("\n")

    private data class ReleaseInfo(
        val version: String,
        val rawTag: String,
        val notes: String,
        val prerelease: Boolean
    )

    private companion object {
        const val GITHUB_RELEASES_API = "https://api.github.com/repos/Resolutefemi/XenCode/releases"
        val SHARED_CLIENT by lazy {
            okhttp3.OkHttpClient.Builder()
                .proxyAuthenticator(com.xencode.core.net.AppProxy.okHttpAuthenticator)
                .build()
        }
    }
}
