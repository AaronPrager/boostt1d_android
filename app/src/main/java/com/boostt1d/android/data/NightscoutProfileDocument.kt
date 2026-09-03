package com.boostt1d.android.data

/**
 * One document from Nightscout's `/api/v1/profile.json`.
 *
 * That endpoint returns every profile ever uploaded, each with its own start date. That is a
 * real edit history and it covers changes made long before the app was installed — which is
 * exactly what the therapy change detector reads. Flattening the list to "the current
 * settings" throws that history away, so the documents are kept whole and [TherapyProfile] is
 * derived from one on demand.
 *
 * Maps to the iOS `DiabetesProfile`.
 */
data class NightscoutProfileDocument(
    val id: String?,
    val defaultProfile: String?,
    val store: Map<String, ProfileStoreEntry>,
    /** Nightscout's own epoch milliseconds for when this profile started applying. Most reliable. */
    val mills: Long?,
    /** String dates, a fallback for uploaders that omit `mills`. */
    val startDate: String?,
    val createdAt: String?,
    val units: String?,
) {
    /** The named default entry, else whichever is first — the same choice iOS makes. */
    val activeEntry: ProfileStoreEntry?
        get() = defaultProfile?.let { store[it] } ?: store.values.firstOrNull()

    /** The flattened shape the rest of the app reads, or null when there is nothing usable. */
    fun toTherapyProfile(): TherapyProfile? {
        val entry = activeEntry ?: return null
        val profile = TherapyProfile(
            basal = entry.basal,
            carbRatio = entry.carbRatio,
            sensitivity = entry.sensitivity,
            targetLow = entry.targetLow,
            targetHigh = entry.targetHigh,
            dia = entry.dia,
            source = TherapyProfile.Source.NIGHTSCOUT,
        )
        return profile.takeUnless { it.isEmpty }
    }
}

/**
 * One named profile inside a document's `store`. Maps to the iOS `ProfileData`.
 *
 * Nightscout spells two of these fields two ways (`carbratio`/`carb_ratio`, `sens`/
 * `sensitivity`); both spellings are merged at parse time so nothing downstream has to know.
 */
data class ProfileStoreEntry(
    val units: String?,
    /** Duration of insulin action, hours, as uploaded — clamped by whoever reasons about it. */
    val dia: Double?,
    val basal: List<TimeValue>,
    val carbRatio: List<TimeValue>,
    val sensitivity: List<TimeValue>,
    val targetLow: List<TimeValue>,
    val targetHigh: List<TimeValue>,
)
