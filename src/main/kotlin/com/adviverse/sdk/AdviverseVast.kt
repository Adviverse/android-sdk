package com.adviverse.sdk

import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/**
 * Third-party VAST tag resolver.
 *
 * A video creative may carry a third-party VAST tag (`vast_tag_url`) instead of
 * a hosted asset (`asset_url` empty). [Adviverse.loadAd] resolves it BEFORE
 * reporting success: fetch the tag, follow the Wrapper chain (≤5 hops), pick a
 * playable MediaFile (video/mp4 first, then anything the platform player
 * plays; VPAID / JavaScript units are skipped) and collect every third-party
 * beacon along the chain (Impression / Error / Tracking / ClickTracking) plus
 * the final ClickThrough, skipoffset and Duration.
 *
 * Mirrors the web SDK's sdk_vast.js. No new dependencies: the platform
 * XmlPullParser + the SDK's existing OkHttp client.
 */
data class AdviverseVastResult(
    /** The chosen progressive MediaFile URL. */
    val mediaUrl: String? = null,
    val mediaType: String = "",
    /** The InLine `<ClickThrough>` (advertiser destination). */
    val clickThrough: String? = null,
    val impressions: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val clickTrackings: List<String> = emptyList(),
    /** Linear `<Tracking event="…">` URLs keyed by event name. */
    val tracking: Map<String, List<String>> = emptyMap(),
    /** Linear skipoffset in seconds; null = not skippable. */
    val skipOffsetSec: Double? = null,
    /** InLine `<Duration>` in seconds. */
    val durationSec: Double? = null,
)

/**
 * A failed resolution: VAST error [code] (100 XML, 301 timeout/network, 302
 * wrapper limit, 303 empty VAST, 403 no supported media) plus the beacons
 * collected before the failure (whose `<Error>` URLs must fire).
 */
class AdviverseVastFailure(val code: Int, val partial: AdviverseVastResult) :
    Exception("VAST error $code")

internal object AdviverseVast {

    const val MAX_WRAPPERS = 5

    /** Non-mp4 types the platform MediaPlayer plays, in preference order. */
    private val PLAYABLE = listOf("video/webm", "video/3gpp", "application/x-mpegurl",
        "application/vnd.apple.mpegurl")

    /**
     * Resolve [tag] synchronously (call off the main thread). [fetch] returns the
     * body or null on any transport / non-2xx failure; [newParser] builds a
     * fresh XmlPullParser. Both are injectable for JVM unit tests.
     */
    fun resolve(
        tag: String,
        fetch: (String) -> String?,
        newParser: () -> XmlPullParser,
    ): Result<AdviverseVastResult> {
        val acc = Acc()
        var url = tag
        var depth = 1
        while (true) {
            if (depth > MAX_WRAPPERS) return fail(302, acc)
            val body = runCatching { fetch(expandMacros(url)) }.getOrNull()
            if (body.isNullOrBlank()) return fail(301, acc)
            val root = parse(body, newParser()) ?: return fail(100, acc)
            when (val step = apply(root, acc)) {
                is Step.Next -> { url = step.url; depth++ }
                is Step.Failed -> return fail(step.code, acc)
                Step.Done -> return Result.success(acc.toResult())
            }
        }
    }

    private fun fail(code: Int, acc: Acc): Result<AdviverseVastResult> =
        Result.failure(AdviverseVastFailure(code, acc.toResult()))

    internal sealed class Step {
        data class Next(val url: String) : Step()
        data class Failed(val code: Int) : Step()
        object Done : Step()
    }

    internal class Acc {
        val imp = mutableListOf<String>()
        val err = mutableListOf<String>()
        val clk = mutableListOf<String>()
        val track = linkedMapOf<String, MutableList<String>>()
        var clickThrough: String? = null
        var media: Pair<String, String>? = null
        var skip: Double? = null
        var duration: Double? = null

        fun toResult() = AdviverseVastResult(
            mediaUrl = media?.first, mediaType = media?.second.orEmpty(),
            clickThrough = clickThrough, impressions = imp.toList(), errors = err.toList(),
            clickTrackings = clk.toList(), tracking = track.mapValues { it.value.toList() },
            skipOffsetSec = skip, durationSec = duration,
        )
    }

