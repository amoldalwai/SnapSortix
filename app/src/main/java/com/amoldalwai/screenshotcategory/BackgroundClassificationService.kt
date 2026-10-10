package com.amoldalwai.screenshotcategory

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class BackgroundClassificationService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var runningJob: Job? = null
    private var classifier: GemmaClassifier? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runningJob?.cancel()
            updateNotification("Classification cancelled", "Stopped by user.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (runningJob?.isActive == true) {
            return START_STICKY
        }

        val requestedLimit = intent?.getIntExtra(EXTRA_LIMIT, Int.MAX_VALUE)?.coerceAtLeast(1) ?: Int.MAX_VALUE
        createNotificationChannel()
        isRunning = true
        progressSummary = "Preparing model..."
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                title = "Screenshot classification running",
                text = "Preparing model...",
                progressMax = 0,
                progressValue = 0,
                indeterminate = true
            )
        )

        runningJob = serviceScope.launch {
            runClassification(requestedLimit)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        runningJob?.cancel()
        classifier?.close()
        classifier = null
        isRunning = false
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun runClassification(limit: Int) {
        val modelDownload = ModelDownload(this)
        if (!modelDownload.isReady()) {
            updateNotification("Model missing", "Download Gemma model from app first.")
            stopSelf()
            return
        }

        val prefs = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        val categories = normalizeCategories(
            prefs.getString(CATEGORY_PREFERENCES_KEY, null)?.split("\n").orEmpty()
        )
        val customPrompt = prefs.getString(CUSTOM_PROMPT_PREFERENCES_KEY, "").orEmpty()
        val visionMode = VisionMode.entries.firstOrNull {
            it.name == prefs.getString(VISION_MODE_PREFERENCES_KEY, VisionMode.SuperFast.name)
        } ?: VisionMode.SuperFast

        val storeFile = File(filesDir, CLASSIFICATION_STORE_FILE_NAME)
        val existing = loadClassificationsFromDisk(storeFile).toMutableMap()

        val allScreenshots = ScreenshotMedia(this).loadRecentScreenshots()
        val pending = allScreenshots.filter { shot ->
            !existing.containsKey(UriKey(shot.uri.toString()).value)
        }.let { shots ->
            if (limit == Int.MAX_VALUE) shots else shots.take(limit)
        }

        if (pending.isEmpty()) {
            updateNotification("Nothing to classify", "All recent screenshots are already categorized.")
            stopSelf()
            return
        }

        progressTotal = pending.size
        progressDone = 0
        activeVisionMode = visionMode.label
        activeTokenBudget = visionMode.visualTokenBudget
        activeResizeLongSidePx = visionMode.maxImageLongSide
        runtimeBackend = "Initializing..."
        averageDecodeMs = 0L
        averageInferenceMs = 0L
        averageSaveMs = 0L
        dominantStage = "Calculating..."
        optimizationHint = "Collecting telemetry..."

        val activeClassifier = classifier ?: GemmaClassifier(this, modelDownload.modelFile).also {
            classifier = it
            it.warmUp()
        }

        var done = 0
        var failed = 0
        var attempted = 0
        var totalDecodeMs = 0L
        var totalInferenceMs = 0L
        var totalSaveMs = 0L
        val startEpochMs = System.currentTimeMillis()
        try {
            pending.forEach { screenshot ->
                currentCoroutineContext().ensureActive()
                attempted += 1
                val avgSeconds = if (attempted > 1) {
                    (System.currentTimeMillis() - startEpochMs).toDouble() / 1000.0 / (attempted - 1).toDouble()
                } else {
                    0.0
                }
                val title = "Classifying screenshots"
                val text = "${attempted}/${pending.size} | avg ${"%.1f".format(avgSeconds)}s/img"
                updateNotification(title, text, pending.size, attempted - 1, indeterminate = false)

                runCatching {
                    activeClassifier.classifyWithMetrics(
                        uri = screenshot.uri,
                        categories = categories,
                        customPrompt = customPrompt,
                        visionMode = visionMode
                    )
                }.onSuccess { result ->
                    totalDecodeMs += result.decodeDownscaleMs
                    totalInferenceMs += result.inferenceMs
                    runtimeBackend = result.runtimeProfileLabel
                    val savedEntry = JSONObject().apply {
                        put("imageId", screenshot.imageId)
                        put("imageUri", screenshot.uri.toString())
                        put("category", result.classification.category)
                        put("confidence", result.classification.confidence)
                        put("description", result.classification.description)
                        put("updatedAt", System.currentTimeMillis())
                    }
                    existing[UriKey(screenshot.uri.toString()).value] = savedEntry
                    val saveStart = System.currentTimeMillis()
                    done += 1
                    saveClassificationsToDisk(storeFile, existing.values)
                    val saveMs = (System.currentTimeMillis() - saveStart).coerceAtLeast(0L)
                    totalSaveMs += saveMs

                    averageDecodeMs = (totalDecodeMs / done).coerceAtLeast(0L)
                    averageInferenceMs = (totalInferenceMs / done).coerceAtLeast(0L)
                    averageSaveMs = (totalSaveMs / done).coerceAtLeast(0L)
                    val dominant = resolveDominantStage(averageDecodeMs, averageInferenceMs, averageSaveMs)
                    dominantStage = dominant
                    optimizationHint = when (dominant) {
                        "Model inference" -> "Inference dominates: lower token budget or use Super Fast for speed."
                        "Decode/downscale" -> "Preprocess dominates: reduce resize target/compression cost."
                        else -> "Save dominates: optimize disk writes or batch persistence."
                    }
                }.onFailure {
                    failed += 1
                }

                progressDone = (done + failed).coerceAtMost(progressTotal)
                val summary = buildString {
                    append("${progressDone}/${progressTotal} | avg ${"%.1f".format(avgSeconds)}s/img")
                    if (done > 0) {
                        append(" | dec ${averageDecodeMs}ms inf ${averageInferenceMs}ms save ${averageSaveMs}ms")
                    }
                }
                updateNotification(
                    title = "Classifying screenshots",
                    text = summary,
                    progressMax = progressTotal,
                    progressValue = progressDone,
                    indeterminate = false
                )
            }

            val resultText = if (failed == 0) {
                "Completed: $done/${pending.size} screenshots | avg ${formatAverageSeconds(startEpochMs, pending.size)}s/img"
            } else {
                "Completed: $done success, $failed failed | avg ${formatAverageSeconds(startEpochMs, pending.size)}s/img"
            }
            progressDone = progressTotal
            updateNotification("Background classification complete", resultText)
            stopSelf()
        } finally {
            activeClassifier.close()
            classifier = null
        }
    }

    private fun loadClassificationsFromDisk(file: File): Map<String, JSONObject> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            val array = JSONArray(file.readText())
            buildMap {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val imageUri = item.optString("imageUri").trim()
                    if (imageUri.isBlank()) continue
                    put(imageUri, item)
                }
            }
        }.getOrElse { emptyMap() }
    }

    private fun saveClassificationsToDisk(file: File, values: Collection<JSONObject>) {
        val array = JSONArray()
        values.forEach { array.put(it) }
        runCatching { file.writeText(array.toString()) }
    }

    private fun updateNotification(
        title: String,
        text: String,
        progressMax: Int = 0,
        progressValue: Int = 0,
        indeterminate: Boolean = false
    ) {
        progressSummary = "$title - $text"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(title, text, progressMax, progressValue, indeterminate)
        )
    }

    private fun buildNotification(
        title: String,
        text: String,
        progressMax: Int = 0,
        progressValue: Int = 0,
        indeterminate: Boolean = false
    ): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val launchPendingIntent = PendingIntent.getActivity(
            this,
            1001,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, BackgroundClassificationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1002,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        appendLine(text)
                        if (progressDone > 0) {
                            appendLine("decode/downscale avg: ${averageDecodeMs} ms")
                            appendLine("model inference avg: ${averageInferenceMs} ms")
                            appendLine("write/save avg: ${averageSaveMs} ms")
                        }
                        appendLine("runtime: $runtimeBackend")
                        appendLine("mode: $activeVisionMode (budget $activeTokenBudget, resize ${activeResizeLongSidePx}px)")
                    }
                )
            )
            .setContentIntent(launchPendingIntent)
            .setOnlyAlertOnce(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)

        if (progressMax > 0) {
            builder.setProgress(progressMax, progressValue.coerceAtMost(progressMax), indeterminate)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screenshot Classification",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Shows progress for background screenshot classification"
        }
        manager.createNotificationChannel(channel)
    }

    private fun formatAverageSeconds(startEpochMs: Long, totalAttempted: Int): String {
        if (totalAttempted <= 0) return "0.0"
        val average = (System.currentTimeMillis() - startEpochMs).toDouble() / 1000.0 / totalAttempted.toDouble()
        return "%.1f".format(average)
    }

    private fun resolveDominantStage(decodeMs: Long, inferenceMs: Long, saveMs: Long): String {
        return when {
            inferenceMs >= decodeMs && inferenceMs >= saveMs -> "Model inference"
            decodeMs >= inferenceMs && decodeMs >= saveMs -> "Decode/downscale"
            else -> "Write/save"
        }
    }

    private fun normalizeCategories(categories: List<String>): List<String> =
        categories
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
            .ifEmpty { DEFAULT_CATEGORIES }

    companion object {
        private const val CHANNEL_ID = "screenshot_classification_channel"
        private const val NOTIFICATION_ID = 1107
        private const val EXTRA_LIMIT = "extra_limit"
        private const val ACTION_STOP = "com.amoldalwai.screenshotcategory.action.STOP_CLASSIFICATION"

        private const val PREFERENCES_NAME = "screenshot_sorter"
        private const val CATEGORY_PREFERENCES_KEY = "categories"
        private const val CUSTOM_PROMPT_PREFERENCES_KEY = "custom_prompt"
        private const val VISION_MODE_PREFERENCES_KEY = "vision_mode"
        private const val CLASSIFICATION_STORE_FILE_NAME = "classification_index.json"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var progressSummary: String = ""
            private set

        @Volatile
        var progressDone: Int = 0
            private set

        @Volatile
        var progressTotal: Int = 0
            private set

        @Volatile
        var averageDecodeMs: Long = 0
            private set

        @Volatile
        var averageInferenceMs: Long = 0
            private set

        @Volatile
        var averageSaveMs: Long = 0
            private set

        @Volatile
        var runtimeBackend: String = ""
            private set

        @Volatile
        var activeVisionMode: String = ""
            private set

        @Volatile
        var activeTokenBudget: Int = 0
            private set

        @Volatile
        var activeResizeLongSidePx: Int = 0
            private set

        @Volatile
        var dominantStage: String = ""
            private set

        @Volatile
        var optimizationHint: String = ""
            private set

        private val DEFAULT_CATEGORIES = listOf(
            "LinkedIn",
            "Quotes/Learning",
            "Food",
            "Movie",
            "Tickets/Bookings",
            "OTP/Verification Codes",
            "Payment/Finance/Bills",
            "Identity cards",
            "QR",
            "Comment Section (YouTube / Instagram)",
            "Nature/Plants",
            "Only people no text",
            "Games (BGMI etc)",
            "Maps",
            "Greeting Messages (Good morning etc)",
            "Animals",
            "Others"
        )

        fun start(context: Context, limit: Int = Int.MAX_VALUE) {
            val intent = Intent(context, BackgroundClassificationService::class.java)
                .putExtra(EXTRA_LIMIT, limit)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val stopIntent = Intent(context, BackgroundClassificationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(stopIntent)
        }
    }
}
