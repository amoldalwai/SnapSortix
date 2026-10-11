package com.amoldalwai.screenshotcategory

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.provider.Settings
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {
    private var classifier: GemmaClassifier? = null
    private val preferences by lazy { getSharedPreferences("screenshot_sorter", MODE_PRIVATE) }
    private val classificationStoreFile by lazy { File(filesDir, CLASSIFICATION_STORE_FILE_NAME) }
    private val classificationHistoryStoreFile by lazy { File(filesDir, CLASSIFICATION_HISTORY_FILE_NAME) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ScreenshotSorterApp() }
    }

    override fun onDestroy() {
        classifier?.close()
        classifier = null
        super.onDestroy()
    }

    private fun loadCategories(): List<String> {
        val savedCategories = normalizeCategories(
            preferences.getString(CATEGORY_PREFERENCES_KEY, null)?.split("\n").orEmpty()
        )
        if (preferences.getInt(CATEGORY_DEFAULTS_VERSION_KEY, 0) >= CATEGORY_DEFAULTS_VERSION) {
            return savedCategories
        }

        val updatedCategories = normalizeCategories(
            savedCategories + listOf(
                IDENTITY_CARDS_CATEGORY,
                MOVIE_CATEGORY,
                TICKETS_CATEGORY,
                OTP_CATEGORY
            )
        )
        preferences.edit()
            .putString(CATEGORY_PREFERENCES_KEY, updatedCategories.joinToString("\n"))
            .putInt(CATEGORY_DEFAULTS_VERSION_KEY, CATEGORY_DEFAULTS_VERSION)
            .apply()
        return updatedCategories
    }

    private fun saveCategories(categories: List<String>): List<String> {
        val normalized = normalizeCategories(categories)
        preferences.edit()
            .putString(CATEGORY_PREFERENCES_KEY, normalized.joinToString("\n"))
            .apply()
        return normalized
    }

    private fun loadCustomPrompt(): String = preferences.getString(CUSTOM_PROMPT_PREFERENCES_KEY, "").orEmpty()

    private fun saveCustomPrompt(prompt: String): String {
        val normalized = prompt.trim()
        preferences.edit().putString(CUSTOM_PROMPT_PREFERENCES_KEY, normalized).apply()
        return normalized
    }

    private fun loadVisionMode(): VisionMode {
        val saved = preferences.getString(VISION_MODE_PREFERENCES_KEY, VisionMode.SuperFast.name)
        return VisionMode.entries.firstOrNull { it.name == saved } ?: VisionMode.SuperFast
    }

    private fun saveVisionMode(mode: VisionMode): VisionMode {
        preferences.edit().putString(VISION_MODE_PREFERENCES_KEY, mode.name).apply()
        return mode
    }

    private fun resetCategories(): List<String> {
        preferences.edit().remove(CATEGORY_PREFERENCES_KEY).apply()
        return DEFAULT_CATEGORIES
    }

    private fun loadClassificationsFromDisk(): Map<UriKey, ClassifiedImage> {
        if (!classificationStoreFile.exists()) return emptyMap()
        return runCatching {
            val array = JSONArray(classificationStoreFile.readText())
            buildMap {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val imageUri = item.optString("imageUri").trim()
                    if (imageUri.isBlank()) continue
                    val confidence = item.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f)
                    put(
                        uriKeyFor(imageUri),
                        ClassifiedImage(
                            imageId = item.optString("imageId").ifBlank { imageUri },
                            imageUri = imageUri,
                            imageCreatedAtMs = if (item.has("imageCreatedAtMs") && !item.isNull("imageCreatedAtMs")) {
                                item.optLong("imageCreatedAtMs")
                            } else {
                                null
                            },
                            category = item.optString("category").ifBlank { "Others" },
                            confidence = confidence,
                            description = item.optString("description").orEmpty(),
                            updatedAt = item.optLong("updatedAt", 0L)
                        )
                    )
                }
            }
        }.getOrElse { emptyMap() }
    }

    private fun saveClassificationsToDisk(classifications: Collection<ClassifiedImage>): Boolean {
        val array = JSONArray()
        classifications.forEach { classification ->
            array.put(
                JSONObject().apply {
                    put("imageId", classification.imageId)
                    put("imageUri", classification.imageUri)
                    put("imageCreatedAtMs", classification.imageCreatedAtMs ?: JSONObject.NULL)
                    put("category", classification.category)
                    put("confidence", classification.confidence)
                    put("description", classification.description)
                    put("updatedAt", classification.updatedAt)
                }
            )
        }
        return runCatching {
            classificationStoreFile.writeText(array.toString())
        }.isSuccess
    }

    private fun uriStillExists(uriValue: String): Boolean = runCatching {
        val uri = Uri.parse(uriValue)
        val foundByQuery = contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns._ID),
            null,
            null,
            null
        )?.use { cursor ->
            cursor.moveToFirst()
        } ?: false

        if (foundByQuery) {
            true
        } else {
            // Some picker/document URIs are readable but may not expose MediaStore columns.
            contentResolver.openInputStream(uri)?.use { true } ?: false
        }
    }.getOrElse { false }

    private fun uriKeyFor(rawUri: String): UriKey {
        return UriKey(canonicalizeMediaStoreUriKey(rawUri))
    }

    private fun canonicalizeMediaStoreUriKey(raw: String): String {
        return raw
            .replace("content://media/external/images/media/", "content://media/external_primary/images/media/")
            .replace("content://media/external/video/media/", "content://media/external_primary/video/media/")
    }

    private fun persistGalleryUriPermission(uri: Uri): Boolean = runCatching {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    }.getOrElse { false }

    private fun pruneMissingClassifications(
        current: Map<UriKey, ClassifiedImage>
    ): Map<UriKey, ClassifiedImage> {
        val kept = current.filterValues { uriStillExists(it.imageUri) }
        if (kept.size != current.size) {
            saveClassificationsToDisk(kept.values)
        }
        return kept
    }

    private fun mergeScreenshotsWithPersisted(
        scanned: List<Screenshot>,
        persisted: Map<UriKey, ClassifiedImage>
    ): List<Screenshot> {
        val dedupedScanned = scanned.distinctBy { it.uri.toString() }
        val existing = dedupedScanned.map { uriKeyFor(it.uri.toString()) }.toSet()
        val restored = persisted.values
            .filter { uriStillExists(it.imageUri) }
            .filterNot { uriKeyFor(it.imageUri) in existing }
            .map { saved ->
                Screenshot(
                    imageId = saved.imageId,
                    uri = Uri.parse(saved.imageUri),
                    displayName = "Restored image",
                    relativePath = "Saved",
                    createdAtMs = saved.imageCreatedAtMs
                )
            }
        return (dedupedScanned + restored).distinctBy { it.uri.toString() }
    }

    @Composable
    private fun ScreenshotSorterApp() {
        val modelDownload = remember { ModelDownload(this) }
        var screenshots by remember { mutableStateOf(emptyList<Screenshot>()) }
        var savedCategories by remember { mutableStateOf(loadCategories()) }
        var savedCustomPrompt by remember { mutableStateOf(loadCustomPrompt()) }
        var savedVisionMode by remember { mutableStateOf(loadVisionMode()) }
        var draftCustomPrompt by remember { mutableStateOf(savedCustomPrompt) }
        var draftVisionMode by remember { mutableStateOf(savedVisionMode) }
        var showSettings by remember { mutableStateOf(false) }
        var showDiscardChanges by remember { mutableStateOf(false) }
        var showDeleteAllClassificationsDialog by remember { mutableStateOf(false) }
        var activeClassification by remember { mutableStateOf<UriKey?>(null) }
        var activeReclassification by remember { mutableStateOf<UriKey?>(null) }
        var isModelPreparing by remember { mutableStateOf(false) }
        var isBatchClassifying by remember { mutableStateOf(false) }
        var batchDone by remember { mutableStateOf(0) }
        var batchTotal by remember { mutableStateOf(0) }
        var batchStartEpochMs by remember { mutableStateOf<Long?>(null) }
        var batchStageMessage by remember { mutableStateOf<String?>(null) }
        var isBackgroundClassifying by remember { mutableStateOf(BackgroundClassificationService.isBatchRunning) }
        var isWatcherRunning by remember { mutableStateOf(BackgroundClassificationService.isWatcherRunning) }
        var watcherQueueCount by remember { mutableStateOf(BackgroundClassificationService.queuedCount) }
        var backgroundProgressSummary by remember { mutableStateOf(BackgroundClassificationService.progressSummary) }
        var backgroundDone by remember { mutableStateOf(BackgroundClassificationService.progressDone) }
        var backgroundTotal by remember { mutableStateOf(BackgroundClassificationService.progressTotal) }
        var averageDecodeMs by remember { mutableStateOf(BackgroundClassificationService.averageDecodeMs) }
        var averageInferenceMs by remember { mutableStateOf(BackgroundClassificationService.averageInferenceMs) }
        var averageSaveMs by remember { mutableStateOf(BackgroundClassificationService.averageSaveMs) }
        var runtimeBackend by remember { mutableStateOf(BackgroundClassificationService.runtimeBackend) }
        var dominantStage by remember { mutableStateOf(BackgroundClassificationService.dominantStage) }
        var optimizationHint by remember { mutableStateOf(BackgroundClassificationService.optimizationHint) }
        var activeVisionModeLabel by remember { mutableStateOf(BackgroundClassificationService.activeVisionMode) }
        var activeTokenBudget by remember { mutableStateOf(BackgroundClassificationService.activeTokenBudget) }
        var activeResizeLongSidePx by remember { mutableStateOf(BackgroundClassificationService.activeResizeLongSidePx) }
        var searchQuery by remember { mutableStateOf("") }
        var isSearchResultsLoading by remember { mutableStateOf(false) }
        var selectedCategory by remember { mutableStateOf<String?>(null) }
        var showAllCategories by remember { mutableStateOf(false) }
        var showAllGallery by remember { mutableStateOf(false) }
        var selectedGalleryImage by remember { mutableStateOf<Screenshot?>(null) }
        var isMetricsRefreshing by remember { mutableStateOf(false) }
        var showModelSetupDetails by remember { mutableStateOf(false) }
        var showPermissionSetupDetails by remember { mutableStateOf(false) }
        var showWelcomeSplash by remember {
            mutableStateOf(!preferences.getBoolean(FIRST_LAUNCH_DONE_KEY, false))
        }
        var showRecentClassificationDetails by remember { mutableStateOf(false) }
        var isCategorySectionLoading by remember { mutableStateOf(true) }
        var isScreenshotSectionLoading by remember { mutableStateOf(true) }
        var selectedImageProfile by remember { mutableStateOf<Pair<Screenshot, ClassifiedImage>?>(null) }
        var status by remember {
            mutableStateOf(
                if (modelDownload.isReady()) {
                    "Model ready. Scan screenshots to begin."
                } else {
                    "Download Gemma 4 E2B before classifying screenshots."
                }
            )
        }
        var classifications by remember { mutableStateOf(loadClassificationsFromDisk()) }
        var classificationHistory by remember {
            mutableStateOf(
                loadClassificationHistoryFromDisk(
                    classificationHistoryStoreFile,
                    limit = MAX_CLASSIFICATION_HISTORY_ENTRIES
                )
            )
        }
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (!granted) {
                isCategorySectionLoading = false
                isScreenshotSectionLoading = false
                status = "Image permission is required. Opening app settings so you can enable it."
                openAppPermissionSettings()
                return@rememberLauncherForActivityResult
            }
            isCategorySectionLoading = true
            isScreenshotSectionLoading = true
            lifecycleScope.launch {
                val cleaned = withContext(Dispatchers.IO) { pruneMissingClassifications(classifications) }
                classifications = cleaned
                loadScreenshots { loadedScreenshots ->
                    screenshots = mergeScreenshotsWithPersisted(loadedScreenshots, cleaned)
                    status = "Found ${loadedScreenshots.size} recent screenshots."
                    isCategorySectionLoading = false
                    isScreenshotSectionLoading = false
                }
            }
        }
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (!granted) {
                status = "Notification permission is required for progress popups. Opening notification settings."
                openNotificationSettings()
            }
        }
        val galleryPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { selectedUri ->
            if (selectedUri != null) {
                val permissionPersisted = persistGalleryUriPermission(selectedUri)
                val selectedImage = Screenshot(
                    imageId = selectedUri.lastPathSegment ?: selectedUri.toString(),
                    uri = selectedUri,
                    displayName = "Selected gallery image",
                    relativePath = "Gallery",
                    createdAtMs = null
                )
                selectedGalleryImage = selectedImage
                screenshots = listOf(selectedImage) + screenshots.filterNot { it.uri == selectedUri }
                status = if (permissionPersisted) {
                    "Gallery image added. It will remain available after app restart."
                } else {
                    "Gallery image added. If this URI is temporary, it may not persist after restart."
                }
            }
        }
        LaunchedEffect(modelDownload.isReady()) {
            if (!modelDownload.isReady() || classifier != null || isModelPreparing) return@LaunchedEffect
            isModelPreparing = true
            status = "Preparing Gemma engine in background. First classification will be smoother."
            val warmup = runCatching {
                withContext(Dispatchers.IO) {
                    val activeClassifier = classifier ?: GemmaClassifier(
                        this@MainActivity,
                        modelDownload.modelFile
                    ).also { classifier = it }
                    activeClassifier.warmUp()
                }
            }
            isModelPreparing = false
            warmup.onSuccess {
                status = "Model ready. Scan screenshots to begin."
            }.onFailure { error ->
                status = "Model preparation failed: ${error.message ?: "unknown error"}"
            }
        }

        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                isCategorySectionLoading = true
                isScreenshotSectionLoading = true
                lifecycleScope.launch {
                    val cleaned = withContext(Dispatchers.IO) { pruneMissingClassifications(classifications) }
                    classifications = cleaned
                    loadScreenshots { loadedScreenshots ->
                        val merged = mergeScreenshotsWithPersisted(loadedScreenshots, cleaned)
                        screenshots = merged
                        status = "Loaded ${merged.size} images. Restored ${cleaned.size} saved classifications."
                        isCategorySectionLoading = false
                        isScreenshotSectionLoading = false
                    }
                }
            } else {
                isCategorySectionLoading = false
                isScreenshotSectionLoading = false
            }
        }

        LaunchedEffect(searchQuery) {
            val activeSearch = searchQuery.trim().isNotEmpty()
            if (!activeSearch) {
                isSearchResultsLoading = false
                return@LaunchedEffect
            }
            isSearchResultsLoading = true
            delay(120)
            isSearchResultsLoading = false
        }

        LaunchedEffect(Unit) {
            while (true) {
                isBackgroundClassifying = BackgroundClassificationService.isBatchRunning
                isWatcherRunning = BackgroundClassificationService.isWatcherRunning
                watcherQueueCount = BackgroundClassificationService.queuedCount
                backgroundProgressSummary = BackgroundClassificationService.progressSummary
                backgroundDone = BackgroundClassificationService.progressDone
                backgroundTotal = BackgroundClassificationService.progressTotal
                averageDecodeMs = BackgroundClassificationService.averageDecodeMs
                averageInferenceMs = BackgroundClassificationService.averageInferenceMs
                averageSaveMs = BackgroundClassificationService.averageSaveMs
                runtimeBackend = BackgroundClassificationService.runtimeBackend
                dominantStage = BackgroundClassificationService.dominantStage
                optimizationHint = BackgroundClassificationService.optimizationHint
                activeVisionModeLabel = BackgroundClassificationService.activeVisionMode
                activeTokenBudget = BackgroundClassificationService.activeTokenBudget
                activeResizeLongSidePx = BackgroundClassificationService.activeResizeLongSidePx

                if (isBackgroundClassifying || isWatcherRunning) {
                    val latest = withContext(Dispatchers.IO) { loadClassificationsFromDisk() }
                    if (latest.size != classifications.size) {
                        classifications = latest
                    }
                    classificationHistory = withContext(Dispatchers.IO) {
                        loadClassificationHistoryFromDisk(
                            classificationHistoryStoreFile,
                            limit = MAX_CLASSIFICATION_HISTORY_ENTRIES
                        )
                    }
                }
                delay(500)
            }
        }

        MaterialTheme(colorScheme = screenshotSorterColors) {
            Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
                val backendCpuPercent = 50
                val backendGpuPercent = 50

                fun refreshMetrics() {
                    if (isMetricsRefreshing) return
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.READ_MEDIA_IMAGES
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        status = "Image permission is required before refresh."
                        return
                    }
                    isMetricsRefreshing = true
                    status = "Refreshing screenshots..."
                    lifecycleScope.launch {
                        val cleaned = withContext(Dispatchers.IO) { pruneMissingClassifications(classifications) }
                        classifications = cleaned
                        loadScreenshots { loadedScreenshots ->
                            val merged = mergeScreenshotsWithPersisted(loadedScreenshots, cleaned)
                            screenshots = merged
                            status = "Refreshed ${merged.size} images."
                            isMetricsRefreshing = false
                        }
                    }
                }

                suspend fun classifyAndPersist(screenshot: Screenshot, source: String): Boolean {
                    val imageKey = uriKeyFor(screenshot.uri.toString())
                    activeClassification = imageKey
                    val startedAt = System.currentTimeMillis()
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            val activeClassifier = classifier ?: GemmaClassifier(
                                this@MainActivity,
                                modelDownload.modelFile
                            ).also { classifier = it }
                            activeClassifier.classify(
                                screenshot.uri,
                                savedCategories,
                                savedCustomPrompt,
                                savedVisionMode
                            )
                        }
                    }
                    activeClassification = null
                    return result.map { classification ->
                        val savedEntry = ClassifiedImage(
                            imageId = screenshot.imageId,
                            imageUri = screenshot.uri.toString(),
                            imageCreatedAtMs = screenshot.createdAtMs,
                            category = classification.category,
                            confidence = classification.confidence,
                            description = classification.description,
                            updatedAt = System.currentTimeMillis()
                        )
                        val updated = classifications + (imageKey to savedEntry)
                        classifications = updated
                        withContext(Dispatchers.IO) {
                            saveClassificationsToDisk(updated.values)
                            appendClassificationHistory(
                                context = this@MainActivity,
                                entry = ClassificationHistoryEntry(
                                    timestampMs = System.currentTimeMillis(),
                                    source = source,
                                    status = "Success",
                                    imageCount = 1,
                                    successCount = 1,
                                    failedCount = 0,
                                    averageTimeMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
                                    backend = "Gemma local",
                                    note = classification.category
                                )
                            )
                        }
                        classificationHistory = withContext(Dispatchers.IO) {
                            loadClassificationHistoryFromDisk(
                                classificationHistoryStoreFile,
                                limit = MAX_CLASSIFICATION_HISTORY_ENTRIES
                            )
                        }
                        true
                    }.getOrElse { false }
                }

                val isSearchView = searchQuery.trim().isNotEmpty()
                val filteredScreenshots = screenshots.filter { screenshot ->
                    val classification = classifications[uriKeyFor(screenshot.uri.toString())]
                    val categoryMatches = selectedCategory == null || classification?.category == selectedCategory
                    val requiresClassifiedImage = showAllGallery || isSearchView
                    val allGalleryMatches = !requiresClassifiedImage || classification != null
                    val combined = listOf(
                        screenshot.displayName,
                        screenshot.relativePath.orEmpty(),
                        classification?.category.orEmpty(),
                        classification?.description.orEmpty()
                    ).joinToString(" ").lowercase()
                    categoryMatches && allGalleryMatches && (searchQuery.isBlank() || combined.contains(searchQuery.trim().lowercase()))
                }

                val collectionItems = filteredScreenshots.mapNotNull { screenshot ->
                    val classification = classifications[uriKeyFor(screenshot.uri.toString())] ?: return@mapNotNull null
                    screenshot to classification
                }
                val groupedCollections = collectionItems.groupBy { (_, classification) -> classification.category }
                val totalScreenshotsCount = screenshots.size
                val classifiedScreenshotsCount = screenshots.count {
                    uriKeyFor(it.uri.toString()) in classifications
                }
                val hasMediaPermission = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFFF2F8F8), Color(0xFFF8FAFC))
                            )
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val hasBackTarget = selectedCategory != null || showAllGallery
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (hasBackTarget) {
                                IconButton(onClick = {
                                    selectedCategory = null
                                    showAllGallery = false
                                    selectedImageProfile = null
                                    status = "Back to home view."
                                }) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.ArrowBack,
                                        contentDescription = "Back",
                                        tint = Color(0xFF2F6E6A)
                                    )
                                }
                            }
                            Image(
                                painter = painterResource(id = R.drawable.app_logo),
                                contentDescription = "SnapSortix logo",
                                modifier = Modifier.size(30.dp)
                            )
                            Text(
                                "SnapSortix",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color(0xFF102A43),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        IconButton(onClick = {
                            draftCustomPrompt = savedCustomPrompt
                            draftVisionMode = savedVisionMode
                            showSettings = true
                        }) {
                            Icon(
                                Icons.Outlined.Settings,
                                contentDescription = "Prompt settings",
                                tint = Color(0xFF2F6E6A)
                            )
                        }
                    }

                    if (showSettings) {
                        PromptSettings(
                            customPrompt = draftCustomPrompt,
                            visionMode = draftVisionMode,
                            autoDetectEnabled = isWatcherRunning,
                            promptPreview = buildPromptPreview(savedCategories, draftCustomPrompt),
                            onBack = {
                                if (draftCustomPrompt != savedCustomPrompt || draftVisionMode != savedVisionMode) {
                                    showDiscardChanges = true
                                } else {
                                    showSettings = false
                                }
                            },
                            onCustomPromptChange = { draftCustomPrompt = it },
                            onVisionModeChange = { draftVisionMode = it },
                            onAutoDetectToggle = { enabled ->
                                if (enabled) {
                                    if (!modelDownload.isReady()) {
                                        status = "Download the model first."
                                    } else if (ContextCompat.checkSelfPermission(
                                        this@MainActivity,
                                        Manifest.permission.READ_MEDIA_IMAGES
                                    ) != PackageManager.PERMISSION_GRANTED) {
                                        permissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                                        status = "Grant image permission to enable auto detect."
                                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        val hasNotificationPermission = ContextCompat.checkSelfPermission(
                                            this@MainActivity,
                                            Manifest.permission.POST_NOTIFICATIONS
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (!hasNotificationPermission) {
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            status = "Grant notification permission to enable auto detect."
                                        } else {
                                            BackgroundClassificationService.startWatcher(this@MainActivity)
                                            status = "Auto detect enabled. New screenshots will be queued and classified in background."
                                        }
                                    } else {
                                        BackgroundClassificationService.startWatcher(this@MainActivity)
                                        status = "Auto detect enabled. New screenshots will be queued and classified in background."
                                    }
                                } else {
                                    BackgroundClassificationService.stop(this@MainActivity)
                                    status = "Auto detect disabled."
                                }
                            },
                            onSave = {
                                savedCustomPrompt = saveCustomPrompt(draftCustomPrompt)
                                savedVisionMode = saveVisionMode(draftVisionMode)
                                draftCustomPrompt = savedCustomPrompt
                                draftVisionMode = savedVisionMode
                                showSettings = false
                                status = "Prompt saved. Vision mode set to ${savedVisionMode.label}."
                            },
                            onReset = {
                                draftCustomPrompt = ""
                                draftVisionMode = VisionMode.SuperFast
                            }
                        )
                        if (showDiscardChanges) {
                            AlertDialog(
                                onDismissRequest = { showDiscardChanges = false },
                                title = { Text("Discard changes?") },
                                text = { Text("Your prompt edits have not been saved.") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        draftCustomPrompt = savedCustomPrompt
                                        draftVisionMode = savedVisionMode
                                        showDiscardChanges = false
                                        showSettings = false
                                    }) { Text("Yes") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDiscardChanges = false }) { Text("No") }
                                }
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 16.dp)
                        ) {
                            item {
                                SearchField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    onClear = { searchQuery = "" }
                                )
                            }
                            item { StatusCard(status) }
                            item {
                                HomeMetricsCard(
                                    classifiedCount = classifiedScreenshotsCount,
                                    totalCount = totalScreenshotsCount,
                                    isRefreshing = isMetricsRefreshing,
                                    onRefresh = { refreshMetrics() }
                                )
                            }
                            if (isBatchClassifying && batchTotal > 0) {
                                item {
                                    val pending = (batchTotal - batchDone).coerceAtLeast(0)
                                    val elapsedSeconds = batchStartEpochMs?.let {
                                        ((System.currentTimeMillis() - it) / 1000L).coerceAtLeast(0L)
                                    } ?: 0L
                                    val avgPerImageSeconds = if (batchDone > 0) {
                                        elapsedSeconds.toDouble() / batchDone.toDouble()
                                    } else {
                                        0.0
                                    }
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "Classify 10 progress: done $batchDone | pending $pending",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = Color(0xFF1E3A5F),
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            batchStageMessage?.let { stage ->
                                                Text(
                                                    text = stage,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color(0xFF2F6E6A),
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                            Text(
                                                text = "Backend usage (estimated): CPU $backendCpuPercent% | GPU $backendGpuPercent%",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF355C7D)
                                            )
                                            Text(
                                                text = "Elapsed: ${elapsedSeconds}s | Avg/image: ${"%.1f".format(avgPerImageSeconds)}s",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF355C7D)
                                            )
                                            LinearProgressIndicator(
                                                progress = { (batchDone.toFloat() / batchTotal.toFloat()).coerceIn(0f, 1f) },
                                                modifier = Modifier.fillMaxWidth(),
                                                color = Color(0xFF2F6E6A),
                                                trackColor = Color(0xFFD6E4F3)
                                            )
                                        }
                                    }
                                }
                            }
                            if (isBackgroundClassifying) {
                                item {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "Background classify is running",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = Color(0xFF1E3A5F),
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            if (backgroundProgressSummary.isNotBlank()) {
                                                Text(
                                                    text = backgroundProgressSummary,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color(0xFF355C7D)
                                                )
                                            }
                                            if (backgroundTotal > 0) {
                                                LinearProgressIndicator(
                                                    progress = {
                                                        (backgroundDone.toFloat() / backgroundTotal.toFloat()).coerceIn(0f, 1f)
                                                    },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    color = Color(0xFF2F6E6A),
                                                    trackColor = Color(0xFFD6E4F3)
                                                )
                                                Text(
                                                    text = "Progress: $backgroundDone/$backgroundTotal",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color(0xFF355C7D)
                                                )
                                            }
                                            Text(
                                                text = "decode/downscale avg: ${averageDecodeMs} ms | inference avg: ${averageInferenceMs} ms | save avg: ${averageSaveMs} ms",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF355C7D)
                                            )
                                            if (dominantStage.isNotBlank()) {
                                                Text(
                                                    text = "Dominant stage: $dominantStage",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color(0xFF1E3A5F),
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            if (optimizationHint.isNotBlank()) {
                                                Text(
                                                    text = optimizationHint,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color(0xFF2F6E6A)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (isWatcherRunning && !isBackgroundClassifying) {
                                item {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "Auto watch is active",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = Color(0xFF1E3A5F),
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "Watching screenshot captures. Queue: $watcherQueueCount pending",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF355C7D)
                                            )
                                            if (backgroundProgressSummary.isNotBlank()) {
                                                Text(
                                                    text = backgroundProgressSummary,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color(0xFF2F6E6A)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (!modelDownload.isReady() || !hasMediaPermission) {
                                item {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        if (!modelDownload.isReady()) {
                                            SetupStepCard(
                                                icon = Icons.Outlined.Add,
                                                title = "Download AI model",
                                                summary = "Required once to run local screenshot classification.",
                                                expanded = showModelSetupDetails,
                                                onToggle = { showModelSetupDetails = !showModelSetupDetails },
                                                details = "SnapSortix runs on-device AI. Downloading Gemma enables private, offline-friendly screenshot categorization on your phone.",
                                                actionLabel = "Download now",
                                                onAction = {
                                                    modelDownload.enqueue()
                                                    status = "Model download started. Keep app installed while it completes."
                                                }
                                            )
                                        }
                                        if (!hasMediaPermission) {
                                            SetupStepCard(
                                                icon = Icons.Outlined.PhotoLibrary,
                                                title = "Allow photo access",
                                                summary = "Needed to read screenshots and categorize them.",
                                                expanded = showPermissionSetupDetails,
                                                onToggle = { showPermissionSetupDetails = !showPermissionSetupDetails },
                                                details = "SnapSortix only reads screenshot metadata and image content for classification. Without permission, it cannot detect new screenshots.",
                                                actionLabel = "Grant access",
                                                onAction = {
                                                    permissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            item {
                                ClassifyTenControlCard(
                                    isRunning = isBackgroundClassifying,
                                    queueCount = watcherQueueCount,
                                    onToggle = {
                                        if (!modelDownload.isReady()) {
                                            status = "Download the model first."
                                            return@ClassifyTenControlCard
                                        }
                                        if (isBackgroundClassifying) {
                                            BackgroundClassificationService.stop(this@MainActivity)
                                            status = "Stopping background classification..."
                                            return@ClassifyTenControlCard
                                        }

                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            val hasNotificationPermission = ContextCompat.checkSelfPermission(
                                                this@MainActivity,
                                                Manifest.permission.POST_NOTIFICATIONS
                                            ) == PackageManager.PERMISSION_GRANTED
                                            if (!hasNotificationPermission) {
                                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            }
                                        }

                                        val pending = screenshots.filter { shot ->
                                            uriKeyFor(shot.uri.toString()) !in classifications
                                        }.take(10)
                                        if (pending.isEmpty()) {
                                            status = "No uncategorized screenshots available in latest 10."
                                            return@ClassifyTenControlCard
                                        }

                                        BackgroundClassificationService.start(
                                            context = this@MainActivity,
                                            limit = 10
                                        )
                                        status = "Classify 10 started. Latest uncategorized screenshots will be processed first."
                                    }
                                )
                            }
                            item {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    QuickAction(
                                        icon = Icons.Outlined.PhotoLibrary,
                                        label = "Gallery",
                                        onClick = {
                                            galleryPicker.launch(arrayOf("image/*"))
                                        }
                                    )
                                    QuickAction(
                                        icon = Icons.Outlined.Refresh,
                                        label = "Sync",
                                        onClick = {
                                            if (ContextCompat.checkSelfPermission(
                                                    this@MainActivity,
                                                    Manifest.permission.READ_MEDIA_IMAGES
                                                ) != PackageManager.PERMISSION_GRANTED
                                            ) {
                                                permissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                                                return@QuickAction
                                            }
                                            isCategorySectionLoading = true
                                            isScreenshotSectionLoading = true
                                            lifecycleScope.launch {
                                                val cleaned = withContext(Dispatchers.IO) {
                                                    pruneMissingClassifications(classifications)
                                                }
                                                classifications = cleaned
                                                loadScreenshots { loadedScreenshots ->
                                                    screenshots = mergeScreenshotsWithPersisted(loadedScreenshots, cleaned)
                                                    status = "Synced ${loadedScreenshots.size} screenshots from your phone gallery."
                                                    isCategorySectionLoading = false
                                                    isScreenshotSectionLoading = false
                                                }
                                            }
                                        }
                                    )
                                    QuickAction(
                                        icon = Icons.Outlined.Delete,
                                        label = "Delete",
                                        onClick = {
                                            if (isBackgroundClassifying) {
                                                status = "Stop background classification before deleting classifications."
                                                return@QuickAction
                                            }
                                            showDeleteAllClassificationsDialog = true
                                        }
                                    )
                                }
                            }
                            if (!isSearchView && isCategorySectionLoading) {
                                item { CategoryCollectionSkeleton() }
                            } else if (!isSearchView && groupedCollections.isNotEmpty()) {
                                item {
                                    CategoryCollectionGrid(
                                        groupedCollections = groupedCollections,
                                        selectedCategory = selectedCategory,
                                        onCategoryClick = { category ->
                                            selectedCategory = category
                                            showAllCategories = false
                                            showAllGallery = false
                                            selectedImageProfile = null
                                            status = "Viewing $category photos."
                                        },
                                        onViewAllClick = {
                                            val nextShowAllCategories = !showAllCategories
                                            showAllCategories = nextShowAllCategories
                                            selectedCategory = null
                                            showAllGallery = false
                                            selectedImageProfile = null
                                            status = if (nextShowAllCategories) {
                                                "Showing all categories."
                                            } else {
                                                "Showing top categories."
                                            }
                                        },
                                        showAllCategories = showAllCategories
                                    )
                                }
                                item {
                                    RecentClassificationCard(
                                        history = classificationHistory,
                                        expanded = showRecentClassificationDetails,
                                        onToggleExpanded = {
                                            showRecentClassificationDetails = !showRecentClassificationDetails
                                        }
                                    )
                                }
                            }
                            if (isSearchView || selectedCategory != null || showAllGallery) {
                                item {
                                    Text(
                                        text = if (isSearchView) {
                                            "Search results for: ${searchQuery.trim()}"
                                        } else if (selectedCategory != null) {
                                            "Showing category: ${selectedCategory}"
                                        } else {
                                            "Showing all categorized screenshots"
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Color(0xFF2F6E6A),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                item {
                                    Text(
                                        if (isSearchView) "Search image results" else "Category gallery",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF102A43)
                                    )
                                }
                                if (isScreenshotSectionLoading || isSearchResultsLoading) {
                                    item { CategoryGallerySkeleton() }
                                } else {
                                    item {
                                        CategoryImageGrid(
                                            screenshots = filteredScreenshots,
                                            classifications = classifications,
                                            onImageClick = { screenshot ->
                                                val details = classifications[uriKeyFor(screenshot.uri.toString())]
                                                if (details != null) {
                                                    selectedImageProfile = screenshot to details
                                                }
                                            }
                                        )
                                    }
                                }
                            } else {
                                selectedGalleryImage?.let { galleryImage ->
                                    item {
                                        Text(
                                            text = "Selected gallery image",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF102A43)
                                        )
                                    }
                                    item {
                                        ScreenshotRow(
                                            screenshot = galleryImage,
                                            classification = classifications[uriKeyFor(galleryImage.uri.toString())]?.toClassification(),
                                            enabled = modelDownload.isReady() && activeClassification == null && !isModelPreparing && !isBatchClassifying,
                                            isClassifying = activeClassification == uriKeyFor(galleryImage.uri.toString()),
                                            isReclassifying = activeReclassification == uriKeyFor(galleryImage.uri.toString()),
                                            onEditClick = {
                                                val details = classifications[uriKeyFor(galleryImage.uri.toString())]
                                                if (details != null) {
                                                    selectedImageProfile = galleryImage to details
                                                } else {
                                                    status = "Classify this image first, then you can edit category and description."
                                                }
                                            },
                                            onReclassifyClick = {
                                                lifecycleScope.launch {
                                                    val imageKey = uriKeyFor(galleryImage.uri.toString())
                                                    activeReclassification = imageKey
                                                    status = if (classifier == null) {
                                                        "Preparing Gemma for re-classification."
                                                    } else {
                                                        "Re-classifying ${galleryImage.displayName} with latest prompt..."
                                                    }
                                                    val savedToDisk = classifyAndPersist(galleryImage, source = "Gallery reclassify")
                                                    activeReclassification = null
                                                    status = if (savedToDisk) {
                                                        "Re-classification finished. Category has been updated."
                                                    } else {
                                                        "Re-classification failed or could not be saved."
                                                    }
                                                }
                                            },
                                            onClassify = {
                                                lifecycleScope.launch {
                                                    status = if (classifier == null) {
                                                        "Preparing Gemma for the first image. This can take a minute."
                                                    } else {
                                                        "Classifying ${galleryImage.displayName}..."
                                                    }
                                                    val savedToDisk = classifyAndPersist(galleryImage, source = "Gallery classify")
                                                    status = if (savedToDisk) {
                                                        "Classification finished."
                                                    } else {
                                                        "Classification failed or could not be saved."
                                                    }
                                                }
                                            }
                                        )
                                    }
                                    item {
                                        TextButton(onClick = { selectedGalleryImage = null }) {
                                            Text("Clear selected gallery image")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    }

                    selectedImageProfile?.let { (screenshot, details) ->
                        ImageProfileDialog(
                            screenshot = screenshot,
                            details = details,
                            categories = savedCategories,
                            isReclassifying = activeReclassification == uriKeyFor(screenshot.uri.toString()),
                            onSaveEdits = { updatedCategory, updatedDescription ->
                                val imageKey = uriKeyFor(screenshot.uri.toString())
                                val existing = classifications[imageKey] ?: return@ImageProfileDialog false
                                val updatedEntry = existing.copy(
                                    category = updatedCategory,
                                    description = updatedDescription.trim(),
                                    updatedAt = System.currentTimeMillis()
                                )
                                val updatedMap = classifications + (imageKey to updatedEntry)
                                classifications = updatedMap
                                lifecycleScope.launch(Dispatchers.IO) {
                                    saveClassificationsToDisk(updatedMap.values)
                                    appendClassificationHistory(
                                        context = this@MainActivity,
                                        entry = ClassificationHistoryEntry(
                                            timestampMs = System.currentTimeMillis(),
                                            source = "Manual edit",
                                            status = "Success",
                                            imageCount = 1,
                                            successCount = 1,
                                            failedCount = 0,
                                            averageTimeMs = 0L,
                                            backend = "UI",
                                            note = updatedCategory
                                        )
                                    )
                                }
                                classificationHistory = loadClassificationHistoryFromDisk(
                                    classificationHistoryStoreFile,
                                    limit = MAX_CLASSIFICATION_HISTORY_ENTRIES
                                )
                                status = "Updated classification for ${screenshot.displayName}."
                                selectedImageProfile = screenshot to updatedEntry
                                true
                            },
                            onReclassify = {
                                lifecycleScope.launch {
                                    val imageKey = uriKeyFor(screenshot.uri.toString())
                                    activeReclassification = imageKey
                                    status = if (classifier == null) {
                                        "Preparing Gemma for re-classification."
                                    } else {
                                        "Re-classifying ${screenshot.displayName} with latest prompt..."
                                    }
                                    val savedToDisk = classifyAndPersist(screenshot, source = "Gallery reclassify")
                                    activeReclassification = null
                                    if (savedToDisk) {
                                        val latest = classifications[uriKeyFor(screenshot.uri.toString())]
                                        if (latest != null) {
                                            selectedImageProfile = screenshot to latest
                                        }
                                        status = "Re-classification finished. Category has been updated."
                                    } else {
                                        status = "Re-classification failed or could not be saved."
                                    }
                                }
                            },
                            onDismiss = { selectedImageProfile = null }
                        )
                    }

                    if (showDeleteAllClassificationsDialog) {
                        AlertDialog(
                            onDismissRequest = { showDeleteAllClassificationsDialog = false },
                            title = { Text("Delete all classifications?") },
                            text = {
                                Text(
                                    "This will remove all classification entries from local JSON and reset categorized results. Images remain on your phone."
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    classifications = emptyMap()
                                    selectedImageProfile = null
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        saveClassificationsToDisk(emptyList())
                                    }
                                    showDeleteAllClassificationsDialog = false
                                    status = "All classifications deleted. You can start classifying from scratch."
                                }) {
                                    Text("Delete all")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteAllClassificationsDialog = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }

                    if (showWelcomeSplash) {
                        WelcomeSplashDialog(
                            modelReady = modelDownload.isReady(),
                            hasPhotoPermission = hasMediaPermission,
                            onDownloadModel = {
                                modelDownload.enqueue()
                                status = "Model download started."
                            },
                            onGrantPermission = {
                                permissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                            },
                            onContinue = {
                                preferences.edit().putBoolean(FIRST_LAUNCH_DONE_KEY, true).apply()
                                showWelcomeSplash = false
                                status = "Welcome to SnapSortix. Complete setup cards to start auto classification."
                            }
                        )
                    }
                }
            }
        }
    }

    private fun loadScreenshots(onLoaded: (List<Screenshot>) -> Unit) {
        lifecycleScope.launch {
            onLoaded(withContext(Dispatchers.IO) { ScreenshotMedia(this@MainActivity).loadRecentScreenshots() })
        }
    }

    private fun openAppPermissionSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
            .onFailure { openAppPermissionSettings() }
    }
}

private const val CATEGORY_PREFERENCES_KEY = "categories"
private const val CATEGORY_DEFAULTS_VERSION_KEY = "category_defaults_version"
private const val CUSTOM_PROMPT_PREFERENCES_KEY = "custom_prompt"
private const val VISION_MODE_PREFERENCES_KEY = "vision_mode"
private const val CLASSIFICATION_STORE_FILE_NAME = "classification_index.json"
private const val FIRST_LAUNCH_DONE_KEY = "first_launch_done"
private const val CATEGORY_DEFAULTS_VERSION = 3
private const val IDENTITY_CARDS_CATEGORY = "Identity cards"
private const val MOVIE_CATEGORY = "Movie"
private const val TICKETS_CATEGORY = "Tickets/Bookings"
private const val OTP_CATEGORY = "OTP/Verification Codes"

private data class ClassifiedImage(
    val imageId: String,
    val imageUri: String,
    val imageCreatedAtMs: Long?,
    val category: String,
    val confidence: Float,
    val description: String,
    val updatedAt: Long
) {
    fun toClassification(): Classification = Classification(
        category = category,
        confidence = confidence,
        description = description
    )
}

private val DEFAULT_CATEGORIES = listOf(
    "LinkedIn",
    "Quotes/Learning",
    "Food",
    MOVIE_CATEGORY,
    TICKETS_CATEGORY,
    OTP_CATEGORY,
    "Payment/Finance/Bills",
    IDENTITY_CARDS_CATEGORY,
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

private fun normalizeCategories(categories: List<String>): List<String> =
    categories
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase)
        .ifEmpty { DEFAULT_CATEGORIES }

@Composable
private fun PromptSettings(
    customPrompt: String,
    visionMode: VisionMode,
    autoDetectEnabled: Boolean,
    promptPreview: String,
    onBack: () -> Unit,
    onCustomPromptChange: (String) -> Unit,
    onVisionModeChange: (VisionMode) -> Unit,
    onAutoDetectToggle: (Boolean) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
            Text("Prompt settings", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onReset) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Reset prompt")
            }
        }

        Text("Edit the extra prompt that Gemma applies during classification.")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF3F3))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Vision & image settings",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF102A43),
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Choose speed/quality for image understanding. This also changes internal image resize.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4A6572)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VisionMode.entries.forEach { mode ->
                        val selected = mode == visionMode
                        Surface(
                            color = if (selected) Color(0xFF2F6E6A) else Color(0xFFDCE9E8),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                            modifier = Modifier.clickable { onVisionModeChange(mode) }
                        ) {
                            Text(
                                text = mode.label,
                                color = if (selected) Color.White else Color(0xFF1E4F4B),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                Text(
                    "Current: ${visionMode.label} | Token budget ${visionMode.visualTokenBudget} | Resize ${visionMode.maxImageLongSide}px",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF355C7D)
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Custom prompt", style = MaterialTheme.typography.titleMedium)
                Text("This is used as-is during classification. Keep it short and specific.")
                OutlinedTextField(
                    value = customPrompt,
                    onValueChange = onCustomPromptChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Instructions for Gemma") },
                    placeholder = { Text("For example: prioritize food over quotes") },
                    minLines = 3
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF4F7FB))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Prompt currently used by app", style = MaterialTheme.typography.titleMedium)
                Text("This is the exact full prompt template used during classification.")
                OutlinedTextField(
                    value = promptPreview,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                    maxLines = 14
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Auto detect screenshots",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF102A43),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Keeps a lightweight watcher active and classifies new screenshots in background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF355C7D)
                    )
                }
                Switch(
                    checked = autoDetectEnabled,
                    onCheckedChange = onAutoDetectToggle
                )
            }
        }

        Button(onClick = onSave) {
            Text("Save prompt + settings")
        }
    }
}

private val screenshotSorterColors = lightColorScheme(
    primary = Color(0xFF2F6E6A),
    onPrimary = Color.White,
    secondary = Color(0xFFFFB454),
    surface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFFE9F2F2),
    onSurfaceVariant = Color(0xFF1E4F4B)
)

@Composable
private fun StatusCard(status: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE9F2F2)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    ) {
        Text(
            text = status,
            modifier = Modifier.padding(14.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun HomeMetricsCard(
    classifiedCount: Int,
    totalCount: Int,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    val completionPercent = if (totalCount > 0) {
        (classifiedCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F8FF)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Classified screenshots",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF1E3A5F),
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF2F6E6A)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Refresh classified screenshot counts",
                            tint = Color(0xFF1E3A5F)
                        )
                    }
                }
            }
            Text(
                text = "$classifiedCount / $totalCount",
                style = MaterialTheme.typography.headlineSmall,
                color = Color(0xFF102A43),
                fontWeight = FontWeight.Bold
            )
            LinearProgressIndicator(
                progress = { completionPercent },
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF2F6E6A),
                trackColor = Color(0xFFD6E4F3)
            )
        }
    }
}

