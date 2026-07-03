# Adviverse Android SDK

Native Kotlin ad SDK for the **Adviverse** ad network. Small and dependency-light
(OkHttp + Kotlin coroutines + Play Services advertising-id), coroutine **and**
callback APIs.

- **minSdk 21**, compileSdk 34
- Honors Limit-Ad-Tracking, forwards GDPR / US-privacy consent, SHA-256 email
  advanced matching

Full docs: **https://adviverse.com/guides/ios-sdk** (the iOS + Android SDKs share
the same request contract; an Android guide is on the docs site too).

## Install (Gradle via JitPack)

Add JitPack to your repositories (in `settings.gradle.kts`):

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then add the dependency in your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.Adviverse:android-sdk:1.0.1")
}
```

The SDK declares `INTERNET`, `ACCESS_NETWORK_STATE`, and (for Android 13+) the
`com.google.android.gms.permission.AD_ID` permission via manifest merging.

## Quick start

Configure once (e.g. in `Application.onCreate`) with your placement tag:

```kotlin
Adviverse.configure(context, tag = "android-banner-home", baseUrl = "https://serve.adviverse.com")
```

### Drop-in banner

```kotlin
val banner = AdviverseAdView(context)
container.addView(banner)
banner.loadAd()            // uses the configured tag
```

### Load an ad yourself

```kotlin
// coroutine
val ad = Adviverse.loadAd(placement = "android-mrec", size = AdSize.MEDIUM_RECTANGLE)
ad?.fireImpression()
// on tap: ad?.fireClick(); open ad?.landingUrl

// callback
Adviverse.loadAd(placement = "android-rewarded") { result ->
    // render, then fireImpression() / fireClick(); grant rewards server-side
}
```

### Interstitial

```kotlin
val interstitial = AdviverseInterstitial(activity, placement = "android-interstitial")
interstitial.load()
// later:
interstitial.show()
```

## Consent & privacy

```kotlin
Adviverse.setConsent(gdprConsent = tcfString, usPrivacy = "1YNN")
Adviverse.setEmail("user@example.com")   // hashed on-device (SHA-256), never sent in plaintext
```

The Google Advertising ID is used only when Limit-Ad-Tracking is off; otherwise
the SDK falls back to a first-party UUID and the engine serves contextual ads.

## License

Proprietary — see [LICENSE](LICENSE).
