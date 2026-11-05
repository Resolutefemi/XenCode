package com.xencode.feature.agent.domain.container

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** A downloadable image entry: [path] is a relative path; the {abi} placeholder is substituted per architecture by [ContainerImageCatalog.urlFor]. */
@Serializable
data class ContainerImageEntry(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val sizeBytes: Long = 0,
    /** The distro this belongs to (a key in the global sources table, e.g. alpine/ubuntu). */
    val distro: String = "",
    val path: String,
    /** Standard architecture key (arm64/x86_64) → the distro's actual directory name (e.g. aarch64/amd64). */
    val abiNames: Map<String, String> = emptyMap()
)

/** A download source: Chinese/English display names + per-distro prefixes (missing/empty means this source does not offer that distro). */
@Serializable
data class ContainerImageSource(
    val name: Map<String, String> = emptyMap(),
    val distros: Map<String, String> = emptyMap()
)

/** Global image catalog: sources (source id → definition) + the image list. */
@Serializable
data class ContainerImageCatalogData(
    val sources: Map<String, ContainerImageSource> = emptyMap(),
    val images: List<ContainerImageEntry> = emptyList()
)

/**
 * Loads the downloadable image catalog from the built-in assets/container-images.json.
 * Sources and images are stored separately: URL = the sources[source][distro] prefix + the entry path ({abi} replaced per device architecture).
 */
@Singleton
class ContainerImageCatalog @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private var data = ContainerImageCatalogData()

    fun load(): List<ContainerImageEntry> {
        val raw = runCatching {
            context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyList()
        val parsed = runCatching {
            json.decodeFromString<ContainerImageCatalogData>(raw)
        }.getOrNull()
        data = parsed ?: ContainerImageCatalogData()
        return data.images
    }

    /** Global source id list (JSON order preserved), for the source switcher at the top-right. */
    val sourceIds: List<String>
        get() = data.sources.keys.toList()

    /** Source display name (looked up by the zh/en language key, falling back to any available name); returns null if the source does not exist. */
    fun sourceName(sourceId: String, lang: String): String? {
        val names = data.sources[sourceId]?.name ?: return null
        return names[lang] ?: names.values.firstOrNull()
    }

    /** Full download URL of [entry] from the given source for the current device architecture; returns null when source/distro/architecture is missing. */
    fun urlFor(entry: ContainerImageEntry, sourceId: String, abi: String): String? {
        val prefix = data.sources[sourceId]?.distros?.get(entry.distro)
            ?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val abiName = entry.abiNames[abi] ?: return null
        return "$prefix/${entry.path}".replace("{abi}", abiName)
    }

    companion object {
        private const val ASSET_FILE = "container-images.json"
        private val json = Json { ignoreUnknownKeys = true }

        /** Architecture detection consistent with [com.xencode.feature.agent.domain.container.ContainerInstaller]: x86 devices use x86_64, everything else arm64. */
        val CURRENT_ABI: String = if (Build.SUPPORTED_ABIS.any { it.contains("x86") }) "x86_64" else "arm64"
    }
}
