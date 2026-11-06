pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    // Versions live here so a module declares `kotlin("jvm")` without repeating them. Nothing
    // is downloaded until a module applies the plugin, which keeps the Android toolchain out of
    // a desktop-only or core-only build.
    plugins {
        kotlin("jvm") version "2.1.21"
        kotlin("android") version "2.1.21"
        kotlin("plugin.serialization") version "2.1.21"
        kotlin("plugin.compose") version "2.1.21"
        id("com.android.application") version "8.7.3"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "subvox"

include(":core", ":desktop")

// The Android module needs the Android Gradle Plugin and an SDK. SUBVOX_ANDROID=on or off
// decides; unset, it is included when an SDK location is known. So `gradlew :desktop:run` and
// `gradlew :core:test` work on a machine without Android Studio, and CI can pin either way.
val androidSwitch = System.getenv("SUBVOX_ANDROID")?.lowercase()
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
val includeAndroid = when (androidSwitch) {
    "on", "true", "1" -> true
    "off", "false", "0" -> false
    else -> hasAndroidSdk
}
gradle.extra["subvoxAndroid"] = includeAndroid
if (includeAndroid) {
    include(":android")
}
