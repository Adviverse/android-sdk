package com.adviverse.sdk

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.VideoView
import kotlin.math.ceil

/**
 * Full-screen linear video player for interstitial + rewarded ads, built on the
 * platform [VideoView] (no new dependencies). Plays [AdviverseAd.videoUrl] —
 * the hosted asset or the MediaFile resolved from a third-party VAST tag — and
 * fires start / firstQuartile / midpoint / thirdQuartile / complete (ours +
 * VAST trackers), skip, and VAST errors 405 (playback failure) / 402 (media
 * never started). The hosting Activity owns impression, click and close.
 */
internal class AdviverseVideoPlayer(
    private val activity: Activity,
    private val ad: AdviverseAd,
    private val rewarded: Boolean,
    private val listener: Listener,
) {
    interface Listener {
        /** First frame played: fire the impression. */
        fun onStarted()
        fun onCompleted()
        /** Media failed (error already reported); let the user leave. */
        fun onFailed()
        fun onClicked()
        fun onSkipped()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val videoView = VideoView(activity)
    private val skip = label("").apply { visibility = View.GONE }
    private val cta = label(ad.creative.cta ?: "Learn more")
    private var started = false
    private var finished = false
    private var prepared = false
    private var resumeAt = 0

    val view: FrameLayout = FrameLayout(activity).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.BLACK)
        addView(videoView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(skip, corner(Gravity.BOTTOM or Gravity.END))
        addView(cta, corner(Gravity.BOTTOM or Gravity.START))
        setOnClickListener { if (started) listener.onClicked() }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (finished) return
            tick()
            handler.postDelayed(this, 250)
        }
    }

    private val watchdog = Runnable { if (!started) fail(402) }

    init {
        cta.setOnClickListener { if (started) listener.onClicked() }
        skip.setOnClickListener {
            if (!skip.isEnabled) return@setOnClickListener
            ad.fireVideoEvent("skip")
            finished = true
            listener.onSkipped()
        }
        videoView.setOnPreparedListener { prepared = true }
        videoView.setOnCompletionListener { complete() }
        videoView.setOnErrorListener { _, _, _ -> fail(405); true }
    }

    /** Begin (or continue after onPause) playback. */
    fun resume() {
        if (finished) return
        if (!prepared && resumeAt == 0 && !started) {
            val url = ad.videoUrl ?: return fail(403)
            videoView.setVideoURI(Uri.parse(url))
            handler.postDelayed(watchdog, 10_000)
        } else if (resumeAt > 0) {
            videoView.seekTo(resumeAt)
        }
        videoView.start()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun pause() {
        if (finished) return
        resumeAt = videoView.currentPosition
        videoView.pause()
        handler.removeCallbacks(ticker)
    }

    fun release() {
        finished = true
        handler.removeCallbacksAndMessages(null)
        videoView.stopPlayback()
    }

    private fun tick() {
        val pos = videoView.currentPosition
        if (pos <= 0) return
        if (!started) {
            started = true
            handler.removeCallbacks(watchdog)
            listener.onStarted()
            ad.fireVideoEvent("start")
        }
        val dur = videoView.duration
        if (dur > 0) {
            val p = pos.toDouble() / dur
            if (p >= 0.25) ad.fireVideoEvent("firstQuartile")
            if (p >= 0.50) ad.fireVideoEvent("midpoint")
            if (p >= 0.75) ad.fireVideoEvent("thirdQuartile")
        }
        // Skippable (interstitial only — a rewarded ad must be watched).
        val offset = ad.skipOffsetSec
        if (!rewarded && offset != null) {
            skip.visibility = View.VISIBLE
            val remaining = ceil(offset - pos / 1000.0).toInt()
            skip.isEnabled = remaining <= 0
            skip.text = if (remaining > 0) "Skip in $remaining" else "Skip ▶"
        }
    }

    private fun complete() {
        if (finished) return
        if (!started) { started = true; listener.onStarted(); ad.fireVideoEvent("start") }
        finished = true
        handler.removeCallbacksAndMessages(null)
        ad.fireVideoEvent("complete")
        skip.visibility = View.GONE
        listener.onCompleted()
    }

    private fun fail(code: Int) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        runCatching { videoView.stopPlayback() }
        ad.reportVideoError(code)
        skip.visibility = View.GONE
        listener.onFailed()
    }

    private fun label(text: String) = TextView(activity).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 15f
        setBackgroundColor(Color.argb(140, 0, 0, 0))
        setPadding(32, 18, 32, 18)
    }

    private fun corner(gravity: Int) = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity,
    ).apply { setMargins(36, 36, 36, 48) }
}
