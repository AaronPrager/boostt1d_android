package com.boostt1d.android.data

import kotlinx.serialization.json.Json

/**
 * Turns whatever the model sends back into a [FoodAnalysis] — or a specific failure.
 *
 * The model is asked for exact JSON and usually complies, but not always: markdown fences,
 * prose around the object, a missing field. Each strategy below existed on iOS because a real
 * response defeated the one before it. Ported 1:1, including the order the fallbacks fire in.
 */
object FoodAnalysisParsing {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    const val UNSTRUCTURED_NOTE = "The nutrition breakdown could not be read in full, so these numbers were extracted from an unstructured response. Treat them with extra caution and verify the carb estimate yourself."

    class UnparseableResponse(message: String) : Exception(message)

    /** The proxy's raw HTTP body → an analysis, through every fallback iOS has. */
    fun parseBackendBody(body: String): FoodAnalysis {
        val response = runCatching { json.decodeFromString(GeminiResponse.serializer(), body) }.getOrNull()
        if (response != null) {
            val text = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: throw CarbEstimationException.NoFoodDetected()
            return parseCandidateText(text)
        }

        // Not a Gemini envelope. The backend may have answered with the analysis itself.
        runCatching { json.decodeFromString(FoodAnalysis.serializer(), body) }.getOrNull()?.let { return it }
        extractJson(body)?.let { extracted ->
            runCatching { json.decodeFromString(FoodAnalysis.serializer(), extracted) }.getOrNull()?.let { return it }
        }
        throw UnparseableResponse("Unable to process the response from the server. Please try again.")
    }

    /** The model's text → an analysis, or a specific failure. */
    fun parseCandidateText(text: String): FoodAnalysis {
        val extracted = extractJson(text)
        if (extracted != null) {
            val decoded = runCatching { json.decodeFromString(FoodAnalysis.serializer(), extracted) }
            val analysis = decoded.getOrNull()
            if (analysis != null) {
                if (analysis.carbsGrams != null) return analysis
                // Structured but carb-less: the one number that matters, from the text.
                val carbs = extractCarbsFromText(text)
                    ?: throw UnparseableResponse("Could not extract carbohydrate information from the analysis. Please try again with a clearer photo.")
                return FoodAnalysis(description = analysis.description, carbsGrams = carbs, confidence = analysis.confidence, notes = analysis.notes)
            }
            val carbs = extractCarbsFromText(text)
                ?: throw UnparseableResponse("Could not parse the food analysis response. Please try again with a clearer photo.")
            return FoodAnalysis(description = extractDescriptionFromText(text), carbsGrams = carbs, confidence = "Medium", notes = UNSTRUCTURED_NOTE)
        }

        // No JSON at all.
        val carbs = extractCarbsFromText(text) ?: throw CarbEstimationException.NoFoodDetected()
        return FoodAnalysis(description = extractDescriptionFromText(text), carbsGrams = carbs, confidence = "Medium", notes = UNSTRUCTURED_NOTE)
    }

    /** The first complete JSON object in [text], fences stripped; null when none parses. */
    fun extractJson(text: String): String? {
        var candidate = text.trim()

        // Strategy 1: remove a markdown code block.
        val fence = if (candidate.contains("```json")) "```json" else if (candidate.contains("```")) "```" else null
        if (fence != null) {
            val start = candidate.indexOf(fence) + fence.length
            val end = candidate.indexOf("```", start)
            if (end > start) candidate = candidate.substring(start, end).trim()
        }

        // Strategy 2: the first balanced object.
        val open = candidate.indexOf('{')
        if (open >= 0) {
            var depth = 0
            var close = -1
            for (i in open until candidate.length) {
                when (candidate[i]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { close = i + 1; break } }
                }
            }
            if (close > open) {
                val extracted = candidate.substring(open, close)
                if (isValidJson(extracted)) return extracted
            }
        }

        // Strategy 3: the whole string.
        return if (isValidJson(candidate)) candidate else null
    }

    private fun isValidJson(s: String): Boolean = runCatching { json.parseToJsonElement(s) }.isSuccess

    private val carbPatterns = listOf(
        Regex("carbs[\\s_]*grams?[\\s:]*([0-9]+\\.?[0-9]*)", RegexOption.IGNORE_CASE),
        Regex("([0-9]+\\.?[0-9]*)\\s*g(?:rams?)?\\s*(?:of\\s*)?carbs?", RegexOption.IGNORE_CASE),
        Regex("carbohydrates?[\\s:]*([0-9]+\\.?[0-9]*)", RegexOption.IGNORE_CASE),
        Regex("\"carbs_grams\"\\s*:\\s*([0-9]+\\.?[0-9]*)", RegexOption.IGNORE_CASE),
    )

    /** "45 grams", "45g carbs", "carbs: 45" — the first number that reads as carbs. */
    fun extractCarbsFromText(text: String): Double? {
        for (pattern in carbPatterns) {
            val value = pattern.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            if (value != null) return value
        }
        return null
    }

    fun extractDescriptionFromText(text: String): String? {
        Regex("\"description\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.let { return it }
        return text.split('.', '\n').map { it.trim() }.firstOrNull { it.isNotEmpty() }
    }
}
