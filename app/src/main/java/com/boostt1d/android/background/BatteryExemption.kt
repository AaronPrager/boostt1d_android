package com.boostt1d.android.background

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Asking to be left running.
 *
 * Doze and, more aggressively, several OEM battery managers will stop periodic work for
 * hours without telling anyone. On a phone that does that, a CGM's history window can
 * close between one sync and the next, and the readings are gone.
 *
 * The exemption is requested, never assumed: the system dialog is the user's decision and
 * some builds refuse it outright. The app has to work either way, which is why the stale
 * reminder exists rather than this being relied on.
 */
object BatteryExemption {

    fun isExempt(context: Context): Boolean {
        val manager = context.getSystemService(PowerManager::class.java) ?: return true
        return manager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Opens the system's own battery-optimisation screen for this app.
     *
     * Deliberately the settings screen rather than the direct
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog: Play's policy restricts that dialog
     * to apps whose core function genuinely requires it, and a listing rejection is a
     * worse outcome than one extra tap.
     */
    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            // Not every build has that screen; the app's own settings page always exists.
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
