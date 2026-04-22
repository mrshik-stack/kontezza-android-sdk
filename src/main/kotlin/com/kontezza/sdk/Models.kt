package com.kontezza.sdk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ──────────────────────────────────────────────────────────────
// Public Models
// ──────────────────────────────────────────────────────────────

/**
 * An ad returned by the Kontezza API.
 */
data class KontezzaAd(
    val id: String,
    val campaignId: String,
    val trackingToken: String?,
    val title: String,
    val text: String,
    val url: String,
    val imageUrl: String,
    val category: String,
    val adType: AdType,
    val design: AdDesign,
) {
    enum class AdType { RELEVANT, VECTOR, BANNER }

    data class AdDesign(
        val bgColor: String,
        val borderColor: String,
    )
}

/**
 * Configuration for the Kontezza SDK.
 *
 * @param apiKey    Publisher API key (starts with `ak_`).
 * @param baseUrl   Backend URL. Defaults to `https://api.kontezza.com`.
 * @param debug     When `true`, logs network activity via [android.util.Log].
 * @param context   Optional Android [android.content.Context]. When provided, the
 *                  SDK persists its session ID across app launches via
 *                  `SharedPreferences`. If `null`, a new session ID is generated
 *                  on each process start.
 */
data class KontezzaConfig(
    val apiKey: String,
    val baseUrl: String = "https://api.kontezza.com",
    val debug: Boolean = false,
    val context: android.content.Context? = null,
) {
    init {
        require(apiKey.isNotBlank()) { "[KontezzaSDK] apiKey must not be empty" }
    }

    internal val normalizedBaseUrl: String
        get() = baseUrl.trimEnd('/')
}

// ──────────────────────────────────────────────────────────────
// Internal API Models
// ──────────────────────────────────────────────────────────────

@Serializable
internal data class CampaignStatusResponse(
    val relevant_campaign_id: String? = null,
    val relevant_ad_ids: List<String>? = null,
    val banner_campaign_id: String? = null,
    val banner_ad_ids: List<String>? = null,
    val vector_campaign_id: String? = null,
    val vector_ad_ids: List<String>? = null,
    val design: DesignResponse? = null,
) {
    @Serializable
    data class DesignResponse(
        val bg_color: String? = null,
        val border_color: String? = null,
    )
}

@Serializable
internal data class AdSearchResponse(
    val ad: RawAd? = null,
    val message: String? = null,
) {
    @Serializable
    data class RawAd(
        val id: String,
        val campaign_id: String? = null,
        val tracking_token: String? = null,
        val payload: Payload? = null,
    ) {
        @Serializable
        data class Payload(
            val title: String? = null,
            val text: String? = null,
            val url: String? = null,
            val image_url: String? = null,
            val category: String? = null,
        )
    }
}

@Serializable
internal data class SearchRequestBody(
    val user_message: String,
    val ai_response: String,
    val session_id: String,
)

@Serializable
internal data class TrackRequestBody(
    val ad_id: String,
    val campaign_id: String,
    val tracking_token: String,
    val session_id: String,
)
