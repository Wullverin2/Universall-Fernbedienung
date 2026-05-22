package de.craftplay.samsungtvbridge.data

import android.content.Context
import java.io.File

class PersistentAppLogger(context: Context) {
    private val appContext = context.applicationContext
    private val internalLogFile = File(appContext.filesDir, LOG_FILE_NAME)
    private val externalLogFile: File?
        get() = appContext.getExternalFilesDir(null)?.let { File(it, LOG_FILE_NAME) }

    val adbReadCommand: String =
        "adb shell run-as de.craftplay.universalremote cat files/$LOG_FILE_NAME"

    val adbPullCommand: String =
        "adb pull /sdcard/Android/data/de.craftplay.universalremote/files/$LOG_FILE_NAME ."

    @Synchronized
    fun append(line: String) {
        val sanitized = sanitize(line)
        runCatching {
            internalLogFile.parentFile?.mkdirs()
            internalLogFile.appendText("$sanitized\n")
            trimIfNeeded(internalLogFile)
        }
        runCatching {
            externalLogFile?.let { file ->
                file.parentFile?.mkdirs()
                file.appendText("$sanitized\n")
                trimIfNeeded(file)
            }
        }
    }

    @Synchronized
    fun readRecentLines(maxLines: Int = 120): List<String> {
        return runCatching {
            if (!internalLogFile.exists()) return emptyList()
            internalLogFile.readLines()
                .takeLast(maxLines)
                .asReversed()
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun lineCount(): Int {
        return runCatching {
            if (!internalLogFile.exists()) 0 else internalLogFile.readLines().size
        }.getOrDefault(0)
    }

    @Synchronized
    fun clear() {
        runCatching { internalLogFile.writeText("") }
        runCatching { externalLogFile?.writeText("") }
    }

    @Synchronized
    fun fileDescription(): String {
        val size = runCatching { internalLogFile.length() }.getOrDefault(0L)
        val externalPath = externalLogFile?.absolutePath ?: "nicht verfuegbar"
        return buildString {
            appendLine("Interne Datei: files/$LOG_FILE_NAME")
            appendLine("Externe ADB-Datei: $externalPath")
            appendLine("Groesse: $size Bytes")
            appendLine("ADB lesen: $adbReadCommand")
            append("ADB kopieren: $adbPullCommand")
        }
    }

    private fun trimIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_BYTES) return
        val tail = file.readLines().takeLast(MAX_LINES)
        file.writeText(tail.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun sanitize(value: String): String {
        return value
            .replace(secretKeyValueRegex, "$1$2***")
            .replace(querySecretRegex, "$1=***")
    }

    companion object {
        const val LOG_FILE_NAME = "universal-remote-diagnostic.log"
        private const val MAX_BYTES = 512 * 1024
        private const val MAX_LINES = 1500
        private val secretKeyValueRegex =
            Regex("(?i)\\b(token|client-key|clientKey|client_key)([\\\"'=:\\s]+)([^\\s,\\\"}]+)")
        private val querySecretRegex =
            Regex("(?i)\\b(token|client-key|clientKey|client_key)=([^&\\s]+)")
    }
}
