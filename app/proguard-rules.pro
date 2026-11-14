# kotlinx.serialization: keep serializers for our own @Serializable classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class org.transdroid.** {
    *** Companion;
}
-keepclasseswithmembers class org.transdroid.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# MaxMind DB reader (peer country lookups), kept whole as Transdroid 2 did. The Map-based lookup
# GeoIpDatabase uses needs no reflection, but the library is tiny and its decoder does reflect
# for annotated model classes - not worth the risk of shrinking it for a few KB.
-keep class com.maxmind.db.** { *; }
-dontwarn com.maxmind.db.**

# Built-in torrent search: jsoup refers to optional annotation/regex libraries that aren't shipped
-dontwarn org.jspecify.annotations.**
-dontwarn com.google.re2j.**
-dontwarn javax.annotation.**
-dontwarn org.jsoup.**

# ML Kit text recognition (OCR on shared screenshots) finds its components through manifest metadata and
# reflection, so shrinking/renaming it in release builds made it crash with a NullPointerException
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.odml.** { *; }
-keep class com.google.firebase.components.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-dontwarn com.google.mlkit.**
