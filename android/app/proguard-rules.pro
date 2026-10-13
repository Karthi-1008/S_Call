# WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.seniorcall.signaling.model.** { *; }
-keep class com.seniorcall.data.model.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# ZXing
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
-keep class com.journeyapps.barcodescanner.** { *; }

# WorkManager
-keep class androidx.work.** { *; }
