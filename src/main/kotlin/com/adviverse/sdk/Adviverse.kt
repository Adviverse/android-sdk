package com.adviverse.sdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Adviverse — native Android ad SDK.
 *
 * A small, dependency-light client for the Adviverse ad engine's in-app
 * endpoint (`/mobile/ad`). It builds a request from the publisher's tag plus
 * the device's advertising signals, parses the standard ad JSON, and fires the
 * impression / click beacons the engine returns.
 *
 * ## Quick start
 * ```
 * Adviverse.configure(context, tag = "pub-123-banner", baseUrl = "https://ads.adviverse.com")
 *
 * // Coroutine:
 * val ad = Adviverse.loadAd("home-banner", AdSize.BANNER)
 * adView.render(ad)
 *
 * // Callback (Java-friendly):
 * Adviverse.loadAd("home-banner", AdSize.BANNER) { ad, err -> ... }
 * ```
 *
 * ## Privacy
 * The SDK honors Limit-Ad-Tracking: when the user has opted out of ad
 * personalization it sends `lmt=1` and drops the advertising id entirely, so
 * the engine falls back to a cookieless (hashed IP+UA) identity. Pass consent
 * signals with [setConsent]; pass a hashed-email advanced-matching key with
 * [setEmail].
 *
 * This is a reference implementation: the logic is idiomatic and matches the
 * server contract exactly, but it has not been compiled against a device
 * toolchain in this repo — build and smoke-test on device before shipping.
 */
object Adviverse {

    /** Identifies this client to the engine's version gate (`client` + `v`). */
    private const val CLIENT_NAME = "android"
    // Not `const`: BuildConfig fields are not Kotlin compile-time constants.
    private val SDK_VERSION = BuildConfig.SDK_VERSION
    private val USER_AGENT = "AdviverseSDK-Android/$SDK_VERSION"

