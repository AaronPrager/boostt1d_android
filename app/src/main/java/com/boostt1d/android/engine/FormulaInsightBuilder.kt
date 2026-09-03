package com.boostt1d.android.engine

import java.util.UUID

enum class OutOfRangeDriver { MOSTLY_HIGH, MOSTLY_LOW, MIXED }

/** Lightweight stats bundle for insight generation. */
data class SlotAnalysisSnapshot(
    val averageGlucose: Double,
    val timeInRange: Double,
    val timeBelowRange: Double,
    val timeAboveRange: Double,
    val dataPoints: Int,
    val isClosedLoop: Boolean = false,
)

/**
 * Deterministic, scannable copy for rule-based and AI pattern insights.
 *
 * Ported 1:1 from the iOS FormulaInsightBuilder.
 */
object FormulaInsightBuilder {

    // MARK: - Rule-based factories

    fun makeSlotTIRInsight(
        timeSlotKey: String,
        priority: Priority,
        analysis: SlotAnalysisSnapshot,
        overallTIR: Int,
        periodDays: Int,
        belowTarget: Int,
        aboveTarget: Int,
    ): PatternInsight {
        val window = formatTimeWindow(timeSlotKey)
        val period = periodName(timeSlotKey)
        val driver = outOfRangeDriver(below = belowTarget, above = aboveTarget)
        val inRange = analysis.timeInRange.toInt()

        return PatternInsight(
            id = UUID.randomUUID().toString(),
            timeSlotKey = timeSlotKey,
            timeWindow = window,
            title = patternTitle(period, driver),
            priority = priority,
            summary = tirSummary(inRange, window, driver),
            comparison = null,
            inRangePercent = inRange,
            highPercent = aboveTarget,
            lowPercent = belowTarget,
            readingCount = analysis.dataPoints,
            contributors = contributorsForOutOfRange(driver, analysis.isClosedLoop),
            doctorQuestions = doctorQuestionsForSlot(driver, period, window),
        )
    }

    fun makeSustainedHighInsight(
        timeSlotKey: String,
        priority: Priority,
        analysis: SlotAnalysisSnapshot,
        pointsAbove: Int,
        highTarget: Int,
        overallTIR: Int,
        periodDays: Int,
        isClosedLoop: Boolean,
    ): PatternInsight {
        val window = formatTimeWindow(timeSlotKey)
        val period = periodName(timeSlotKey)

        return PatternInsight(
            id = UUID.randomUUID().toString(),
            timeSlotKey = timeSlotKey,
            timeWindow = window,
            title = "$period elevated glucose",
            priority = priority,
            summary = "Average ${analysis.averageGlucose.toInt()} mg/dL from $window — about $pointsAbove mg/dL above your $highTarget mg/dL upper target.",
            comparison = null,
            inRangePercent = analysis.timeInRange.toInt(),
            highPercent = analysis.timeAboveRange.toInt(),
            lowPercent = analysis.timeBelowRange.toInt(),
            readingCount = analysis.dataPoints,
            contributors = contributorsForSustainedHighs(isClosedLoop),
            doctorQuestions = listOf(
                "Could background insulin coverage for $window be worth reviewing together?",
                "What should I log during this window to understand these sustained highs?",
            ),
        )
    }

    fun makeSustainedLowInsight(
        timeSlotKey: String,
        priority: Priority,
        analysis: SlotAnalysisSnapshot,
        pointsBelow: Int,
        lowTarget: Int,
        periodDays: Int,
        isClosedLoop: Boolean,
    ): PatternInsight {
        val window = formatTimeWindow(timeSlotKey)
        val period = periodName(timeSlotKey)

        return PatternInsight(
            id = UUID.randomUUID().toString(),
            timeSlotKey = timeSlotKey,
            timeWindow = window,
            title = "$period lows",
            priority = priority,
            summary = "Average ${analysis.averageGlucose.toInt()} mg/dL from $window — about $pointsBelow mg/dL below your $lowTarget mg/dL lower target.",
            comparison = null,
            inRangePercent = analysis.timeInRange.toInt(),
            highPercent = analysis.timeAboveRange.toInt(),
            lowPercent = analysis.timeBelowRange.toInt(),
            readingCount = analysis.dataPoints,
            contributors = contributorsForSustainedLows(isClosedLoop),
            doctorQuestions = listOf(
                "If lows keep appearing in this window, could overnight or background insulin coverage be worth reviewing?",
                "What triggers should I log to understand what may be driving them?",
            ),
        )
    }

