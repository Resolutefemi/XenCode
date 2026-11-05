package com.xencode.feature.editor.domain

import android.content.Context
import io.github.rosemoe.sora.lang.analysis.AsyncIncrementalAnalyzeManager
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import org.eclipse.tm4e.core.registry.IThemeSource

/**
 * One-time initialization of TextMate grammars and themes.
 *
 * The grammar bundle is nearly 1 MB and [GrammarRegistry.loadGrammars] parses it in full, so it must
 * run on an IO thread, not in Application.onCreate or the main thread, or it slows cold startup / stalls the first frame.
 */
object TextMateSetup {

    const val THEME_DARK = "dark_plus"
    const val THEME_LIGHT = "light_plus"

    @Volatile
    private var initialized = false

    /** Idempotent: called every time the editor screen opens, but only the first call actually loads. */
    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            // TextMate analysis runs sequentially from the first line to the last (each line depends on the previous one); a full pass over a large file can take seconds.
            // With this switch on, the styling obtained so far is pushed to the editor every 1000 analyzed lines, so the first screen need not wait for full analysis.
            AsyncIncrementalAnalyzeManager.setUpdateStylesDuringAnalysis(true)
            FileProviderRegistry.getInstance()
                .addFileProvider(AssetsFileResolver(context.applicationContext.assets))
            loadThemes()
            GrammarRegistry.getInstance().loadGrammars(ASSET_LANGUAGES)
            initialized = true
        }
    }

    private fun loadThemes() {
        val registry = ThemeRegistry.getInstance()
        listOf(THEME_DARK to true, THEME_LIGHT to false).forEach { (name, dark) ->
            val path = "textmate/$name.json"
            val source = IThemeSource.fromInputStream(
                FileProviderRegistry.getInstance().tryGetInputStream(path),
                path,
                null
            )
            registry.loadTheme(ThemeModel(source, name).apply { isDark = dark })
        }
    }

    /** Switches the current grammar theme; must match the Compose theme's light/dark mode, otherwise token colors clash with the background. */
    fun applyTheme(dark: Boolean) {
        ThemeRegistry.getInstance().setTheme(if (dark) THEME_DARK else THEME_LIGHT)
    }

    /**
     * Returns the TextMate scopeName, covering only grammars actually bundled in assets/textmate/languages.json.
     * Returns null for no highlighting, i.e. plain text display.
     *
     * Must be called after [ensureInitialized]: the extension map and languages.json are two separate
     * lists, so grammars not bundled must be filtered out here - TextMateLanguage.create throws
     * IllegalArgumentException for an unregistered scope, crashing right in editor construction.
     */
    fun scopeNameFor(path: String): String? {
        val fileName = path.substringAfterLast('/')
        val scope = scopeByExtension(fileName.substringAfterLast('.', "").lowercase()) ?: return null
        return scope.takeIf { GrammarRegistry.getInstance().findGrammar(it) != null }
    }

    private fun scopeByExtension(ext: String): String? = when (ext) {
        "kt", "kts" -> "source.kotlin"
        "java" -> "source.java"
        "py", "pyw", "pyi" -> "source.python"
        "js", "mjs", "cjs", "jsx" -> "source.js"
        "ts", "mts", "cts" -> "source.ts"
        "tsx" -> "source.tsx"
        "json" -> "source.json"
        "jsonc", "json5" -> "source.json.comments"
        "yaml", "yml" -> "source.yaml"
        "sh", "bash", "zsh", "ksh", "bashrc", "zshrc", "profile" -> "source.shell"
        "go" -> "source.go"
        "rs" -> "source.rust"
        "c", "h" -> "source.c"
        "cpp", "cc", "cxx", "hpp", "hh", "hxx" -> "source.cpp"
        "css" -> "source.css"
        "php" -> "source.php"
        "lua" -> "source.lua"
        "xml" -> "text.xml"
        "html", "htm" -> "text.html.basic"
        "md", "markdown" -> "text.html.markdown"
        else -> null
    }

    private const val ASSET_LANGUAGES = "textmate/languages.json"
}
