buildscript {
    repositories {
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
    }
    dependencies {
        // Overrides the R8 8.9.x bundled with AGP 8.9.3. Kotlin 2.2 officially requires at least R8 8.10.21, but that version
        // still throws harmless
        // "Should never be called" warnings for every class while rewriting Kotlin 2.2.21 stdlib metadata
        // (an overlay on core-library desugaring per class), flooding the log (2000+ WARNINGs, up to 40 MB).
        // Using 8.13.19 (the official Kotlin 2.3 pick; AGP 8.9.3 is within its 8.2.2-8.13 compat range and it stays backward compatible with 2.2 classes) removes the noise.
        // Must live in the root project buildscript: putting it in settings.gradle.kts' buildscript only affects the settings
        // script's own classpath, while AGP keeps using its bundled version — verified no-op in practice.
        classpath("com.android.tools:r8:8.13.19")
    }
}

plugins {
    id("com.android.application") version "8.9.3" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("com.google.dagger.hilt.android") version "2.56.1" apply false
    id("com.google.devtools.ksp") version "2.2.21-2.0.5" apply false
}
