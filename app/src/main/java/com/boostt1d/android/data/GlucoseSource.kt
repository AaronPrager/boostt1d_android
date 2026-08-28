package com.boostt1d.android.data

/** The `source` values actually written to a stored reading. */
object GlucoseSourceTag {
    const val NIGHTSCOUT = "nightscout"
    const val DEXCOM = "dexcom"
    const val LIBRE = "libre"

    /** Typed on this device. */
    const val MANUAL = "manual"

    /**
     * Tags naming a remote account — the only readings that can belong to someone else,
     * and therefore the only ones a source switch may withhold from a chart.
     */
    val remoteAccountTags = setOf(NIGHTSCOUT, DEXCOM, LIBRE)
}

/**
 * Which source wins when two of them describe the same moment.
 *
 * Nightscout outranks a direct CGM because it is the richest record — it is the only
 * source carrying treatments and therapy settings — and both outrank a manual entry,
 * which is a person's recollection rather than a sensor's reading.
 */
object GlucoseSourceRank {

    /** Added to the live source so it outranks every other vendor at the same moment. */
    private const val ACTIVE_BOOST = 100

    /** Higher wins. Unlabelled rows rank lowest, so any known source outranks them. */
    fun rank(source: String?, preferredActive: String? = null): Int {
        val normalized = source?.trim()?.lowercase()
        val base = when (normalized) {
            GlucoseSourceTag.NIGHTSCOUT -> 3
            GlucoseSourceTag.DEXCOM, GlucoseSourceTag.LIBRE -> 2
            GlucoseSourceTag.MANUAL -> 1
            else -> 0
        }

        val preferred = preferredActive?.trim()?.lowercase()
        return if (!preferred.isNullOrEmpty() && normalized == preferred) base + ACTIVE_BOOST else base
    }
}

/**
 * Builds one timeline from every cached glucose source.
 *
 * Switching CGM — Nightscout to Dexcom, or back — must not hide the history already on
 * disk. The live source wins when two vendors describe the same moment; older points
 * from a previous source fill the rest of the retention window.
 */
object GlucoseSourceStitch {

    /** Two readings this close together are the same moment described twice. */
    const val DUPLICATE_TOLERANCE_MILLIS = 90_000L

    fun normalized(source: String?): String = source?.trim()?.lowercase() ?: ""

    fun isSameSource(lhs: String?, rhs: String?): Boolean = normalized(lhs) == normalized(rhs)

    /**
     * One representative per ~90-second cluster.
     *
     * An empty [isolateToRemoteTags] stitches every cached source, which is the default
     * after a switch. A non-empty set restores the "only this vendor" filter, for the
     * case where the stored history belongs to a different person's account.
     */
    fun <T> stitched(
        stored: List<T>,
        source: (T) -> String?,
        epoch: (T) -> Long,
        preferredActive: String?,
        isolateToRemoteTags: Set<String> = emptySet(),
    ): List<T> {
        val visible = if (isolateToRemoteTags.isEmpty()) {
            stored
        } else {
            stored.filter { reading ->
                val tag = normalized(source(reading))
                // An untagged or device-local reading is always this user's own.
                if (tag.isEmpty() || tag !in GlucoseSourceTag.remoteAccountTags) true
                else tag in isolateToRemoteTags
            }
        }

        if (visible.size <= 1) return visible

        // The caller's order is preserved, so a newest-first list stays newest-first.
        val newestFirst = epoch(visible.first()) >= epoch(visible.last())
        val chronological = visible.sortedBy(epoch)

        val winners = mutableListOf<T>()
        var cluster = mutableListOf<T>()

        fun flush() {
            val winner = cluster.maxByOrNull {
                GlucoseSourceRank.rank(source(it), preferredActive)
            } ?: return
            winners.add(winner)
            cluster = mutableListOf()
        }

        for (reading in chronological) {
            val first = cluster.firstOrNull()
            if (first != null && epoch(reading) - epoch(first) <= DUPLICATE_TOLERANCE_MILLIS) {
                cluster.add(reading)
            } else {
                flush()
                cluster = mutableListOf(reading)
            }
        }
        flush()

        return if (newestFirst) winners.reversed() else winners
    }
}
