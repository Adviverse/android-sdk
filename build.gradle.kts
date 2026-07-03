// Adviverse Android Ad SDK — standalone, publishable Gradle library.
// Consumed via JitPack: implementation("com.github.Adviverse:android-sdk:<tag>")

plugins {
    id("com.android.library") version "8.2.2"
    kotlin("android") version "1.9.24"
    id("maven-publish")
}

android {
    namespace = "com.adviverse.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        // SDK version reported to the engine for server-side version gating.
        buildConfigField("String", "SDK_VERSION", "\"1.0.0\"")
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
        // Expose `suspend` entry points to Java callers via generated overloads.
        freeCompilerArgs = freeCompilerArgs + "-Xjvm-default=all"
    }

    // Single-variant publishing (the release AAR + a sources jar).
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Advertising ID (GAID) + Limit-Ad-Tracking; degrades to a first-party UUID.
    implementation("com.google.android.gms:play-services-ads-identifier:18.0.1")
}

// Maven publication (JitPack rewrites the group to com.github.Adviverse).
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.adviverse"
            artifactId = "sdk"
            version = "1.0.0"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
