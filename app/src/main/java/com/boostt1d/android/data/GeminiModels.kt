package com.boostt1d.android.data

import kotlinx.serialization.Serializable

// The request and response shapes the BoostT1D proxy speaks — Gemini's own, so the proxy
// forwards bodies unchanged. Optional fields are omitted when null, as Swift's encoder does.

@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    /** Omitted when null, which leaves Gemini on its default sampling. */
    val generationConfig: GeminiGenerationConfig? = null,
)

@Serializable
data class GeminiGenerationConfig(val temperature: Double) {
    companion object {
        /**
         * For analysis that should read the same way when the data reads the same way. At the
         * default temperature of 1.0, an identical prompt comes back with different wording —
         * and different suggested values — on every call.
         */
        val deterministic = GeminiGenerationConfig(temperature = 0.0)
    }
}

@Serializable
data class GeminiContent(val parts: List<GeminiPart>)

@Serializable
data class GeminiPart(val text: String? = null, val inlineData: GeminiInlineData? = null)

@Serializable
data class GeminiInlineData(val mimeType: String, val data: String)

@Serializable
/** No default on `candidates`: a body that is not an envelope must fail here so the direct parse can try. */
data class GeminiResponse(val candidates: List<GeminiCandidate>)

@Serializable
data class GeminiCandidate(val content: GeminiContent)
