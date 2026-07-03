package com.adviverse.sdk

import android.view.View

/**
 * A native ad. Unlike banner/interstitial/rewarded, the SDK does NOT render a
 * native ad — the engine returns structured creative fields (title, body, icon,
 * main image, CTA, brand) and the host app lays them out in its own views to
 * match its design. The SDK still owns tracking: register the rendered view(s)
 * to fire the impression once on screen and route taps through the engine's
 * tracked click URL (which records the click and opens the landing page).
 *
 * Load via [AdviverseNativeAd.load], render the fields, then call [registerView].
 *
 * No networking is duplicated here: loading reuses `Adviverse.loadAd` and
 * tracking reuses `Adviverse.fireImpression` / `Adviverse.openClickThrough`.
 */
class AdviverseNativeAd internal constructor(val ad: AdviverseAd) {

    // Convenience accessors for a host layout.
    val title: String? get() = ad.creative.title
    val body: String? get() = ad.creative.description
    val iconUrl: String? get() = ad.creative.iconUrl
    val mainImageUrl: String? get() = ad.creative.mainImageUrl ?: ad.creative.assetUrl
    val callToAction: String? get() = ad.creative.cta
    val brandName: String? get() = ad.creative.brandName
    val landingUrl: String? get() = ad.creative.landingUrl

    private var impressionFired = false

    /**
     * Wire the rendered native ad for tracking. Fires the impression beacon once
     * (when [container] is attached to a window) and routes taps on each of
     * [clickableViews] (e.g. the CTA button + main image) through the tracked
     * click URL, opening the landing page on tap.
     */
    fun registerView(container: View, clickableViews: List<View>) {
        if (container.isAttachedToWindow) {
            fireImpressionOnce()
        } else {
            container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    fireImpressionOnce()
                    v.removeOnAttachStateChangeListener(this)
                }
                override fun onViewDetachedFromWindow(v: View) {}
            })
        }
        val onClick = View.OnClickListener { v -> Adviverse.openClickThrough(v.context, ad) }
        clickableViews.forEach { it.setOnClickListener(onClick) }
    }

    private fun fireImpressionOnce() {
        if (impressionFired) return
        impressionFired = true
        Adviverse.fireImpression(ad)
    }

    companion object {
        /** Load a native ad for [placement]. Returns null on no-fill (HTTP 204). */
        @JvmStatic
        @JvmOverloads
        suspend fun load(placement: String? = null): AdviverseNativeAd? {
            val ad = Adviverse.loadAd(placement, size = null, format = AdviverseFormat.NATIVE)
                ?: return null
            return AdviverseNativeAd(ad)
        }
    }
}
