package com.adviverse.sdk

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared full-screen renderer for interstitial + rewarded ads.
 *
 * The presenting controller stashes the loaded [AdviverseAd] in a static
 * registry keyed by request_id and launches this Activity with that key (the ad
 * is not Parcelable; this keeps the surface dependency-light). The Activity
 * renders the creative full-bleed, fires the impression on resume, reveals a
 * close button after a delay, and — for rewarded — signals "earned" once the
 * user has stayed for the creative duration.
 *
 * Register this Activity in the host app's AndroidManifest.xml (the library
 * manifest already declares it; see the SDK README).
 *
 * Reference implementation — verify presentation/back-handling on device.
 */
class AdviverseFullscreenActivity : Activity() {

    companion object {
        const val EXTRA_REQUEST_ID = "com.adviverse.sdk.requestId"
        const val EXTRA_REWARDED = "com.adviverse.sdk.rewarded"
        const val EXTRA_CLOSE_DELAY_MS = "com.adviverse.sdk.closeDelayMs"

        // request_id -> ad + callback bundle. Cleared when the Activity finishes.
        private val registry = ConcurrentHashMap<String, Presentation>()

        internal fun register(ad: AdviverseAd, callbacks: Callbacks): String {
            registry[ad.requestId] = Presentation(ad, callbacks)
            return ad.requestId
        }

        internal fun take(requestId: String?): Presentation? =
            requestId?.let { registry[it] }

        internal fun clear(requestId: String?) {
            requestId?.let { registry.remove(it) }
        }
    }

    internal data class Presentation(val ad: AdviverseAd, val callbacks: Callbacks)

    internal interface Callbacks {
        fun onImpression() {}
        fun onClicked() {}
        fun onRewardEarned() {}
        fun onClosed() {}
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private var requestId: String? = null
    private var presentation: Presentation? = null
    private var rewarded = false
    private var closeDelayMs = 2_000L

    private var impressionFired = false
    private var rewardSignaled = false
    private var closeButton: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        rewarded = intent.getBooleanExtra(EXTRA_REWARDED, false)
        closeDelayMs = intent.getLongExtra(EXTRA_CLOSE_DELAY_MS, 2_000L)
        presentation = take(requestId)

        val p = presentation
        if (p == null) {
            // Lost state (e.g. process death); nothing to show.
            finish()
            return
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
            contentDescription = p.ad.creative.title
                ?: p.ad.creative.brandName ?: "Advertisement"
            setOnClickListener { handleClick() }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(imageView)

        closeButton = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 20f
            setBackgroundColor(Color.argb(128, 0, 0, 0))
            setPadding(28, 12, 28, 16)
            visibility = View.GONE
            contentDescription = "Close advertisement"
            setOnClickListener { finishWithClose() }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.TOP or Gravity.END; topMargin = 36; rightMargin = 36 }
        }
        root.addView(closeButton)

        setContentView(root)
        loadCreative(p.ad, imageView)
    }

    private fun loadCreative(ad: AdviverseAd, imageView: ImageView) {
        val url = Adviverse.resolve(ad.creative.imageUrl) ?: run { finishWithClose(); return }
        scope.launch {
            val bmp: Bitmap? = withContext(Dispatchers.IO) {
                Adviverse.fetchBytes(url)?.let { bytes ->
                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                }
            }
            if (bmp == null) { finishWithClose(); return@launch }
            imageView.setImageBitmap(bmp)
        }
    }

    override fun onResume() {
        super.onResume()
        val p = presentation ?: return
        if (!impressionFired) {
            impressionFired = true
            Adviverse.fireImpression(p.ad)
            p.callbacks.onImpression()
        }
        // Reveal close after the delay. For rewarded, also gate "earned" on the
        // creative duration (proxy for completion in this image-based renderer).
        val durationMs = ((p.ad.creative.duration ?: 5).coerceAtLeast(1)) * 1000L
        val revealAt = if (rewarded) maxOf(closeDelayMs, durationMs) else closeDelayMs
        handler.postDelayed({
            closeButton?.visibility = View.VISIBLE
            if (rewarded && !rewardSignaled) {
                rewardSignaled = true
                p.callbacks.onRewardEarned()
            }
        }, revealAt)
    }

    private fun handleClick() {
        val p = presentation ?: return
        p.callbacks.onClicked()
        // The engine's click URL records the click AND 302-redirects to the
        // advertiser landing page; opening it both counts and navigates.
        Adviverse.openClickThrough(this, p.ad)
    }

    private fun finishWithClose() {
        presentation?.callbacks?.onClosed()
        clear(requestId)
        finish()
    }

    // Only allow back-dismiss once the close button is visible.
    override fun onBackPressed() {
        if (closeButton?.visibility == View.VISIBLE) {
            finishWithClose()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        clear(requestId)
        super.onDestroy()
    }
}
