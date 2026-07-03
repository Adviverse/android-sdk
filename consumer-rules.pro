# Keep the public SDK surface so reflection-free integration is stable and the
# data classes survive R8/ProGuard shrinking in release builds.
-keep class com.adviverse.sdk.** { *; }

# OkHttp ships its own consumer rules; these silence its optional-dependency
# warnings (Conscrypt / BouncyCastle / Animal-Sniffer) in release builds.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
