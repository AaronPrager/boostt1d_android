package com.boostt1d.android.bolus

/**
 * What Snap a Meal hands the calculator: the estimated carbs plus the glucose, IOB and COB it
 * was already showing, so the user does not retype numbers the app just displayed. All mg/dL.
 */
data class BolusPrefill(
    val carbsGrams: Double,
    val glucoseMgdl: Int? = null,
    val iob: Double = 0.0,
    val cob: Double = 0.0,
)
