package com.adviverse.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

/**
 * VAST parser / resolver tests (pure JVM). The network is stubbed through the
 * resolver's injectable fetcher; KXmlParser (test-only dependency) stands in for
 * the platform XmlPullParser that `android.util.Xml` returns on device.
 */
class AdviverseVastTest {

    private fun wrapper(next: String, imp: String) = """
        <VAST version="4.0"><Ad id="w"><Wrapper followAdditionalWrappers="true">
          <AdSystem>W</AdSystem>
          <VASTAdTagURI><![CDATA[$next]]></VASTAdTagURI>
          <Impression><![CDATA[$imp]]></Impression>
          <Error><![CDATA[https://w.example/err?c=[ERRORCODE]]]></Error>
          <Creatives><Creative><Linear><TrackingEvents>
            <Tracking event="start"><![CDATA[https://w.example/start]]></Tracking>
          </TrackingEvents>
          <VideoClicks><ClickTracking>https://w.example/clk</ClickTracking></VideoClicks>
          </Linear></Creative></Creatives>
        </Wrapper></Ad></VAST>
    """.trimIndent()

    private val inline = """
        <?xml version="1.0" encoding="UTF-8"?>
        <VAST version="4.0"><Ad id="i"><InLine>
          <AdSystem>I</AdSystem>
          <Impression><![CDATA[https://i.example/imp]]></Impression>
          <Error>https://i.example/err?c=[ERRORCODE]&amp;x=1</Error>
          <Creatives><Creative><Linear skipoffset="25%">
            <Duration>00:00:20.000</Duration>
            <TrackingEvents>
              <Tracking event="start">https://i.example/start</Tracking>
              <Tracking event="complete">https://i.example/done</Tracking>
            </TrackingEvents>
            <VideoClicks>
              <ClickThrough><![CDATA[https://advertiser.example/landing?a=1]]></ClickThrough>
              <ClickTracking>https://i.example/clk</ClickTracking>
            </VideoClicks>
            <MediaFiles>
              <MediaFile type="application/javascript" apiFramework="VPAID">https://i.example/v.js</MediaFile>
              <MediaFile type="video/webm" width="1280">https://i.example/v.webm</MediaFile>
              <MediaFile type="video/mp4" width="1920">https://i.example/1080.mp4</MediaFile>
              <MediaFile type="video/mp4" width="1280">https://i.example/720.mp4</MediaFile>
              <MediaFile type="video/mp4" width="640">https://i.example/360.mp4</MediaFile>
            </MediaFiles>
          </Linear></Creative></Creatives>
        </InLine></Ad></VAST>
    """.trimIndent()

    private val fetched = mutableListOf<String>()

    private fun resolve(tag: String, docs: Map<String, String>): Result<AdviverseVastResult> =
        AdviverseVast.resolve(
            tag,
            fetch = { url -> fetched += url; docs.entries.firstOrNull { url.startsWith(it.key) }?.value },
            newParser = { KXmlParser() },
        )

    private fun failureCode(r: Result<AdviverseVastResult>): AdviverseVastFailure =
        r.exceptionOrNull() as AdviverseVastFailure

    @Test fun inlinePicksMp4AndCollectsBeacons() {
        val v = resolve("https://i.example/vast", mapOf("https://i.example/vast" to inline)).getOrThrow()
        assertEquals("https://i.example/720.mp4", v.mediaUrl)
        assertEquals("video/mp4", v.mediaType)
        assertEquals("https://advertiser.example/landing?a=1", v.clickThrough)
        assertEquals(listOf("https://i.example/imp"), v.impressions)
        assertEquals(listOf("https://i.example/err?c=[ERRORCODE]&x=1"), v.errors)
        assertEquals(listOf("https://i.example/done"), v.tracking["complete"])
        assertEquals(20.0, v.durationSec!!, 0.001)
        assertEquals(5.0, v.skipOffsetSec!!, 0.001) // 25% of 20s
    }

