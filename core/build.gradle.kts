import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// Versions shared by the PC line (core/cli/desktop); kept in one place so bumps stay atomic.
val ktorVersion = "3.6.1"
val gsonVersion = "2.13.1"
val coroutinesVersion = "1.10.2"

dependencies {
    api("io.ktor:ktor-client-core:$ktorVersion")
    api("io.ktor:ktor-client-cio:$ktorVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    api("com.google.code.gson:gson:$gsonVersion")

    testImplementation(kotlin("test"))
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks.test {
    useJUnitPlatform()
}
