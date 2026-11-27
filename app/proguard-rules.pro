# Keep names readable in crash reports; still shrink and optimise.
-dontobfuscate

# Trailer player talks to the app through this bridge.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# OkHttp / Okio optional platform classes.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Seekr: its JSON models are read by kotlinx-serialization (the library ships the same rules).
-keepclassmembers class tv.seekr.previews.core.internal.** {
    *** Companion;
}
-keepclasseswithmembers class tv.seekr.previews.core.internal.** {
    kotlinx.serialization.KSerializer serializer(...);
}
