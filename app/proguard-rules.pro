# Keep DeviceAdminReceiver and services referenced from the manifest.
-keep class com.morpheus.family.admin.** { *; }
-keep class com.morpheus.family.vpn.** { *; }
-keep class com.morpheus.family.service.** { *; }
-keep class com.morpheus.family.receiver.** { *; }

# Firebase / Firestore models are accessed reflectively.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.morpheus.family.remote.model.** { *; }

# Manifest-referenced worker + WorkManager.
-keep class com.morpheus.family.work.** { *; }

# osmdroid (map) loads tile sources/overlays reflectively.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