    private const val PREFS = "adviverse_sdk"
    private const val KEY_FALLBACK_UID = "fallback_uid"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            // Click beacons reply with a 302 to the landing page; we never want
            // OkHttp to chase that automatically — the caller decides whether to
            // open it. Redirects are toggled per-request via newClient below.
            .followRedirects(true)
            .build()
    }

    /** Background scope for fire-and-forget beacons. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var appContext: Context? = null
    @Volatile private var baseUrl: String? = null
    @Volatile private var defaultTag: String? = null

    // Advanced matching: sha256(lowercased email) -> `em`. Null until set.
    @Volatile private var emailHash: String? = null

    // Consent passthrough (see setConsent).
    @Volatile private var consent: Consent = Consent()

    // -- Configuration ---------------------------------------------------------

    /**
     * Configure the SDK once at app startup.
     *
     * @param context   any context; the application context is retained.
     * @param tag       the publisher's default ad tag (used when [loadAd] is
     *                  called without an explicit placement).
     * @param baseUrl   the engine origin, e.g. `https://ads.adviverse.com`.
     */
    @JvmStatic
    fun configure(context: Context, tag: String, baseUrl: String) {
        this.appContext = context.applicationContext
        this.defaultTag = tag.trim().ifEmpty { null }
        this.baseUrl = baseUrl.trim().trimEnd('/')
    }

    /**
     * Set the user's email for advanced (Meta-style) matching. The email is
     * lowercased, trimmed, and SHA-256 hashed on-device; only the 64-char hex
     * digest leaves the device as the `em` parameter. The raw email is never
     * sent or stored. Pass a blank string (or call [clearEmail]) to remove it.
     */
    @JvmStatic
    fun setEmail(email: String?) {
        val normalized = email?.trim()?.lowercase().orEmpty()
        emailHash = if (normalized.isEmpty()) null else sha256Hex(normalized)
    }

    /** Remove any previously-set email hash. */
    @JvmStatic
    fun clearEmail() {
        emailHash = null
    }

    /**
     * Provide privacy / consent signals, forwarded to the engine on every
     * request.
     *
     * @param gdprApplies whether GDPR applies to this user (null = unknown).
     *                    Sent as `gdpr=1|0`.
     * @param tcfConsent  the IAB TCF v2 consent string. Sent as `gdpr_consent`.
     * @param usPrivacy   the IAB US-privacy (CCPA) string, e.g. `1YNN`. Sent as
     *                    `us_privacy`.
     */
    @JvmStatic
    @JvmOverloads
    fun setConsent(
        gdprApplies: Boolean? = null,
        tcfConsent: String? = null,
        usPrivacy: String? = null,
    ) {
        consent = Consent(
            gdprApplies = gdprApplies,
            tcfConsent = tcfConsent?.trim()?.ifEmpty { null },
            usPrivacy = usPrivacy?.trim()?.ifEmpty { null },
        )
    }

    // -- Ad loading ------------------------------------------------------------

    /**
     * Load an ad for [placement] (falls back to the configured default tag when
     * blank). Returns null on no-fill (HTTP 204 / empty body). Suspends; the
     * GAID lookup + HTTP run on [Dispatchers.IO].
     *
     * @throws AdviverseException on transport / parse errors.
     */
    @JvmStatic
    @JvmOverloads
    suspend fun loadAd(
        placement: String? = null,
        size: AdSize? = null,
        format: AdviverseFormat = AdviverseFormat.BANNER,
    ): AdviverseAd? = withContext(Dispatchers.IO) {
        val base = requireBase()
        val ctx = requireContext()
        val tag = (placement?.trim().takeUnless { it.isNullOrEmpty() }) ?: requireTag()

        val identity = resolveIdentity(ctx)
        val device = collectDeviceInfo(ctx)

        val url = buildString {
            append(base).append("/mobile/ad?")
            val q = QueryBuilder()
            q.add("tag", tag)
            q.add("client", CLIENT_NAME)
            q.add("v", SDK_VERSION)
            q.add("os", "android")
            q.add("format", format.wire)
            size?.let {
                if (it.width > 0) q.add("w", it.width.toString())
                if (it.height > 0) q.add("h", it.height.toString())
            }
            // Privacy: under LMT we send neither ifa nor uid; the engine uses a
            // cookieless identity. Otherwise the GAID (or a first-party UUID) is
            // the stable in-app uid so frequency capping works across sessions.
            q.add("lmt", if (identity.lmt) "1" else "0")
            identity.ifa?.let { q.add("ifa", it) }
            identity.uid?.let { q.add("uid", it) }
            // Device metadata for OS detection + reporting.
            q.add("bundle", device.bundle)
            q.add("appname", device.appName)
            q.add("make", device.make)
            q.add("model", device.model)
            q.add("osv", device.osVersion)
            // Advanced matching + consent passthrough.
            emailHash?.let { q.add("em", it) }
            consent.applyTo(q)
            append(q.build())
        }

        val (status, body) = httpGet(url, followRedirects = true)
        when {
            status == 204 -> null
            status in 200..299 -> if (body.isBlank()) null else parseAd(body)
            else -> throw AdviverseException("ad request failed: HTTP $status")
        }
    }

    /**
     * Callback variant of [loadAd] for Java / non-coroutine callers. The
     * [callback] is invoked on the main thread with either the ad (possibly
     * null on no-fill) or an error.
     */
    @JvmStatic
    @JvmOverloads
    fun loadAd(
        placement: String? = null,
        size: AdSize? = null,
        callback: AdLoadCallback,
    ) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                val ad = loadAd(placement, size)
                callback.onAdLoaded(ad)
            } catch (t: Throwable) {
                callback.onAdFailed(t)
            }
        }
    }

    // -- Beacons ---------------------------------------------------------------

    /**
     * Fire the impression beacon (fire-and-forget). The engine records by
     * request_id; render code should still guard against double-firing per ad
     * instance ([AdviverseAdView] does this for you).
     */
    @JvmStatic
    fun fireImpression(ad: AdviverseAd) {
        val url = absolute(ad.impUrl) ?: return
        ioScope.launch { runCatching { httpGet(url, followRedirects = false) } }
    }

    /**
     * Fire the click beacon WITHOUT navigating (fire-and-forget). Use this only
     * for custom renderers that open the destination themselves (e.g. native
     * ads that navigate straight to the landing URL). For a banner tap prefer
     * [openClickThrough], which records the click AND opens the destination via
     * the engine's redirect in a single request — calling both double-counts.
     */
    @JvmStatic
    fun fireClick(ad: AdviverseAd) {
        val url = absolute(ad.clickUrl) ?: return
        ioScope.launch { runCatching { httpGet(url, followRedirects = false) } }
    }

    /**
     * Open the click-through. This launches the engine's `click_url`, which
     * records the click and 302-redirects to the advertiser's landing page.
     * Prefers a Chrome Custom Tab (in-app browser) and falls back to a normal
     * VIEW intent (which also routes market:// deep links to the Play Store).
     */
    @JvmStatic
    fun openClickThrough(context: Context, ad: AdviverseAd) {
        val url = absolute(ad.clickUrl) ?: ad.creative.landingUrl ?: return
        openUrl(context, url)
    }

    /**
     * Server-verified reward grant. Call ONLY after the user has genuinely
     * completed the rewarded ad. The engine re-validates the request_id via the
     * ad's `reward_url` and is the authoritative grant; returns true only on a
     * confirmed 2xx (and false when the ad carries no reward URL). Suspends; runs
     * on [Dispatchers.IO].
     */
    @JvmStatic
    suspend fun grantReward(ad: AdviverseAd): Boolean = withContext(Dispatchers.IO) {
        val url = absolute(ad.rewardUrl) ?: return@withContext false
        val (status, _) = runCatching { httpGet(url, followRedirects = false) }
            .getOrDefault(-1 to "")
        status in 200..299
    }

    // -- Identity (GAID / LMT / first-party UUID) ------------------------------

    private data class Identity(val ifa: String?, val uid: String?, val lmt: Boolean)

    /**
     * Resolve the in-app identity, respecting Limit-Ad-Tracking:
     *  - LMT on  -> no ifa, no uid (engine uses hashed IP+UA). lmt=1.
     *  - GAID ok -> ifa = uid = GAID. lmt=0.
     *  - no GAID but tracking allowed -> a stable first-party UUID as uid.
     */
    private fun resolveIdentity(ctx: Context): Identity {
        val ad = resolveAdvertisingId(ctx)
        return when {
            ad.limitTracking -> Identity(ifa = null, uid = null, lmt = true)
            ad.gaid != null -> Identity(ifa = ad.gaid, uid = ad.gaid, lmt = false)
            else -> Identity(ifa = null, uid = fallbackUid(ctx), lmt = false)
        }
    }

    private data class AdId(val gaid: String?, val limitTracking: Boolean)

    /**
     * Read the GAID + LMT flag from Play Services. When the user opted out of
     * ads personalization (or the id comes back zeroed), we treat it as
     * limit-tracking. Any failure (Play Services absent) ⇒ no id, tracking
     * NOT forced off — we fall back to a first-party UUID instead.
     */
    private fun resolveAdvertisingId(ctx: Context): AdId {
        return try {
            val info = AdvertisingIdClient.getAdvertisingIdInfo(ctx)
            val id = info.id
            val zeroed = id == null || id == "00000000-0000-0000-0000-000000000000"
            if (info.isLimitAdTrackingEnabled || zeroed) AdId(null, true)
            else AdId(id, false)
        } catch (_: Throwable) {
            AdId(null, false)
        }
    }

    /** A persistent first-party id used only when ad-tracking is NOT limited. */
    private fun fallbackUid(ctx: Context): String {
        val prefs: SharedPreferences =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_FALLBACK_UID, null)?.let { return it }
        val uid = "adv-" + UUID.randomUUID().toString()
        prefs.edit().putString(KEY_FALLBACK_UID, uid).apply()
        return uid
    }

    // -- Device metadata -------------------------------------------------------

    private data class DeviceInfo(
        val bundle: String,
        val appName: String,
        val make: String,
        val model: String,
        val osVersion: String,
    )

    private fun collectDeviceInfo(ctx: Context): DeviceInfo {
        val pkg = ctx.packageName ?: "unknown"
        val appName = runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("unknown")
        return DeviceInfo(
            bundle = pkg,
            appName = appName,
            make = Build.MANUFACTURER ?: "unknown",
            model = Build.MODEL ?: "unknown",
            osVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
        )
    }

    // -- HTTP ------------------------------------------------------------------

    /** Resolve an engine-relative path against the configured base. */
    private fun absolute(pathOrUrl: String?): String? {
        if (pathOrUrl.isNullOrEmpty()) return null
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) return pathOrUrl
        val base = baseUrl ?: return null
        return if (pathOrUrl.startsWith("/")) base + pathOrUrl else "$base/$pathOrUrl"
    }

    /** GET returning (status, body). Used for the ad request + beacons. */
    private fun httpGet(url: String, followRedirects: Boolean): Pair<Int, String> {
        val client = if (followRedirects) http
        else http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            return resp.code to (resp.body?.string().orEmpty())
        }
    }

    /** Fetch raw bytes (used by [AdviverseAdView] to load the banner image). */
    internal fun fetchBytes(url: String): ByteArray? {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        }.getOrNull()
    }

    internal fun resolve(pathOrUrl: String?): String? = absolute(pathOrUrl)

    // -- Landing intent --------------------------------------------------------

    /**
     * Open [url] preferring a Chrome Custom Tab, falling back to a plain VIEW
     * intent. Constructed with the documented Custom-Tabs extras directly, so
     * the SDK does not require the androidx.browser dependency.
     */
    private fun openUrl(context: Context, url: String) {
        val uri = Uri.parse(url)
        val customTab = Intent(Intent.ACTION_VIEW, uri).apply {
            putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1)
            putExtras(Bundle().apply {
                putBinder("android.support.customtabs.extra.SESSION", null)
            })
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(customTab); true }.getOrDefault(false)) return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    // -- Parsing ---------------------------------------------------------------

    /** Parse the engine ad JSON into [AdviverseAd]. Optional fields tolerated. */
    private fun parseAd(json: String): AdviverseAd {
        val root = runCatching { JSONObject(json) }
            .getOrElse { throw AdviverseException("invalid ad JSON", it) }

        val requestId = root.optString("request_id")
        if (requestId.isEmpty()) throw AdviverseException("ad response missing request_id")

        val c = root.optJSONObject("creative") ?: JSONObject()
        val creative = AdviverseCreative(
            id = c.optString("id", ""),
            type = c.optString("type", "image"),
            assetUrl = c.optStringOrNull("asset_url"),
            landingUrl = c.optStringOrNull("landing_url"),
            title = c.optStringOrNull("title"),
            description = c.optStringOrNull("description"),
            iconUrl = c.optStringOrNull("icon_url"),
            mainImageUrl = c.optStringOrNull("main_image_url"),
            cta = c.optStringOrNull("cta"),
            brandName = c.optStringOrNull("brand_name"),
            width = c.optIntOrNull("width"),
            height = c.optIntOrNull("height"),
            duration = c.optIntOrNull("duration"),
        )

        return AdviverseAd(
            requestId = requestId,
            format = root.optString("format", "banner"),
            creative = creative,
            impUrl = root.optString("imp_url").ifEmpty { "/imp?rid=$requestId" },
            clickUrl = root.optString("click_url").ifEmpty { "/click?rid=$requestId" },
            rewardUrl = root.optStringOrNull("reward_url"),
        )
    }

    // -- Small helpers ---------------------------------------------------------

    private fun requireBase(): String =
        baseUrl ?: error("Adviverse.configure() must be called before loadAd()")

    private fun requireContext(): Context =
        appContext ?: error("Adviverse.configure() must be called before loadAd()")

    private fun requireTag(): String =
        defaultTag ?: error("no placement provided and no default tag configured")

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