    fun makeCorrectionMismatchInsight(
        timeSlotKey: String,
        priority: Priority,
        analysis: SlotAnalysisSnapshot?,
        correctionCount: Int,
        percentDiff: Int,
        periodDays: Int,
    ): PatternInsight {
        val window = formatTimeWindow(timeSlotKey)

        return PatternInsight(
            id = UUID.randomUUID().toString(),
            timeSlotKey = timeSlotKey,
            timeWindow = window,
            title = "Correction response pattern",
            priority = priority,
            summary = "$correctionCount corrections from $window suggest insulin sensitivity may differ from your configured factor by about $percentDiff%.",
            comparison = null,
            inRangePercent = analysis?.timeInRange?.toInt(),
            highPercent = analysis?.timeAboveRange?.toInt(),
            lowPercent = analysis?.timeBelowRange?.toInt(),
            readingCount = analysis?.dataPoints,
            contributors = listOf(
                "Insulin sensitivity shifts by time of day",
                "Exercise, stress, or sleep changes",
                "Glucose change 2–3 hours after corrections",
            ),
            doctorQuestions = listOf(
                "If this keeps happening, could my correction factor for $window be worth reviewing?",
                "How much data would you want before adjusting sensitivity for this time of day?",
            ),
        )
    }

    fun makePostMealSpikeInsight(
        priority: Priority,
        totalMeals: Int,
        postMealSpikes: Int,
        spikeRate: Int,
        periodDays: Int,
    ): PatternInsight = PatternInsight(
        id = UUID.randomUUID().toString(),
        timeSlotKey = "Meal Times",
        timeWindow = "Meal times",
        title = "Post-meal rises",
        priority = priority,
        summary = "Glucose rose above target within 2 hours after $postMealSpikes of $totalMeals logged meals ($spikeRate%).",
        comparison = null,
        inRangePercent = null,
        highPercent = null,
        lowPercent = null,
        readingCount = totalMeals,
        contributors = listOf(
            "Bolus timing relative to meals",
            "Meal composition and absorption speed",
            "Active insulin from earlier doses",
        ),
        doctorQuestions = listOf(
            "If post-meal rises keep happening, could bolus timing or carb ratios be worth reviewing for my typical meals?",
            "What should I log to evaluate post-meal patterns safely?",
        ),
    )

    fun makeOverviewInsight(
        timeSlotKey: String,
        priority: Priority,
        overallTIR: Int,
        belowRange: Int,
        aboveRange: Int,
        readingCount: Int,
        averageGlucose: Int,
        periodDays: Int,
        worstWindowLabels: List<String>,
    ): PatternInsight {
        val driver = outOfRangeDriver(below = belowRange, above = aboveRange)
        var summary = "Only $overallTIR% in range over the last $periodDays days. "
        summary += driverPhrase(driver)
        if (worstWindowLabels.isNotEmpty()) {
            summary += " Weakest windows: ${worstWindowLabels.take(2).joinToString(", ")}."
        }

        return PatternInsight(
            id = UUID.randomUUID().toString(),
            timeSlotKey = timeSlotKey,
            timeWindow = "$periodDays-day view",
            title = overviewTitle(driver),
            priority = priority,
            summary = summary,
            comparison = if (overallTIR < 70) "Below common goal of ~70% in range" else null,
            inRangePercent = overallTIR,
            highPercent = aboveRange,
            lowPercent = belowRange,
            readingCount = readingCount,
            contributors = contributorsForOutOfRange(driver, isClosedLoop = false),
            doctorQuestions = if (worstWindowLabels.isEmpty()) {
                listOf(
                    "If this overall pattern continues, where should we focus first? My time in range is about $overallTIR%.",
                    "What data should I bring to help interpret highs versus lows?",
                )
            } else {
                listOf(
                    "If this overall pattern continues, could ${worstWindowLabels.take(2).joinToString(" and ")} be good places to start reviewing settings?",
                    "What should I log to understand what is driving out-of-range time?",
                )
            },
        )
    }