@JvmInline
value class UriKey(val value: String)

@Composable
private fun ScreenshotRow(
    screenshot: Screenshot,
    classification: Classification?,
    enabled: Boolean,
    isClassifying: Boolean,
    isReclassifying: Boolean = false,
    onEditClick: (() -> Unit)? = null,
    onReclassifyClick: (() -> Unit)? = null,
    onClassify: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFCFEFE))
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = screenshot.uri,
                contentDescription = screenshot.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(92.dp)
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    screenshot.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color(0xFF102A43)
                )
                Text(
                    screenshot.relativePath ?: "Unknown location",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color(0xFF5E6C84)
                )
                if (classification != null) {
                    Surface(
                        color = categoryColor(classification.category),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = "${classification.category}  ${(classification.confidence * 100).toInt()}%",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            color = Color.White
                        )
                    }
                    if (classification.description.isNotBlank()) {
                        Text(
                            classification.description,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = Color(0xFF5E6C84)
                        )
                    }
                } else {
                    Text("Not categorized", color = MaterialTheme.colorScheme.primary)
                }
                if (isClassifying) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF2E9B58),
                        trackColor = Color(0xFFCEE9D7)
                    )
                    Text(
                        if (isReclassifying) "Reclassifying with latest prompt..." else "Classifying...",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF2E9B58),
                        fontWeight = FontWeight.Medium
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = if (enabled && !isClassifying) Color(0xFFE2F0EF) else Color(0xFFF1F5F9),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                    ) {
                        IconButton(enabled = enabled && !isClassifying, onClick = onClassify) {
                            if (isClassifying) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Check, contentDescription = "Classify image", tint = Color(0xFF2F6E6A))
                            }
                        }
                    }
                    Text(
                        if (isClassifying) {
                            if (isReclassifying) "Reclassifying..." else "Classifying..."
                        } else {
                            "Classify"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF2F6E6A)
                    )
                    if (classification != null && onEditClick != null) {
                        Surface(
                            color = Color(0xFFF1F5F9),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                        ) {
                            IconButton(onClick = onEditClick) {
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = "Edit category and description",
                                    tint = Color.Gray
                                )
                            }
                        }
                    }
                    if (classification != null && onReclassifyClick != null) {
                        Surface(
                            color = Color(0xFFF1F5F9),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                        ) {
                            IconButton(enabled = enabled && !isClassifying, onClick = onReclassifyClick) {
                                Icon(
                                    imageVector = Icons.Outlined.AutoAwesome,
                                    contentDescription = "Reclassify with latest prompt",
                                    tint = Color(0xFF5E6C84)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        placeholder = {
            Text("Search anything from screenshots...", color = Color(0xFF8A99A8))
        },
        leadingIcon = {
            Icon(Icons.Outlined.Search, contentDescription = "Search", tint = Color(0xFF8A99A8))
        },
        trailingIcon = {
            if (value.isNotBlank()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Outlined.Close, contentDescription = "Clear search", tint = Color(0xFF8A99A8))
                }
            }
        }
    )
}

