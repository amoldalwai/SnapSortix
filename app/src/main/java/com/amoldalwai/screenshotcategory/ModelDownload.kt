package com.amoldalwai.screenshotcategory

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

class ModelDownload(private val context: Context) {
    val modelFile: File
        get() = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), MODEL_FILE_NAME)

    fun isReady(): Boolean = modelFile.exists() && modelFile.length() > MINIMUM_MODEL_BYTES

    fun enqueue(): Long {
        modelFile.parentFile?.mkdirs()
        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("Downloading Gemma 4 E2B")
            .setDescription("The model remains on this device for offline classification.")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(false)
            .setDestinationUri(Uri.fromFile(modelFile))
        return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }

    companion object {
        private const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        private const val MINIMUM_MODEL_BYTES = 2_000_000_000L
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    }
}