plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun pickUrl(fromGradle: String?, fromEnv: String?, default: String): String =
    listOf(fromGradle, fromEnv).firstOrNull { !it.isNullOrBlank() }?.trim() ?: default

// API #1 (Autopilot) dan API #2 (cadangan + ngobrol Skylen). Nilai kosong dari CI tidak menimpa bawaan.
val galaxyAiApiUrl = pickUrl(
    providers.gradleProperty("galaxyAiApiUrl").orNull,
    System.getenv("GALAXY_AI_API_URL"),
    "https://api.kyio.web.id/api/v2/ai/gpt-5?q="
)
val escapedAiApiUrl = galaxyAiApiUrl.replace("\\", "\\\\").replace("\"", "\\\"")

val galaxyAiApiUrl2 = pickUrl(
    providers.gradleProperty("galaxyAiApiUrl2").orNull,
    System.getenv("GALAXY_AI_API_URL_2"),
    "https://api.kyio.web.id/api/v2/ai/gpt-6-luna?q="
)
val galaxyVisionApiUrl = pickUrl(
    providers.gradleProperty("galaxyVisionApiUrl").orNull,
    System.getenv("GALAXY_VISION_API_URL"),
    "https://api.kyio.web.id/api/v2/ai/ai?q={q}&sessionId={sid}&url={url}"
)
val escapedVisionApiUrl = galaxyVisionApiUrl.replace("\\", "\\\\").replace("\"", "\\\"")

val escapedAiApiUrl2 = galaxyAiApiUrl2.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.imzz.galaxyassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.imzz.galaxyassistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.6.0"
        buildConfigField("String", "AI_API_URL", "\"$escapedAiApiUrl\"")
        buildConfigField("String", "AI_API_URL_2", "\"$escapedAiApiUrl2\"")
        buildConfigField("String", "AI_VISION_URL", "\"$escapedVisionApiUrl\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