@Composable
private fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            color = Color(0xFFE2F0EF),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
        ) {
            IconButton(onClick = onClick) {
                Icon(icon, contentDescription = label, tint = Color(0xFF2F6E6A))
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF4A6572),
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SetupStepCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    details: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, contentDescription = title, tint = Color(0xFF2F6E6A))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFF1E3A5F),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                TextButton(onClick = onToggle) {
                    Text(if (expanded) "Hide" else "Why?")
                }
            }
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF355C7D)
            )
            if (expanded) {
                HorizontalDivider(color = Color(0xFFD6E4F3))
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF355C7D)
                )
            }
            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun ClassifyTenControlCard(
    isRunning: Boolean,
    queueCount: Int,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF3FF)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Classify Latest 10",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF1E3A5F),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Processes newest uncategorized screenshots first and skips already classified ones.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF355C7D)
                    )
                }
                Button(onClick = onToggle) {
                    Text(if (isRunning) "Stop" else "Run")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = Color(0xFFD6E4F3),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = "Queue: ${queueCount.coerceAtLeast(0)}",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF1E3A5F)
                    )
                }
                Text(
                    text = if (isRunning) "Background run active" else "Ready",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF2F6E6A),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun RecentClassificationCard(
    history: List<ClassificationHistoryEntry>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    val totalImages = history.sumOf { it.imageCount }
    val avgTimeMs = if (history.isNotEmpty()) history.map { it.averageTimeMs }.average().toLong() else 0L
    val success = history.sumOf { it.successCount }
    val failed = history.sumOf { it.failedCount }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF4FBF7)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Classification",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF102A43),
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onToggleExpanded) {
                    Text(if (expanded) "Hide" else "Expand")
                }
            }

            if (history.isEmpty()) {
                Text(
                    text = "No classification activity yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4A6572)
                )
                return@Column
            }

            Text(
                text = "Last ${history.size} runs | images: $totalImages | success: $success | failed: $failed | avg: ${formatMs(avgTimeMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF355C7D)
            )

            if (expanded) {
                HorizontalDivider(color = Color(0xFFD6E4F3))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    history.forEach { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "${item.source} • ${item.status}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color(0xFF1E3A5F),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${DateFormat.format("dd MMM, hh:mm:ss a", item.timestampMs)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF4A6572)
                                )
                                Text(
                                    text = "images: ${item.imageCount}, success: ${item.successCount}, failed: ${item.failedCount}, avg/run: ${formatMs(item.averageTimeMs)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF355C7D)
                                )
                                if (item.note.isNotBlank()) {
                                    Text(
                                        text = "note: ${item.note}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF2F6E6A)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatMs(value: Long): String {
    if (value <= 0L) return "0 ms"
    return if (value < 1000L) "$value ms" else "${"%.2f".format(value / 1000.0)} s"
}

@Composable
private fun WelcomeSplashDialog(
    modelReady: Boolean,
    hasPhotoPermission: Boolean,
    onDownloadModel: () -> Unit,
    onGrantPermission: () -> Unit,
    onContinue: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onContinue,
        title = { Text("Welcome to SnapSortix") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "SnapSortix auto-organizes your screenshots using on-device AI. Complete these 2 setup steps to get started:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF355C7D)
                )
                Text(
                    "1. Download Gemma model (${if (modelReady) "Done" else "Pending"})\n2. Grant photo permission (${if (hasPhotoPermission) "Done" else "Pending"})",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF1E3A5F)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!modelReady) {
                        TextButton(onClick = onDownloadModel) {
                            Text("Download model")
                        }
                    }
                    if (!hasPhotoPermission) {
                        TextButton(onClick = onGrantPermission) {
                            Text("Grant photos")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text("Continue")
            }
        }
    )
}

