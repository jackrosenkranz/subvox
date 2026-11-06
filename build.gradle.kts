// Plugin versions are declared in settings.gradle.kts (pluginManagement). Loading the Kotlin
// plugin once here keeps Gradle from loading a copy per module.
//
// When the Android module is included, the Android Gradle Plugin has to sit on this same
// classpath: the Kotlin Android plugin resolves AGP classes through the classloader it was
// loaded in, and a plugin loaded only by the android module cannot be seen from here.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        if (gradle.extra["subvoxAndroid"] == true) {
            classpath("com.android.tools.build:gradle:8.7.3")
        }
    }
}

plugins {
    kotlin("jvm") apply false
    kotlin("plugin.serialization") apply false
}
