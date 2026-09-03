package com.boostt1d.android.sync

import android.util.Base64
import com.boostt1d.android.data.Config
import com.boostt1d.android.data.FoodAnalysis
import com.boostt1d.android.data.FoodAnalysisParsing
import com.boostt1d.android.data.GeminiContent
import com.boostt1d.android.data.GeminiGenerationConfig
import com.boostt1d.android.data.GeminiInlineData
import com.boostt1d.android.data.GeminiPart
import com.boostt1d.android.data.GeminiRequest
import com.boostt1d.android.data.GeminiResponse
import com.boostt1d.android.data.InsulinTherapyType
import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.data.NightscoutProfileDocument
import com.boostt1d.android.data.NightscoutTreatment
import com.boostt1d.android.data.UsageTracker
import com.boostt1d.android.engine.AIDailyTherapyReview
import com.boostt1d.android.engine.AIPatternResponse
import com.boostt1d.android.engine.AIPrompts
import com.boostt1d.android.engine.DailyTherapyReviewService
import com.boostt1d.android.engine.DoctorVisitTherapySnapshot
import com.boostt1d.android.engine.PatternReviewer
import com.boostt1d.android.engine.TherapySettingsReview
import com.boostt1d.android.engine.WhatHappenedPattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** A refused or failed backend call, with the message the user reads. */
class BackendException(val code: Int, message: String) : IOException(message)

/**
 * The BoostT1D proxy: meal photos to `/api/food-analysis`, text prompts to `/api/insights`.
 * Bodies are Gemini's own request/response shapes, forwarded unchanged; the key lives on the
 * server. No direct-key path exists here — users never supply one, and a developer key is an
 * iOS-only convenience.
 *
 * Ported from the network half of the iOS APIService.
 */