@Composable
private fun CategoryCollectionGrid(
    groupedCollections: Map<String, List<Pair<Screenshot, ClassifiedImage>>>,
    selectedCategory: String?,
    onCategoryClick: (String) -> Unit,
    onViewAllClick: () -> Unit,
    showAllCategories: Boolean
) {
    val sortedCollections = groupedCollections.entries
        .sortedWith(compareByDescending<Map.Entry<String, List<Pair<Screenshot, ClassifiedImage>>>> { it.value.size }
            .thenBy { it.key })
    val visibleCollections = if (showAllCategories) sortedCollections else sortedCollections.take(4)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Primary categories",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF102A43)
        )
        TextButton(onClick = onViewAllClick) {
            Text(if (showAllCategories) "Show Less" else "View All")
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        visibleCollections.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowItems.forEach { entry ->
                    Box(modifier = Modifier.weight(1f)) {
                        CategoryCollectionCard(
                            category = entry.key,
                            count = entry.value.size,
                            previewUri = entry.value.maxByOrNull { it.second.updatedAt }?.first?.uri,
                            isSelected = entry.key == selectedCategory,
                            onClick = { onCategoryClick(entry.key) }
                        )
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CategoryCollectionCard(
    category: String,
    count: Int,
    previewUri: Uri?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.22f)
            .clickable(onClick = onClick),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFFD6ECEA) else Color(0xFFEAF3F3)
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (previewUri != null) {
                AsyncImage(
                    model = previewUri,
                    contentDescription = category,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xAA000000))
                            )
                        )
                )
            }

            Surface(
                color = Color(0xCCECEFF4),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
            ) {
                Text(
                    text = if (count == 1) "1 item" else "$count items",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1F2937)
                )
            }

            Text(
                text = category,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (previewUri == null) MaterialTheme.colorScheme.onSurface else Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
            )
        }
    }
}

