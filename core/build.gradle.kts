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
val graalJsVersion = "24.2.1"

dependencies {
    api("io.ktor:ktor-client-core:$ktorVersion")
    api("io.ktor:ktor-client-cio:$ktorVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    api("com.google.code.gson:gson:$gsonVersion")

    // Embedded JS runtime for the .mjs automation engine (the rquickjs-parity
    // layer driven by "xencode:core" bindings). The 24.x layout: the
    // org.graalvm.polyglot:js-community POM is a Maven-only aggregator (Gradle
    // does not follow its <type>pom</type> deps), so the real artifacts are
    // declared directly. js-language pulls regex/truffle-api/icu4j, polyglot
    // brings the embedder API (Context/Value/ProxyExecutable).
    implementation("org.graalvm.polyglot:polyglot:$graalJsVersion")
    implementation("org.graalvm.js:js-language:$graalJsVersion")
    implementation("org.graalvm.truffle:truffle-runtime:$graalJsVersion")

    testImplementation(kotlin("test"))
    // MockEngine: SSE fixtures for ChatClient protocol tests without a network.
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks.test {
    useJUnitPlatform()
}