    /** Apply one parsed VAST document to the accumulated chain result. */
    internal fun apply(root: VastNode, acc: Acc): Step {
        val ad = root.first("Ad")
        if (ad == null) {
            // No-ad response: its root-level <Error> is the one to report.
            val top = root.first("VAST") ?: root
            top.children.filter { it.name == "Error" && it.text.isNotEmpty() }.forEach { acc.err += it.text }
            return Step.Failed(303)
        }
        ad.all("Impression").forEach { if (it.text.isNotEmpty()) acc.imp += it.text }
        ad.all("Error").forEach { if (it.text.isNotEmpty()) acc.err += it.text }
        ad.all("ClickTracking").forEach { if (it.text.isNotEmpty()) acc.clk += it.text }
        ad.all("Tracking").forEach { n ->
            val ev = n.attrs["event"]
            if (!ev.isNullOrEmpty() && n.text.isNotEmpty()) acc.track.getOrPut(ev) { mutableListOf() } += n.text
        }
        val next = ad.first("VASTAdTagURI")?.text.orEmpty()
        if (ad.first("Wrapper") != null && next.isNotEmpty()) return Step.Next(next)
        acc.clickThrough = ad.first("ClickThrough")?.text?.ifEmpty { null }
        acc.duration = ad.first("Duration")?.text?.let(::parseTime)
        ad.first("Linear")?.attrs?.get("skipoffset")?.let { acc.skip = parseOffset(it, acc.duration) }
        acc.media = pickMedia(ad) ?: return Step.Failed(403)
        return Step.Done
    }

    /**
     * Choose a progressive, non-interactive MediaFile: video/mp4 first, then
     * another platform-playable type. Within a type the widest rendition
     * ≤1280px wins (mobile-friendly bitrate), else the narrowest.
     * Returns (url, type).
     */
    internal fun pickMedia(ad: VastNode): Pair<String, String>? {
        data class F(val url: String, val type: String, val width: Int)
        val files = ad.all("MediaFile").mapNotNull { n ->
            val type = n.attrs["type"].orEmpty().lowercase(Locale.US)
            val api = n.attrs["apiFramework"].orEmpty().lowercase(Locale.US)
            val lower = n.text.lowercase(Locale.US)
            when {
                api.contains("vpaid") || type.contains("javascript") || type.contains("shockwave") -> null
                !(lower.startsWith("https://") || lower.startsWith("http://")) -> null
                else -> F(n.text, type, n.attrs["width"]?.toIntOrNull() ?: 0)
            }
        }
        for (t in listOf("video/mp4") + PLAYABLE) {
            val same = files.filter { it.type == t }
            if (same.isEmpty()) continue
            val best = same.filter { it.width <= 1280 }.maxByOrNull { it.width }
                ?: same.minByOrNull { it.width }!!
            return best.url to best.type
        }
        return null
    }

    /** "HH:MM:SS" or "HH:MM:SS.mmm" → seconds. */
    internal fun parseTime(s: String): Double? {
        val p = s.trim().split(":")
        if (p.size != 3) return null
        val h = p[0].toDoubleOrNull() ?: return null
        val m = p[1].toDoubleOrNull() ?: return null
        val sec = p[2].toDoubleOrNull() ?: return null
        return h * 3600 + m * 60 + sec
    }

    /** skipoffset is a time ("00:00:05") or a percentage ("25%") of duration. */
    internal fun parseOffset(s: String, duration: Double?): Double? {
        val t = s.trim()
        if (t.endsWith("%")) {
            val pct = t.dropLast(1).toDoubleOrNull() ?: return null
            return duration?.let { it * pct / 100 }
        }
        return parseTime(t)
    }