@Composable
private fun CategoryImageGrid(
    screenshots: List<Screenshot>,
    classifications: Map<UriKey, ClassifiedImage>,
    onImageClick: (Screenshot) -> Unit
) {
    if (screenshots.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF3F3)),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
        ) {
            Text(
                text = "No images in this category yet.",
                modifier = Modifier.padding(14.dp),
                color = Color(0xFF2F6E6A)
            )
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        screenshots.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowItems.forEach { screenshot ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                            .clickable { onImageClick(screenshot) }
                    ) {
                        AsyncImage(
                            model = screenshot.uri,
                            contentDescription = screenshot.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, Color(0x99000000))
                                    )
                                )
                        )
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageProfileDialog(
    screenshot: Screenshot,
    details: ClassifiedImage,
    categories: List<String>,
    isReclassifying: Boolean,
    onSaveEdits: (String, String) -> Boolean,
    onReclassify: () -> Unit,
    onDismiss: () -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var draftCategory by remember(details.category, categories) {
        mutableStateOf(
            categories.firstOrNull { it.equals(details.category, ignoreCase = true) }
                ?: categories.firstOrNull()
                ?: details.category
        )
    }
    var draftDescription by remember(details.description) { mutableStateOf(details.description) }
    var showCategoryMenu by remember { mutableStateOf(false) }
    var showFullImagePreview by remember { mutableStateOf(false) }

    if (showFullImagePreview) {
        Dialog(onDismissRequest = { showFullImagePreview = false }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xE6000000))
                    .clickable { showFullImagePreview = false },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = screenshot.uri,
                    contentDescription = screenshot.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isEditing) {
                    TextButton(onClick = {
                        isEditing = false
                        draftCategory = categories.firstOrNull { it.equals(details.category, ignoreCase = true) }
                            ?: categories.firstOrNull()
                            ?: details.category
                        draftDescription = details.description
                        showCategoryMenu = false
                    }) {
                        Text("Cancel")
                    }
                    TextButton(onClick = {
                        val saved = onSaveEdits(draftCategory, draftDescription)
                        if (saved) {
                            isEditing = false
                            showCategoryMenu = false
                        }
                    }) {
                        Text("Save")
                    }
                } else {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Image profile",
                    color = Color(0xFF102A43),
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(onClick = {
                    isEditing = !isEditing
                    showCategoryMenu = false
                    if (!isEditing) {
                        draftCategory = categories.firstOrNull { it.equals(details.category, ignoreCase = true) }
                            ?: categories.firstOrNull()
                            ?: details.category
                        draftDescription = details.description
                    }
                }) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "Edit category and description",
                        tint = Color.Gray
                    )
                }
                IconButton(onClick = {
                    showCategoryMenu = false
                    onReclassify()
                }, enabled = !isReclassifying) {
                    if (isReclassifying) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = "Reclassify with latest prompt",
                            tint = Color(0xFF5E6C84)
                        )
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isReclassifying) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF2E9B58),
                        trackColor = Color(0xFFCEE9D7)
                    )
                    Text(
                        text = "Reclassifying this image with latest prompt...",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF2E9B58),
                        fontWeight = FontWeight.Medium
                    )
                }
                AsyncImage(
                    model = screenshot.uri,
                    contentDescription = screenshot.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clickable { showFullImagePreview = true }
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                )
                Text(
                    text = "Tap image to preview full size",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4A6572)
                )

                Surface(
                    color = categoryColor(if (isEditing) draftCategory.trim().ifBlank { details.category } else details.category),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = if (isEditing) draftCategory.trim().ifBlank { details.category } else details.category,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }

                Text("Confidence: ${((details.confidence * 100).toInt())}%")

                if (isEditing) {
                    ExposedDropdownMenuBox(
                        expanded = showCategoryMenu,
                        onExpandedChange = { showCategoryMenu = !showCategoryMenu }
                    ) {
                        OutlinedTextField(
                            value = draftCategory,
                            onValueChange = {},
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            label = { Text("Category") },
                            singleLine = true,
                            readOnly = true,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = showCategoryMenu)
                            }
                        )
                        ExposedDropdownMenu(
                            expanded = showCategoryMenu,
                            onDismissRequest = { showCategoryMenu = false }
                        ) {
                            categories.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        draftCategory = option
                                        showCategoryMenu = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = draftDescription,
                        onValueChange = { draftDescription = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Description") },
                        maxLines = 3
                    )
                } else if (details.description.isNotBlank()) {
                    Text("Description: ${details.description}")
                }

                HorizontalDivider(color = Color(0xFFE2E8F0))
                Text("Image id: ${details.imageId}", style = MaterialTheme.typography.bodySmall)
                Text("URI: ${details.imageUri}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "Updated: ${DateFormat.format("dd MMM yyyy, hh:mm a", details.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4A6572)
                )
            }
        }
    )
}

