import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

group = "com.xencode"

// The app derives its version from git tags (see app/build.gradle.kts gitVersionName); the CLI is
// versioned with the repo instead, so a plain constant keeps source and distro in sync.
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(project(":core"))
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.xencode.cli.MainKt")
    // Install as `xencode` / `xencode.bat` instead of the default project name.
    applicationName = "xencode"
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks.test {
    useJUnitPlatform()
}
