package com.amoldalwai.screenshotcategory

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.net.Uri
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalApi::class)
class GemmaClassifier(private val context: Context, private val modelFile: File) {
    private var engine: Engine? = null
    private var runtimeProfileLabel: String = "Unknown"

    fun warmUp() {
        getOrCreateEngine()
    }

    fun classify(
        uri: Uri,
        categories: List<String>,
        customPrompt: String,
        visionMode: VisionMode = VisionMode.Balanced
    ): Classification = classifyWithMetrics(uri, categories, customPrompt, visionMode).classification

    fun classifyWithMetrics(
        uri: Uri,
        categories: List<String>,
        customPrompt: String,
        visionMode: VisionMode = VisionMode.Balanced
    ): ClassificationRunMetrics {
        ExperimentalFlags.visualTokenBudget = visionMode.visualTokenBudget
        val decodeStart = System.currentTimeMillis()
        val imageFile = preprocessImageForVision(uri, visionMode.maxImageLongSide)
        val decodeMs = (System.currentTimeMillis() - decodeStart).coerceAtLeast(0L)
        val activeEngine = getOrCreateEngine()

        val inferenceStart = System.currentTimeMillis()
        val classification = activeEngine.createConversation(
            ConversationConfig(
                thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                maxOutputToken = 64
            )
        ).use { conversation ->
            val response = conversation.sendMessage(
                Contents.of(
                    Content.ImageFile(imageFile.absolutePath),
                    Content.Text(buildClassificationPrompt(categories, customPrompt))
                )
            ).contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString(separator = "") { it.text }
            Classification.fromModelResponse(response, categories)
        }
        val inferenceMs = (System.currentTimeMillis() - inferenceStart).coerceAtLeast(0L)

        return ClassificationRunMetrics(
            classification = classification,
            decodeDownscaleMs = decodeMs,
            inferenceMs = inferenceMs,
            runtimeProfileLabel = runtimeProfileLabel,
            visionModeLabel = visionMode.label,
            tokenBudget = visionMode.visualTokenBudget,
            resizeLongSidePx = visionMode.maxImageLongSide
        )
    }

    fun close() {
        engine?.close()
        engine = null
    }

