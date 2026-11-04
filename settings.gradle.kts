pluginManagement {
    repositories {
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Local mirror for PrismalAGSL (Liquid Glass): the AAR is vendored under <root>/prismalrepo,
        // with coordinates identical to JitPack's (com.github.styropyr0:PrismalAGSL:v1.0.4),
        // so we do not depend on JitPack's just-in-time builds. See the header comment in prismalrepo/.../PrismalAGSL-v1.0.4.pom.
        maven { url = uri(File(settingsDir, "prismalrepo")) }
        // Termux terminal components (terminal-emulator / terminal-view) are published only on JitPack; the Tencent mirror does not carry them,
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
    }
}
rootProject.name = "app"

include(":app")
include(":terminal-emulator")
include(":terminal-view")

