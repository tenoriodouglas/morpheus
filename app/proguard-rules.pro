# Keep DeviceAdminReceiver and services referenced from the manifest.
-keep class com.morpheus.family.admin.** { *; }
-keep class com.morpheus.family.vpn.** { *; }
-keep class com.morpheus.family.service.** { *; }
-keep class com.morpheus.family.receiver.** { *; }

# Firebase / Firestore models are accessed reflectively.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.morpheus.family.remote.model.** { *; }
