package com.boostt1d.android.engine

import kotlinx.serialization.Serializable

/**
 * AI's reply when reviewing formula-detected patterns. `enriched` improves the wording of
 * patterns we already found (by index); `additional` proposes patterns the formula missed.
 */
@Serializable
data class AIPatternResponse(
    val enriched: List<AIEnrichedPattern>? = null,
    val additional: List<AIProposedPattern>? = null,
) {
    val resolvedEnriched: List<AIEnrichedPattern> get() = enriched ?: emptyList()
    val resolvedAdditional: List<AIProposedPattern> get() = additional ?: emptyList()
}

@Serializable
data class AIEnrichedPattern(
    /** Position of the formula pattern this refines, 0-based. */
    val index: Int,
    val observation: String? = null,
    val contributingFactors: List<String>? = null,
    val discussQuestions: List<String>? = null,
)

/**
 * A pattern AI found on its own. There are no chart values here by design — the app computes
 * those from real data rather than trusting AI to produce numbers.
 */
@Serializable
data class AIProposedPattern(
    val title: String,
    val observation: String,
    /** Days AI believes matched. Clamped against the real day count before display. */
    val occurrenceCount: Int? = null,
    val priority: String? = null,
    val contributingFactors: List<String>? = null,
    val discussQuestions: List<String>? = null,
)
