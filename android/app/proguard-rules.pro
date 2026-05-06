# MeshLink R8 / ProGuard rules.
#
# Keep just enough to let kotlinx.serialization, Room, BouncyCastle and
# CameraX survive minification on release builds. Everything else is left
# to the default optimizer rules from `proguard-android-optimize.txt`.

# --- kotlinx.serialization ---
# Generated companion serializers are accessed reflectively for every
# @Serializable class. Without this every release build crashes with
# SerializationException("Serializer for class 'Foo' is not found").
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class team.hex.meshlink.**$$serializer { *; }
-keepclassmembers class team.hex.meshlink.** {
    *** Companion;
}
-keepclasseswithmembers class team.hex.meshlink.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# --- Room (KSP-generated implementations) ---
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class androidx.room.** { *; }
-dontwarn androidx.room.paging.**

# --- BouncyCastle (X25519/Ed25519) ---
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# --- CameraX + ZXing ---
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# --- Kotlin reflection / coroutines internals ---
-dontwarn kotlin.Unit
-dontwarn kotlinx.coroutines.debug.AgentPremain
-keepclassmembers class kotlin.Metadata { *; }

# --- App entry points used reflectively ---
-keep class team.hex.meshlink.MeshLinkApp { *; }
-keep class team.hex.meshlink.ui.MainActivity { *; }
-keep class team.hex.meshlink.service.MeshService { *; }

# Crash reporting hook: keep enclosing methods for stack trace symbolication.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute MeshLink
