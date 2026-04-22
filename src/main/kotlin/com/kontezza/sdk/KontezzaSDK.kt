package com.kontezza.sdk

import kotlinx.serialization.serializer
import java.net.URLEncoder

/**
 * Kontezza AI Ad SDK for Android.
 *
 * Provides contextual advertising for AI applications.
 *
 * ```kotlin
 * val kontezza = KontezzaSDK(KontezzaConfig(apiKey = "ak_...", context = appContext))
 * val ad = kontezza.getVectorAd("Хочу заказать доставку", "Могу помочь...")
 * ```
 *
 * All `get*Ad` methods are **suspend** functions — call them from a coroutine scope.
 * Impression tracking is automatic. Call [trackClick] when the user taps an ad.
 */
class KontezzaSDK(private val config: KontezzaConfig) {

    companion object {
        const val VERSION = "1.1.1"
        private const val CAMPAIGN_CACHE_MS = 30_000L
    }

    private val http = HttpClient(
        apiKey = config.apiKey,
        baseUrl = config.normalizedBaseUrl,
        debug = config.debug,
    )

    private val session = SessionManager(config.context)

    @Volatile private var campaigns: CampaignState = CampaignState.EMPTY
    @Volatile private var campaignsTTL: Long = 0L

    /**
     * Convenience constructor with just an API key.
     */
    constructor(apiKey: String) : this(KontezzaConfig(apiKey = apiKey))

    // ──────────────────────────────────────────────────────
    // Ad Retrieval
    // ──────────────────────────────────────────────────────

    /**
     * Get a contextually relevant ad based on the last dialog exchange.
     *
     * @param userMessage  The user's last message in the conversation.
     * @param aiResponse   The AI assistant's last response.
     * @return A matched ad, or `null` if no relevant ad was found.
     */
    suspend fun getRelevantAd(userMessage: String, aiResponse: String): KontezzaAd? {
        return try {
            refreshCampaignsIfNeeded()

            if (!hasActiveCampaign(KontezzaAd.AdType.RELEVANT)) {
                log("No active relevant campaign")
                return null
            }

            val body = SearchRequestBody(
                user_message = userMessage,
                ai_response = aiResponse,
                session_id = session.get(),
            )
            val response = http.post(
                "/search", body,
                serializer<AdSearchResponse>(),
            )

            val raw = response.ad ?: run {
                log("No relevant ad found")
                return null
            }

            val ad = formatAd(raw, KontezzaAd.AdType.RELEVANT)
            trackImpression(ad)
            log("Relevant ad: ${ad.title}")
            ad
        } catch (e: Exception) {
            log("getRelevantAd error: ${e.message}")
            null
        }
    }

    /**
     * Get a lightweight contextual ad. Faster than [getRelevantAd],
     * good for high-frequency calls.
     *
     * @param userMessage  The user's last message.
     * @param aiResponse   The AI assistant's last response.
     * @return A matched ad, or `null` if no ad is available.
     */
    suspend fun getVectorAd(userMessage: String, aiResponse: String): KontezzaAd? {
        return try {
            refreshCampaignsIfNeeded()

            if (!hasActiveCampaign(KontezzaAd.AdType.VECTOR)) {
                log("No active vector campaign")
                return null
            }

            val body = SearchRequestBody(
                user_message = userMessage,
                ai_response = aiResponse,
                session_id = session.get(),
            )
            val response = http.post(
                "/vector-search", body,
                serializer<AdSearchResponse>(),
            )

            val raw = response.ad ?: run {
                log("No vector ad found")
                return null
            }

            val ad = formatAd(raw, KontezzaAd.AdType.VECTOR)
            trackImpression(ad)
            log("Vector ad: ${ad.title}")
            ad
        } catch (e: Exception) {
            log("getVectorAd error: ${e.message}")
            null
        }
    }

    /**
     * Get a banner ad from an active banner campaign.
     *
     * @return A banner ad, or `null` if no banner campaigns are active.
     */
    suspend fun getBannerAd(): KontezzaAd? {
        return try {
            refreshCampaignsIfNeeded()

            if (!hasActiveCampaign(KontezzaAd.AdType.BANNER)) {
                log("No active banner campaign")
                return null
            }

            val sid = URLEncoder.encode(session.get(), "UTF-8")
            val response = http.get("/banner?session_id=$sid", serializer<AdSearchResponse>())

            val raw = response.ad ?: run {
                log("No banner ad found")
                return null
            }

            val ad = formatAd(raw, KontezzaAd.AdType.BANNER)
            trackImpression(ad)
            log("Banner ad: ${ad.title}")
            ad
        } catch (e: Exception) {
            log("getBannerAd error: ${e.message}")
            null
        }
    }

    // ──────────────────────────────────────────────────────
    // Tracking
    // ──────────────────────────────────────────────────────

    /**
     * Track an impression for an ad. Called automatically by `get*Ad` methods.
     *
     * You only need this if you deferred rendering and want to track the
     * actual moment the ad became visible.
     */
    fun trackImpression(ad: KontezzaAd) {
        val token = ad.trackingToken ?: return
        if (ad.id.isBlank() || ad.campaignId.isBlank()) return
        http.postFireAndForget("/track/impression", TrackRequestBody(
            ad_id = ad.id,
            campaign_id = ad.campaignId,
            tracking_token = token,
            session_id = session.get(),
        ))
    }

