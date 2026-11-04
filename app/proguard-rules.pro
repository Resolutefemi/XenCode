# Keep rules for release builds (R8/ProGuard).
# Applies to this project: Hilt + Room + kotlinx.serialization + Retrofit/Gson + Compose + Termux terminal.

# ---- General: keep annotations, signatures, inner classes, generic signatures ----
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes SourceFile,LineNumberTable        # keep crash stack line numbers

# ---- Retrofit + OkHttp ----
# Retrofit reads API interface method annotations via reflection; the interfaces and signatures must be kept.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ---- BouncyCastle (bcprov-jdk18on) ----
# AIEditorApp.registerBouncyCastle() registers the BC provider at startup for sshj's X25519 key exchange.
# BouncyCastleProvider's constructor reflectively loads the *Mappings classes and algorithm SPIs under jcajce/jce.
# Keep the provider and SPI reflection packages precisely so R8 does not strip them and throw KeyStoreException: BKS not found,
# while still letting R8 trim the huge unrelated PQC (post-quantum) and OpenPGP modules to shrink the APK substantially.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.crypto.engines.** { *; }
-keep class org.bouncycastle.crypto.digests.** { *; }
-keep class org.bouncycastle.crypto.macs.** { *; }
-keep class org.bouncycastle.crypto.modes.** { *; }
-keep class org.bouncycastle.crypto.paddings.** { *; }
-dontwarn org.bouncycastle.**

# ---- Gson (Retrofit converter + ALEncoder logging via GsonBuilder reflective serialization) ----
# Retrofit/Gson read/write data class fields via reflection; class and field names must not be obfuscated or stripped.
#
# Caveat: `-keep class ...OpenAIModels*` does NOT work! OpenAIModels.kt is only the Kotlin file name;
# the real DTO classes inside are ChatCompletionRequest/OpenAIChatMessage/AnthropicMessageRequest etc.,
# none of them start with "OpenAIModels". ProGuard's `*` matches only class-name suffixes and will not keep every class in a file.
# If field names get obfuscated to a/b/c, the JSON keys Gson emits turn to gibberish -> server responds 400.
# So keep every DTO class and its fields under the whole remote package.
-keep class com.aicode.feature.agent.data.remote.** { *; }
# Any field carrying @SerializedName
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# Gson's TypeAdapter / InstanceCreator
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keep,allowobfuscation class com.google.gson.reflect.TypeToken { *; }
# Gson uses sun.misc.Unsafe (older versions) to create uninitialized instances; keep constructors to avoid crashes on some devices
-keepclassmembers class com.aicode.** {
    <init>();
}

# ---- kotlinx.serialization (generates $$serializer at compile time; looks up Companion.serializer() reflectively at runtime) ----
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Serializers for every @Serializable class in this project
-keep,includedescriptorclasses class com.aicode.**$$serializer { *; }
-keepclassmembers class com.aicode.** {
    *** Companion;
}
-keepclasseswithmembers class com.aicode.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- Hilt (Dagger) ----
-keep class dagger.hilt.** { *; }
-keep,allowobfuscation @dagger.hilt.android.HiltAndroidApp class *
-keep,allowobfuscation @dagger.hilt.android.lifecycle.HiltViewModel class *
-dontwarn dagger.hilt.**

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
-keep class com.aicode.**.entity.** { *; }   # Room @Entity column-name mappings must not be obfuscated

# ---- Compose ----
-dontwarn androidx.compose.**

# ---- Shizuku (UserService runs commands as the adb shell user) ----
# The UserService implementation is loaded reflectively by class name inside Shizuku's shell process; class names and constructors must survive.
-keep class com.aicode.feature.agent.domain.shizuku.ShizukuShellService { *; }
# AIDL interfaces and Stubs cross process boundaries; keep them unobfuscated.
-keep interface com.aicode.feature.agent.domain.shizuku.IShizukuShellService { *; }
-keep class com.aicode.feature.agent.domain.shizuku.IShizukuShellService$Stub { *; }
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# ---- Termux terminal-emulator/view（native + JNI）----
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }

# ---- Apache FtpServer / MINA (built-in FTP server) ----
# MINA's SimpleIoProcessorPool reflectively looks up (ExecutorService)/(Executor) constructors on IoProcessor implementations
# (NioProcessor only has public NioProcessor(Executor)). These constructors have no direct callers; once R8 removes them,
# enabling the built-in FTP server on release builds fails with "must have a public constructor with one ExecutorService parameter".
-keepclassmembers class * implements org.apache.mina.core.service.IoProcessor {
    public <init>(...);
}

# ---- DataStore / security-crypto (internal reflection) ----
-dontwarn androidx.security.**

# ---- Silence warnings about JDK classes missing on Android for SnakeYAML / EdDSA etc. ----
-dontwarn java.beans.**
-dontwarn sun.security.x509.**

# ---- jlatexmath (LaTeX formula rendering) ----
# jlatexmath's macro system invokes macro implementations reflectively at runtime: when MacroInfo is constructed it
# Class.forName("org.scilab.forge.jlatexmath.NewCommandMacro") +
# calls getDeclaredMethod("executeMacro", TeXParser, String[]). All macros defined via \newcommand / \newenvironment
# (including all \begin{...} environments) go through this path. R8's class merging collapses NewCommandMacro
# entirely (shown as R8$$REMOVED$$CLASS$$0 in the mapping), Class.forName then throws ClassNotFoundException,
# MacroInfo.macro stays null and Method.invoke NPEs at render time — on release builds every formula with \begin or custom macros
# renders blank (debug is fine since it is not obfuscated). Keep the package's class and method names so interface devirtualization and similar optimizations cannot break it again.
-keep class org.scilab.forge.jlatexmath.** { *; }

# ---- PrismalAGSL（Liquid Glass）----
# Two reasons for keeping it:
# 1) The AAR's AndroidManifest declares androidx.startup.InitializationProvider +
#    meta-data com.styropyr0.prismal.internal.PrismalAgslInitializer。
#    the initializer is referenced only from the manifest string; with no code reference visible, R8 removes/renames it,
#    and the startup-time reflective lookup by class name throws ClassNotFoundException (so AGSL shader resources never load).
# 2) The library leans on Compose lambda-based configuration (shape/effects/specular/depth are all
#    Function0/Function1) plus convention-driven implementations like PrismalShaderCache,
#    so keeping the package whole avoids gaps from piecemeal keep rules.
-keep class com.styropyr0.prismal.** { *; }
-keep class androidx.startup.** { *; }