@Composable
private fun CategoryCollectionSkeleton() {
    val shimmer = rememberInfiniteTransition(label = "categorySkeleton")
    val alpha by shimmer.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "categorySkeletonAlpha"
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(24.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .background(Color(0xFFDCE9E8).copy(alpha = alpha))
            )
            Box(
                modifier = Modifier
                    .size(width = 62.dp, height = 20.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .background(Color(0xFFDCE9E8).copy(alpha = alpha))
            )
        }

        repeat(2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                repeat(2) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1.22f)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                            .background(Color(0xFFDCE9E8).copy(alpha = alpha))
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryGallerySkeleton() {
    val shimmer = rememberInfiniteTransition(label = "gallerySkeleton")
    val alpha by shimmer.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gallerySkeletonAlpha"
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                repeat(2) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                            .background(Color(0xFFDCE9E8).copy(alpha = alpha))
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenshotListSkeleton() {
    val shimmer = rememberInfiniteTransition(label = "listSkeleton")
    val alpha by shimmer.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "listSkeletonAlpha"
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .background(Color(0xFFF3F7F7))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .background(Color(0xFFDCE9E8).copy(alpha = alpha))
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.65f)
                            .height(16.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .background(Color(0xFFDCE9E8).copy(alpha = alpha))
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(12.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .background(Color(0xFFDCE9E8).copy(alpha = alpha))
                    )
                }
            }
        }
    }
}

