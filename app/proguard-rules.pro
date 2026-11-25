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
