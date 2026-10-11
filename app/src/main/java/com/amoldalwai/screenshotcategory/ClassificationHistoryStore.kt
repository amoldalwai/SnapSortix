package com.amoldalwai.screenshotcategory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

const val CLASSIFICATION_HISTORY_FILE_NAME = "classification_history.json"
const val MAX_CLASSIFICATION_HISTORY_ENTRIES = 20
private const val HISTORY_READ_BUFFER_MULTIPLIER = 4

data class ClassificationHistoryEntry(
    val timestampMs: Long,
    val source: String,
    val status: String,
    val imageCount: Int,
    val successCount: Int,
    val failedCount: Int,
    val averageTimeMs: Long,
    val backend: String,
    val note: String
)

fun loadClassificationHistoryFromDisk(
    file: File,
    limit: Int = MAX_CLASSIFICATION_HISTORY_ENTRIES
): List<ClassificationHistoryEntry> {
    if (!file.exists()) return emptyList()
    return runCatching {
        val array = JSONArray(file.readText())
        val parsed = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    ClassificationHistoryEntry(
                        timestampMs = item.optLong("timestampMs", 0L),
                        source = item.optString("source").ifBlank { "Unknown" },
                        status = item.optString("status").ifBlank { "Unknown" },
                        imageCount = item.optInt("imageCount", 0).coerceAtLeast(0),
                        successCount = item.optInt("successCount", 0).coerceAtLeast(0),
                        failedCount = item.optInt("failedCount", 0).coerceAtLeast(0),
                        averageTimeMs = item.optLong("averageTimeMs", 0L).coerceAtLeast(0L),
                        backend = item.optString("backend").ifBlank { "n/a" },
                        note = item.optString("note").orEmpty()
                    )
                )
            }
        }
        parsed.sortedByDescending { it.timestampMs }.take(limit.coerceAtLeast(1))
    }.getOrElse { emptyList() }
}

fun appendClassificationHistory(
    context: Context,
    entry: ClassificationHistoryEntry,
    maxEntries: Int = MAX_CLASSIFICATION_HISTORY_ENTRIES,
    fileName: String = CLASSIFICATION_HISTORY_FILE_NAME
): Boolean {
    val targetFile = File(context.filesDir, fileName)
    val existing = loadClassificationHistoryFromDisk(
        targetFile,
        limit = maxEntries * HISTORY_READ_BUFFER_MULTIPLIER
    )
    val updated = listOf(entry) + existing
    val capped = updated.sortedByDescending { it.timestampMs }.take(maxEntries.coerceAtLeast(1))
    val array = JSONArray()
    capped.forEach { item ->
        array.put(
            JSONObject().apply {
                put("timestampMs", item.timestampMs)
                put("source", item.source)
                put("status", item.status)
                put("imageCount", item.imageCount)
                put("successCount", item.successCount)
                put("failedCount", item.failedCount)
                put("averageTimeMs", item.averageTimeMs)
                put("backend", item.backend)
                put("note", item.note)
            }
        )
    }
    return runCatching {
        targetFile.writeText(array.toString())
    }.isSuccess
}