    /**
     * Track a click on an ad. Call this when the user taps the ad.
     */
    fun trackClick(ad: KontezzaAd) {
        val token = ad.trackingToken ?: return
        if (ad.id.isBlank() || ad.campaignId.isBlank()) return
        http.postFireAndForget("/track/click", TrackRequestBody(
            ad_id = ad.id,
            campaign_id = ad.campaignId,
            tracking_token = token,
            session_id = session.get(),
        ))
    }

    // ──────────────────────────────────────────────────────
    // Campaign Status
    // ──────────────────────────────────────────────────────

    /** Check if there is an active campaign of the given type. */
    fun hasActiveCampaign(type: KontezzaAd.AdType): Boolean {
        val c = campaigns
        return when (type) {
            KontezzaAd.AdType.RELEVANT -> c.relevantCampaignId != null && c.relevantAdIds.isNotEmpty()
            KontezzaAd.AdType.VECTOR   -> c.vectorCampaignId != null   && c.vectorAdIds.isNotEmpty()
            KontezzaAd.AdType.BANNER   -> c.bannerCampaignId != null   && c.bannerAdIds.isNotEmpty()
        }
    }

    /** Force-refresh the campaign status cache. */
    suspend fun refreshCampaigns() {
        campaignsTTL = 0L
        refreshCampaignsIfNeeded()
    }

    /** Publisher design settings from the server. */
    val design: KontezzaAd.AdDesign
        get() = KontezzaAd.AdDesign(
            bgColor = campaigns.designBgColor ?: "#1e1b4b",
            borderColor = campaigns.designBorderColor ?: "#6366f1",
        )

    // ──────────────────────────────────────────────────────
    // Internal
    // ──────────────────────────────────────────────────────

    private suspend fun refreshCampaignsIfNeeded() {
        val now = System.currentTimeMillis()
        if (now < campaignsTTL) return

        try {
            val status = http.get(
                "/campaigns/active/status",
                serializer<CampaignStatusResponse>(),
            )
            campaigns = CampaignState(
                relevantCampaignId = status.relevant_campaign_id,
                relevantAdIds = status.relevant_ad_ids.orEmpty(),
                bannerCampaignId = status.banner_campaign_id,
                bannerAdIds = status.banner_ad_ids.orEmpty(),
                vectorCampaignId = status.vector_campaign_id,
                vectorAdIds = status.vector_ad_ids.orEmpty(),
                designBgColor = status.design?.bg_color,
                designBorderColor = status.design?.border_color,
            )
            campaignsTTL = now + CAMPAIGN_CACHE_MS
            log("Campaigns refreshed")
        } catch (e: Exception) {
            log("Campaign refresh failed: ${e.message}")
        }
    }

    private fun formatAd(raw: AdSearchResponse.RawAd, type: KontezzaAd.AdType): KontezzaAd {
        val payload = raw.payload
        val c = campaigns
        return KontezzaAd(
            id = raw.id,
            campaignId = raw.campaign_id.orEmpty(),
            trackingToken = raw.tracking_token,
            title = payload?.title.orEmpty(),
            text = payload?.text.orEmpty(),
            url = payload?.url.orEmpty(),
            imageUrl = payload?.image_url.orEmpty(),
            category = payload?.category.orEmpty(),
            adType = type,
            design = KontezzaAd.AdDesign(
                bgColor = c.designBgColor ?: "#1e1b4b",
                borderColor = c.designBorderColor ?: "#6366f1",
            ),
        )
    }

    private fun log(message: String) {
        if (config.debug) android.util.Log.d("KontezzaSDK", message)
    }
}

// ──────────────────────────────────────────────────────────────
// Session Manager
// ──────────────────────────────────────────────────────────────

/**
 * Generates and persists a stable per-install session ID.
 *
 * When an Android [android.content.Context] is provided via [KontezzaConfig],
 * the session ID is stored in `SharedPreferences` and survives app restarts.
 * Without a context the SDK falls back to an in-memory UUID that lives for
 * the current process.
 *
 * Publishers do not need to manage this manually — the SDK attaches it to
 * every request so the backend can enforce deduplication, frequency capping,
 * and fraud controls.
 */
internal class SessionManager(private val context: android.content.Context?) {
    companion object {
        private const val PREFS_NAME = "kontezza_sdk"
        private const val KEY = "kontezza_sid"
    }

    @Volatile private var cached: String? = null
    private val lock = Any()

    fun get(): String {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }

            val ctx = context
            val id = if (ctx != null) {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                val existing = prefs.getString(KEY, null)
                if (!existing.isNullOrBlank()) {
                    existing
                } else {
                    val new = java.util.UUID.randomUUID().toString()
                    prefs.edit().putString(KEY, new).apply()
                    new
                }
            } else {
                java.util.UUID.randomUUID().toString()
            }
            cached = id
            return id
        }
    }
}

// ──────────────────────────────────────────────────────
// Internal Campaign Cache
// ──────────────────────────────────────────────────────

internal data class CampaignState(
    val relevantCampaignId: String?,
    val relevantAdIds: List<String>,
    val bannerCampaignId: String?,
    val bannerAdIds: List<String>,
    val vectorCampaignId: String?,
    val vectorAdIds: List<String>,
    val designBgColor: String?,
    val designBorderColor: String?,
) {
    companion object {
        val EMPTY = CampaignState(
            relevantCampaignId = null, relevantAdIds = emptyList(),
            bannerCampaignId = null, bannerAdIds = emptyList(),
            vectorCampaignId = null, vectorAdIds = emptyList(),
            designBgColor = null, designBorderColor = null,
        )
    }
}
