package com.amoldalwai.screenshotcategory

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class OcrResult(
    val text: String,
    val status: String,
    val durationMs: Long
)

class MlKitOcrEngine(private val context: Context) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun extractText(uri: Uri): OcrResult {
        val startedAt = System.currentTimeMillis()
        return runCatching {
            val image = InputImage.fromFilePath(context, uri)
            val visionText = Tasks.await(recognizer.process(image))
            val normalized = visionText.text.orEmpty().trim()
            OcrResult(
                text = normalized.take(MAX_OCR_TEXT_LENGTH),
                status = if (normalized.isBlank()) "no_text" else "success",
                durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            )
        }.getOrElse {
            OcrResult(
                text = "",
                status = "failed",
                durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            )
        }
    }

    fun close() {
        recognizer.close()
    }

    companion object {
        private const val MAX_OCR_TEXT_LENGTH = 4000
    }
}