private fun buildPromptPreview(categories: List<String>, customPrompt: String): String = buildString {
    appendLine("You classify one screenshot into exactly one allowed category.")
    appendLine()
    appendLine("Allowed categories: ${categories.joinToString(", ")}")
    appendLine()
    appendLine("Category definitions:")
    categories.forEach { category ->
        appendLine("- $category: ${promptCategoryDefinition(category)}")
    }
    appendLine()
    appendLine("Rules:")
    appendLine("1. Return exactly one category from the allowed list, preserving its spelling.")
    appendLine("2. Choose the most specific matching category. Use Others only when no other category fits.")
    appendLine("3. Prioritize meaningful readable text and the primary subject over background scenery.")

    categories.findCategoryName("Quotes/Learning")?.let { quotesCategory ->
        appendLine(
            "4. Choose $quotesCategory when the primary content is a quote, lesson, educational text, " +
                "study material, tutorial, or explanation. Do this even if it was shared on a social-media app."
        )
    }
    categories.findCategoryName("Only people no text", "People")?.let { peopleCategory ->
        appendLine(
            "5. Choose $peopleCategory when people are the primary subject and there is no meaningful " +
                "text that clearly fits another category. Ignore tiny incidental text like watermarks, " +
                "status-bar labels, camera overlays, or single-word UI labels."
        )
    }
    categories.findCategoryName("QR")?.let { qrCategory ->
        appendLine("6. Choose $qrCategory when a QR code is clearly visible, even when other content is present.")
    }
    categories.findCategoryName("Maps")?.let { mapsCategory ->
        appendLine("7. Choose $mapsCategory when a map or navigation interface is visible.")
    }
    categories.findCategoryName("Payment/Finance/Bills")?.let { financeCategory ->
        appendLine(
            "8. Choose $financeCategory for payment, banking, invoices, bills, balances, or transactions " +
                "when they are the main content."
        )
    }
    categories.findCategoryName("Movie")?.let { movieCategory ->
        appendLine(
            "9. Choose $movieCategory when the screenshot shows movie titles, movie suggestions, " +
                "film posters, trailer pages, OTT movie lists, or movie recommendation content."
        )
    }
    categories.findCategoryName("Tickets/Bookings")?.let { ticketsCategory ->
        appendLine(
            "10. Choose $ticketsCategory for railway, flight, bus, movie, or hotel tickets, " +
                "boarding passes, booking confirmations, or reservation details."
        )
    }
    categories.findCategoryName("OTP/Verification Codes")?.let { otpCategory ->
        appendLine(
            "11. Choose $otpCategory when the primary content is an OTP, one-time code, login code, " +
                "or SMS/email verification message."
        )
    }
    categories.findCategoryName("Comment Section (YouTube / Instagram)")?.let { commentsCategory ->
        appendLine(
            "12. Choose $commentsCategory only when an actual comment thread is visible, such as multiple " +
                "comments or replies with user names, avatars, timestamps, likes, or reply controls. Do not " +
                "choose it for a standalone quote, caption, post, or image just because it came from YouTube or Instagram."
        )
    }
    categories.findCategoryName("Greeting Messages (Good morning etc)")?.let { greetingCategory ->
        appendLine("13. Choose $greetingCategory when greeting, blessing, festival, or good-morning text is the main content.")
    }
    categories.findCategoryName("Nature/Plants")?.let { natureCategory ->
        appendLine("14. Choose $natureCategory only when nature is the primary subject and no person is present.")
    }
    appendLine("15. Custom instructions below override these baseline rules when they conflict.")
    appendLine()
    appendLine("Custom instructions: ${customPrompt.trim().ifBlank { "None" }}")
    appendLine()
    appendLine("16. Also provide a very short plain-language description of the screenshot in at most 12 words.")
    append("Return JSON only, with no explanation: {\"category\":\"${categories.firstOrNull() ?: "Others"}\",\"confidence\":0.0,\"description\":\"brief summary\"}")
}

