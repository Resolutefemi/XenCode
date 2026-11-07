plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.21" apply false
    // Compose compiler for the desktop app (applied by :desktop).
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    // Compose Desktop (Windows/macOS/Linux); 1.9.x pairs with Kotlin 2.2.x per the CMP compatibility notes
    id("org.jetbrains.compose") version "1.9.0" apply false
}
