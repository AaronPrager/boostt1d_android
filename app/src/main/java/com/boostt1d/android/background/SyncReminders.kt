package com.boostt1d.android.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.boostt1d.android.MainActivity
import com.boostt1d.android.R
import com.boostt1d.android.data.GlucoseConnectionOption

/**
 * The dead man's switch.
 *
 * Background sync narrows the average gap; it does not close it. Doze, an OEM battery
 * manager, or simply a phone left face-down can stop it for hours, and a vendor CGM only
 * serves a bounded window of history — about 24 hours for Dexcom Share, about 12 for
 * LibreLinkUp. Past that window the readings are gone for good.
 *
 * So the guarantee is not the sync, it is this: tell the person before the window closes,
 * while opening the app still costs them nothing. Background sync is only the thing that
 * should stop these from ever firing.
 */
object SyncReminders {

    private const val CHANNEL_ID = "sync_reminders"
    private const val STALE_ID = 1001
    private const val AUTH_ID = 1002
    private const val GAP_ID = 1003

    /**
     * Fires at three-quarters of the vendor's window, leaving the last quarter as room to
     * act in: 18 hours for Dexcom's 24, 9 for LibreLinkUp's 12.
     */
    fun staleDelayMillis(windowHours: Int): Long =
        (windowHours * 0.75 * 60 * 60 * 1000).toLong()

    /** Rejected credentials cannot repair themselves, so this does not wait out the window. */
    const val AUTH_DELAY_MILLIS = 30L * 60 * 1000

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sync reminders",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Warns you before glucose readings fall out of your CGM's history."
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Deliberately short. A collapsed banner truncates by rendered lines, not characters,
     * so anything past a sentence is unreliable. The title carries the what, the body only
     * the action; detail belongs in the app.
     */
    fun postStale(context: Context, windowHours: Int) = post(
        context, STALE_ID,
        "Glucose data going stale",
        "Open to sync before readings are lost.",
    )

    fun postAuthFailure(context: Context, source: GlucoseConnectionOption) = post(
        context, AUTH_ID,
        "${source.displayName} needs attention",
        "Your credentials were rejected. Open to fix them.",
    )

    /**
     * A gap wider than the window is data that no longer exists anywhere. Said plainly and
     * once, because there is nothing to do about it and repeating it only nags.
     */
    fun postUnrecoverableGap(context: Context, hours: Int) = post(
        context, GAP_ID,
        "Some readings could not be recovered",
        "About ${hours}h is missing and is past your CGM's history window.",
    )

    fun cancelStale(context: Context) =
        NotificationManagerCompat.from(context).cancel(STALE_ID)

    fun cancelAuthFailure(context: Context) =
        NotificationManagerCompat.from(context).cancel(AUTH_ID)

    private fun post(context: Context, id: Int, title: String, body: String) {
        if (!canPost(context)) return
        ensureChannel(context)

        // Tapping opens the app, which is the only action any of these ask for.
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_boost)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
