package dev.subvox.desktop

import java.awt.Desktop
import java.io.File

/** File locations and the clock, chosen per operating system. */
object Platform {
    val os: String = System.getProperty("os.name").lowercase()
    val isWindows = os.contains("win")
    val isMac = os.contains("mac")

    val home: File = File(System.getProperty("user.home"))

    /** Monotonic seconds; every motion and audio timestamp in the desktop app uses this clock. */
    fun clock(): Double = System.nanoTime() / 1e9

    /**
     * Application data. On macOS this is the same folder the SwiftUI app uses, so both apps read
     * the same `dataset.json`.
     */
    val dataDir: File = when {
        isWindows -> File(System.getenv("APPDATA") ?: File(home, "AppData/Roaming").path, "Subvox")
        isMac -> File(home, "Library/Application Support/Subvox")
        else -> File(System.getenv("XDG_DATA_HOME") ?: File(home, ".local/share").path, "Subvox")
    }

    val datasetFile: File get() = File(dataDir, "dataset.json")
    val voskModelDir: File get() = System.getenv("SUBVOX_VOSK_MODEL")?.let(::File) ?: File(dataDir, "vosk-model")
    val sessionsDir: File get() = File(home, "Documents/Subvox/Sessions")

    fun reveal(file: File) {
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(if (file.isDirectory) file else file.parentFile)
        } catch (e: Exception) {
            System.err.println("Subvox: cannot open ${file.path}: ${e.message}")
        }
    }
}