// ---- Consent ------------------------------------------------------------------

private data class Consent(
    val gdprApplies: Boolean? = null,
    val tcfConsent: String? = null,
    val usPrivacy: String? = null,
) {
    fun applyTo(q: QueryBuilder) {
        gdprApplies?.let { q.add("gdpr", if (it) "1" else "0") }
        tcfConsent?.let { q.add("gdpr_consent", it) }
        usPrivacy?.let { q.add("us_privacy", it) }
    }
}

// ---- Query builder ------------------------------------------------------------

/** Tiny URL-encoded query accumulator (skips null/blank values). */
internal class QueryBuilder {
    private val sb = StringBuilder()
    fun add(key: String, value: String?) {
        if (value.isNullOrEmpty()) return
        if (sb.isNotEmpty()) sb.append('&')
        sb.append(key).append('=').append(URLEncoder.encode(value, "UTF-8"))
    }
    fun build(): String = sb.toString()
}

// ---- Public model -------------------------------------------------------------

/**
 * The ad format requested via the `format` query param. Mirrors the engine's
 * accepted set: banner | interstitial | rewarded | native | video.
 */
enum class AdviverseFormat(val wire: String) {
    BANNER("banner"),
    INTERSTITIAL("interstitial"),
    REWARDED("rewarded"),
    NATIVE("native"),
    VIDEO("video"),
}

