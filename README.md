# Adviverse Android SDK

A small, native Kotlin client for the Adviverse ad engine's in-app endpoint
(`/mobile/ad`). It builds the ad request from your tag plus the device's
advertising signals, parses the standard ad JSON, renders a banner, and fires
the impression / click beacons the engine returns.

- **Minimal dependencies**: OkHttp + Kotlin coroutines, plus Play Services'
  advertising-id client for the GAID.
- **Privacy-first**: honors Limit-Ad-Tracking, forwards GDPR / US-privacy
  consent, supports SHA-256 email advanced matching.
- **Coroutine and callback** APIs.

> Reference implementation: the code is idiomatic and matches the server
> contract exactly, but build and smoke-test on a device before shipping.

## Install

### Gradle (module dependency)

Add the module to `settings.gradle.kts`:

```kotlin
include(":adviverse-sdk")
project(":adviverse-sdk").projectDir =
    file("sdks/mobile/android")
```

Then in your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":adviverse-sdk"))
}
```

The SDK targets `minSdk 21` and pulls in `kotlinx-coroutines-android`,
`okhttp`, and `play-services-ads-identifier` transitively.

### Permissions

The library manifest already declares `INTERNET`, `ACCESS_NETWORK_STATE`, and
`com.google.android.gms.permission.AD_ID` (required to read the Advertising ID
on Android 13+); they merge into your app automatically.

## Quick start

Configure once at app startup (e.g. in `Application.onCreate`):

```kotlin
Adviverse.configure(
    context = this,
    tag = "pub-123-default",            // your default ad tag
    baseUrl = "https://ads.adviverse.com",
)
```

### Render a banner

```kotlin
val adView = AdviverseAdView(context)
container.addView(adView)

// self-loading: pick the placement tag + a standard size
adView.loadAd("home-banner", AdSize.BANNER)
```

`AdSize` ships `BANNER` (320x50), `LARGE_BANNER` (320x100),
`MEDIUM_RECTANGLE` (300x250), `FULL_BANNER` (468x60), and `LEADERBOARD`
(728x90), or pass a custom `AdSize(w, h)`.

Tapping the banner records the click and opens the advertiser's landing page
(via the engine's redirect) in a Chrome Custom Tab.

### Jetpack Compose

```kotlin
import com.adviverse.sdk.AdviverseAd
import com.adviverse.sdk.AdSize

