package com.boostt1d.android.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Number fields that Nightscout sends inconsistently.
 *
 * The same site will return `sgv` as a JSON number from one uploader and a quoted
 * string from another, and `glucose` as any of string, int or double. iOS handles
 * this with a chain of `try? decode` attempts per field; these serializers are the
 * equivalent, so a single oddly-typed field cannot fail the whole payload.
 *
 * Nothing in a manual-only build hits these — they exist because the wire types are
 * ported once and the sync services arrive later.
 */

private fun Decoder.primitiveOrNull(): JsonPrimitive? =
    (this as? JsonDecoder)?.decodeJsonElement()?.let { element ->
        runCatching { element.jsonPrimitive }.getOrNull()
    }

object FlexibleInt : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        val p = decoder.primitiveOrNull() ?: return decoder.decodeInt()
        return p.content.trim().toIntOrNull()
            ?: p.content.trim().toDoubleOrNull()?.let { Math.round(it).toInt() }
            ?: 0
    }

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

object FlexibleIntOrNull : KSerializer<Int?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleIntOrNull", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int? {
        val p = decoder.primitiveOrNull() ?: return null
        if (p.content == "null") return null
        return p.content.trim().toIntOrNull()
            ?: p.content.trim().toDoubleOrNull()?.let { Math.round(it).toInt() }
    }

    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
    }
}

object FlexibleLongOrNull : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleLongOrNull", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long? {
        val p = decoder.primitiveOrNull() ?: return null
        if (p.content == "null") return null
        return p.content.trim().toLongOrNull()
            ?: p.content.trim().toDoubleOrNull()?.let { Math.round(it) }
    }

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull() else encoder.encodeLong(value)
    }
}

object FlexibleDoubleOrNull : KSerializer<Double?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleDoubleOrNull", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): Double? {
        val p = decoder.primitiveOrNull() ?: return null
        if (p.content == "null") return null
        return p.content.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    }

    override fun serialize(encoder: Encoder, value: Double?) {
        if (value == null) encoder.encodeNull() else encoder.encodeDouble(value)
    }
}

/**
 * A field that may arrive as a string or a number and is always kept as a string.
 * Nightscout's `glucose` is the example: "120", 120 and 120.0 all mean the same
 * reading, and a double is rounded to whole mg/dL exactly as iOS does.
 */
object FlexibleStringOrNull : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleStringOrNull", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val p = decoder.primitiveOrNull() ?: return null
        val raw = p.content
        if (raw == "null" || raw.isEmpty()) return null
        val asDouble = raw.toDoubleOrNull()
        return if (asDouble != null && raw.contains('.')) {
            Math.round(asDouble).toString()
        } else {
            raw
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}