/**
 * A standard banner ad size (dp). Use the named constants or a custom size.
 */
data class AdSize(val width: Int, val height: Int) {
    companion object {
        @JvmField val BANNER = AdSize(320, 50)
        @JvmField val LARGE_BANNER = AdSize(320, 100)
        @JvmField val MEDIUM_RECTANGLE = AdSize(300, 250)
        @JvmField val FULL_BANNER = AdSize(468, 60)
        @JvmField val LEADERBOARD = AdSize(728, 90)
    }
}

/**
 * Creative payload, mirroring the engine's `creative` object. Fields are
 * optional because they vary by creative type (image / native / video).
 */
data class AdviverseCreative(
    val id: String,
    val type: String,
    val assetUrl: String?,
    val landingUrl: String?,
    val title: String?,
    val description: String?,
    val iconUrl: String?,
    val mainImageUrl: String?,
    val cta: String?,
    val brandName: String?,
    val width: Int?,
    val height: Int?,
    val duration: Int?,
) {
    /** Best image URL to render: explicit banner asset, else native main image. */
    val imageUrl: String? get() = assetUrl ?: mainImageUrl
}

/**
 * A resolved ad. [impUrl] / [clickUrl] may be engine-relative paths; the SDK
 * resolves them against the configured base when firing beacons.
 */
data class AdviverseAd(
    val requestId: String,
    val format: String,
    val creative: AdviverseCreative,
    val impUrl: String,
    val clickUrl: String,
    /** Server-verified rewarded-completion URL (rewarded only; null otherwise). */
    val rewardUrl: String? = null,
)

/** Callback for the non-coroutine [Adviverse.loadAd] overload. */
interface AdLoadCallback {
    /** Invoked with the ad, or null on no-fill. Called on the main thread. */
    fun onAdLoaded(ad: AdviverseAd?)
    /** Invoked on transport / parse failure. Called on the main thread. */
    fun onAdFailed(error: Throwable)
}

/** Errors surfaced by ad loading. */
class AdviverseException(message: String, cause: Throwable? = null) : Exception(message, cause)

// ---- JSON helpers -------------------------------------------------------------

private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key, "").ifEmpty { null }
}

private fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return when (val v = opt(key)) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }
}
