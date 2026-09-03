package com.boostt1d.android.engine

import com.boostt1d.android.data.NightscoutProfileDocument
import com.boostt1d.android.data.TimeValue
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class DoctorVisitTherapySegment(val time: String, val valueLabel: String)

data class DoctorVisitTherapyOverride(val name: String, val detail: String)

/**
 * The therapy profile as a report or a prompt describes it: every schedule as labelled
 * segments, plus one-line summaries. Ported from the iOS DoctorVisitReportBuilder.
 */
data class DoctorVisitTherapySnapshot(
    val profileName: String? = null,
    val timezone: String? = null,
    val units: String? = null,
    val diaHours: Double? = null,
    val notes: String? = null,
    val basalSegments: List<DoctorVisitTherapySegment> = emptyList(),
    val carbRatioSegments: List<DoctorVisitTherapySegment> = emptyList(),
    val sensitivitySegments: List<DoctorVisitTherapySegment> = emptyList(),
    val targetSegments: List<DoctorVisitTherapySegment> = emptyList(),
    val overrides: List<DoctorVisitTherapyOverride> = emptyList(),
    val basalSummary: String? = null,
    val carbRatioSummary: String? = null,
    val sensitivitySummary: String? = null,
    val targetSummary: String? = null,
) {
    val hasAnyContent: Boolean
        get() = profileName != null || diaHours != null || !notes.isNullOrEmpty() ||
            basalSegments.isNotEmpty() || carbRatioSegments.isNotEmpty() ||
            sensitivitySegments.isNotEmpty() || targetSegments.isNotEmpty() || overrides.isNotEmpty()

    companion object {
        fun from(profile: NightscoutProfileDocument?): DoctorVisitTherapySnapshot {
            if (profile == null) return DoctorVisitTherapySnapshot()
            val name = profile.defaultProfile
            val data = profile.activeEntry ?: return DoctorVisitTherapySnapshot(profileName = name)

            val basal = sortedSegments(data.basal)
            val icr = sortedSegments(data.carbRatio)
            val isf = sortedSegments(data.sensitivity)
            val low = sortedSegments(data.targetLow)
            val high = sortedSegments(data.targetHigh)

            val targetSegments = mutableListOf<DoctorVisitTherapySegment>()
            if (low.isNotEmpty() || high.isNotEmpty()) {
                for (index in 0 until maxOf(low.size, high.size)) {
                    val lowItem = low.getOrNull(index)
                    val highItem = high.getOrNull(index)
                    val time = (lowItem ?: highItem)?.timeFormatted ?: "—"
                    val l = lowItem?.let { String.format(Locale.US, "%.0f", it.value) }
                    val h = highItem?.let { String.format(Locale.US, "%.0f", it.value) }
                    val label = when {
                        l != null && h != null -> "$l–$h mg/dL"
                        l != null -> "Low $l mg/dL"
                        h != null -> "High $h mg/dL"
                        else -> "—"
                    }
                    targetSegments += DoctorVisitTherapySegment(time, label)
                }
            }

            return DoctorVisitTherapySnapshot(
                profileName = name,
                timezone = null,
                units = data.units ?: profile.units,
                diaHours = data.dia,
                notes = null,
                basalSegments = basal.map { DoctorVisitTherapySegment(it.timeFormatted, String.format(Locale.US, "%.2f u/hr", it.value)) },
                carbRatioSegments = icr.map { DoctorVisitTherapySegment(it.timeFormatted, String.format(Locale.US, "%.1f g/u", it.value)) },
                sensitivitySegments = isf.map { DoctorVisitTherapySegment(it.timeFormatted, String.format(Locale.US, "%.0f mg/dL/u", it.value)) },
                targetSegments = targetSegments,
                overrides = emptyList(),
                basalSummary = summarizeRates(basal, "u/hr"),
                carbRatioSummary = summarizeRates(icr, "g/u"),
                sensitivitySummary = summarizeRates(isf, "mg/dL/u"),
                targetSummary = targetSummary(low, high),
            )
        }

        private fun minutes(time: String): Int {
            val parts = time.split(":")
            val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
            val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            return h * 60 + m
        }

        private fun sortedSegments(values: List<TimeValue>): List<TimeValue> = values.sortedBy { minutes(it.time) }

        internal fun summarizeRates(values: List<TimeValue>, unit: String): String? {
            val nums = values.map { it.value }
            val first = nums.firstOrNull() ?: return null
            if (nums.all { abs(it - first) < 0.01 }) return String.format(Locale.US, "%.2f %s", first, unit)
            return String.format(Locale.US, "%.2f–%.2f %s (%d segments)", nums.min(), nums.max(), unit, nums.size)
        }

        internal fun targetSummary(low: List<TimeValue>, high: List<TimeValue>): String? {
            val l = low.firstOrNull()?.value
            val h = high.firstOrNull()?.value
            return when {
                l != null && h != null -> "${l.roundToInt()}–${h.roundToInt()} mg/dL"
                l != null -> "Low ${l.roundToInt()} mg/dL"
                h != null -> "High ${h.roundToInt()} mg/dL"
                else -> null
            }
        }
    }
}
