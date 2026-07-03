package com.adviverse.sdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Full-screen interstitial controller. Preload with [load], then [show] once
 * [isReady]. Presents the shared [AdviverseFullscreenActivity].
 *
 * The networking/identity/beacon logic is not duplicated here — this only adds
 * the presentation layer on top of the existing `Adviverse.loadAd` API.
 *
 * NOTE: `AdviverseFullscreenActivity` must be declared in the merged
 * AndroidManifest (the SDK library manifest declares it for you).
 */
class AdviverseInterstitial(
    private val context: Context,
    private val placement: String? = null,
) {
    interface Listener {
        fun onLoaded() {}
        fun onFailed(error: Throwable?) {}
        fun onNoFill() {}
        fun onShown() {}
        fun onClicked() {}
        fun onDismissed() {}
    }

    var listener: Listener? = null

    /** Seconds before the close button appears. */
    var closeDelaySeconds: Int = 2

    private var ad: AdviverseAd? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val isReady: Boolean get() = ad != null

    /** Preload the interstitial. Call [show] once [isReady] (or wait for onLoaded). */
    fun load() {
        scope.launch {
            try {
                val loaded = Adviverse.loadAd(placement, size = null, format = AdviverseFormat.INTERSTITIAL)
                if (loaded == null) {
                    listener?.onNoFill()
                    return@launch
                }
                ad = loaded
                listener?.onLoaded()
            } catch (t: Throwable) {
                listener?.onFailed(t)
            }
        }
    }

    /** Present the preloaded interstitial full-screen. */
    fun show() {
        val current = ad ?: run {
            listener?.onFailed(AdviverseException("interstitial not ready"))
            return
        }
        val callbacks = object : AdviverseFullscreenActivity.Callbacks {
            override fun onImpression() { listener?.onShown() }
            override fun onClicked() { listener?.onClicked() }
            override fun onClosed() {
                listener?.onDismissed()
                ad = null // consume; a fresh load() is required for the next show
            }
        }
        val requestId = AdviverseFullscreenActivity.register(current, callbacks)
        val intent = Intent(context, AdviverseFullscreenActivity::class.java).apply {
            putExtra(AdviverseFullscreenActivity.EXTRA_REQUEST_ID, requestId)
            putExtra(AdviverseFullscreenActivity.EXTRA_REWARDED, false)
            putExtra(AdviverseFullscreenActivity.EXTRA_CLOSE_DELAY_MS, closeDelaySeconds * 1000L)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
