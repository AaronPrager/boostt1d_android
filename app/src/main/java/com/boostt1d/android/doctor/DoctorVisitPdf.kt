package com.boostt1d.android.doctor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.boostt1d.android.R
import com.boostt1d.android.data.Config
import com.boostt1d.android.data.NightscoutGlucoseEntry
import com.boostt1d.android.engine.AgpProfile
import com.boostt1d.android.engine.DoctorVisitDailyProfile
import com.boostt1d.android.engine.DoctorVisitReport
import com.boostt1d.android.engine.DoctorVisitTherapySegment
import com.boostt1d.android.engine.Priority
import com.boostt1d.android.engine.WhatHappenedPattern
import com.boostt1d.android.engine.WhatHappenedPatternChartKind
import com.boostt1d.android.engine.WhatHappenedPatternChartPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds the multi-page clinical PDF for the Doctor Visit Report. Ported from the iOS
 * DoctorVisitPDFExporter: US Letter, the same four pages, sections spilling onto continuation
 * pages, and a measuring pass so footers can say "Page n of total".
 */
class DoctorVisitPdf(private val context: Context) {

    data class Appointment(val questions: String, val patientName: String?, val lowThresholdMgdL: Double, val highThresholdMgdL: Double)

    class ExportException : Exception("The report could not be generated. Please try again.")

    private data class Header(val eyebrow: String, val title: String, val subtitle: String) {
        fun continued() = copy(eyebrow = "$eyebrow · CONTINUED")
    }

    private enum class Weight { REGULAR, MEDIUM, SEMIBOLD, BOLD }

    /** Writes the PDF into the app's cache and returns the file. */
    fun export(report: DoctorVisitReport, appointment: Appointment, agpEntries: List<NightscoutGlucoseEntry>, timeZone: TimeZone = TimeZone.getDefault()): File {
        val agp = AgpProfile.points(agpEntries, timeZone)

        // Sections can overflow onto continuation pages, so the page total isn't known up
        // front. Measure with a throwaway pass, then render for real with the total known.
        val measuring = Renderer(report, appointment, agp, total = null)
        measuring.drawAllPages(); measuring.document.close()

        val final = Renderer(report, appointment, agp, total = max(measuring.pageCount, 1))
        final.drawAllPages()

        val dir = File(context.cacheDir, "pdf").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(report.periodEndMillis))
        val file = File(dir, "BoostT1D-Doctor-Visit-$stamp.pdf")
        file.outputStream().use { final.document.writeTo(it) }
        final.document.close()

