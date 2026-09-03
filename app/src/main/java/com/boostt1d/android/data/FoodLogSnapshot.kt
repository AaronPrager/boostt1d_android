package com.boostt1d.android.data

/**
 * One logged meal, as a plain value.
 *
 * The engine reads meals from this rather than from a persisted entity so it can be handed
 * six numbers in a test without standing up a database — the same reason iOS has a snapshot
 * beside its SwiftData model. Carbs stay nullable: a photographed meal with no estimate is
 * still a meal, it just cannot be joined to a glucose curve.
 */
data class FoodLogSnapshot(
    val id: String,
    val recordedAtMillis: Long,
    val descriptionText: String,
    val carbsGrams: Double?,
    val insulinUnits: Double?,
    val fatGrams: Double?,
    val proteinGrams: Double?,
    val fiberGrams: Double?,
    val notes: String?,
)
