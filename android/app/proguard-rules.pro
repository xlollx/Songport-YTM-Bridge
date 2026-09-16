# kotlinx.serialization: mantieni i serializer generati.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.xlollx.songport.ytmbridge.**$$serializer { *; }
-keepclassmembers class com.xlollx.songport.ytmbridge.** { *** Companion; }
-keepclasseswithmembers class com.xlollx.songport.ytmbridge.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# WebView: i metodi annotati @JavascriptInterface (login Apple Music) vengono chiamati per nome
# dal JavaScript della pagina; se R8 li rinomina il login si rompe solo in release.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