        // Never hand back a file that won't open — surface it instead.
        if (file.length() < 1_000 || final.pageCount == 0) { file.delete(); throw ExportException() }
        return file
    }

    private inner class Renderer(
        private val report: DoctorVisitReport,
        private val appointment: Appointment,
        private val agp: List<AgpProfile.Point>,
        private val total: Int?,
    ) {
        val document = PdfDocument()
        var pageCount = 0; private set
        private var page: PdfDocument.Page? = null
        private lateinit var canvas: Canvas

        private val pageWidth = 612f
        private val pageHeight = 792f
        private val margin = 40f
        private val contentWidth get() = pageWidth - margin * 2

        /** On the measuring pass the true total is unknown, so fall back to the running count. */
        private val footerTotal get() = total ?: pageCount

        fun drawAllPages() {
            drawPage1BeforeVisit()
            drawPage2Clinical()
            drawPage3Details()
            drawPage4Therapy()
            endPage()
        }

        // MARK: - Pages

        private fun drawPage1BeforeVisit() {
            val header = Header("BOOSTT1D · DOCTOR VISIT REPORT", "Before the Appointment", periodLabel())
            var y = startPage(header)
            fun fit(needed: Float, cursor: Float) = ensureSpace(needed, cursor, header.continued())

            y = drawIntroBand(y, "Bring this report to your visit. This page summarises what changed, the patterns BoostT1D noticed, and your questions. The pages that follow give the clinical detail behind it.")

            if (report.plainLanguageSummary.isNotEmpty()) {
                y += 16; y = fit(SECTION_TITLE_HEIGHT + 40, y)
                y = drawSectionTitle("Summary", y, size = 15f)
                y = drawBody(report.plainLanguageSummary, y, margin, contentWidth, size = 13f)
            }
            if (report.deliverySummary.isNotEmpty()) {
                y += 16; y = fit(SECTION_TITLE_HEIGHT + 40, y)
                y = drawSectionTitle("How insulin is delivered", y, size = 15f)
                y = drawBody(report.deliverySummary, y, margin, contentWidth, size = 13f)
            }

            y += 16
            y = drawSectionTitle("What changed since my last appointment", y, size = 15f)
            val changeNote = if (report.usesHalfPeriodComparison) "Compared first half vs second half of this ${report.period.title}." else "Compared with the prior ${report.period.title}."
            y = drawBody(changeNote, y, margin, contentWidth, color = MUTED, size = 12f)
            y += 8
            for (line in report.changeSummaryLines) { y = fit(26f, y); y = drawBullet(line, y, size = 13f); y += 6 }

            y += 14; y = fit(SECTION_TITLE_HEIGHT + 60, y)
            y = drawSectionTitle("Patterns BoostT1D detected", y, size = 15f)
            if (report.patterns.isEmpty()) y = drawBody("No strong recurrent patterns stood out in this period.", y, margin, contentWidth, color = MUTED, size = 13f)
            else report.patterns.take(PAGE_PATTERN_LIMIT).forEachIndexed { index, pattern ->
                // Reserve room for the block plus its factors/questions lines, so a pattern is never split across the footer.
                val extraLines = pattern.discussQuestions.size + (if (pattern.contributingFactors.isEmpty()) 0 else 1)
                y = fit(86f + extraLines * 16f + patternChartHeight(pattern), y)
                y = drawPatternBlock(index + 1, pattern, y, larger = true)
                y = drawPatternChart(pattern.chartPoints, pattern.chartKind, y + 4, margin + 14, contentWidth - 14)
                if (pattern.contributingFactors.isNotEmpty()) y = drawBody("Possible factors: " + pattern.contributingFactors.joinToString(", "), y, margin + 18, contentWidth - 18, color = MUTED, size = 11f)
                for (question in pattern.discussQuestions) y = drawBody("Ask: $question", y, margin + 18, contentWidth - 18, color = MUTED, size = 11f)
                y += 12
            }

            // Free text the patient typed — a line at a time so a long list paginates.
            val questionLines = appointment.questions.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
            y += 14
            val firstLineHeight = questionLines.firstOrNull()?.let { bodyHeight(it, contentWidth, 14f) } ?: 24f
            y = fit(SECTION_TITLE_HEIGHT + firstLineHeight, y)
            y = drawSectionTitle("Questions I want answered", y, size = 15f)
            if (questionLines.isEmpty()) drawBody("—", y, margin, contentWidth, size = 14f)
            else for (text in questionLines) { y = fit(bodyHeight(text, contentWidth, 14f), y); y = drawBody(text, y, margin, contentWidth, size = 14f) }
        }

        private fun drawPage2Clinical() {
            val subtitle = "${periodLabel()}  ·  Target ${appointment.lowThresholdMgdL.toInt()}–${appointment.highThresholdMgdL.toInt()} mg/dL"
            val header = Header("CLINICAL SUMMARY", "Glucose overview", subtitle)
            var y = startPage(header)
            fun fit(needed: Float, cursor: Float) = ensureSpace(needed, cursor, header.continued())

            if (agp.isNotEmpty()) {
                val chartHeight = 190f
                y = fit(SECTION_TITLE_HEIGHT + chartHeight + 28, y)
                y = drawSectionTitle("Ambulatory glucose profile", y)
                y = drawAgpChart(y, chartHeight)
                y = drawBody("Median with 25th–75th percentile band across ${report.period.title}, by time of day.", y, margin, contentWidth, color = MUTED, size = 10f)
                y += 10
            }

            y = fit(SECTION_TITLE_HEIGHT + 70, y)
            y = drawSectionTitle("Time in range", y)
            y = drawMetricGrid(listOf(
                Triple("In range", pct(report.current.timeInRangePercent), ACCENT),
                Triple("Low", pct(report.current.timeLowPercent), SYSTEM_RED),
                Triple("High", pct(report.current.timeHighPercent), SYSTEM_ORANGE),
                Triple("Very high", pct(report.current.timeVeryHighPercent), SYSTEM_PURPLE),
            ), y)

            y += 16
            y = drawSectionTitle("Average glucose & variability", y)
            y = drawMetricGrid(listOf(
                Triple("Average", "${report.current.averageGlucoseMgdL.roundToInt()}", INK),
                Triple("GMI", fmt("%.1f%%", report.current.gmi), INK),
                Triple("Est. A1C", fmt("%.1f%%", report.current.estimatedA1C), INK),
                Triple("CV", fmt("%.0f%%", report.current.coefficientOfVariation), INK),
            ), y)
            y += 8
            y = drawKeyValue("Standard deviation", fmt("%.0f mg/dL", report.current.standardDeviationMgdL), y)
            y = drawKeyValue("Low episodes (≥15 min)", "${report.current.lowEpisodeCount}", y)
            y = drawKeyValue("Total low time", "${report.current.lowEpisodeTotalMinutes} min", y)

            y += 14
            val recurrentRows = report.recurrentHighBlocks.size + report.recurrentLowBlocks.size
            y = fit(SECTION_TITLE_HEIGHT + max(recurrentRows, 1) * KEY_VALUE_ROW_HEIGHT, y)
            y = drawSectionTitle("Recurrent high & low periods", y)
            if (recurrentRows == 0) y = drawBody("No strong clock-time clusters above the recurrence threshold.", y, margin, contentWidth, color = MUTED)
            else for (block in report.recurrentHighBlocks + report.recurrentLowBlocks) { y = fit(KEY_VALUE_ROW_HEIGHT, y); y = drawKeyValue(block.label, fmt("%.0f%% of readings", block.percent), y) }

            // Per-day insulin and carbs live in the single day-by-day table on the next page.
            y += 14
            y = fit(SECTION_TITLE_HEIGHT + KEY_VALUE_ROW_HEIGHT * 2, y)
            y = drawSectionTitle("Data coverage", y)
            y = drawKeyValue("CGM readings analysed", "${report.current.readingCount}", y)
            y = drawKeyValue("Days of data available", fmt("%.1f of %d", report.dataDaysAvailable, report.period.days), y)

            y += 14
            y = fit(SECTION_TITLE_HEIGHT + comparisonTableHeight(4), y)
            y = drawSectionTitle("Weekday vs weekend", y)
            y = drawComparisonTable(listOf(
                Triple("Time in range", pct(report.weekday.timeInRangePercent), pct(report.weekend.timeInRangePercent)),
                Triple("Average glucose", "${report.weekday.averageGlucoseMgdL.roundToInt()}", "${report.weekend.averageGlucoseMgdL.roundToInt()}"),
                Triple("CV", fmt("%.0f%%", report.weekday.coefficientOfVariation), fmt("%.0f%%", report.weekend.coefficientOfVariation)),
                Triple("Low episodes", "${report.weekday.lowEpisodeCount}", "${report.weekend.lowEpisodeCount}"),
            ), y)

            y += 14
            y = fit(SECTION_TITLE_HEIGHT + 44, y)
            y = drawSectionTitle("Exercise-associated changes", y)
            val delta = report.exerciseAssociatedDeltaMgdL
            if (delta != null) {
                val direction = if (delta < 0) "lower" else "higher"
                drawBody("After ${report.exerciseCount} exercise events, glucose averaged ${fmt("%.0f", abs(delta))} mg/dL $direction in the following 2 hours vs earlier the same day.", y, margin, contentWidth)
            } else drawBody("Need at least two exercise events with nearby CGM to estimate post-exercise change.", y, margin, contentWidth, color = MUTED)
        }

        private fun drawPage3Details() {
            val header = Header("VISIT DETAILS", "Daily profiles & patterns", periodLabel())
            var y = startPage(header)
            fun fit(needed: Float, cursor: Float) = ensureSpace(needed, cursor, header.continued())

            y = drawSectionTitle("Day by day", y)
            y = drawBody(report.insulinSummaryLine, y, margin, contentWidth)
            y += 4
            y = drawDailyTable(
                report.dailyProfiles.take(report.period.days),
                insulinColumnTitle = if (report.averageDailyBasalUnits == null) "Bolus" else "TDD",
                y = y,
            ) { cursor -> fit(DAILY_TABLE_HEADER_HEIGHT + DAILY_TABLE_ROW_HEIGHT, cursor) }

            // Page 1 already carries the headline patterns with their discussion prompts.
            val remaining = report.patterns.drop(PAGE_PATTERN_LIMIT)
            if (remaining.isNotEmpty()) {
                y += 16; y = fit(SECTION_TITLE_HEIGHT + 40, y)
                y = drawSectionTitle("Additional patterns", y)
                remaining.forEachIndexed { offset, pattern ->
                    val extraLines = pattern.discussQuestions.size + (if (pattern.contributingFactors.isEmpty()) 0 else 1)
                    y = fit(72f + extraLines * 14f + patternChartHeight(pattern), y)
                    y = drawPatternBlock(PAGE_PATTERN_LIMIT + offset + 1, pattern, y, larger = false)
                    y = drawPatternChart(pattern.chartPoints, pattern.chartKind, y + 4, margin + 14, contentWidth - 14)
                    if (pattern.contributingFactors.isNotEmpty()) y = drawBody("Possible factors: " + pattern.contributingFactors.joinToString(", "), y, margin + 18, contentWidth - 18, color = MUTED, size = 10f)
                    for (question in pattern.discussQuestions) y = drawBody("Ask: $question", y, margin + 18, contentWidth - 18, color = MUTED, size = 10f)
                    y += 10
                }
            }

            // Empty unless doses may be shown at all, so store builds never export dose figures.
            if (!Config.HIDE_DOSE_RECOMMENDATIONS && report.doseSuggestions.isNotEmpty()) {
                y += 16; y = fit(SECTION_TITLE_HEIGHT + 40, y)
                y = drawSectionTitle("Dose suggestions", y)
                y = drawBody("Formula-derived, for discussion with the care team — not instructions.", y, margin, contentWidth, color = MUTED, size = 10f)
                for (suggestion in report.doseSuggestions) {
                    y = fit(72f, y)
                    y = drawBody("${suggestion.type.displayName} · ${suggestion.timeSlot}: " + fmt("%.2f → %.2f", suggestion.currentValue, suggestion.suggestedValue), y, margin + 6, contentWidth - 6, size = 11f)
                    y = drawBody(suggestion.reasoning, y, margin + 18, contentWidth - 18, color = MUTED, size = 10f)
                    y += 8
                }
            }

            appointment.patientName?.takeIf { it.isNotEmpty() }?.let { name ->
                y += 16; y = fit(28f, y)
                drawBody("Prepared for: $name", y, margin, contentWidth, color = MUTED, size = 10f)
            }
        }

        private fun drawPage4Therapy() {
            val header = Header("CURRENT THERAPY PROFILE", "Full therapy settings", periodLabel())
            var y = startPage(header)
            fun fit(needed: Float, cursor: Float) = ensureSpace(needed, cursor, header.continued())

            val therapy = report.therapy
            if (!therapy.hasAnyContent) { drawBody("No therapy profile on device.", y, margin, contentWidth, color = MUTED); return }

            therapy.profileName?.let { y = drawKeyValue("Profile", it, y) }
            therapy.timezone?.let { y = drawKeyValue("Timezone", it, y) }
            therapy.units?.let { y = drawKeyValue("Units", it, y) }
            therapy.diaHours?.let { y = drawKeyValue("DIA", fmt("%.1f hours", it), y) }

            y += 8
            y = drawTherapySchedule("Basal rates", therapy.basalSegments, y, ::fit)
            y = drawTherapySchedule("Carb ratios", therapy.carbRatioSegments, y, ::fit)
            y = drawTherapySchedule("Insulin sensitivity", therapy.sensitivitySegments, y, ::fit)
            y = drawTherapySchedule("Target range", therapy.targetSegments, y, ::fit)

            if (therapy.overrides.isNotEmpty()) {
                y = fit(SECTION_TITLE_HEIGHT + KEY_VALUE_ROW_HEIGHT, y)
                y = drawSectionTitle("Override presets", y)
                for (preset in therapy.overrides) { y = fit(KEY_VALUE_ROW_HEIGHT, y); y = drawKeyValue(preset.name, preset.detail, y) }
            }
            therapy.notes?.takeIf { it.isNotEmpty() }?.let { notes ->
                y = fit(SECTION_TITLE_HEIGHT + 8 + bodyHeight(notes, contentWidth), y)
                y += 8
                y = drawSectionTitle("Notes", y)
                drawBody(notes, y, margin, contentWidth)
            }
        }

        private fun drawTherapySchedule(title: String, segments: List<DoctorVisitTherapySegment>, y: Float, fit: (Float, Float) -> Float): Float {
            if (segments.isEmpty()) return y
            // Keep the title with at least a couple of rows rather than orphaning it.
            var cursor = fit(SECTION_TITLE_HEIGHT + min(segments.size, 3) * KEY_VALUE_ROW_HEIGHT, y)
            cursor += 6
            cursor = drawSectionTitle(title, cursor)
            for (segment in segments) { cursor = fit(KEY_VALUE_ROW_HEIGHT, cursor); cursor = drawKeyValue(segment.time, segment.valueLabel, cursor) }
            return cursor + 6
        }

        // MARK: - Page plumbing

        /** Finishes the open page (footer included) and starts the next; returns the header cursor. */
        private fun startPage(header: Header): Float {
            endPage()
            pageCount += 1
            val next = document.startPage(PdfDocument.PageInfo.Builder(pageWidth.toInt(), pageHeight.toInt(), pageCount).create())
            page = next
            canvas = next.canvas
            return drawHeader(header)
        }

        private fun endPage() {
            val open = page ?: return
            drawFooter()
            document.finishPage(open)
            page = null
        }

        /** Bottom of the drawable area — content must never cross into the footer band. */
        private val contentBottom get() = pageHeight - FOOTER_RESERVE - 6

        /** Spills onto a continuation page when `needed` points won't fit above the footer. */
        private fun ensureSpace(needed: Float, y: Float, header: Header): Float =
            if (y + needed > contentBottom) startPage(header) else y

        // MARK: - Drawing primitives

        private fun paint(size: Float, color: Int, weight: Weight = Weight.REGULAR): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color
            typeface = when (weight) {
                Weight.REGULAR -> Typeface.DEFAULT
                Weight.MEDIUM, Weight.SEMIBOLD -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
                Weight.BOLD -> Typeface.DEFAULT_BOLD
            }
        }
        private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = width }
        private fun alpha(color: Int, fraction: Float) = Color.argb((255 * fraction).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))

        /** Draws a single line with its top-left at (x, y), as UIKit's `draw(at:)` does. */
        private fun text(s: String, x: Float, y: Float, p: TextPaint): Float { canvas.drawText(s, x, y - p.ascent(), p); return p.measureText(s) }
        private fun textHeight(p: TextPaint) = p.descent() - p.ascent()

        private fun layout(s: String, width: Float, p: TextPaint): StaticLayout =
            StaticLayout.Builder.obtain(s, 0, s.length, p, max(width.toInt(), 1)).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()

        private fun drawHeader(header: Header): Float {
            val bandHeight = 108f
            canvas.drawRect(0f, 0f, pageWidth, bandHeight, fill(ACCENT))

            val logoSize = 52f
            val logoTop = (bandHeight - logoSize) / 2
            logoBitmap?.let { logo ->
                canvas.drawRoundRect(RectF(margin - 3, logoTop - 3, margin + logoSize + 3, logoTop + logoSize + 3), 12f, 12f, fill(Color.WHITE))
                canvas.drawBitmap(logo, null, RectF(margin, logoTop, margin + logoSize, logoTop + logoSize), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            val textX = margin + logoSize + 14
            text("BoostT1D", textX, 18f, paint(11f, Color.WHITE, Weight.BOLD).apply { letterSpacing = 0.05f })
            text(header.eyebrow, textX, 36f, paint(8f, alpha(Color.WHITE, 0.8f), Weight.SEMIBOLD).apply { letterSpacing = 0.12f })
            text(header.title, textX, 52f, paint(18f, Color.WHITE, Weight.BOLD))
            text(header.subtitle, textX, 76f, paint(10f, alpha(Color.WHITE, 0.9f)))
            return bandHeight + 16
        }

        private fun drawIntroBand(y: Float, body: String): Float {
            val p = paint(11f, MUTED)
            val l = layout(body, contentWidth - 24, p)
            val box = RectF(margin, y, margin + contentWidth, y + l.height + 16)
            canvas.drawRoundRect(box, 8f, 8f, fill(alpha(ACCENT, 0.10f)))
            canvas.save(); canvas.translate(margin + 12, y + 8); l.draw(canvas); canvas.restore()
            return box.bottom
        }

        private fun drawSectionTitle(title: String, y: Float, size: Float = 13f): Float {
            canvas.drawRoundRect(RectF(margin, y + 2, margin + 3, y + 2 + size + 2), 1.5f, 1.5f, fill(ACCENT))
            text(title, margin + 10, y, paint(size, INK, Weight.BOLD))
            return y + size + 10
        }

        /** Height `drawBody` will consume for this text, so callers can reserve space accurately. */
        private fun bodyHeight(body: String, width: Float, size: Float = 11f): Float = layout(body, width, paint(size, INK)).height + 2f

        private fun drawBody(body: String, y: Float, x: Float, width: Float, color: Int = INK, size: Float = 11f): Float {
            val l = layout(body, width, paint(size, color))
            canvas.save(); canvas.translate(x, y); l.draw(canvas); canvas.restore()
            return y + l.height + 2
        }

        private fun drawBullet(body: String, y: Float, size: Float = 11f): Float {
            text("•", margin, y, paint(size, ACCENT, Weight.BOLD))
            return drawBody(body, y, margin + 14, contentWidth - 14, size = size)
        }

        private fun drawKeyValue(key: String, value: String, y: Float): Float {
            text(key, margin, y, paint(11f, MUTED))
            val vp = paint(11f, INK, Weight.SEMIBOLD)
            text(value, margin + contentWidth - vp.measureText(value), y, vp)
            return y + KEY_VALUE_ROW_HEIGHT
        }

        private fun drawMetricGrid(items: List<Triple<String, String, Int>>, y: Float): Float {
            val gap = 10f
            val cellWidth = (contentWidth - gap * 3) / 4
            val cellHeight = 58f
            items.forEachIndexed { index, (label, value, color) ->
                val x = margin + index * (cellWidth + gap)
                canvas.drawRoundRect(RectF(x, y, x + cellWidth, y + cellHeight), 8f, 8f, fill(CARD_FILL))
                text(label, x + 8, y + 10, paint(9f, MUTED, Weight.MEDIUM))
                text(value, x + 8, y + 28, paint(16f, color, Weight.BOLD))
            }
            return y + cellHeight
        }

        private fun drawPatternBlock(index: Int, pattern: WhatHappenedPattern, y: Float, larger: Boolean): Float {
            val titleSize = if (larger) 14f else 12f
            val freqSize = if (larger) 12f else 10f
            val bodySize = if (larger) 13f else 10f
            var cursor = y
            text("$index. ${pattern.title}", margin, cursor, paint(titleSize, INK, Weight.SEMIBOLD))

            // Priority badge, right-aligned on the title row.
            val badgeColor = priorityColor(pattern.priority)
            val bp = paint(8f, badgeColor, Weight.BOLD)
            val label = pattern.priority.name.uppercase(Locale.US)
            val w = bp.measureText(label); val h = textHeight(bp)
            val badge = RectF(margin + contentWidth - w - 10, cursor + 1, margin + contentWidth, cursor + 1 + h + 4)
            canvas.drawRoundRect(badge, 3f, 3f, fill(alpha(badgeColor, 0.14f)))
            text(label, badge.left + 5, badge.top + 2, bp)
            cursor += titleSize + 6

            text(pattern.frequencyLabel, margin + 14, cursor, paint(freqSize, ACCENT, Weight.MEDIUM))
            cursor += freqSize + 6
            return drawBody(pattern.observation, cursor, margin + 14, contentWidth - 14, color = MUTED, size = bodySize)
        }

        private fun patternChartHeight(pattern: WhatHappenedPattern): Float =
            if (pattern.chartPoints.isEmpty()) 0f else if (pattern.chartKind == WhatHappenedPatternChartKind.OCCURRENCE_FLAGS) 50f else 60f

        private fun priorityColor(priority: Priority): Int = when (priority) {
            Priority.HIGH -> SYSTEM_RED
            Priority.MEDIUM -> SYSTEM_ORANGE
            Priority.LOW -> SYSTEM_GREEN
        }

        /** The pattern's supporting day-level chart: scaled bars, or filled/empty day markers. */
        private fun drawPatternChart(points: List<WhatHappenedPatternChartPoint>, kind: WhatHappenedPatternChartKind, y: Float, x: Float, width: Float): Float {
            if (points.isEmpty()) return y
            val lp = paint(6f, MUTED)
            val chartHeight = if (kind == WhatHappenedPatternChartKind.OCCURRENCE_FLAGS) 22f else 40f
            val slot = width / points.size
            val barWidth = min(slot * 0.55f, 16f)
            val baseline = y + chartHeight

            when (kind) {
                WhatHappenedPatternChartKind.AVERAGE_GLUCOSE -> {
                    val maxValue = max(points.maxOf { it.value }, 1.0)
                    points.forEachIndexed { index, point ->
                        val barHeight = max((point.value / maxValue).toFloat() * chartHeight, 1f)
                        val left = x + slot * index + (slot - barWidth) / 2
                        val rect = RectF(left, baseline - barHeight, left + barWidth, baseline)
                        canvas.drawRoundRect(rect, 1.5f, 1.5f, fill(if (point.highlighted) ACCENT else alpha(ACCENT, 0.28f)))
                        val value = "${point.value.roundToInt()}"
                        text(value, rect.centerX() - lp.measureText(value) / 2, rect.top - textHeight(lp) - 1, lp)
                    }
                }
                WhatHappenedPatternChartKind.OCCURRENCE_FLAGS -> points.forEachIndexed { index, point ->
                    val side = 9f
                    val left = x + slot * index + (slot - side) / 2
                    val rect = RectF(left, baseline - side - 4, left + side, baseline - 4)
                    if (point.value > 0) canvas.drawRoundRect(rect, 2f, 2f, fill(ACCENT))
                    else canvas.drawRoundRect(rect, 2f, 2f, stroke(HAIRLINE, 0.75f))
                }
            }

            var cursor = baseline + 2
            points.forEachIndexed { index, point -> text(point.label, x + slot * index + (slot - lp.measureText(point.label)) / 2, cursor, lp) }
            cursor += 10
            if (kind == WhatHappenedPatternChartKind.OCCURRENCE_FLAGS) { text("Filled = day matched this pattern", x, cursor, lp); cursor += 9 }
            return cursor + 4
        }

        /** AGP chart: target band, 25th–75th percentile ribbon, median line, 24-hour axis. */
        private fun drawAgpChart(y: Float, height: Float): Float {
            if (agp.isEmpty()) return y
            val axisWidth = 34f
            val plot = RectF(margin + axisWidth, y, margin + contentWidth, y + height)
            // Fixed 40–400 mg/dL scale so charts are comparable between reports.
            fun yFor(value: Double): Float = (plot.bottom - ((value.coerceIn(40.0, 400.0) - 40.0) / 360.0) * plot.height()).toFloat()
            fun xFor(minute: Int): Float = plot.left + minute / (24f * 60f) * plot.width()

            canvas.drawRect(plot.left, yFor(appointment.highThresholdMgdL), plot.right, yFor(appointment.lowThresholdMgdL), fill(alpha(SYSTEM_GREEN, 0.10f)))
            val gp = paint(7f, MUTED)
            for (value in listOf(70.0, 180.0, 250.0, 400.0)) {
                val lineY = yFor(value)
                canvas.drawLine(plot.left, lineY, plot.right, lineY, stroke(HAIRLINE, 0.5f))
                text("${value.toInt()}", margin, lineY - 4, gp)
            }

            val ribbon = Path().apply {
                moveTo(xFor(agp.first().minuteOfDay), yFor(agp.first().p75))
                agp.drop(1).forEach { lineTo(xFor(it.minuteOfDay), yFor(it.p75)) }
                agp.reversed().forEach { lineTo(xFor(it.minuteOfDay), yFor(it.p25)) }
                close()
            }
            canvas.drawPath(ribbon, fill(alpha(ACCENT, 0.18f)))
            val median = Path().apply {
                moveTo(xFor(agp.first().minuteOfDay), yFor(agp.first().median))
                agp.drop(1).forEach { lineTo(xFor(it.minuteOfDay), yFor(it.median)) }
            }
            canvas.drawPath(median, stroke(ACCENT, 1.5f).apply { strokeJoin = Paint.Join.ROUND })
            canvas.drawRect(plot, stroke(HAIRLINE, 0.5f))

            var cursor = plot.bottom + 4
            for (hour in listOf(0, 6, 12, 18, 24)) {
                val label = when (hour) { 0, 24 -> "12a"; 12 -> "12p"; in 1..11 -> "${hour}a"; else -> "${hour - 12}p" }
                val w = gp.measureText(label)
                text(label, (xFor(hour * 60) - w / 2).coerceIn(plot.left, plot.right - w), cursor, gp)
            }
            cursor += 12

            val lp = paint(8f, MUTED)
            canvas.drawRect(margin, cursor + 1 + 2.25f, margin + 14, cursor + 1 + 3.75f, fill(ACCENT))
            text("Median", margin + 18, cursor - 1, lp)
            canvas.drawRect(margin + 70, cursor + 1, margin + 84, cursor + 7, fill(alpha(ACCENT, 0.18f)))
            text("25th–75th percentile", margin + 88, cursor - 1, lp)
            canvas.drawRect(margin + 210, cursor + 1, margin + 224, cursor + 7, fill(alpha(SYSTEM_GREEN, 0.25f)))
            text("Target ${appointment.lowThresholdMgdL.toInt()}–${appointment.highThresholdMgdL.toInt()}", margin + 228, cursor - 1, lp)
            return cursor + 14
        }

        /** 16pt header + 8pt rule gap + 18pt per row. */
        private fun comparisonTableHeight(rows: Int) = 24f + rows * 18f

        private fun drawComparisonTable(rows: List<Triple<String, String, String>>, y: Float): Float {
            val col1 = contentWidth * 0.40f; val col2 = contentWidth * 0.30f
            var cursor = y
            val hp = paint(10f, MUTED, Weight.SEMIBOLD)
            text("Metric", margin, cursor, hp); text("Weekday", margin + col1, cursor, hp); text("Weekend", margin + col1 + col2, cursor, hp)
            cursor += 16
            canvas.drawLine(margin, cursor, margin + contentWidth, cursor, stroke(HAIRLINE, 0.5f))
            cursor += 8
            val cp = paint(11f, INK)
            for ((title, weekday, weekend) in rows) {
                text(title, margin, cursor, cp); text(weekday, margin + col1, cursor, cp); text(weekend, margin + col1 + col2, cursor, cp)
                cursor += 18
            }
            return cursor
        }

        /** One row per day covering glucose and treatments. Paginates through [onOverflow]. */
        private fun drawDailyTable(
            profiles: List<DoctorVisitDailyProfile>,
            insulinColumnTitle: String,
            y: Float,
            onOverflow: (Float) -> Float,
        ): Float {
            val columns = listOf(0f, contentWidth * 0.24f, contentWidth * 0.46f, contentWidth * 0.66f, contentWidth * 0.84f)
            val titles = listOf("Day", "Average", "Time in range", insulinColumnTitle, "Carbs")
            val hp = paint(10f, MUTED, Weight.SEMIBOLD)
            val cp = paint(10f, INK)

            fun columnHeader(at: Float): Float {
                titles.zip(columns).forEach { (title, offset) -> text(title, margin + offset, at, hp) }
                val next = at + 16
                canvas.drawLine(margin, next, margin + contentWidth, next, stroke(HAIRLINE, 0.5f))
                return next + 6
            }

            var cursor = columnHeader(y)
            for (profile in profiles) {
                if (cursor + DAILY_TABLE_ROW_HEIGHT > contentBottom) cursor = columnHeader(onOverflow(cursor))
                val values = listOf(
                    profile.weekdayLabel,
                    profile.averageGlucoseMgdL?.let { "${it.roundToInt()} mg/dL" } ?: "—",
                    profile.timeInRangePercent?.let { pct(it) } ?: "—",
                    dailyInsulinLabel(profile),
                    if (profile.carbsGrams > 0) fmt("%.0f g", profile.carbsGrams) else "—",
                )
                values.zip(columns).forEach { (value, offset) -> text(value, margin + offset, cursor, cp) }
                cursor += DAILY_TABLE_ROW_HEIGHT
            }
            return cursor
        }

        /**
         * A day's insulin: the total daily dose where basal is known, the bolus figure where
         * it is not, and a dash where nothing was logged.
         */
        private fun dailyInsulinLabel(profile: DoctorVisitDailyProfile): String {
            val tdd = profile.totalDailyDose
            if (tdd != null && tdd > 0) return fmt("%.1f u", tdd)
            return if (profile.insulinUnits > 0) fmt("%.1f u", profile.insulinUnits) else "—"
        }

        private fun drawFooter() {
            val footerTop = pageHeight - FOOTER_RESERVE
            canvas.drawLine(margin, footerTop, pageWidth - margin, footerTop, stroke(alpha(ACCENT, 0.35f), 1f))
            var y = footerTop + 8
            val brand = paint(9f, ACCENT, Weight.BOLD); val muted = paint(8f, MUTED); val link = paint(8f, ACCENT, Weight.MEDIUM)
            text("BoostT1D — Type 1 Diabetes Companion", margin, y, brand)
            val pageText = "Page $pageCount of $footerTotal"
            text(pageText, pageWidth - margin - muted.measureText(pageText), y, muted)
            y += 12
            val version = appVersionString()
            text(if (version.isEmpty()) "Educational & informational only — not medical advice." else "$version · Educational & informational only — not medical advice.", margin, y, muted)
            y += 11
            text("Website: $WEBSITE_URL", margin, y, link)
            y += 11
            text("Support: $SUPPORT_EMAIL  ·  Available on Google Play", margin, y, link)
        }

        private fun periodLabel(): String {
            val formatter = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            return "${formatter.format(Date(report.periodStartMillis))} – ${formatter.format(Date(report.periodEndMillis))} · ${report.period.title}"
        }
    }

    private val logoBitmap: Bitmap? by lazy {
        runCatching { ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap(104, 104) }.getOrNull()
    }

    private fun appVersionString(): String {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull() ?: return ""
        val version = info.versionName ?: return ""
        val build = info.longVersionCode.toString()
        return if (build == version) "Version $version" else "Version $version ($build)"
    }

    private fun pct(value: Double) = "${value.roundToInt()}%"
    private fun fmt(format: String, vararg args: Any) = String.format(Locale.US, format, *args)

    private companion object {
        /** BoostT1D brand purple used throughout the app. */
        val ACCENT = Color.rgb(122, 59, 199)
        val INK = Color.rgb(31, 31, 31)
        val MUTED = Color.rgb(97, 97, 97)
        val HAIRLINE = Color.rgb(209, 209, 209)
        val CARD_FILL = Color.rgb(245, 245, 245)
        val SYSTEM_RED = Color.rgb(255, 59, 48)
        val SYSTEM_ORANGE = Color.rgb(255, 149, 0)
        val SYSTEM_GREEN = Color.rgb(52, 199, 89)
        val SYSTEM_PURPLE = Color.rgb(175, 82, 222)

        const val WEBSITE_URL = "https://boostt1d.com"
        const val SUPPORT_EMAIL = "info@boostt1d.com"
        const val FOOTER_RESERVE = 78f
        /** How many patterns the summary page carries; later pages start after these. */
        const val PAGE_PATTERN_LIMIT = 3
        const val SECTION_TITLE_HEIGHT = 26f
        const val KEY_VALUE_ROW_HEIGHT = 18f
        const val DAILY_TABLE_HEADER_HEIGHT = 22f
        const val DAILY_TABLE_ROW_HEIGHT = 15f
    }
}
