package com.xencode.feature.settings.presentation.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xencode.R
import com.xencode.core.theme.Brand
import com.xencode.feature.settings.domain.model.AIProviderConfig
import compose.icons.FeatherIcons
import compose.icons.feathericons.Cloud
import compose.icons.feathericons.Cpu
import android.content.Context
import androidx.compose.ui.res.stringResource

/**
 * The brand key finally matched for a provider: detect the brand from the provider name first, then fall back to the protocol type.
 * Matches [modelBrandKey]'s priority; returns null when no logo can be shown.
 */
fun providerBrandKey(provider: AIProviderConfig?): String? {
    if (provider == null) return null
    val nameKey = modelBrandKey(provider.name)
    if (brandLogoRes(nameKey) != null) return nameKey
    return when (provider.type) {
        com.xencode.feature.settings.domain.model.ProviderType.OPENAI -> "openai"
        com.xencode.feature.settings.domain.model.ProviderType.ANTHROPIC -> "anthropic"
        com.xencode.feature.settings.domain.model.ProviderType.GEMINI -> "google"
    }
}

/**
 * Match the brand logo drawable resource from the provider's brand key (name detection first, protocol type fallback).
 */
fun providerLogoRes(provider: AIProviderConfig?): Int? =
    providerBrandKey(provider)?.let { brandLogoRes(it) }

/**
 * Infer the brand category key (lowercase English identifier) from the model name.
 * Same matching priority as providerLogoRes / modelLogoRes, ensuring "zhipu" is not preempted by "glm" etc.
 * The returned brand key is used for grouping and logo lookup.
 */
fun modelBrandKey(modelName: String): String {
    val target = modelName.lowercase()
    return when {
        target.contains("doubao") || target.contains("豆包") -> "doubao"
        // minimax must come before grok: names like minimaxai contain "xai" and would otherwise be mismatched by the grok rule
        target.contains("minimax") || target.contains("abab") -> "minimax"
        target.contains("moonshot") || target.contains("kimi") -> "moonshot"
        target.contains("zhipu") || target.contains("智谱") || target.contains("bigmodel") || target.contains("glm") -> "zhipu"
        // alibaba / Tongyi Qianwen: qwen belongs to the alibaba brand (Alibaba Cloud Tongyi), placed before gemini to avoid mismatches.
        target.contains("qwen") || target.contains("通义") || target.contains("alibaba") || target.contains("aliyun") || target.contains("dashscope") -> "alibaba"
        target.contains("deepseek") || target.contains("deep-seek") -> "deepseek"
        target.contains("grok") || target.contains("xai") -> "grok"
        target.contains("groq") -> "groq"
        target.contains("claude") || target.contains("anthropic") -> "anthropic"
        // google / gemini: both gemma and gemini belong to the google brand.
        target.contains("google") || target.contains("gemini") || target.contains("gemma") -> "google"
        target.contains("hunyuan") || target.contains("混元") || target.contains("tencent") || target.contains("hy") -> "hunyuan"
        target.contains("openrouter") -> "openrouter"
        target.contains("perplexity") -> "perplexity"
        target.contains("siliconflow") || target.contains("硅基") -> "siliconflow"
        // ollama must come before meta: ollama contains "llama" and would otherwise be mismatched by the meta rule
        target.contains("ollama") -> "ollama"
        target.contains("meta") || target.contains("llama") -> "meta"
        target.contains("mistral") -> "mistral"
        target.contains("gpt") || target.contains("o1") || target.contains("o3") || target.contains("o4") || target.contains("openai") || target.contains("chatgpt") || target.contains("dall-e") -> "openai"
        else -> "other"
    }
}