    @Test fun wrapperChainAccumulatesAcrossHops() {
        val docs = mapOf(
            "https://a.example/tag" to wrapper("https://b.example/tag?cb=[CACHEBUSTING]", "https://a.example/imp"),
            "https://b.example/tag" to wrapper("https://i.example/vast", "https://b.example/imp"),
            "https://i.example/vast" to inline,
        )
        val v = resolve("https://a.example/tag", docs).getOrThrow()
        assertEquals(3, fetched.size)
        assertFalse("macro must be expanded", fetched[1].contains("[CACHEBUSTING]"))
        assertEquals(listOf("https://a.example/imp", "https://b.example/imp", "https://i.example/imp"), v.impressions)
        assertEquals(3, v.tracking["start"]?.size)
        assertEquals(3, v.clickTrackings.size)
        assertEquals(3, v.errors.size)
        assertEquals("https://i.example/720.mp4", v.mediaUrl)
    }

    @Test fun emptyVastIs303WithRootError() {
        val empty = """<VAST version="3.0"><Error><![CDATA[https://e.example/noad?c=[ERRORCODE]]]></Error></VAST>"""
        val f = failureCode(resolve("https://e.example/tag", mapOf("https://e.example/tag" to empty)))
        assertEquals(303, f.code)
        assertEquals(listOf("https://e.example/noad?c=[ERRORCODE]"), f.partial.errors)
    }

    @Test fun wrapperLoopStopsAt302() {
        val loop = wrapper("https://l.example/tag", "https://l.example/imp")
        val f = failureCode(resolve("https://l.example/tag", mapOf("https://l.example/tag" to loop)))
        assertEquals(302, f.code)
        assertEquals(AdviverseVast.MAX_WRAPPERS, fetched.size)
        assertEquals(AdviverseVast.MAX_WRAPPERS, f.partial.impressions.size)
    }

    @Test fun noPlayableMediaIs403() {
        val vpaidOnly = """
            <VAST version="3.0"><Ad><InLine><Creatives><Creative><Linear><MediaFiles>
              <MediaFile type="application/javascript" apiFramework="VPAID">https://x.example/v.js</MediaFile>
              <MediaFile type="video/mp4" apiFramework="VPAID">https://x.example/v.mp4</MediaFile>
            </MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>
        """.trimIndent()
        assertEquals(403, failureCode(resolve("https://x.example/tag", mapOf("https://x.example/tag" to vpaidOnly))).code)
    }

    @Test fun networkFailureIs301AndMalformedIs100() {
        assertEquals(301, failureCode(resolve("https://down.example/tag", emptyMap())).code)
        assertEquals(100, failureCode(resolve("https://bad.example/tag", mapOf("https://bad.example/tag" to "<VAST><Ad>"))).code)
    }

    @Test fun pickMediaFallsBackToPlayableNonMp4() {
        val xml = """
            <VAST><Ad><InLine><Creatives><Creative><Linear><MediaFiles>
              <MediaFile type="video/x-flv">https://h.example/v.flv</MediaFile>
              <MediaFile type="video/webm">https://h.example/v.webm</MediaFile>
            </MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>
        """.trimIndent()
        val ad = AdviverseVast.parse(xml, KXmlParser())!!.first("Ad")!!
        assertEquals("https://h.example/v.webm" to "video/webm", AdviverseVast.pickMedia(ad))
    }

    @Test fun offsetsAndMacros() {
        assertEquals(3723.0, AdviverseVast.parseTime("01:02:03")!!, 0.0)
        assertNull(AdviverseVast.parseOffset("25%", null))
        val e = AdviverseVast.expandMacros("https://x/e?c=[ERRORCODE]&cb=[CACHEBUSTING]&t=[TIMESTAMP]", 405)
        assertTrue(e.startsWith("https://x/e?c=405&cb="))
        assertFalse(e.contains("["))
    }
}