private fun List<String>.findCategoryName(vararg names: String): String? = firstOrNull { category ->
    names.any { category.equals(it, ignoreCase = true) }
}

private fun promptCategoryDefinition(category: String): String = when (category.lowercase()) {
    "linkedin" -> "LinkedIn posts, profiles, jobs, professional updates, or workplace content."
    "quotes/learning" -> "Quotes, study material, educational notes, tutorials, or useful explanations."
    "food" -> "Meals, recipes, restaurant menus, cooking, or food and drink products."
    "movie" -> "Movie titles, film recommendations, cinema suggestions, OTT watchlists, reviews, trailers, or movie posters."
    "tickets/bookings" -> "Railway, flight, bus, movie, or hotel tickets, boarding passes, booking confirmations, and reservation details."
    "otp/verification codes" -> "OTP or one-time password messages, login verification codes, authentication SMS, and temporary security codes."
    "payment/finance/bills" -> "Payments, banking, invoices, bills, balances, transactions, or financial notices."
    "identity cards" -> "Official identity documents such as Aadhaar cards, PAN cards, voter IDs, passports, driving licences, or similar IDs."
    "qr" -> "A QR code intended to be scanned."
    "comment section (youtube / instagram)" -> "YouTube, Instagram, or other social-media comments, replies, or discussions."
    "nature/plants" -> "Plants, landscapes, flowers, outdoor scenery, or other nature-focused images."
    "only people no text" -> "People are the main subject. Small incidental text is okay, but do not use this when meaningful text should drive another category."
    "games (bgmi etc)" -> "Game play, game menus, scores, game characters, or gaming-related content."
    "maps" -> "Maps, directions, navigation, routes, or location interfaces."
    "greeting messages (good morning etc)" -> "Greeting, blessing, festival, good-morning, or good-night messages."
    "animals" -> "Animals are the primary subject."
    "others" -> "Anything that does not fit another category."
    else -> "Content that best matches the category name: $category."
}

private val categoryColors = listOf(
    Color(0xFF0E6B6E), Color(0xFFB85450), Color(0xFF8A5D16), Color(0xFF5B6EA6),
    Color(0xFF7B4B94), Color(0xFF34745A), Color(0xFFAA5C27), Color(0xFF4A718C)
)

private fun categoryColor(category: String): Color =
    categoryColors[(category.hashCode() and Int.MAX_VALUE) % categoryColors.size]