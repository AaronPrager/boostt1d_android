package com.boostt1d.android.home

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.boostt1d.android.data.Config
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Decides when to ask for a Play Store rating: never on the first few launches, then on a
 * slow cadence with a long cool-down.
 *
 * Ported from the iOS ReviewRequestService, cadence for cadence. Play caps how often the
 * dialog actually appears, exactly as the App Store does, so this side only decides when it
 * is worth asking; a suppressed prompt is indistinguishable from a shown one and neither
 * reports back. [Config.IS_DEV_MODE] asks on every launch for testing and records nothing.
 */
class ReviewRequestService(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** One qualified open counts per process, however many times the shell recomposes. */
    private var countedThisSession = false

    /**
     * Call when the main app UI appears. Counts a single qualified open for this session and
     * asks for a review when the cadence and cool-down allow it.
     */
    fun registerQualifiedOpen(activity: Activity, appVersion: String) {
        if (countedThisSession) return
        countedThisSession = true

        val now = System.currentTimeMillis()
        if (!prefs.contains(KEY_INSTALL_DATE)) prefs.edit().putLong(KEY_INSTALL_DATE, now).apply()

        val opens = prefs.getInt(KEY_OPENS, 0) + 1
        prefs.edit().putInt(KEY_OPENS, opens).apply()

        val due = ReviewCadence.isDue(
            opens = opens,
            installMillis = prefs.getLong(KEY_INSTALL_DATE, 0L),
            lastRequestMillis = prefs.getLong(KEY_LAST_REQUEST_DATE, 0L),
            lastRequestVersion = prefs.getString(KEY_LAST_REQUEST_VERSION, null),
            currentVersion = appVersion,
            nowMillis = now,
            isDevMode = Config.IS_DEV_MODE,
        )
        if (!due) {
            devLog("qualified open #$opens, not due yet")
            return
        }
        devLog("qualified open #$opens, due, asking")
        request(activity, now, appVersion)
    }

    private fun request(activity: Activity, nowMillis: Long, appVersion: String) {
        val manager = ReviewManagerFactory.create(context)
        manager.requestReviewFlow().addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                devLog("review flow unavailable: ${task.exception?.message}")
                return@addOnCompleteListener
            }
            manager.launchReviewFlow(activity, task.result).addOnCompleteListener {
                devLog("review flow finished")
                if (Config.IS_DEV_MODE) return@addOnCompleteListener
                prefs.edit()
                    .putLong(KEY_LAST_REQUEST_DATE, nowMillis)
                    .putString(KEY_LAST_REQUEST_VERSION, appVersion)
                    .apply()
            }
        }
    }

    private fun devLog(message: String) {
        if (Config.IS_DEV_MODE) Log.d(TAG, message)
    }

    companion object {
        private const val TAG = "ReviewRequest"
        private const val PREFS_NAME = "review_request"
        private const val KEY_INSTALL_DATE = "installDate"
        private const val KEY_OPENS = "qualifiedOpens"
        private const val KEY_LAST_REQUEST_DATE = "lastRequestDate"
        private const val KEY_LAST_REQUEST_VERSION = "lastRequestVersion"

    }
}

/**
 * When a qualified open is due a rating prompt. Pure, so the cadence can be tested without a
 * device: everything it needs is passed in.
 */
object ReviewCadence {

    /** First ask after this many qualified opens, then once per this many opens after that. */
    const val FIRST_REQUEST_OPEN = 5
    const val REPEAT_EVERY_OPENS = 25

    /** Never ask in the first few days, and never more than once per quarter. */
    const val MIN_DAYS_SINCE_INSTALL = 3
    const val MIN_DAYS_BETWEEN_REQUESTS = 90

    private const val DAY_MILLIS = 86_400_000L

    fun isDue(
        opens: Int,
        installMillis: Long,
        lastRequestMillis: Long,
        lastRequestVersion: String?,
        currentVersion: String,
        nowMillis: Long,
        isDevMode: Boolean,
    ): Boolean {
        // Dev builds ask on every launch so the prompt can actually be seen. Recording the ask
        // is skipped too, otherwise the first dev run would write a cool-down that suppresses
        // every run after it.
        if (isDevMode) return true

        // Cadence: 5, 30, 55, 80, and so on.
        val dueByCount = opens == FIRST_REQUEST_OPEN ||
            (opens > FIRST_REQUEST_OPEN && (opens - FIRST_REQUEST_OPEN) % REPEAT_EVERY_OPENS == 0)
        if (!dueByCount) return false

        if (installMillis > 0 && days(installMillis, nowMillis) < MIN_DAYS_SINCE_INSTALL) return false
        if (lastRequestMillis > 0 && days(lastRequestMillis, nowMillis) < MIN_DAYS_BETWEEN_REQUESTS) return false

        // Play ignores a repeat prompt inside its own quota, so asking again on a version we
        // have already asked on would burn a qualified open for nothing.
        if (lastRequestVersion != null && lastRequestVersion == currentVersion) return false

        return true
    }

    private fun days(startMillis: Long, endMillis: Long): Int =
        ((endMillis - startMillis) / DAY_MILLIS).toInt()
}