/** Brand key -> user-visible display name */
fun brandDisplayName(context: Context, key: String): String = when (key) {
    "doubao" -> context.getString(R.string.provider_brand_doubao)
    "minimax" -> "MiniMax"
    "moonshot" -> "Moonshot"
    "zhipu" -> context.getString(R.string.provider_brand_zhipu)
    "alibaba" -> "Alibaba"
    "deepseek" -> "DeepSeek"
    "grok" -> "Grok"
    "groq" -> "Groq"
    "anthropic" -> "Anthropic"
    "google" -> "Google"
    "hunyuan" -> context.getString(R.string.provider_brand_hunyuan)
    "openrouter" -> "OpenRouter"
    "perplexity" -> "Perplexity"
    "siliconflow" -> context.getString(R.string.provider_brand_siliconflow)
    "ollama" -> "Ollama"
    "meta" -> "Meta"
    "mistral" -> "Mistral"
    "openai" -> "OpenAI"
    "other" -> context.getString(R.string.common_other)
    else -> key.replaceFirstChar { it.uppercase() }
}

/** Brand key -> corresponding logo drawable resource, null when no match */
fun brandLogoRes(key: String): Int? = when (key) {
    "doubao" -> R.drawable.logo_doubao
    "minimax" -> R.drawable.logo_minimax
    "moonshot" -> R.drawable.logo_moonshot
    "zhipu" -> R.drawable.logo_zhipu
    "alibaba" -> R.drawable.logo_qwen
    "deepseek" -> R.drawable.logo_deepseek
    "grok" -> R.drawable.logo_grok
    "groq" -> R.drawable.logo_groq
    "anthropic" -> R.drawable.logo_anthropic
    "google" -> R.drawable.logo_gemini
    "hunyuan" -> R.drawable.logo_hunyuan
    "openrouter" -> R.drawable.logo_openrouter
    "perplexity" -> R.drawable.logo_perplexity
    "siliconflow" -> R.drawable.logo_siliconflow
    "ollama" -> R.drawable.logo_ollama
    "meta" -> R.drawable.logo_meta
    "mistral" -> R.drawable.logo_mistral
    "openai" -> R.drawable.logo_openai
    else -> null
}

/** Whether to apply a theme tint to the logo; brands keeping their original colors (e.g. multicolor hunyuan/siliconflow logos) are excluded. */
private fun shouldTintModelLogo(key: String): Boolean =
    key == "grok" || key == "groq" || key == "moonshot" || key == "openai" ||
        key == "openrouter" || key == "perplexity" ||
        key == "ollama" || key == "meta" || key == "mistral"

@Composable
private fun modelLogoTint(): Color {
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (isDarkTheme) Color.White else Color.Black
}

/**
 * Render the brand logo icon; shows the default Cpu icon when unmatched or provider is null.
 */
@Composable
fun ProviderLogoIcon(
    provider: AIProviderConfig?,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp
) {
    val res = providerLogoRes(provider)
    if (res != null) {
        val brandKey = providerBrandKey(provider)
        Image(
            painter = painterResource(res),
            contentDescription = provider?.name ?: "AI Vendor",
            colorFilter = if (brandKey != null && shouldTintModelLogo(brandKey)) ColorFilter.tint(modelLogoTint()) else null,
            modifier = modifier.size(size)
        )
    } else {
        Icon(
            imageVector = FeatherIcons.Cloud,
            contentDescription = stringResource(R.string.common_ai_providers),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.size(size)
        )
    }
}

/**
 * Render the brand logo icon from the model name; shows the default Cpu icon when unmatched.
 */
@Composable
fun ModelLogoIcon(
    modelName: String,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp
) {
    val key = modelBrandKey(modelName)
    val res = brandLogoRes(key)
    if (res != null) {
        Image(
            painter = painterResource(res),
            contentDescription = modelName,
            colorFilter = if (shouldTintModelLogo(key)) ColorFilter.tint(modelLogoTint()) else null,
            modifier = modifier.size(size)
        )
    } else {
        Icon(
            imageVector = FeatherIcons.Cpu,
            contentDescription = stringResource(R.string.provider_model_icon),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.size(size)
        )
    }
}
