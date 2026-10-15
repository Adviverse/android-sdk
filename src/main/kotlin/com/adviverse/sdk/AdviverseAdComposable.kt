package com.adviverse.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Compose host for [AdviverseAdView].
 *
 * Requires the consuming app to already depend on Compose UI (this SDK declares
 * it `compileOnly`).
 *
 * @param adUnitId placement tag; null uses the configured default tag.
 * @param size requested ad size.
 */
@Composable
fun AdviverseAd(
    adUnitId: String? = null,
    modifier: Modifier = Modifier,
    size: AdSize = AdSize.BANNER,
    listener: AdviverseAdView.Listener? = null,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            AdviverseAdView(ctx).apply {
                this.listener = listener
                tag = adUnitId to size
                loadAd(adUnitId, size)
            }
        },
        update = { view ->
            view.listener = listener
            val key = adUnitId to size
            if (view.tag != key) {
                view.tag = key
                view.loadAd(adUnitId, size)
            }
        },
    )
}
