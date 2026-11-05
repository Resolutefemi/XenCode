import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// XenCode Compose Desktop app (Windows / macOS / Linux). All shared logic lives in
// :core (provider transport, agent tool loop, goal DAG, XP ledger); this module is
// only the Compose UI shell + wiring. Versions for the plugins applied here are
// declared once in the root build.gradle.kts (Kotlin 2.2.21 / Compose 1.9.0).

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // Shared PC-line core: ChatClient, AgentSession, LocalTools, GoalDag(Engine/File), XpLedger, PcConfigStore.
    implementation(project(":core"))

    // Compose Desktop for the current OS: runtime + foundation + ui + the desktop
    // Window API (package androidx.compose.ui.window since Compose 1.9).
    // NOTE: the accessor is `currentOs` — `compose.desktop.currentCompose` does not
    // exist in the Compose Multiplatform Gradle plugin (verified against 1.9.0:
    // ComposePlugin.DesktopDependencies exposes currentOs / linux_x64 / macos_x64 ...).
    implementation(compose.desktop.currentOs)

    // Material 3 (darkColorScheme, NavigationRail, OutlinedTextField, progress
    // indicators, DropdownMenu, ...). Not transitive via compose.desktop.currentOs:
    // desktop-jvm only pulls foundation + material(M2) + runtime + ui, so this line
    // is required for every androidx.compose.material3 import in the panes.
    // The 1.9.0 Gradle plugin resolves this accessor to material3 1.8.2, which is
    // the intended pairing for Compose 1.9.
    implementation(compose.material3)

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.xencode.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)

            packageName = "XenCode"
            packageVersion = "1.0.0"
            description = "XenCode — AI coding agent and mobile-class workstation for the desktop"
            vendor = "XenCode"

            linux {
                iconFile = file("icons/xencode.png")
            }
            macOS {
                iconFile = file("icons/xencode.icns")
            }
            windows {
                iconFile = file("icons/xencode.ico")
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
