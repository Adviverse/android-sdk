// Adviverse Android Ad SDK — Gradle library module.
//
// A dependency-light native client for the Adviverse ad engine `/mobile/ad`
// endpoint. Networking is OkHttp; concurrency is Kotlin coroutines. The only
// other runtime dependency is Play Services' advertising-id client, used to
// resolve the GAID + Limit-Ad-Tracking flag.
//
// Consume from an app module with:
//     implementation(project(":adviverse-sdk"))
// or, once published, the Maven coordinates documented in README.md.

plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.adviverse.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
        // SDK version reported to the engine for server-side version gating
        // (an outdated/exploitable build can be force-updated to "no-fill").
        buildConfigField("String", "SDK_VERSION", "\"1.1.0\"")
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
    // Compose compiler for AdviverseAdComposable.kt (Kotlin 1.9.24 <-> 1.5.14).
    // Without it @Composable compiles as a plain function and crashes when
    // called from a composition.
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
        // Expose `suspend` entry points to Java callers via the generated
        // continuation overloads; keep the public surface stable.
        freeCompilerArgs = freeCompilerArgs + "-Xjvm-default=all"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Advertising ID (GAID) + Limit-Ad-Tracking. `implementation` so the SDK
    // can resolve the id out of the box; apps that ship their own copy can
    // exclude it and the SDK degrades gracefully to a first-party UUID.
    implementation("com.google.android.gms:play-services-ads-identifier:18.0.1")

    // Compose host (AdviverseAdComposable.kt). compileOnly: the SDK does not
    // force Compose on View-based consumers; apps using `AdviverseAd { }` must
    // already depend on Compose UI. Never touched at runtime unless called.
    compileOnly("androidx.compose.ui:ui:1.6.8")
    compileOnly("androidx.compose.runtime:runtime:1.6.8")

    // JVM unit tests (src/test): JUnit + kXML2 as a stand-in XmlPullParser for
    // the VAST resolver tests. Test-only — never shipped in the AAR.
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    // The Compose compiler needs the runtime on every compiled classpath, tests included.
    testImplementation("androidx.compose.runtime:runtime:1.6.8")
}
