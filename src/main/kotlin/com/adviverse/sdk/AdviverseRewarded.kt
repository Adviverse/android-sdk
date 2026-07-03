package com.adviverse.sdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** A reward confirmed by the engine. */
data class AdviverseReward(val type: String = "coins", val amount: Int = 1)

/**
 * Full-screen rewarded controller. Same presentation as the interstitial, but
 * the reward is granted ONLY after (a) the user completes the ad and (b) the
 * engine confirms via the server-verified reward URL. The SDK never grants on
 * the client alone.
 *
 * The networking/identity/beacon logic is not duplicated here — this only adds
 * the presentation layer on top of the existing `Adviverse.loadAd` /
 * `Adviverse.grantReward` APIs.
 *
 * NOTE: `AdviverseFullscreenActivity` must be declared in the merged
 * AndroidManifest (the SDK library manifest declares it for you).
 */
class AdviverseRewarded(
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
        /** Credit the user ONLY here — this fires only on a server-confirmed grant. */
        fun onRewardGranted(reward: AdviverseReward) {}
    }

    var listener: Listener? = null

    private var ad: AdviverseAd? = null
    private var rewardEarnedLocally = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val isReady: Boolean get() = ad != null

    /** Preload the rewarded ad. Call [show] once [isReady] (or wait for onLoaded). */
    fun load() {
        scope.launch {
            try {
                val loaded = Adviverse.loadAd(placement, size = null, format = AdviverseFormat.REWARDED)
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

    /** Present the preloaded rewarded ad full-screen. */
    fun show() {
        val current = ad ?: run {
            listener?.onFailed(AdviverseException("rewarded not ready"))
            return
        }
        rewardEarnedLocally = false
        val callbacks = object : AdviverseFullscreenActivity.Callbacks {
            override fun onImpression() { listener?.onShown() }
            override fun onClicked() { listener?.onClicked() }
            // Earned locally = user watched to completion. Do NOT grant yet;
            // wait for server validation, which happens on close.
            override fun onRewardEarned() { rewardEarnedLocally = true }
            override fun onClosed() {
                listener?.onDismissed()
                consumeAndMaybeGrant(current)
            }
        }
        val requestId = AdviverseFullscreenActivity.register(current, callbacks)
        val intent = Intent(context, AdviverseFullscreenActivity::class.java).apply {
            putExtra(AdviverseFullscreenActivity.EXTRA_REQUEST_ID, requestId)
            putExtra(AdviverseFullscreenActivity.EXTRA_REWARDED, true)
            putExtra(AdviverseFullscreenActivity.EXTRA_CLOSE_DELAY_MS, 0L)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * On dismiss: if earned locally, call the engine's reward URL. The engine
     * re-verifies the request_id server-side (authoritative). Only on a
     * confirmed 2xx do we notify the host to credit the user.
     */
    private fun consumeAndMaybeGrant(shownAd: AdviverseAd) {
        val earned = rewardEarnedLocally
        ad = null
        if (!earned) return
        scope.launch {
            val confirmed = Adviverse.grantReward(shownAd)
            if (confirmed) listener?.onRewardGranted(AdviverseReward())
        }
    }
}
