package com.boostt1d.android.engine

import com.boostt1d.android.data.BGUnit
import com.boostt1d.android.data.NightscoutProfileDocument
import com.boostt1d.android.data.TimeValue
import java.util.Locale

/**
 * A profile document's schedules, normalised into what the engine can compare.
 *
 * Two things happen here that must happen in exactly one place: schedule times become
 * fractional hours, and ISF becomes mg/dL. A Nightscout profile uploaded in mmol/L carries
 * mmol-valued sensitivity; comparing that against a measured mg/dL drop would be off by a
 * factor of eighteen. The change detector and the settings review both read through this,
 * so they cannot disagree about what a setting was.
 *
 * Ported from the iOS TherapyProfileSettings.
 */
class TherapyProfileSettings(document: NightscoutProfileDocument?) {

    val basal: List<TherapySegmentValue>
    /** mg/dL per unit, whatever the profile was uploaded in. */
    val isf: List<TherapySegmentValue>
    /** Grams per unit. */
    val carbRatio: List<TherapySegmentValue>
    /** Hours, clamped to a plausible 3–7. */
    val insulinDuration: Double?

    init {
        val entry = document?.activeEntry
        if (entry == null) {
            basal = emptyList(); isf = emptyList(); carbRatio = emptyList(); insulinDuration = null
        } else {
            val rawIsf = segments(entry.sensitivity)

            // Normalise by the declared unit first, and by plausibility when nothing is
            // declared: no mg/dL correction factor is under 25.
            val declaredMmol = (entry.units ?: document.units)?.lowercase(Locale.US)?.contains("mmol") ?: false
            val looksMmol = rawIsf.isNotEmpty() && rawIsf.all { it.value > 0 && it.value < 25 }

            basal = segments(entry.basal)
            isf = if (declaredMmol || looksMmol) {
                rawIsf.map { TherapySegmentValue(it.startHour, BGUnit.toMgdL(it.value)) }
            } else {
                rawIsf
            }
            carbRatio = segments(entry.carbRatio)
            insulinDuration = entry.dia?.coerceIn(3.0, 7.0)
        }
    }

    val hasAny: Boolean get() = basal.isNotEmpty() || isf.isNotEmpty() || carbRatio.isNotEmpty()

    fun basalAt(hour: Int): Double? = valueIn(basal, hour)
    fun isfAt(hour: Int): Double? = valueIn(isf, hour)
    fun carbRatioAt(hour: Int): Double? = valueIn(carbRatio, hour)

    /** True when more than one distinct value applies across the hours of a window. */
    fun varies(segments: List<TherapySegmentValue>, hours: List<Int>): Boolean =
        hours.mapNotNull { valueIn(segments, it)?.let { v -> Math.round(v * 100) } }.toSet().size > 1

    companion object {
        /**
         * The value a schedule holds at a clock hour. Before the first segment's start, the
         * schedule wraps from the last one — that is how a pump reads it.
         */
        fun valueIn(segments: List<TherapySegmentValue>, hour: Int): Double? {
            if (segments.isEmpty()) return null
            val target = hour.toDouble()
            var best: TherapySegmentValue? = segments.first()
            for (segment in segments) {
                if (segment.startHour > target) continue
                val current = best
                if (current != null && current.startHour > segment.startHour) continue
                best = segment
            }
            val chosen = best ?: return null
            if (chosen.startHour > target) return segments.last().value
            return chosen.value
        }

        /** Schedule entries as fractional hours, dropping anything unparseable or non-positive. */
        fun segments(values: List<TimeValue>): List<TherapySegmentValue> =
            values.mapNotNull { entry ->
                val parts = entry.time.split(":")
                val hour = parts.firstOrNull()?.toDoubleOrNull() ?: return@mapNotNull null
                val minutes = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                if (entry.value <= 0) return@mapNotNull null
                TherapySegmentValue(hour + minutes / 60, entry.value)
            }.sortedBy { it.startHour }
    }
}