    private fun getOrCreateEngine(): Engine {
        engine?.let { return it }
        ExperimentalFlags.enableSpeculativeDecoding = false

        val gpuVisionConfig = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.CPU(),
            visionBackend = Backend.GPU(),
            cacheDir = context.cacheDir.absolutePath
        )
        val cpuFallbackConfig = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.CPU(),
            visionBackend = Backend.CPU(),
            cacheDir = context.cacheDir.absolutePath
        )

        return runCatching {
            Engine(gpuVisionConfig).also { it.initialize() }
        }.onSuccess {
            runtimeProfileLabel = "Text backend: CPU | Vision backend: GPU"
        }.recoverCatching {
            Engine(cpuFallbackConfig).also { it.initialize() }
        }.onSuccess {
            if (runtimeProfileLabel == "Unknown") {
                runtimeProfileLabel = "Text backend: CPU | Vision backend: CPU (fallback)"
            }
        }.getOrThrow().also {
            engine = it
        }
    }

    private fun buildClassificationPrompt(categories: List<String>, customPrompt: String): String = buildString {
        appendLine("You classify one screenshot into exactly one allowed category.")
        appendLine()
        appendLine("Allowed categories: ${categories.joinToString(", ")}")
        appendLine()
        appendLine("Category definitions:")
        categories.forEach { category ->
            appendLine("- $category: ${categoryDefinition(category)}")
        }
        appendLine()
        appendLine("Rules:")
        appendLine("1. Return exactly one category from the allowed list, preserving its spelling.")
        appendLine("2. Choose the most specific matching category. Use Others only when no other category fits.")
        appendLine("3. Prioritize meaningful readable text and the primary subject over background scenery.")

        categories.findCategory("Quotes/Learning")?.let { quotesCategory ->
            appendLine(
                "4. Choose $quotesCategory when the primary content is a quote, lesson, educational text, " +
                    "study material, tutorial, or explanation. Do this even if it was shared on a social-media app."
            )
        }
        categories.findCategory("Only people no text", "People")?.let { peopleCategory ->
            appendLine(
                "5. Choose $peopleCategory when people are the primary subject and there is no meaningful " +
                    "text that clearly fits another category. Ignore tiny incidental text like watermarks, " +
                    "status-bar labels, camera overlays, or single-word UI labels."
            )
        }
        categories.findCategory("QR")?.let { qrCategory ->
            appendLine("6. Choose $qrCategory when a QR code is clearly visible, even when other content is present.")
        }
        categories.findCategory("Maps")?.let { mapsCategory ->
            appendLine("7. Choose $mapsCategory when a map or navigation interface is visible.")
        }
        categories.findCategory("Payment/Finance/Bills")?.let { financeCategory ->
            appendLine(
                "8. Choose $financeCategory for payment, banking, invoices, bills, balances, or transactions " +
                    "when they are the main content."
            )
        }
        categories.findCategory("Movie")?.let { movieCategory ->
            appendLine(
                "9. Choose $movieCategory when the screenshot shows movie titles, movie suggestions, " +
                    "film posters, trailer pages, OTT movie lists, or movie recommendation content."
            )
        }
        categories.findCategory("Tickets/Bookings")?.let { ticketsCategory ->
            appendLine(
                "10. Choose $ticketsCategory for railway, flight, bus, movie, or hotel tickets, " +
                    "boarding passes, booking confirmations, or reservation details."
            )
        }
        categories.findCategory("OTP/Verification Codes")?.let { otpCategory ->
            appendLine(
                "11. Choose $otpCategory when the primary content is an OTP, one-time code, login code, " +
                    "or SMS/email verification message."
            )
        }
        categories.findCategory("Comment Section (YouTube / Instagram)")?.let { commentsCategory ->
            appendLine(
                "12. Choose $commentsCategory only when an actual comment thread is visible, such as multiple " +
                    "comments or replies with user names, avatars, timestamps, likes, or reply controls. Do not " +
                    "choose it for a standalone quote, caption, post, or image just because it came from YouTube or Instagram."
            )
        }
        categories.findCategory("Greeting Messages (Good morning etc)")?.let { greetingCategory ->
            appendLine("13. Choose $greetingCategory when greeting, blessing, festival, or good-morning text is the main content.")
        }
        categories.findCategory("Nature/Plants")?.let { natureCategory ->
            appendLine("14. Choose $natureCategory only when nature is the primary subject and no person is present.")
        }
        appendLine("15. Custom instructions below override these baseline rules when they conflict.")
        appendLine()
        appendLine("Custom instructions: ${customPrompt.trim().ifBlank { "None" }}")
        appendLine()
        appendLine("16. Also provide a very short plain-language description of the screenshot in at most 12 words.")
        appendLine("17. Ensure category and description are semantically consistent. Use Others only when no listed category matches the described content.")
        append("Return JSON only, with no explanation: {\"category\":\"${categories.first()}\",\"confidence\":0.0,\"description\":\"brief summary\"}")
    }

    private fun List<String>.findCategory(vararg names: String): String? = firstOrNull { category ->
        names.any { category.equals(it, ignoreCase = true) }
    }

    private fun categoryDefinition(category: String): String = when (category.lowercase()) {
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

    private fun preprocessImageForVision(uri: Uri, maxLongSide: Int): File {
        val decoded = decodeDownscaledBitmap(uri, maxLongSide)
        if (decoded == null) {
            return copyOriginalToCache(uri)
        }

        val destination = File.createTempFile("screenshot-", ".jpg", context.cacheDir)
        FileOutputStream(destination).use { output ->
            decoded.compress(Bitmap.CompressFormat.JPEG, 90, output)
        }
        decoded.recycle()
        return destination
    }

    private fun decodeDownscaledBitmap(uri: Uri, maxLongSide: Int): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, boundsOptions)
        } ?: return null

        val sourceWidth = boundsOptions.outWidth
        val sourceHeight = boundsOptions.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return null

        val longest = maxOf(sourceWidth, sourceHeight)
        val sample = (longest / maxLongSide).coerceAtLeast(1)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = Integer.highestOneBit(sample)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        } ?: return null

        val decodedLongest = maxOf(decoded.width, decoded.height)
        if (decodedLongest <= maxLongSide) return decoded

        val ratio = maxLongSide.toFloat() / decodedLongest.toFloat()
        val scaledWidth = (decoded.width * ratio).toInt().coerceAtLeast(1)
        val scaledHeight = (decoded.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(decoded, scaledWidth, scaledHeight, true)
        if (scaled != decoded) {
            decoded.recycle()
        }
        return scaled
    }

    private fun copyOriginalToCache(uri: Uri): File {
        val destination = File.createTempFile("screenshot-", ".image", context.cacheDir)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Could not read screenshot")
        val output = destination.outputStream()
        try {
            input.copyTo(output)
        } finally {
            output.close()
            input.close()
        }
        return destination
    }

}

enum class VisionMode(
    val label: String,
    val visualTokenBudget: Int,
    val maxImageLongSide: Int
) {
    SuperFast(label = "Super Fast", visualTokenBudget = 70, maxImageLongSide = 224),
    Fast(label = "Fast", visualTokenBudget = 140, maxImageLongSide = 256),
    Balanced(label = "Balanced", visualTokenBudget = 280, maxImageLongSide = 384),
    Quality(label = "Quality", visualTokenBudget = 560, maxImageLongSide = 512)
}

data class ClassificationRunMetrics(
    val classification: Classification,
    val decodeDownscaleMs: Long,
    val inferenceMs: Long,
    val runtimeProfileLabel: String,
    val visionModeLabel: String,
    val tokenBudget: Int,
    val resizeLongSidePx: Int
)

