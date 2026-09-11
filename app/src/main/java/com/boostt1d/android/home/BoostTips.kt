package com.boostt1d.android.home

import android.content.Context
import android.content.SharedPreferences
import com.boostt1d.android.data.TodaySoFarBuilder
import java.util.TimeZone

/**
 * The four coaching tips that sit above the bottom bar, and the rules for when one appears.
 *
 * Android has no TipKit, so the parts of it BoostT1D actually used are reproduced here: a
 * tip waits until the product tour is finished, shows at most twice, never returns once
 * dismissed, and only one tip appears on any given day. iOS declares two further tips that
 * are never attached to a view; those are not ported.
 */
enum class BoostTipId(val title: String, val message: String) {
    FOOD(
        "Meals live under Food",
        "Tap Food for Snap a Meal, which estimates carbs from a photo, and Food Log, which " +
            "keeps your saved meals in one place.",
    ),
    INSIGHTS(
        "Explore What Happened?",
        "Tap Insights for What Happened? and the Doctor Visit report.",
    ),
    LOGS(
        "Log as you go",
        "Tap Logs for Event Log and BG Log. Meals belong under Food.",
    ),
    MENU(
        "Everything else",
        "Your profile, insulin doses, help and the product tour live in this menu.",
    ),
}

class BoostTips(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var hasCompletedTutorial: Boolean
        get() = prefs.getBoolean(KEY_TUTORIAL_COMPLETED, false)
        private set(value) = prefs.edit().putBoolean(KEY_TUTORIAL_COMPLETED, value).apply()

    fun markTutorialCompleted() {
        hasCompletedTutorial = true
    }

    /**
     * The tip to show now, or null.
     *
     * Order is the bar's own order, so a new user meets Food before Menu rather than in
     * whatever order they happen to open things.
     */
    fun nextTip(nowMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): BoostTipId? {
        if (!hasCompletedTutorial) return null
        val today = TodaySoFarBuilder.startOfDay(nowMillis, timeZone)
        if (prefs.getLong(KEY_LAST_SHOWN_DAY, 0L) >= today) return null
        return BoostTipId.entries.firstOrNull { tip ->
            !prefs.getBoolean(dismissedKey(tip), false) && prefs.getInt(countKey(tip), 0) < MAX_DISPLAYS
        }
    }

    /** Counts one appearance and closes the day, so a second tip cannot follow the first. */
    fun recordShown(tip: BoostTipId, nowMillis: Long, timeZone: TimeZone = TimeZone.getDefault()) {
        prefs.edit()
            .putInt(countKey(tip), prefs.getInt(countKey(tip), 0) + 1)
            .putLong(KEY_LAST_SHOWN_DAY, TodaySoFarBuilder.startOfDay(nowMillis, timeZone))
            .apply()
    }

    /** Tapping the close button retires a tip for good. Two showings is the cap either way. */
    fun dismiss(tip: BoostTipId) {
        prefs.edit().putBoolean(dismissedKey(tip), true).apply()
    }

    /** Wipes tip history and the tutorial flag, so the tour and the tips can be seen again. */
    fun resetAll() {
        prefs.edit().clear().apply()
    }

    private fun countKey(tip: BoostTipId) = "count_${tip.name}"
    private fun dismissedKey(tip: BoostTipId) = "dismissed_${tip.name}"

    companion object {
        private const val PREFS_NAME = "boost_tips"
        private const val KEY_TUTORIAL_COMPLETED = "tutorialCompleted"
        private const val KEY_LAST_SHOWN_DAY = "lastShownDayStart"
        internal const val MAX_DISPLAYS = 2
    }
}
