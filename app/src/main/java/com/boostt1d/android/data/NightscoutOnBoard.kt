package com.boostt1d.android.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * IOB / COB as Nightscout actually publishes them.
 *
 * Loop, Trio, AAPS, OpenAPS and pump uploaders all put these numbers in different JSON
 * shapes. The iOS dashboard used to read only a couple of OpenAPS keys, and a strict
 * double cast dropped whole-unit values that decoded as integers — so a pump showing
 * 4.2 U IOB could still render as 0. Ported 1:1 from the iOS NightscoutOnBoard.
 */
data class NightscoutOnBoardReading(
    val iob: Double?,
    val cob: Double?,
    /** ISO-8601; the device-status row's `created_at` when one carried the figures, else now. */
    val time: String,
) {
    val foundAny: Boolean get() = iob != null || cob != null
}

object NightscoutOnBoard {

    /** Nightscout v2 properties payload: `{ "iob": { "iob": 2.1 }, "cob": { "cob": 18 } }`. */
    fun fromProperties(json: JsonElement, nowIso: String = nowISO()): NightscoutOnBoardReading? {
        val root = json as? JsonObject ?: return null
        val iob = number(nested(root, "iob", "iob"))
            ?: number(nested(root, "iob", "bolusiob"))
            ?: number(root["iob"])
        val cob = number(nested(root, "cob", "cob"))
            ?: number(nested(root, "cob", "mealCOB"))
            ?: number(root["cob"])
        if (iob == null && cob == null) return null
        return NightscoutOnBoardReading(iob, cob, nowIso)
    }

    /**
     * Newest-first device-status array. IOB and COB may live on different rows (Loop posts
     * one document, a CGM uploader posts another more often).
     */
    fun fromDeviceStatuses(json: JsonElement, nowIso: String = nowISO()): NightscoutOnBoardReading? {
        val rows = json as? JsonArray ?: return null
        if (rows.isEmpty()) return null
        var iob: Double? = null
        var cob: Double? = null
        var time = nowIso
        for (row in rows) {
            val entry = row as? JsonObject ?: continue
            if (iob == null) {
                iobValue(entry)?.let {
                    iob = it
                    time = string(entry["created_at"]) ?: time
                }
            }
            if (cob == null) {
                cobValue(entry)?.let {
                    cob = it
                    if (iob != null) time = string(entry["created_at"]) ?: time
                }
            }
            if (iob != null && cob != null) break
        }
        if (iob == null && cob == null) return null
        return NightscoutOnBoardReading(iob, cob, time)
    }

    /** `/pebble` watch face payload: `bgs[0].iob` / `bgs[0].cob`. */
    fun fromPebble(json: JsonElement, nowIso: String = nowISO()): NightscoutOnBoardReading? {
        val root = json as? JsonObject ?: return null
        val bgs = root["bgs"] as? JsonArray ?: return null
        val first = bgs.firstOrNull() as? JsonObject ?: return null
        val iob = number(first["iob"])
        val cob = number(first["cob"])
        if (iob == null && cob == null) return null
        return NightscoutOnBoardReading(iob, cob, nowIso)
    }

    // MARK: - Per-document walk

    fun iobValue(entry: JsonObject): Double? = listOf(
        nested(entry, "loop", "iob", "iob"),
        nested(entry, "loop", "iob", "bolusiob"),
        nested(entry, "loop", "iob", "bolusIOB"),
        nested(entry, "openaps", "iob", "iob"),
        nested(entry, "openaps", "iob", "bolusiob"),
        nested(entry, "openaps", "suggested", "IOB"),
        nested(entry, "openaps", "suggested", "iob"),
        nested(entry, "pump", "iob", "bolusiob"),
        nested(entry, "pump", "iob", "iob"),
        nested(entry, "iob", "iob"),
        entry["iob"],
    ).firstNotNullOfOrNull { number(it) }

    fun cobValue(entry: JsonObject): Double? = listOf(
        nested(entry, "loop", "cob", "cob"),
        nested(entry, "loop", "cob", "mealCOB"),
        nested(entry, "openaps", "suggested", "COB"),
        nested(entry, "openaps", "suggested", "mealCOB"),
        nested(entry, "openaps", "suggested", "cob"),
        nested(entry, "openaps", "cob", "cob"),
        nested(entry, "openaps", "cob"),
        nested(entry, "cob", "cob"),
        entry["cob"],
    ).firstNotNullOfOrNull { number(it) }

    /**
     * JSON numbers show up as integers, doubles, or numeric strings. `0` must stay 0, and a
     * literal `true`/`false` is not a number however it is boxed.
     */
    fun number(value: JsonElement?): Double? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (primitive.isString) {
            val trimmed = primitive.content.trim()
            return if (trimmed.isEmpty()) null else trimmed.toDoubleOrNull()
        }
        if (primitive.booleanOrNull != null) return null
        return primitive.doubleOrNull
    }

    fun nested(root: JsonObject, vararg keys: String): JsonElement? {
        var current: JsonElement = root
        for (key in keys) {
            val dict = current as? JsonObject ?: return null
            current = dict[key] ?: return null
        }
        return current
    }

    private fun string(value: JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun nowISO(): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS))
}
