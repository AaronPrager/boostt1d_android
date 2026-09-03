package com.boostt1d.android.data

/**
 * The hand-entered (or synced-and-flattened) therapy profile as the one-document shape the
 * engine reads.
 *
 * The review builder and the change detector take a Nightscout profile document because on
 * Nightscout that document *is* the edit history. A device profile has no history of its own —
 * the detector records a snapshot on each save instead — so it becomes a single "Default"
 * store entry dated to its last save. Values are already mg/dL; the entry form converts on
 * the way in.
 */
fun TherapyProfile.toDocument(): NightscoutProfileDocument? {
    if (isEmpty) return null
    return NightscoutProfileDocument(
        id = null,
        defaultProfile = "Default",
        store = mapOf(
            "Default" to ProfileStoreEntry(
                units = "mg/dl",
                dia = dia,
                basal = basal,
                carbRatio = carbRatio,
                sensitivity = sensitivity,
                targetLow = targetLow,
                targetHigh = targetHigh,
            )
        ),
        mills = updatedAtMillis.takeIf { it > 0 },
        startDate = null,
        createdAt = null,
        units = "mg/dl",
    )
}