@Composable
fun Home() {
    AdviverseAd(adUnitId = "home-banner", size = AdSize.BANNER)
}
```

`AdviverseAd` hosts `AdviverseAdView` via `AndroidView`. The SDK declares
Compose `compileOnly`, so your app must already depend on Compose UI.

### Load an ad yourself

Coroutine:

```kotlin
val ad: AdviverseAd? = Adviverse.loadAd("home-banner", AdSize.BANNER)
if (ad != null) {
    adView.render(ad)
} else {
    // no-fill (HTTP 204)
}
```

Callback (Java-friendly):

```kotlin
Adviverse.loadAd("home-banner", AdSize.BANNER, object : AdLoadCallback {
    override fun onAdLoaded(ad: AdviverseAd?) { /* render or handle no-fill */ }
    override fun onAdFailed(error: Throwable) { /* log */ }
})
```

### Beacons (custom renderers)

`AdviverseAdView` fires the impression automatically when the banner becomes
visible and records the click on tap. If you render the creative yourself:

```kotlin
Adviverse.fireImpression(ad)                 // when the ad is on-screen
Adviverse.openClickThrough(context, ad)      // on tap: records + opens landing
// or, if you navigate to creative.landingUrl yourself:
Adviverse.fireClick(ad)                       // records the click only
```

Don't combine `fireClick` with `openClickThrough` for the same tap — the
click-through URL already records the click, so doing both double-counts.

## Identity & Limit-Ad-Tracking

The SDK resolves identity in this order:

1. **Limit-Ad-Tracking on** — sends `lmt=1` and **no** advertising id; the
   engine falls back to a cookieless (hashed IP+UA) identity.
2. **GAID available** — uses it as both `ifa` and `uid` so frequency capping
   and sequencing work across sessions.
3. **No GAID, tracking allowed** — uses a stable first-party UUID (stored in
   the SDK's private `SharedPreferences`) as `uid`.

## Consent

Forward GDPR / CCPA signals; they ride on every request:

```kotlin
Adviverse.setConsent(
    gdprApplies = true,
    tcfConsent = "<IAB TCF v2 string>",   // -> gdpr_consent
    usPrivacy = "1YNN",                    // -> us_privacy
)
```

`gdprApplies` maps to `gdpr=1|0`. When GDPR applies but no TCF string is
present, the engine withholds personal-data processing (contextual serving
still works).

## Email advanced matching

Pass a user email for Meta-style advanced matching. It is lowercased, trimmed,
and SHA-256 hashed **on-device**; only the 64-char hex digest leaves the device
as the `em` parameter. The raw email is never sent or stored.

```kotlin
Adviverse.setEmail("user@example.com")   // -> em=<sha256 hex>
Adviverse.clearEmail()
```

## Attribution (SKAdNetwork analog)

Android has no SKAdNetwork. This SDK supports standard install / click
attribution through the parameters already in the request contract (advertising
id, click/impression beacons, landing redirects). Google's **Privacy Sandbox on
Android** (the Attribution Reporting API, the SKAN-equivalent) is the planned
future surface and is not yet wired here.

## Public API surface

| Symbol | Purpose |
| --- | --- |
| `Adviverse.configure(context, tag, baseUrl)` | One-time setup. |
| `Adviverse.setEmail(email?)` / `clearEmail()` | SHA-256 email advanced matching (`em`). |
| `Adviverse.setConsent(gdprApplies?, tcfConsent?, usPrivacy?)` | Consent passthrough. |
| `suspend Adviverse.loadAd(placement?, size?): AdviverseAd?` | Load an ad. |
| `Adviverse.loadAd(placement?, size?, callback)` | Callback variant. |
| `Adviverse.fireImpression(ad)` | Fire-and-forget impression beacon. |
| `Adviverse.fireClick(ad)` | Fire-and-forget click beacon (no navigation). |
| `Adviverse.openClickThrough(context, ad)` | Record click + open landing. |
| `AdviverseAdView` | Banner view: `loadAd(...)`, `render(ad)`, `listener`. |
| `AdviverseAd`, `AdviverseCreative`, `AdSize` | Public data types. |
| `AdLoadCallback`, `AdviverseAdView.Listener` | Callbacks. |
| `AdviverseException` | Load error type. |


## Playable (interactive HTML5) ads

No new API. When a rewarded/interstitial placement serves a **playable**
(`type: html5`) creative, the full-screen activity hosts it in a sandboxed
`WebView` (JavaScript-only bridge, no file/content access) instead of an
`ImageView`. The playable-runtime `ready` / `click` / `finish` messages drive the
same impression / click-through / `onRewardEarned` callbacks. Your
`AdviverseInterstitial` / `AdviverseRewarded` code is unchanged.

## Video & third-party VAST (1.1.0+)

Interstitial / rewarded video creatives play full-screen in the platform
`VideoView` (impression on first frame, start/quartile/complete tracking, a Skip
control after `skipoffset` / `skip_offset_sec` on interstitials). A creative may
carry a third-party VAST tag (`creative.vastTagUrl`) instead of a hosted asset:
the SDK sends `vast=1` and resolves the tag inside `loadAd` — Wrapper chain (≤5
hops), MediaFile pick (mp4 first; VPAID skipped) — so the load returns an ad only
once a playable MediaFile exists; any failure fires the VAST `<Error>` URLs and
returns `null` (no-fill). Custom renderers read `ad.videoUrl` / `ad.vast`, call
`ad.fireVideoEvent("firstQuartile")` etc., and `Adviverse.openClickThrough` (opens
the VAST ClickThrough and records our click via `&track=1`).
