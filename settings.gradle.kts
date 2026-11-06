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

// The Android module needs the Android Gradle Plugin and an SDK. It is only wired in when an
// SDK location is known, so `gradlew :desktop:run` and `gradlew :core:test` work on a machine
// (or CI box) without Android Studio.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (hasAndroidSdk) {
    include(":android")
}
