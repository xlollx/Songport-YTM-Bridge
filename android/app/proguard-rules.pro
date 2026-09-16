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

# Tink (security-crypto) references compile-only Error Prone annotations that are not packaged.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