data class Classification(
    val category: String,
    val confidence: Float,
    val description: String = ""
) {
    companion object {
        private val categoryPattern = Regex("""[\"]category[\"]\s*:\s*[\"]([^\"]+)[\"]""")
        private val confidencePattern = Regex("""[\"]confidence[\"]\s*:\s*([0-9.]+)""")
        private val descriptionPattern = Regex("""[\"]description[\"]\s*:\s*[\"]([^\"]*)[\"]""")

        fun fromModelResponse(response: String, allowedCategories: List<String>): Classification {
            val requestedCategory = categoryPattern.find(response)?.groupValues?.get(1)
            val description = descriptionPattern.find(response)?.groupValues?.get(1)?.trim().orEmpty()
            val normalizedRequested = normalizeCategoryKey(requestedCategory)
            val category = resolveCategory(normalizedRequested, description, allowedCategories)
                ?: allowedCategories.last()
            val confidence = confidencePattern.find(response)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            return Classification(category, confidence.coerceIn(0f, 1f), description)
        }

        private fun resolveCategory(
            normalizedRequested: String,
            description: String,
            allowedCategories: List<String>
        ): String? {
            val normalizedAllowed = allowedCategories.associateBy { normalizeCategoryKey(it) }
            normalizedAllowed[normalizedRequested]?.let { return it }

            val compactRequested = normalizedRequested.replace(" ", "")
            if (compactRequested.isNotEmpty()) {
                allowedCategories.firstOrNull {
                    normalizeCategoryKey(it).replace(" ", "") == compactRequested
                }?.let { return it }
            }

            val peopleCategory = allowedCategories.firstOrNull {
                normalizeCategoryKey(it) == "only people no text"
            }
            if (peopleCategory != null && normalizedRequested in setOf(
                    "people",
                    "person",
                    "only people",
                    "people no text",
                    "person no text"
                )
            ) {
                return peopleCategory
            }

            inferCategoryByKeywords(normalizedRequested, allowedCategories)?.let { return it }

            val requestedIsOthers = normalizedRequested == "others" || normalizedRequested == "other"
            if (requestedIsOthers) {
                inferCategoryByKeywords(normalizeCategoryKey(description), allowedCategories)?.let { return it }
            }

            return null
        }

        private fun inferCategoryByKeywords(text: String, allowedCategories: List<String>): String? {
            if (text.isBlank()) return null

            val conceptHints = listOf(
                listOf("quotes learning", "quote", "learning", "study", "tutorial", "lesson", "educational", "motivation"),
                listOf("payment finance bills", "payment", "bank", "invoice", "bill", "transaction", "upi", "balance"),
                listOf("comment section youtube instagram", "comment", "comments", "reply", "thread", "replies"),
                listOf("greeting messages good morning", "good morning", "good night", "greetings", "festival"),
                listOf("identity cards", "aadhaar", "pan", "passport", "driving licence", "driving license", "id card"),
                listOf("maps", "map", "navigation", "route", "directions", "location"),
                listOf("qr", "qr code", "barcode", "scan code"),
                listOf("only people no text", "person", "people", "selfie", "portrait"),
                listOf("food", "meal", "recipe", "restaurant", "dish", "drink"),
                listOf("movie", "movie", "movies", "film", "cinema", "poster", "trailer", "imdb", "rottentomatoes", "ott", "watchlist"),
                listOf("tickets bookings", "ticket", "tickets", "pnr", "boarding", "reservation", "booking", "booked", "railway", "hotel", "flight", "bus", "check-in"),
                listOf("otp verification codes", "otp", "verification code", "one time password", "one-time password", "login code", "security code", "auth code"),
                listOf("animals", "animal", "pet", "dog", "cat", "bird"),
                listOf("nature plants", "plant", "tree", "flower", "landscape", "nature"),
                listOf("games bgmi", "game", "gaming", "bgmi", "score", "gameplay"),
                listOf("linkedin", "linkedin", "job", "workplace", "professional")
            )

            for (hints in conceptHints) {
                if (hints.drop(1).any { text.contains(it) }) {
                    val targetCategory = allowedCategories.firstOrNull { allowed ->
                        val normalizedAllowed = normalizeCategoryKey(allowed)
                        hints.any { hint ->
                            normalizedAllowed.contains(hint) || hint.contains(normalizedAllowed)
                        }
                    }
                    if (targetCategory != null) return targetCategory
                }
            }

            return null
        }

        private fun normalizeCategoryKey(value: String?): String = value
            .orEmpty()
            .trim()
            .trim('"', '\'', '.', ',', ';', ':', '!', '?', '(', ')', '[', ']', '{', '}')
            .replace("&", " and ")
            .replace("/", " ")
            .replace("-", " ")
            .replace(Regex("""[^a-zA-Z0-9\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .lowercase()
    }
}