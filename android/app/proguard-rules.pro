# Keep BouncyCastle EC/EdDSA providers
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep kotlinx.serialization metadata
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class team.hex.meshlink.**$$serializer { *; }
-keepclassmembers class team.hex.meshlink.** {
    *** Companion;
}
-keepclasseswithmembers class team.hex.meshlink.** {
    kotlinx.serialization.KSerializer serializer(...);
}
