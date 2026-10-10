package com.amoldalwai.screenshotcategory

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.provider.MediaStore
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class BackgroundClassificationService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var batchJob: Job? = null
    private var queueJob: Job? = null
    private var debounceJob: Job? = null
    private var fallbackScanJob: Job? = null
    private var classifier: GemmaClassifier? = null
    private var mediaObserver: ContentObserver? = null
    private val queuedScreenshots = ArrayDeque<Screenshot>()
    private val queuedUris = HashSet<String>()
    private data class ScreenshotScanItem(
        val screenshot: Screenshot,
        val dateAddedSeconds: Long,
        val mediaId: Long
    )

    private data class ScanWatermark(
        val dateAddedSeconds: Long,
        val mediaId: Long
    )

    private val retryAttemptsByUri = HashMap<String, Int>()
    private var watcherEnabled = false
    private var watcherForegroundVisible = false
    private var storedClassifications: MutableMap<String, JSONObject> = mutableMapOf()
    private var currentWatermark = ScanWatermark(0L, 0L)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAllWork()
            updateNotification("Classification cancelled", "Stopped by user.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START_WATCH) {
            startWatcherMode()
            return START_STICKY
        }

        val requestedLimit = intent?.getIntExtra(EXTRA_LIMIT, Int.MAX_VALUE)?.coerceAtLeast(1) ?: Int.MAX_VALUE

        if (watcherEnabled) {
            serviceScope.launch {
                enqueueUnclassifiedRecent(limit = requestedLimit, notifyWhenIdle = true)
            }
            return START_STICKY
        }

        if (batchJob?.isActive == true) {
            return START_STICKY
        }

        createNotificationChannel()
        isRunning = true
        isBatchRunning = true
        isWatcherRunning = false
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

        batchJob = serviceScope.launch {
            runClassification(requestedLimit)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopAllWork()
        unregisterScreenshotObserver()
        classifier?.close()
        classifier = null
        isRunning = false
        isBatchRunning = false
        isWatcherRunning = false
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun stopAllWork() {
        batchJob?.cancel()
        queueJob?.cancel()
        debounceJob?.cancel()
        fallbackScanJob?.cancel()
        queuedScreenshots.clear()
        queuedUris.clear()
        retryAttemptsByUri.clear()
        queuedCount = 0
        watcherEnabled = false
        isWatcherRunning = false
        isBatchRunning = false
        isRunning = false
    }

    private fun startWatcherMode() {
        if (watcherEnabled) {
            if (queuedScreenshots.isNotEmpty()) {
                showWatcherForeground(
                    title = "Classifying screenshots",
                    text = "Queue: ${queuedScreenshots.size} pending",
                    progressMax = queuedScreenshots.size.coerceAtLeast(1),
                    progressValue = 0,
                    indeterminate = false
                )
            }
            return
        }

        createNotificationChannel()
        watcherEnabled = true
        isWatcherRunning = true
        isBatchRunning = false
        isRunning = true
        queuedCount = 0
        progressDone = 0
        progressTotal = 0
        progressSummary = "Watching screenshot folder..."

        // Foreground handshake is required when service is started as foreground.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                title = "Auto detect enabled",
                text = "Monitoring screenshots",
                progressMax = 0,
                progressValue = 0,
                indeterminate = false
            )
        )
        watcherForegroundVisible = true

        val storeFile = File(filesDir, CLASSIFICATION_STORE_FILE_NAME)
        storedClassifications = loadClassificationsFromDisk(storeFile).toMutableMap()
        currentWatermark = loadWatcherWatermark()
        Log.i(TAG, "Watcher started. existingClassifications=${storedClassifications.size}")
        seedWatermarkFromRecentMedia()
        registerScreenshotObserver()
        serviceScope.launch {
            // Catch screenshots that land before observer callbacks are delivered.
            enqueueUnclassifiedRecent(limit = 80, notifyWhenIdle = false)
        }
        startFallbackScanLoop()
        showWatcherForeground(
            title = "Auto detect enabled",
            text = "Monitoring screenshots in background",
            progressMax = 0,
            progressValue = 0,
            indeterminate = false
        )
    }

    private fun startFallbackScanLoop() {
        fallbackScanJob?.cancel()
        fallbackScanJob = serviceScope.launch {
            while (watcherEnabled) {
                delay(WATCHER_FALLBACK_SCAN_MS)
                enqueueUnclassifiedRecent(limit = 30, notifyWhenIdle = false)
            }
        }
    }

    private fun seedWatermarkFromRecentMedia() {
        val latest = loadRecentScreenshotsForWatcher(limit = 1)
        if (latest.isNotEmpty()) {
            val top = latest.first()
            if (isAfterWatermark(top, currentWatermark)) {
                currentWatermark = ScanWatermark(top.dateAddedSeconds, top.mediaId)
                persistWatcherWatermark(currentWatermark)
            }
        }
        Log.d(
            TAG,
            "Seeded watcher watermark date=${currentWatermark.dateAddedSeconds}, mediaId=${currentWatermark.mediaId}"
        )
    }

    private fun registerScreenshotObserver() {
        if (mediaObserver != null) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                scheduleRecentScan()
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )
        mediaObserver = observer
    }

    private fun unregisterScreenshotObserver() {
        mediaObserver?.let { observer ->
            runCatching { contentResolver.unregisterContentObserver(observer) }
        }
        mediaObserver = null
    }

    private fun scheduleRecentScan() {
        if (!watcherEnabled) return
        debounceJob?.cancel()
        debounceJob = serviceScope.launch {
            delay(900)
            enqueueUnclassifiedRecent(limit = 40, notifyWhenIdle = true)
        }
    }

    private suspend fun enqueueUnclassifiedRecent(limit: Int, notifyWhenIdle: Boolean) {
        if (!watcherEnabled) return
        if (storedClassifications.isEmpty()) {
            val storeFile = File(filesDir, CLASSIFICATION_STORE_FILE_NAME)
            storedClassifications = loadClassificationsFromDisk(storeFile).toMutableMap()
        }

        val latest = loadRecentScreenshotsForWatcher(limit = limit.coerceAtLeast(1))
        var newQueued = 0
        var skippedAlreadyClassified = 0
        var maxSeenThisPass = currentWatermark
        latest.asReversed().forEach { item ->
            if (!isAfterWatermark(item, currentWatermark)) return@forEach
            if (isAfterWatermark(item, maxSeenThisPass)) {
                maxSeenThisPass = ScanWatermark(item.dateAddedSeconds, item.mediaId)
            }

            val shot = item.screenshot
            val uriValue = shot.uri.toString()
            if (storedClassifications.containsKey(canonicalizeMediaStoreUriKey(uriValue))) {
                skippedAlreadyClassified += 1
                return@forEach
            }
            if (queuedUris.add(uriValue)) {
                queuedScreenshots.addLast(shot)
                newQueued += 1
            }
        }
        if (isAfterWatermark(maxSeenThisPass, currentWatermark)) {
            currentWatermark = maxSeenThisPass
            persistWatcherWatermark(currentWatermark)
        }
        Log.d(
            TAG,
            "Watcher scan: latest=${latest.size}, newQueued=$newQueued, skippedClassified=$skippedAlreadyClassified, queue=${queuedScreenshots.size}, watermarkDate=${currentWatermark.dateAddedSeconds}, watermarkId=${currentWatermark.mediaId}"
        )

        queuedCount = queuedScreenshots.size
        if (newQueued > 0) {
            val total = queuedScreenshots.size
            showWatcherForeground(
                title = "Classifying screenshots",
                text = "Queued $newQueued new screenshot(s) | 0/$total",
                progressMax = total.coerceAtLeast(1),
                progressValue = 0,
                indeterminate = false
            )
            ensureQueueProcessorRunning()
        } else if (queuedScreenshots.isNotEmpty()) {
            showWatcherForeground(
                title = "Classifying screenshots",
                text = "Queue: ${queuedScreenshots.size} pending",
                progressMax = queuedScreenshots.size.coerceAtLeast(1),
                progressValue = 0,
                indeterminate = false
            )
        } else if (notifyWhenIdle) {
            showWatcherForeground(
                title = "Auto detect enabled",
                text = "Monitoring screenshots in background",
                progressMax = 0,
                progressValue = 0,
                indeterminate = false
            )
        }
    }

    private fun isAfterWatermark(item: ScreenshotScanItem, watermark: ScanWatermark): Boolean {
        return item.dateAddedSeconds > watermark.dateAddedSeconds ||
            (item.dateAddedSeconds == watermark.dateAddedSeconds && item.mediaId > watermark.mediaId)
    }

    private fun isAfterWatermark(candidate: ScanWatermark, watermark: ScanWatermark): Boolean {
        return candidate.dateAddedSeconds > watermark.dateAddedSeconds ||
            (candidate.dateAddedSeconds == watermark.dateAddedSeconds && candidate.mediaId > watermark.mediaId)
    }

    private fun loadWatcherWatermark(): ScanWatermark {
        val prefs = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        val date = prefs.getLong(WATCHER_LAST_DATE_KEY, 0L)
        val mediaId = prefs.getLong(WATCHER_LAST_MEDIA_ID_KEY, 0L)
        return ScanWatermark(date, mediaId)
    }

    private fun persistWatcherWatermark(watermark: ScanWatermark) {
        val prefs = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        prefs.edit()
            .putLong(WATCHER_LAST_DATE_KEY, watermark.dateAddedSeconds)
            .putLong(WATCHER_LAST_MEDIA_ID_KEY, watermark.mediaId)
            .apply()
    }

    private fun loadRecentScreenshotsForWatcher(limit: Int): List<ScreenshotScanItem> {
        val safeLimit = limit.coerceIn(1, 200)
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        val selection = "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR " +
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ? OR " +
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?)"
        val selectionArgs = arrayOf("%Screenshot%", "%Screenshot%", "Screenshot%")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"

        return runCatching {
            buildList {
                val seen = HashSet<String>()
                contentResolver.query(
                    collection,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                    while (cursor.moveToNext()) {
                        if (size >= safeLimit) break
                        val id = cursor.getLong(idIndex)
                        val dateAdded = cursor.getLong(dateIndex)
                        val uri = ContentUris.withAppendedId(collection, id)
                        val key = uri.toString()
                        if (!seen.add(key)) continue
                        add(
                            ScreenshotScanItem(
                                screenshot = Screenshot(
                                    imageId = id.toString(),
                                    uri = uri,
                                    displayName = cursor.getString(nameIndex),
                                    relativePath = cursor.getString(pathIndex)
                                ),
                                dateAddedSeconds = dateAdded,
                                mediaId = id
                            )
                        )
                    }
                }
            }
        }.onFailure {
            Log.w(TAG, "Watcher direct media query failed; falling back to shared loader", it)
        }.getOrElse {
            ScreenshotMedia(this).loadRecentScreenshots(maxCount = safeLimit).map { shot ->
                val mediaId = shot.imageId.toLongOrNull() ?: 0L
                ScreenshotScanItem(
                    screenshot = shot,
                    dateAddedSeconds = 0L,
                    mediaId = mediaId
                )
            }
        }
    }

    private fun ensureQueueProcessorRunning() {
        if (queueJob?.isActive == true) return
        queueJob = serviceScope.launch {
            processQueuedScreenshots()
        }
    }

    private suspend fun processQueuedScreenshots() {
        val modelDownload = ModelDownload(this)
        if (!modelDownload.isReady()) {
            updateNotification("Model missing", "Download Gemma model from app first.")
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

        activeVisionMode = visionMode.label
        activeTokenBudget = visionMode.visualTokenBudget
        activeResizeLongSidePx = visionMode.maxImageLongSide

        val storeFile = File(filesDir, CLASSIFICATION_STORE_FILE_NAME)
        val existing = if (storedClassifications.isEmpty()) {
            loadClassificationsFromDisk(storeFile).toMutableMap()
        } else {
            storedClassifications
        }

        val activeClassifier = classifier ?: GemmaClassifier(this, modelDownload.modelFile).also {
            classifier = it
            it.warmUp()
            Log.i(TAG, "Watcher classifier warm-up complete")
        }

        var done = 0
        var failed = 0
        var failedPermanent = 0
        var totalDecodeMs = 0L
        var totalInferenceMs = 0L
        var totalSaveMs = 0L
        val startedMs = System.currentTimeMillis()

        while (watcherEnabled && queuedScreenshots.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val screenshot = queuedScreenshots.removeFirst()
            val uriValue = screenshot.uri.toString()
            queuedUris.remove(uriValue)
            queuedCount = queuedScreenshots.size
            val retryAttempt = (retryAttemptsByUri[uriValue] ?: 0) + 1

            val completedBefore = done + failed
            val totalThisRun = completedBefore + 1 + queuedScreenshots.size
            showWatcherForeground(
                title = "Classifying screenshots",
                text = "${completedBefore + 1}/$totalThisRun | queue ${queuedScreenshots.size}",
                progressMax = 0,
                progressValue = 0,
                indeterminate = true
            )

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
                retryAttemptsByUri.remove(uriValue)

                val savedEntry = JSONObject().apply {
                    put("imageId", screenshot.imageId)
                    put("imageUri", screenshot.uri.toString())
                    put("category", result.classification.category)
                    put("confidence", result.classification.confidence)
                    put("description", result.classification.description)
                    put("updatedAt", System.currentTimeMillis())
                }
                existing[canonicalizeMediaStoreUriKey(screenshot.uri.toString())] = savedEntry

                val saveStart = System.currentTimeMillis()
                val persisted = saveClassificationsToDisk(storeFile, existing.values)
                if (!persisted) {
                    throw IllegalStateException("Failed to persist classification JSON")
                }
                val saveMs = (System.currentTimeMillis() - saveStart).coerceAtLeast(0L)
                totalSaveMs += saveMs
                done += 1

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
                Log.d(TAG, "Watcher classified uri=$uriValue on attempt=$retryAttempt")
            }.onFailure { error ->
                failed += 1
                val previousAttempts = retryAttemptsByUri[uriValue] ?: 0
                val attemptsUsed = previousAttempts + 1
                if (attemptsUsed < WATCHER_MAX_RETRY_ATTEMPTS && watcherEnabled) {
                    retryAttemptsByUri[uriValue] = attemptsUsed
                    val backoffMs = WATCHER_RETRY_BASE_DELAY_MS * attemptsUsed
                    Log.w(
                        TAG,
                        "Watcher classify failed uri=$uriValue attempt=$attemptsUsed; retry in ${backoffMs}ms",
                        error
                    )
                    serviceScope.launch {
                        delay(backoffMs)
                        if (!watcherEnabled) return@launch
                        if (queuedUris.add(uriValue)) {
                            queuedScreenshots.addLast(screenshot)
                            queuedCount = queuedScreenshots.size
                            ensureQueueProcessorRunning()
                            updateNotification(
                                "Classifying screenshots",
                                "Retrying failed screenshot. Queue: ${queuedScreenshots.size}",
                                progressMax = progressTotal.coerceAtLeast(1),
                                progressValue = progressDone.coerceAtMost(progressTotal.coerceAtLeast(1)),
                                indeterminate = false
                            )
                        }
                    }
                } else {
                    retryAttemptsByUri.remove(uriValue)
                    failedPermanent += 1
                    Log.e(
                        TAG,
                        "Watcher permanently failed uri=$uriValue after attempts=$attemptsUsed",
                        error
                    )
                }
            }

            progressDone = done + failed
            progressTotal = progressDone + queuedScreenshots.size
            val elapsedSec = ((System.currentTimeMillis() - startedMs) / 1000L).coerceAtLeast(1L)
            val avgSec = (elapsedSec.toDouble() / progressDone.coerceAtLeast(1).toDouble())
            updateNotification(
                title = "Classifying screenshots",
                text = "$progressDone/$progressTotal | done $done, failed $failed, queue ${queuedScreenshots.size} | avg ${"%.1f".format(avgSec)}s/img",
                progressMax = progressTotal.coerceAtLeast(1),
                progressValue = progressDone.coerceAtMost(progressTotal.coerceAtLeast(1)),
                indeterminate = false
            )
        }

        storedClassifications = existing
        progressDone = 0
        progressTotal = 0
        queuedCount = queuedScreenshots.size
        if (watcherEnabled) {
            showWatcherForeground(
                title = "Auto detect enabled",
                text = "Monitoring screenshots in background",
                progressMax = 0,
                progressValue = 0,
                indeterminate = false
            )
        }
    }

    private fun showWatcherForeground(
        title: String,
        text: String,
        progressMax: Int,
        progressValue: Int,
        indeterminate: Boolean
    ) {
        if (!watcherForegroundVisible) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(title, text, progressMax, progressValue, indeterminate)
            )
            watcherForegroundVisible = true
        } else {
            updateNotification(title, text, progressMax, progressValue, indeterminate)
        }
    }

    private fun maybeHideWatcherForeground() {
        if (!watcherEnabled || !watcherForegroundVisible) return
        updateNotification(
            title = "Auto detect enabled",
            text = "Monitoring screenshots in background",
            progressMax = 0,
            progressValue = 0,
            indeterminate = false
        )
    }

    private suspend fun runClassification(limit: Int) {
        val modelDownload = ModelDownload(this)
        if (!modelDownload.isReady()) {
            updateNotification("Model missing", "Download Gemma model from app first.")
            isBatchRunning = false
            isRunning = watcherEnabled
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
            !existing.containsKey(canonicalizeMediaStoreUriKey(shot.uri.toString()))
        }.let { shots ->
            if (limit == Int.MAX_VALUE) shots else shots.take(limit)
        }

        if (pending.isEmpty()) {
            updateNotification("Nothing to classify", "All recent screenshots are already categorized.")
            isBatchRunning = false
            isRunning = watcherEnabled
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
                updateNotification(title, text, 0, 0, indeterminate = true)

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
                    existing[canonicalizeMediaStoreUriKey(screenshot.uri.toString())] = savedEntry
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
            isBatchRunning = false
            isRunning = watcherEnabled
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
                    put(canonicalizeMediaStoreUriKey(imageUri), item)
                }
            }
        }.getOrElse { emptyMap() }
    }

    private fun canonicalizeMediaStoreUriKey(raw: String): String {
        return raw
            .replace("content://media/external/images/media/", "content://media/external_primary/images/media/")
            .replace("content://media/external/video/media/", "content://media/external_primary/video/media/")
    }

    private fun saveClassificationsToDisk(file: File, values: Collection<JSONObject>): Boolean {
        val array = JSONArray()
        values.forEach { array.put(it) }
        return runCatching {
            file.writeText(array.toString())
        }.onFailure {
            Log.e(TAG, "Failed writing classification store", it)
        }.isSuccess
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

        if (indeterminate || progressMax > 0) {
            val safeMax = progressMax.coerceAtLeast(0)
            val safeValue = if (safeMax > 0) progressValue.coerceAtMost(safeMax) else 0
            builder.setProgress(safeMax, safeValue, indeterminate)
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
        private const val ACTION_START_WATCH = "com.amoldalwai.screenshotcategory.action.START_WATCH"
        private const val ACTION_STOP = "com.amoldalwai.screenshotcategory.action.STOP_CLASSIFICATION"
        private const val WATCHER_FALLBACK_SCAN_MS = 6_000L
        private const val WATCHER_MAX_RETRY_ATTEMPTS = 3
        private const val WATCHER_RETRY_BASE_DELAY_MS = 1_500L
        private const val TAG = "BgClassifyService"

        private const val PREFERENCES_NAME = "screenshot_sorter"
        private const val CATEGORY_PREFERENCES_KEY = "categories"
        private const val CUSTOM_PROMPT_PREFERENCES_KEY = "custom_prompt"
        private const val VISION_MODE_PREFERENCES_KEY = "vision_mode"
        private const val WATCHER_LAST_DATE_KEY = "watcher_last_date_added"
        private const val WATCHER_LAST_MEDIA_ID_KEY = "watcher_last_media_id"
        private const val CLASSIFICATION_STORE_FILE_NAME = "classification_index.json"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isBatchRunning: Boolean = false
            private set

        @Volatile
        var isWatcherRunning: Boolean = false
            private set

        @Volatile
        var queuedCount: Int = 0
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
                .setAction(Intent.ACTION_RUN)
                .putExtra(EXTRA_LIMIT, limit)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun startWatcher(context: Context) {
            val intent = Intent(context, BackgroundClassificationService::class.java).apply {
                action = ACTION_START_WATCH
            }
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
