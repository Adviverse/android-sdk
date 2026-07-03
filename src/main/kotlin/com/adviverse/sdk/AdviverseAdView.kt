package com.adviverse.sdk

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A minimal banner ad view.
 *
 * Renders an [AdviverseAd]'s banner image into an [ImageView], fires the
 * impression beacon once when the creative first becomes visible, and on tap
 * opens the click-through (which records the click and redirects to the
 * advertiser's landing page).
 *
 * ## Usage
 * ```
 * val adView = AdviverseAdView(context)
 * container.addView(adView)
 *
 * // self-loading:
 * adView.loadAd("home-banner", AdSize.BANNER)
 *
 * // or render an ad you loaded yourself:
 * val ad = Adviverse.loadAd("home-banner", AdSize.BANNER)
 * adView.render(ad)
 * ```
 *
 * The view owns a coroutine scope tied to its attach/detach lifecycle, so any
 * in-flight load or image fetch is cancelled when it leaves the window.
 */
class AdviverseAdView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val imageView = ImageView(context).apply {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            Gravity.CENTER,
        )
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
    }

    /** Recreated on each attach; cancelled on detach. */
    private var scope: CoroutineScope = newScope()

    /** Guards against double impression beacons for the currently-bound ad. */
    private val impressionFired = AtomicBoolean(false)

    private var currentAd: AdviverseAd? = null

    /** Optional listener for load lifecycle (no-fill, error, click). */
    var listener: Listener? = null

    init {
        addView(imageView)
    }

    /**
     * Load and render an ad for [placement] at [size]. No-op visuals on
     * no-fill; errors are routed to [listener]. Safe to call from the main
     * thread — networking happens off-thread.
     */
    @JvmOverloads
    fun loadAd(placement: String? = null, size: AdSize? = null) {
        scope.launch {
            try {
                val ad = Adviverse.loadAd(placement, size)
                if (ad == null) {
                    listener?.onNoFill()
                } else {
                    render(ad)
                }
            } catch (t: Throwable) {
                listener?.onError(t)
            }
        }
    }

    /**
     * Bind and display an already-loaded [ad]. Resets the impression guard,
     * loads the banner image, and wires the tap handler. Pass null to clear.
     */
    fun render(ad: AdviverseAd?) {
        currentAd = ad
        impressionFired.set(false)
        imageView.setImageDrawable(null)
        setOnClickListener(null)
        isClickable = false

        if (ad == null) return

        val imageUrl = Adviverse.resolve(ad.creative.imageUrl)
        if (imageUrl == null) {
            listener?.onNoFill()
            return
        }

        // Tap -> record click + open landing (engine 302 handles the redirect).
        isClickable = true
        contentDescription = ad.creative.title ?: ad.creative.brandName ?: "Advertisement"
        setOnClickListener {
            Adviverse.openClickThrough(context, ad)
            listener?.onClick(ad)
        }

        // Fetch + decode the banner off the main thread, then display.
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                Adviverse.fetchBytes(imageUrl)?.let { bytes ->
                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                }
            }
            if (bmp == null) {
                listener?.onError(AdviverseException("failed to load banner image"))
                return@launch
            }
            imageView.setImageBitmap(bmp)
            maybeFireImpression()
            listener?.onLoaded(ad)
        }
    }

    /** Fire the impression exactly once, when the creative is on-screen. */
    private fun maybeFireImpression() {
        val ad = currentAd ?: return
        if (isShown && impressionFired.compareAndSet(false, true)) {
            Adviverse.fireImpression(ad)
        }
    }

    // -- Lifecycle -------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scope.isActiveScope()) scope = newScope()
    }

    override fun onDetachedFromWindow() {
        scope.coroutineContext[Job]?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // If the banner was bound while off-screen, fire once it becomes visible.
        if (currentAd != null && imageView.drawable != null) maybeFireImpression()
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun CoroutineScope.isActiveScope(): Boolean =
        coroutineContext[Job]?.isActive == true

    /** Optional load/click lifecycle callbacks. */
    interface Listener {
        /** The banner image rendered successfully. */
        fun onLoaded(ad: AdviverseAd) {}
        /** The engine returned no ad (204). */
        fun onNoFill() {}
        /** Loading or image decode failed. */
        fun onError(error: Throwable) {}
        /** The user tapped the banner. */
        fun onClick(ad: AdviverseAd) {}
    }
}
