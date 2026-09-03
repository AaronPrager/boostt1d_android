package com.boostt1d.android.engine

/** Scannable pattern insight for the Pattern Analysis screen. */
data class PatternInsight(
    val id: String,
    /** Dedup key, e.g. `00:00-02:00` or `7-day overview`. */
    val timeSlotKey: String,
    val timeWindow: String,
    val title: String,
    val priority: Priority,
    val summary: String,
    val comparison: String?,
    val inRangePercent: Int?,
    val highPercent: Int?,
    val lowPercent: Int?,
    val readingCount: Int?,
    val contributors: List<String>,
    val doctorQuestions: List<String>,
) {
    val hasMetricStats: Boolean
        get() = inRangePercent != null && highPercent != null && lowPercent != null

    companion object {
        /**
         * View-model over the shared [WhatHappenedPattern], so the Insights screen can render
         * the same findings as What Happened and the doctor PDF without a second engine.
         * [WhatHappenedPattern] is the source of truth; this is presentation only.
         */
        fun from(pattern: WhatHappenedPattern) = PatternInsight(
            id = pattern.id,
            timeSlotKey = pattern.title,
            timeWindow = pattern.frequencyLabel,
            title = pattern.title,
            priority = pattern.priority,
            summary = pattern.observation,
            comparison = if (pattern.source == PatternSource.FORMULA) null else pattern.source.label,
            inRangePercent = null,
            highPercent = null,
            lowPercent = null,
            readingCount = null,
            contributors = pattern.contributingFactors,
            doctorQuestions = pattern.discussQuestions,
        )
    }
}
