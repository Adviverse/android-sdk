package com.adviverse.sdk

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * App-open ads: a full-screen ad shown when the user brings the app to the
 * foreground (cold or warm start) — the format AdMob/AppLovin call "app open".
 * It is an interstitial with a foreground trigger and a cooldown, so this is a
 * thin, honest wrapper over [AdviverseInterstitial] rather than a new renderer.
 *
 * Usage (from your Application):
 *
 *     val appOpen = AdviverseAppOpen(app, "android-app-open")
 *     appOpen.listener = object : AdviverseAppOpen.Listener { ... }
 *     appOpen.start()   // preloads, then auto-shows on each foreground
 */
class AdviverseAppOpen(
    private val app: Application,
    private val placement: String? = null,
) {
    interface Listener {
        fun onLoaded() {}
        fun onShown() {}
        fun onClicked() {}
        fun onDismissed() {}
        fun onFailed(error: Throwable?) {}
    }

    var listener: Listener? = null

    /** Minimum millis between two app-open presentations (default 4 min). */
    var minIntervalMs: Long = 240_000L

    private val interstitial = AdviverseInterstitial(app, placement)
    private var autoShow = false
    private var showing = false
    private var lastShownAt = 0L
    private var currentActivity: Activity? = null
    private var startedCount = 0

    init {
        interstitial.listener = object : AdviverseInterstitial.Listener {
            override fun onLoaded() {
                listener?.onLoaded()
                if (autoShow) showIfReady()
            }
            override fun onFailed(error: Throwable?) { showing = false; listener?.onFailed(error) }
            override fun onNoFill() { showing = false }
            override fun onShown() { lastShownAt = System.currentTimeMillis(); listener?.onShown() }
            override fun onClicked() { listener?.onClicked() }
            override fun onDismissed() {
                showing = false
                listener?.onDismissed()
                if (autoShow) interstitial.load() // preload the next
            }
        }
    }

    val isReady: Boolean get() = interstitial.isReady

    /** Preload without auto-showing. */
    fun load() = interstitial.load()

    /**
     * Preload AND auto-present on each foreground (respecting the cooldown). Uses
     * ActivityLifecycleCallbacks to detect app foreground and the current
     * Activity to present over.
     */
    fun start() {
        autoShow = true
        interstitial.load()
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                currentActivity = activity
                // startedCount 0→1 marks the app entering the foreground.
                if (startedCount == 0) showIfReady()
                startedCount++
            }
            override fun onActivityStopped(activity: Activity) {
                if (startedCount > 0) startedCount--
            }
            override fun onActivityResumed(activity: Activity) { currentActivity = activity }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    fun stop() { autoShow = false }

    /** Present now if loaded and the cooldown has elapsed. */
    fun showIfReady(): Boolean {
        if (!interstitial.isReady || showing) return false
        if (lastShownAt != 0L && System.currentTimeMillis() - lastShownAt < minIntervalMs) return false
        showing = true
        interstitial.show()
        return true
    }
}