    /** Fill the IAB macros a player is responsible for. */
    internal fun expandMacros(u: String, errorCode: Int? = null): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        val ts = URLEncoder.encode(fmt.format(Date()), "UTF-8")
        return u.trim()
            .replace("[CACHEBUSTING]", Random.nextInt(10_000_000, 99_999_999).toString())
            .replace("[TIMESTAMP]", ts)
            .replace("[ERRORCODE]", errorCode?.toString().orEmpty())
    }

    /** Fire third-party GET beacons with macros filled (fire-and-forget). */
    fun fire(urls: List<String>?, errorCode: Int? = null) {
        urls?.forEach { Adviverse.fireUrl(expandMacros(it, errorCode)) }
    }

    // -- Minimal XML tree ------------------------------------------------------

    /** Parse [xml] into a tree under a synthetic document node; null if malformed. */
    internal fun parse(xml: String, parser: XmlPullParser): VastNode? = runCatching {
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        val root = VastNode("#document", emptyMap())
        val stack = ArrayDeque<VastNode>().apply { addLast(root) }
        val text = ArrayDeque<StringBuilder>().apply { addLast(StringBuilder()) }
        var ev = parser.next()
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val attrs = HashMap<String, String>()
                    for (i in 0 until parser.attributeCount) {
                        attrs[parser.getAttributeName(i).substringAfter(':')] = parser.getAttributeValue(i)
                    }
                    val node = VastNode(parser.name.substringAfter(':'), attrs)
                    stack.last().children += node
                    stack.addLast(node)
                    text.addLast(StringBuilder())
                }
                // next() folds CDATA + resolved entities into TEXT.
                XmlPullParser.TEXT -> parser.text?.let { text.last().append(it) }
                XmlPullParser.END_TAG -> {
                    if (stack.size > 1) {
                        stack.removeLast().text = text.removeLast().toString().trim()
                    }
                }
            }
            ev = parser.next()
        }
        if (stack.size != 1) null else root
    }.getOrNull()
}

/** A parsed XML element: name, attributes, trimmed text (CDATA included), children. */
internal class VastNode(val name: String, val attrs: Map<String, String>) {
    var text: String = ""
    val children = mutableListOf<VastNode>()

    /** All descendants named [n], in document order. */
    fun all(n: String): List<VastNode> {
        val out = mutableListOf<VastNode>()
        for (c in children) {
            if (c.name == n) out += c
            out += c.all(n)
        }
        return out
    }

    /** First descendant named [n] (document order). */
    fun first(n: String): VastNode? {
        for (c in children) {
            if (c.name == n) return c
            c.first(n)?.let { return it }
        }
        return null
    }
}

// ---- Video helpers on the ad model ---------------------------------------------

/** Per-ad dedup of video lifecycle events (each fires at most once). */
internal class VideoEventState {
    private val fired = Collections.synchronizedSet(HashSet<String>())
    fun mark(event: String): Boolean = fired.add(event)
}

/** True for a third-party VAST "wrapper creative": no hosted asset, a tag. */
internal val AdviverseCreative.needsVastResolution: Boolean
    get() = assetUrl.isNullOrEmpty() && !vastTagUrl.isNullOrEmpty()

/**
 * The playable video URL: the hosted `asset_url` of a video creative, else the
 * MediaFile resolved from its third-party VAST tag.
 */
val AdviverseAd.videoUrl: String?
    get() = if (creative.type == "video" && !creative.assetUrl.isNullOrEmpty()) {
        Adviverse.resolve(creative.assetUrl)
    } else vast?.mediaUrl

/** True when this ad should be presented as video. */
val AdviverseAd.isVideo: Boolean get() = videoUrl != null

/**
 * Seconds after which a Skip control may appear (VAST skipoffset, else the
 * creative's `skip_offset_sec`); null = not skippable.
 */
val AdviverseAd.skipOffsetSec: Double?
    get() = vast?.skipOffsetSec?.takeIf { it > 0 }
        ?: creative.skipOffsetSec?.takeIf { it > 0 }?.toDouble()

/**
 * Fire a video lifecycle event (start, firstQuartile, midpoint, thirdQuartile,
 * complete, skip, …) at most once per ad: our tracking-only `imp_url&ev=<event>`
 * plus the third-party VAST trackers for it.
 */
fun AdviverseAd.fireVideoEvent(event: String) {
    if (!videoState.mark(event)) return
    Adviverse.resolve(impUrl)?.let { Adviverse.fireUrl("$it&ev=$event") }
    AdviverseVast.fire(vast?.tracking?.get(event))
}

/**
 * Report a VAST error (e.g. 405 playback failure): the chain's `<Error>` URLs
 * with [ERRORCODE] filled, plus our tracking-only `imp_url&ev=error&code=…`.
 */
fun AdviverseAd.reportVideoError(code: Int) {
    if (!videoState.mark("error")) return
    AdviverseVast.fire(vast?.errors, code)
    Adviverse.resolve(impUrl)?.let { Adviverse.fireUrl("$it&ev=error&code=$code") }
}
