package com.boostt1d.android.data

import java.util.UUID
import kotlin.math.roundToInt

/**
 * The rules by which a carb-bearing treatment becomes a Food Log row. Pure, so the decision
 * is testable without a database; the repository applies it.
 *
 * Ported from the iOS FoodLogService.importCarbsFromTreatments.
 */
object FoodLogImport {

    /** Every id a row could have been linked under — the current cache key and the legacy raw ids. */
    fun linkIds(treatment: NightscoutTreatment): List<String> =
        listOfNotNull(treatment.cacheKey, treatment.id, treatment.mongoId)

    fun description(treatment: NightscoutTreatment): String {
        treatment.notes?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        treatment.eventType?.trim()?.takeIf { it.isNotEmpty() && it != "Carb" && it != "Carb Correction" }?.let { return it }
        val grams = (treatment.carbs ?: 0.0).roundToInt()
        return if (grams > 0) "${grams}g carbs" else "Carbs"
    }

    /** The note travels only when it says something the description does not already say. */
    fun notes(treatment: NightscoutTreatment, description: String): String? =
        treatment.notes?.trim()?.takeIf { it.isNotEmpty() && it != description }

    fun source(treatment: NightscoutTreatment): FoodLogSource =
        if (treatment.enteredBy == LogRepository.ENTERED_BY_MANUAL) FoodLogSource.MANUAL else FoodLogSource.NIGHTSCOUT_EVENT

    /**
     * The rows to insert for [treatments], given the link ids already present. Idempotent: a
     * treatment whose cache key or legacy id is already linked yields nothing, and a treatment
     * appearing twice in one batch yields one row.
     */
    fun plan(treatments: List<NightscoutTreatment>, existingLinks: Set<String>): List<FoodLogEntryEntity> {
        val seen = existingLinks.toMutableSet()
        val rows = mutableListOf<FoodLogEntryEntity>()
        for (treatment in treatments) {
            if (!treatment.recordsCarbsForFoodLog) continue
            if (linkIds(treatment).any { it in seen }) continue
            val description = description(treatment)
            rows += FoodLogEntryEntity(
                id = UUID.randomUUID().toString(),
                recordedAtMillis = treatment.recordedAtMillis,
                descriptionText = description,
                carbsGrams = treatment.carbs ?: 0.0,
                fatGrams = treatment.fat?.takeIf { it > 0 },
                proteinGrams = treatment.protein?.takeIf { it > 0 },
                notes = notes(treatment, description),
                source = source(treatment).rawValue,
                linkedTreatmentId = treatment.cacheKey,
                insulinUnits = treatment.insulin?.takeIf { it > 0 },
            )
            seen += treatment.cacheKey
        }
        return rows
    }
}
