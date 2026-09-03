package com.boostt1d.android.engine

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class Priority(val label: String) {
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low"),
}

/**
 * Where a pattern came from. Surfaced to the user (and in the doctor PDF) so the provenance
 * of a finding is never ambiguous.
 */
@Serializable
enum class PatternSource(val label: String) {
    /** Detected and worded entirely by the local formula detector. */
    FORMULA("Formula"),
    /** Detected by the formula; wording, factors and questions improved by AI. */
    AI_ENRICHED("AI-reviewed"),
    /** Proposed by AI from the full data set. Numbers are verified against the data. */
    AI("AI-detected"),
}

@Serializable
enum class WhatHappenedPatternChartKind {
    /** Bars / line of average glucose (mg/dL) per day in the window. */
    AVERAGE_GLUCOSE,
    /** Days that matched the pattern (1) vs not (0). */
    OCCURRENCE_FLAGS,
}

@Serializable
data class WhatHappenedPatternChartPoint(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val value: Double,
    val highlighted: Boolean,
)

/**
 * A single scannable pattern for What Happened.
 *
 * `source` defaults to formula; PatternService produces a copy with a different source when
 * AI contributes wording. Every number is the detector's — a source change never changes a
 * count.
 */
@Serializable
data class WhatHappenedPattern(
    val id: String,
    val source: PatternSource = PatternSource.FORMULA,
    val title: String,
    /** What BoostT1D noticed, in plain language. */
    val observation: String,
    /** How often it occurred, e.g. "5 of 7 days". */
    val frequencyLabel: String,
    val occurrenceCount: Int,
    val opportunityCount: Int,
    val priority: Priority,
    /** Day-level values for a small supporting chart — mg/dL averages or 0/1 flags. */
    val chartPoints: List<WhatHappenedPatternChartPoint>,
    val chartKind: WhatHappenedPatternChartKind,
    val contributingFactors: List<String>,
    val discussQuestions: List<String>,
    /** Internal ranking score; higher is more meaningful. */
    val score: Double,
    /**
     * Clock hours this pattern is about, so the settings review below it on the same page can
     * be pointed at rather than repeated. Empty when the pattern is not tied to a window — day
     * level variability, or anything AI proposed without one.
     */
    val hours: Set<Int> = emptySet(),
)
