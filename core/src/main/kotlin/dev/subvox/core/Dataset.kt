package dev.subvox.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The training set. JSON layout matches the macOS app's `Dataset`. */
@Serializable
data class Dataset(
    val vocabulary: List<String>,
    val templates: List<Template>,
) {
    fun templates(word: String): List<Template> = templates.filter { it.label == word }

    companion object {
        val starter = Dataset(listOf("yes", "no", "next", "back", "stop"), emptyList())
    }
}

/** Reads and writes `dataset.json`, atomically, at a location the platform chooses. */
class TemplateStore(val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): Dataset {
        if (!file.isFile) return Dataset.starter
        return try {
            json.decodeFromString<Dataset>(file.readText())
        } catch (e: Exception) {
            Dataset.starter
        }
    }

    fun save(dataset: Dataset) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(dataset))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
