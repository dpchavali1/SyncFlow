# ============================================================
# SyncFlow Production ProGuard Rules
# ============================================================

# Keep line numbers for crash reports (but obfuscate everything else)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep annotations needed by various libraries
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ============================================================
# REMOVE DEBUG CODE IN RELEASE
# ============================================================

# Remove all Log calls except Log.e and Log.w
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Remove verbose/debug methods from our Logger utility
-assumenosideeffects class com.phoneintegration.app.utils.Logger {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Remove println statements
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}

# ============================================================
# KOTLIN
# ============================================================
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **.*$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# ============================================================
# FIREBASE
# ============================================================
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# Firebase Auth - keep user classes
-keepattributes Signature
-keepattributes *Annotation*

# Firebase Database - keep model classes
-keepclassmembers class com.phoneintegration.app.** {
    *;
}

# Firebase Messaging
-keep class com.google.firebase.messaging.** { *; }

# ============================================================
# ROOM DATABASE
# ============================================================
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
-keep class * extends androidx.room.RoomDatabase { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    abstract *;
}

# ============================================================
# WEBRTC
# ============================================================
-keep class org.webrtc.** { *; }
-keep class io.getstream.** { *; }
-dontwarn org.webrtc.**
-dontwarn io.getstream.**

# ============================================================
# TINK (Encryption) - CRITICAL for security
# ============================================================
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
# Keep all Tink key types
-keep class * extends com.google.crypto.tink.KeyTemplate { *; }

# ============================================================
# SQLCIPHER (Encrypted Database)
# ============================================================
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# ============================================================
# ZXING (QR Code)
# ============================================================
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.** { *; }
-dontwarn com.google.zxing.**

# ============================================================
# ML KIT
# ============================================================
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ============================================================
# COIL (Image Loading)
# ============================================================
-keep class coil.** { *; }
-dontwarn coil.**

# ============================================================
# JETPACK COMPOSE
# ============================================================
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }

# ============================================================
# ANDROID COMPONENTS
# ============================================================
# Services - must be kept for manifest
-keep public class * extends android.app.Service

# BroadcastReceivers - must be kept for manifest
-keep public class * extends android.content.BroadcastReceiver

# ContentProviders
-keep public class * extends android.content.ContentProvider

# Activities
-keep public class * extends android.app.Activity

# Application class
-keep public class * extends android.app.Application

# ViewModels
-keep class * extends androidx.lifecycle.ViewModel {
    <init>();
}
-keep class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}

# ============================================================
# APP-SPECIFIC DATA CLASSES
# ============================================================
# Keep data classes used with Firebase (required for serialization)
-keep class com.phoneintegration.app.SmsMessage { *; }
-keep class com.phoneintegration.app.ConversationInfo { *; }
-keep class com.phoneintegration.app.models.** { *; }
-keep class com.phoneintegration.app.data.database.** { *; }

# Keep E2EE classes (critical for security)
-keep class com.phoneintegration.app.e2ee.** { *; }

# Keep WebRTC classes
-keep class com.phoneintegration.app.webrtc.** { *; }

# Keep all service and receiver classes
-keep class com.phoneintegration.app.*Service { *; }
-keep class com.phoneintegration.app.*Receiver { *; }
-keep class com.phoneintegration.app.desktop.** { *; }

# ============================================================
# SECURITY HARDENING
# ============================================================
# Encrypt class names more aggressively
-repackageclasses 'o'
-allowaccessmodification

# Remove unused code aggressively
-optimizationpasses 5
-dontpreverify

# ============================================================
# PARCELABLE (for Intent extras)
# ============================================================
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ============================================================
# ENUM
# ============================================================
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ============================================================
# NATIVE METHODS
# ============================================================
-keepclasseswithmembernames class * {
    native <methods>;
}

# ============================================================
# SERIALIZABLE
# ============================================================
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
