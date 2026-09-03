package com.boostt1d.android

import com.boostt1d.android.data.CarbEstimationException
import com.boostt1d.android.data.FoodAnalysisParsing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The fallback ladder a real model response has to climb, pinned in the order iOS fires it. */
class FoodAnalysisParsingTest {

    private fun jsonString(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private val envelope = { text: String ->
        """{"candidates":[{"content":{"parts":[{"text":${jsonString(text)}}]}}]}"""
    }

    @Test
    fun `clean JSON in a Gemini envelope decodes straight through`() {
        val analysis = FoodAnalysisParsing.parseBackendBody(envelope("""{"description":"Rice and chicken","carbs_grams":52,"calories_kcal":610,"fat_grams":14,"protein_grams":38,"fiber_grams":3,"confidence":"High","notes":""}"""))
        assertEquals("Rice and chicken", analysis.descriptionText)
        assertEquals(52.0, analysis.carbsValue, 0.0)
        assertEquals("High", analysis.confidenceLevel)
        assertEquals(610.0, analysis.caloriesKcal!!, 0.0)
    }

    @Test
    fun `markdown fences and prose around the object are stripped`() {
        val text = "Sure! Here is the analysis:\n```json\n{\"description\": \"Toast\", \"carbs_grams\": 30, \"confidence\": \"Medium\", \"notes\": \"Butter assumed\"}\n```\nEnjoy."
        val analysis = FoodAnalysisParsing.parseCandidateText(text)
        assertEquals("Toast", analysis.description)
        assertEquals(30.0, analysis.carbsGrams!!, 0.0)
        assertEquals("Butter assumed", analysis.notes)
    }

    @Test
    fun `a structured answer without carbs falls back to the number in the text`() {
        val analysis = FoodAnalysisParsing.parseCandidateText("""{"description":"Salad","confidence":"Low"} The plate has roughly 12 grams of carbs.""")
        assertEquals("Salad", analysis.description)
        assertEquals(12.0, analysis.carbsGrams!!, 0.0)
    }

    @Test
    fun `no JSON at all is read from prose and flagged as unstructured`() {
        val analysis = FoodAnalysisParsing.parseCandidateText("Looks like a bagel with cream cheese. Carbohydrates: 48. Fairly confident.")
        assertEquals(48.0, analysis.carbsGrams!!, 0.0)
        assertEquals("Medium", analysis.confidence)
        assertEquals(FoodAnalysisParsing.UNSTRUCTURED_NOTE, analysis.notes)
        assertEquals("Looks like a bagel with cream cheese", analysis.description)
    }

    @Test
    fun `nothing carb-like anywhere is no food detected, and a non-envelope body is read directly`() {
        assertThrows(CarbEstimationException.NoFoodDetected::class.java) { FoodAnalysisParsing.parseCandidateText("I cannot see any food in this picture.") }
        val direct = FoodAnalysisParsing.parseBackendBody("""{"description":"Apple","carbs_grams":25}""")
        assertEquals(25.0, direct.carbsGrams!!, 0.0)
        assertNull(FoodAnalysisParsing.extractJson("no braces here"))
        assertEquals(45.0, FoodAnalysisParsing.extractCarbsFromText("about 45g carbs")!!, 0.0)
        assertEquals(30.5, FoodAnalysisParsing.extractCarbsFromText("\"carbs_grams\": 30.5")!!, 0.0)
    }
}
