package com.boostt1d.android.engine

import com.boostt1d.android.data.InsulinTherapyType
import com.boostt1d.android.data.NightscoutTreatment
import kotlin.math.max

/**
 * How insulin is being delivered, derived entirely from uploaded Nightscout data.
 *
 * A closed loop's own decisions are indistinguishable from the user's once they're flattened
 * into an event list. A Trio or AndroidAPS user receives dozens of automatic micro-boluses a
 * day; read naively that looks like a person compulsively correcting — and that misreading
 * ends up in an analysis a clinician sees.
 *
 * This is the single source of truth for that question. Both the AI prompt and the local
 * formula insights read it, so the two can never disagree about whether the user is looping.
 *
 * Nothing here is user-declared and nothing is hardcoded per app: the uploader's own name is
 * passed through verbatim, so a loop that doesn't exist yet works exactly as well as Trio does.
 *
 * Ported 1:1 from the iOS InsulinDeliveryContext. The one difference: iOS defaults
 * [therapyType] to a UserDefaults read; here the caller passes what the profile holds.
 */
class InsulinDeliveryContext(
    treatments: List<NightscoutTreatment>,
    /** What the user told us, when they told us. Overrides detection in both directions. */
    val therapyType: InsulinTherapyType = InsulinTherapyType.UNSPECIFIED,
) {
    data class Uploader(val name: String, val count: Int)

    /** Uploading app names as recorded in `enteredBy`, most frequent first. */
    val uploaders: List<Uploader>
    val bolusCount: Int
    /** Doses the uploader explicitly flagged as algorithm-delivered (SMB or automatic). */
    val automaticBolusCount: Int
    val tempBasalCount: Int
    /**
     * Days the uploaded treatments span. Used to read bolus *rate* rather than raw count,
     * which would otherwise say "automated" for anyone with a long window.
     */
    val observedDays: Double

    init {
        val counts = treatments
            .mapNotNull { it.enteredBy?.trim()?.takeIf { name -> name.isNotEmpty() } }
            .groupingBy { it }
            .eachCount()
        uploaders = counts.entries.sortedByDescending { it.value }.map { Uploader(it.key, it.value) }

        val boluses = treatments.filter { (it.insulin ?: 0.0) > 0 }
        bolusCount = boluses.size
        automaticBolusCount = boluses.count { it.isAlgorithmDelivered }
        tempBasalCount = treatments.count { it.eventType == "Temp Basal" }

        // A row with no timestamp at all is dropped from the span rather than read as the
        // epoch, which would stretch the window to decades and read every rate as zero.
        val times = treatments.map { MealOutcomeBuilder.treatmentMillis(it) }.filter { it > 0 }
        val first = times.minOrNull()
        val last = times.maxOrNull()
        observedDays = if (first != null && last != null && last > first) {
            max((last - first) / 86_400_000.0, 1.0)
        } else {
            1.0
        }
    }

    val bolusesPerDay: Double get() = if (observedDays > 0) bolusCount / observedDays else 0.0

    /**
     * What the uploaded data suggests, independent of what the user said.
     *
     * Three signals, because uploaders disagree about what they record. Any one is sufficient:
     * flagged doses, a stream of temp basals, or a bolus rate no hand could produce. Detection
     * is still worth having — it is right for most people and needs no setup — but it is a
     * guess, and a wrong guess here silences the basal review.
     */
    val detectedClosedLoop: Boolean
        get() = automaticBolusCount > 0 ||
            tempBasalCount >= TEMP_BASAL_AUTOMATION_THRESHOLD ||
            bolusesPerDay >= MANUAL_BOLUSES_PER_DAY_CEILING

    /**
     * True when an automated delivery system is running.
     *
     * The user's own answer wins. They know what is on their body, and no amount of inference
     * from an event list beats being told — particularly for the loops whose uploader flags
     * nothing, writes no temp basals, and is therefore indistinguishable from a person dosing
     * by hand.
     */
    val isClosedLoop: Boolean
        get() = when (therapyType) {
            InsulinTherapyType.UNSPECIFIED -> detectedClosedLoop
            InsulinTherapyType.CLOSED_LOOP -> true
            InsulinTherapyType.PUMP, InsulinTherapyType.INJECTIONS -> false
        }

    /**
     * True when the user's answer contradicts what the data suggests — worth surfacing once
     * rather than silently ignoring one of the two.
     */
    val therapyTypeContradictsData: Boolean
        get() = therapyType != InsulinTherapyType.UNSPECIFIED && therapyType.isClosedLoop != detectedClosedLoop

    /**
     * True when individual doses carry automation flags, so user-initiated and
     * algorithm-delivered insulin can be told apart dose by dose.
     */
    val hasPerDoseAttribution: Boolean get() = automaticBolusCount > 0

    /**
     * Doses the user actually chose — meal boluses and manual corrections. Behavioural
     * observations should refer only to these.
     */
    val userInitiatedBolusCount: Int get() = max(bolusCount - automaticBolusCount, 0)

    /**
     * One-line briefing for a clinician reading the Doctor Visit report.
     *
     * Separate from [promptSummary] on purpose: that one instructs a model ("do not describe
     * these as the user's decisions"), which would read as nonsense in a document handed to a
     * doctor. This one just states what the data shows.
     */
    val clinicalSummary: String
        get() {
            val system = uploaders.firstOrNull()?.let { "Insulin delivered via ${it.name}." } ?: "Delivery app not recorded."

            if (hasPerDoseAttribution) {
                return "$system Automated closed loop: $automaticBolusCount of $bolusCount " +
                    "doses in this period were delivered by the algorithm (SMB/automatic); " +
                    "$userInitiatedBolusCount were user-initiated."
            }
            if (isClosedLoop) {
                return "$system An automated system is running ($tempBasalCount temp-basal " +
                    "adjustments this period). Individual doses are not flagged, so " +
                    "algorithm-delivered and user-initiated insulin cannot be separated here."
            }
            return "$system No automation detected — insulin doses appear user-initiated."
        }

    /**
     * Plain-language briefing for the AI prompt.
     *
     * Reports observations rather than naming a system from a lookup table — the model already
     * knows what "Trio" or "AndroidAPS" means, and passing the name through beats maintaining
     * our own mapping that goes stale. The app never asserts a setup it has not evidenced.
     */
    val promptSummary: String
        get() {
            if (bolusCount == 0 && tempBasalCount == 0 && uploaders.isEmpty()) {
                return "No treatment data — delivery method unknown."
            }

            val lines = mutableListOf<String>()

            lines += if (uploaders.isEmpty()) {
                "Uploading app: not recorded."
            } else {
                "Uploaded by: " + uploaders.take(3).joinToString(", ") { "${it.name} (${it.count} events)" }
            }

            if (hasPerDoseAttribution) {
                lines += "$automaticBolusCount of $bolusCount insulin doses are flagged by the uploader as " +
                    "automatic / SMB — delivered by the closed-loop algorithm, NOT chosen by the user. " +
                    "Do not describe these as the user's dosing decisions, and do not suggest they " +
                    "bolus less often or consolidate corrections."
                lines += "The remaining $userInitiatedBolusCount doses are the user's own (meal boluses " +
                    "and manual corrections). Behavioural observations should refer only to those."
            } else if (isClosedLoop) {
                // Some uploaders never emit per-dose flags. Frequent temp basals still prove
                // automation, so say that much and no more.
                lines += "$tempBasalCount temp-basal adjustments in this period indicate an automated " +
                    "delivery system is running, but individual doses are not flagged as automatic. " +
                    "Some boluses may therefore be algorithm-delivered rather than user-initiated — " +
                    "be cautious about attributing bolus timing or frequency to the user's behaviour."
            } else {
                lines += "No automation markers found. Insulin doses appear to be user-initiated. " +
                    "Do not assume a closed-loop system."
            }

            return lines.joinToString("\n")
        }

    companion object {
        /**
         * Temp basals in the window above which automation is the only sensible explanation.
         *
         * A loop cycling every five minutes emits hundreds per day; someone setting them by
         * hand on a pump might manage a handful in a week. The gap is wide enough that a
         * conservative line here is safe in both directions.
         */
        const val TEMP_BASAL_AUTOMATION_THRESHOLD = 20

        /**
         * Boluses per day above which no person is dosing by hand.
         *
         * A heavy manual doser eats four or five times and corrects a few more — call it eight
         * on a busy day. A loop emitting SMBs produces dozens. Twelve leaves room for the most
         * diligent manual user while catching every automated system, including the ones whose
         * uploader flags nothing and writes no temp basals at all.
         */
        const val MANUAL_BOLUSES_PER_DAY_CEILING = 12.0
    }
}