    fun makeFromAI(
        timeSlotKey: String,
        priority: Priority,
        trendObservation: String,
        factorsProse: String,
        doctorQuestions: List<String>,
    ): PatternInsight {
        val window = formatTimeWindow(timeSlotKey)
        val stats = parseStats(trendObservation)

        val cleanedQuestions = doctorQuestions.map { it.trim() }.filter { it.isNotEmpty() }
        val mergedQuestions = if (cleanedQuestions.isEmpty()) {
            listOf("Could related habits and settings be worth reviewing together?")
        } else {
            cleanedQuestions.take(2)
        }

        return PatternInsight(
            id = UUID.randomUUID().toString(),
            timeSlotKey = timeSlotKey,
            timeWindow = window,
            title = aiTitle(trendObservation, timeSlotKey),
            priority = priority,
            summary = trendObservation.trim(),
            comparison = null,
            inRangePercent = stats.inRange,
            highPercent = stats.high,
            lowPercent = stats.low,
            readingCount = stats.readings,
            contributors = bullets(factorsProse),
            doctorQuestions = mergedQuestions,
        )
    }

    val sampleOvernightHigh: PatternInsight
        get() = makeSlotTIRInsight(
            timeSlotKey = "00:00-02:00",
            priority = Priority.HIGH,
            analysis = SlotAnalysisSnapshot(
                averageGlucose = 185.0, timeInRange = 41.0, timeBelowRange = 0.0, timeAboveRange = 58.0,
                dataPoints = 36, isClosedLoop = false,
            ),
            overallTIR = 74,
            periodDays = 3,
            belowTarget = 0,
            aboveTarget = 58,
        )

    // MARK: - Formatting helpers

    fun formatTimeWindow(timeSlotKey: String): String {
        if (timeSlotKey.contains("overview")) {
            val days = timeSlotKey.replace("-day overview", "")
            return "$days-day view"
        }
        if (!timeSlotKey.contains(":")) return timeSlotKey
        val parts = timeSlotKey.split("-", limit = 2)
        if (parts.size != 2) return timeSlotKey
        return "${parts[0]}–${parts[1]}"
    }

    fun outOfRangeDriver(below: Int, above: Int): OutOfRangeDriver = when {
        above > below + 5 -> OutOfRangeDriver.MOSTLY_HIGH
        below > above + 5 -> OutOfRangeDriver.MOSTLY_LOW
        else -> OutOfRangeDriver.MIXED
    }

    // MARK: - Private copy builders

    private fun patternTitle(period: String, driver: OutOfRangeDriver): String = when (driver) {
        OutOfRangeDriver.MOSTLY_HIGH -> "$period highs"
        OutOfRangeDriver.MOSTLY_LOW -> "$period lows"
        OutOfRangeDriver.MIXED -> "$period variability"
    }

    private fun overviewTitle(driver: OutOfRangeDriver): String = when (driver) {
        OutOfRangeDriver.MOSTLY_HIGH -> "Overall highs pattern"
        OutOfRangeDriver.MOSTLY_LOW -> "Overall lows pattern"
        OutOfRangeDriver.MIXED -> "Overall variability"
    }

    private fun tirSummary(inRange: Int, window: String, driver: OutOfRangeDriver): String =
        "Only $inRange% in range from $window. ${driverPhrase(driver)}"

    private fun driverPhrase(driver: OutOfRangeDriver): String = when (driver) {
        OutOfRangeDriver.MOSTLY_HIGH -> "Most out-of-range readings were high."
        OutOfRangeDriver.MOSTLY_LOW -> "Most out-of-range readings were low."
        OutOfRangeDriver.MIXED -> "Out-of-range readings were split between highs and lows."
    }

