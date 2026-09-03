package com.boostt1d.android.engine

/**
 * The Days tab: a day-by-day timeline of readings, doses, meals and events.
 *
 * Only [durationLabel] is ported so far; the rest of the builder follows with its own item.
 */
object WhatHappenedDailyOverviewBuilder {

    /**
     * A Nightscout duration in minutes, made readable.
     *
     * The Days tab prints Nightscout's raw minute counts. An override left running until it is
     * cancelled arrives as 43200, so "43200 min" was a number the reader had to divide before
     * it said anything.
     */
    fun durationLabel(minutes: Int): String {
        if (minutes < 60) return "$minutes min"

        if (minutes < 1440) {
            val hours = minutes / 60
            val rest = minutes % 60
            return if (rest > 0) "${hours}h ${rest}m" else "${hours}h"
        }

        val days = minutes / 1440
        val hours = (minutes % 1440) / 60
        val dayPart = if (days == 1) "1 day" else "$days days"
        return if (hours > 0) "$dayPart ${hours}h" else dayPart
    }
}