class BoostBackend(
    private val usage: UsageTracker,
    /** What the user said about their delivery, so the prompt's delivery context can honour it. */
    private val therapyType: () -> InsulinTherapyType,
    private val foodUrl: String = Config.BACKEND_FOOD_ANALYSIS_URL,
    private val insightsUrl: String = Config.BACKEND_INSIGHTS_URL,
    private val timeZone: TimeZone = TimeZone.getDefault(),
    client: OkHttpClient? = null,
) : DailyTherapyReviewService.Reviewer, PatternReviewer {

    // Analysis prompts are large; give the model room to answer, but fail cleanly rather than
    // leaving the caller hanging on the default timeout.
    private val client: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false; isLenient = true }
    private val jsonMedia = "application/json".toMediaType()

    // MARK: - Text

    /**
     * Text-only completion through the insights proxy. 5xx responses are usually transient —
     * "model overloaded" is a routine 503 — so retry with backoff before giving up.
     */
    suspend fun requestGeminiText(prompt: String, maxAttempts: Int = 3): String {
        val attempts = maxAttempts.coerceAtLeast(1)
        var attempt = 1
        while (true) {
            try {
                return requestGeminiTextOnce(prompt)
            } catch (error: BackendException) {
                if (error.code in 500..599 && attempt < attempts) {
                    delay(attempt * 1_000L) // 1s, then 2s.
                    attempt += 1
                } else {
                    throw error
                }
            }
        }
    }

    private suspend fun requestGeminiTextOnce(prompt: String): String = withContext(Dispatchers.IO) {
        val body = GeminiRequest(listOf(GeminiContent(listOf(GeminiPart(text = prompt)))), GeminiGenerationConfig.deterministic)
        val response = post(insightsUrl, json.encodeToString(GeminiRequest.serializer(), body), endpointName = "BoostT1D server")
        val decoded = runCatching { json.decodeFromString(GeminiResponse.serializer(), response) }.getOrNull()
            ?: throw BackendException(0, "Unable to process the response from the server. Please try again.")
        decoded.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text?.takeIf { it.isNotBlank() }
            ?: throw BackendException(0, "The AI service returned an empty answer. Please try again.")
    }

    /** One POST; anything but 200 becomes a [BackendException] with the message iOS shows for that status. */
    private fun post(url: String, jsonBody: String, endpointName: String): String {
        val request = Request.Builder().url(url).post(jsonBody.toRequestBody(jsonMedia)).header("Content-Type", "application/json").build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: ""
            if (response.code != 200) {
                val message = when (response.code) {
                    401 -> "AI service authentication failed. Please try again later."
                    404 -> "API endpoint not found."
                    405 -> "The server doesn't accept this request method."
                    413 -> "The photo is too large for the server. Try again with a different image or crop the photo."
                    // Name the endpoint: a 503 from the proxy and one from Google are different problems.
                    in 500..599 -> "$endpointName returned ${response.code}. It may be busy or unavailable — please try again."
                    else -> "Server error (Status: ${response.code}). Please try again."
                }
                throw BackendException(response.code, message)
            }
            return text
        }
    }

    // MARK: - Food

    /**
     * A meal photo, already sized for upload, to a nutrition estimate. The daily allowance is
     * checked first and spent only on success.
     */
    suspend fun analyzeFood(jpeg: ByteArray, nowMillis: Long = System.currentTimeMillis()): FoodAnalysis {
        if (!usage.hasRemaining(nowMillis)) {
            throw BackendException(1002, "You've used all ${Config.FOOD_ANALYSIS_DAILY_LIMIT} free estimations for today. Please wait until midnight for the daily reset.")
        }
        val body = GeminiRequest(
            listOf(GeminiContent(listOf(
                GeminiPart(text = AIPrompts.foodAnalysis),
                GeminiPart(inlineData = GeminiInlineData("image/jpeg", Base64.encodeToString(jpeg, Base64.NO_WRAP))),
            )))
        )
        val response = withContext(Dispatchers.IO) { post(foodUrl, json.encodeToString(GeminiRequest.serializer(), body), endpointName = "BoostT1D server") }
        val analysis = FoodAnalysisParsing.parseBackendBody(response)
        usage.use(nowMillis)
        return analysis
    }

    // MARK: - Once-daily therapy review

    override suspend fun reviewDailyTherapyPlan(
        glucoseEntries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment>,
        foodLogSummary: String,
        profile: NightscoutProfileDocument?,
        formulaReview: TherapySettingsReview,
        lowGlucose: Double,
        highGlucose: Double,
        periodStartMillis: Long,
        periodEndMillis: Long,
    ): AIDailyTherapyReview {
        if (glucoseEntries.size < 30) throw BackendException(1005, "Not enough glucose data for an AI review.")
        val prompt = AIPrompts.dailyTherapyPlan(
            glucoseEntries, treatments, foodLogSummary, DoctorVisitTherapySnapshot.from(profile), formulaReview,
            lowGlucose, highGlucose, periodStartMillis, periodEndMillis, therapyType(), timeZone,
        )
        // The cache has already spent today's allowance before reaching this call. Do not turn
        // one daily review into multiple paid requests when the model returns a 5xx.
        val text = requestGeminiText(prompt, maxAttempts = 1)
        val extracted = FoodAnalysisParsing.extractJson(text) ?: throw BackendException(0, "The AI review could not be read.")
        val decoded = json.decodeFromString(AIDailyTherapyReview.serializer(), extracted)
        // Clamp unbounded arrays so a verbose response cannot overwhelm the screen or the cache;
        // invented keys are filtered again during assembly.
        return decoded.copy(
            recommendations = decoded.recommendations.take(5),
            observations = decoded.observations.take(4),
            experiments = decoded.experiments.take(6),
            safetyNotes = decoded.safetyNotes.take(4),
        )
    }

    // MARK: - Pattern wording (gated off, as on iOS)

    override suspend fun reviewPatterns(
        formulaPatterns: List<WhatHappenedPattern>,
        entries: List<NightscoutGlucoseEntry>,
        treatments: List<NightscoutTreatment>,
        lowGlucose: Double,
        highGlucose: Double,
        timeRangeDays: Int,
        mealContext: String,
    ): AIPatternResponse {
        val prompt = AIPrompts.patternReview(formulaPatterns, entries, treatments, lowGlucose, highGlucose, timeRangeDays, mealContext, null, therapyType(), timeZone)
        val text = requestGeminiText(prompt)
        val extracted = FoodAnalysisParsing.extractJson(text) ?: throw BackendException(0, "The pattern review could not be read.")
        return json.decodeFromString(AIPatternResponse.serializer(), extracted)
    }
}
