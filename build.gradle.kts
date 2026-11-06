// Plugin versions are declared in settings.gradle.kts (pluginManagement). Loading the Kotlin
// plugin once here keeps Gradle from loading a copy per module.
plugins {
    kotlin("jvm") apply false
    kotlin("plugin.serialization") apply false
}
