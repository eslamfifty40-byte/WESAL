# Wesal ProGuard / R8 Rules
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*

# WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Firebase Firestore Models
-keepclassmembers class com.wesal.messenger.data.remote.dto.** { *; }
-keep class com.wesal.messenger.data.remote.dto.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# Tink Crypto
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { *; }