    private fun contributorsForOutOfRange(driver: OutOfRangeDriver, isClosedLoop: Boolean): List<String> = when (driver) {
        OutOfRangeDriver.MOSTLY_HIGH -> buildList {
            add("Late meal or snack")
            add("Delayed or missed bolus")
            add("Overnight basal coverage")
            add("Stress, illness, or hormones")
            if (isClosedLoop) add("Loop temp basals — focus on multi-day averages")
        }
        OutOfRangeDriver.MOSTLY_LOW -> buildList {
            add("Recent activity or exercise")
            add("Delayed meal or alcohol")
            add("Background insulin coverage")
            add("Insulin from earlier boluses still active")
            if (isClosedLoop) add("Loop may already be reducing insulin for lows")
        }
        OutOfRangeDriver.MIXED -> listOf(
            "Meals, basal coverage, or corrections",
            "Exercise, stress, or sleep shifts",
            "Track whether highs or lows dominate",
        )
    }

    private fun contributorsForSustainedHighs(isClosedLoop: Boolean): List<String> = buildList {
        add("Background insulin for this window")
        add("Meal timing or late-evening snacks")
        add("Dawn phenomenon or hormonal shifts")
        add("Stress, illness, or activity changes")
        if (isClosedLoop) add("Sustained averages — not single temp basal changes")
    }

    private fun contributorsForSustainedLows(isClosedLoop: Boolean): List<String> = buildList {
        add("Background insulin during this window")
        add("Recent activity or delayed meals")
        add("Alcohol or insulin stacking")
        add("Sleep or schedule changes")
        if (isClosedLoop) add("Loop suspensions may already be active")
    }

    private fun doctorQuestionsForSlot(driver: OutOfRangeDriver, period: String, window: String): List<String> = when (driver) {
        OutOfRangeDriver.MOSTLY_HIGH ->
            if (period == "Overnight") {
                listOf(
                    "Could evening insulin and overnight settings be worth reviewing for $window?",
                    "What should I log to understand what may be driving these highs?",
                )
            } else {
                listOf(
                    "Could meal timing, bolus timing, or background coverage be worth reviewing for $window?",
                    "What should I log to understand what may be driving these highs?",
                )
            }
        OutOfRangeDriver.MOSTLY_LOW -> listOf(
            "If lows keep appearing here, could background insulin and overnight coverage be worth reviewing for $window?",
            "What should I log to understand what may be driving these lows?",
        )
        OutOfRangeDriver.MIXED -> listOf(
            "If this window stays unpredictable, could settings for $window be worth reviewing together?",
            "What should I log — meals, activity, and sleep — to see whether highs or lows dominate?",
        )
    }

    private fun periodName(timeSlotKey: String): String {
        if (timeSlotKey.contains("overview")) return "Overall"
        if (timeSlotKey == "Meal Times") return "Meal-time"
        return when (parseStartHour(timeSlotKey)) {
            in 0 until 6 -> "Overnight"
            in 6 until 12 -> "Morning"
            in 12 until 17 -> "Afternoon"
            in 17 until 21 -> "Evening"
            else -> "Night"
        }
    }

    private fun parseStartHour(timeSlotKey: String): Int {
        val start = timeSlotKey.split("-").firstOrNull() ?: timeSlotKey
        val hourPart = start.split(":").firstOrNull() ?: start
        return hourPart.toIntOrNull() ?: 0
    }

    private fun bullets(prose: String): List<String> = prose
        .split(".")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map(::normalizeBullet)
        .take(4)

    private fun normalizeBullet(sentence: String): String {
        var trimmed = sentence.trim()
        val lower = trimmed.lowercase()
        for (prefix in listOf("review ", "check ", "consider ", "note ", "look for ", "track ", "log ")) {
            if (lower.startsWith(prefix)) {
                trimmed = trimmed.drop(prefix.length)
                break
            }
        }
        return trimmed.replaceFirstChar { it.uppercase() }
    }

    private fun aiTitle(observation: String, timeSlotKey: String): String = "${periodName(timeSlotKey)} pattern"

    private class ParsedStats(val inRange: Int?, val high: Int?, val low: Int?, val readings: Int?)

    /** Best-effort only; structured stats come from the rule-based path. Always empty, as on iOS. */
    private fun parseStats(text: String): ParsedStats = ParsedStats(null, null, null, null)
}
