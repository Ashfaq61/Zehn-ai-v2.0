# =====================================================================
# General Attributes, Kotlin Metadata & JNI
# =====================================================================
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeInvisibleAnnotations
-keep class kotlin.Metadata { *; }

# Keep native JNI method names intact
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Keep Kotlin Coroutines ServiceLoader / MainDispatcherFactory classes
-keepnames class kotlinx.coroutines.android.AndroidExceptionPreHandler
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory
-dontwarn kotlinx.coroutines.**

# =====================================================================
# Room Persistence Library
# =====================================================================
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class * extends androidx.room.RoomDatabase$Callback { *; }
-keep class * extends androidx.room.RoomOpenHelper$Delegate { *; }
-keep class com.zehn.assistant.data.db.ZehnDatabase_Impl { *; }
-keep class com.zehn.assistant.data.db.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    void <init>(...);
}
-keepclassmembers class * {
    @androidx.room.TypeConverter <methods>;
}
-dontwarn androidx.room.paging.**

# =====================================================================
# Hilt & Dagger Dependency Injection
# =====================================================================
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * {
    <init>(...);
    *;
}
-keep @dagger.Module class * { *; }
-keep @dagger.hilt.EntryPoint interface * { *; }
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
    @javax.inject.Inject <fields>;
    @javax.inject.Inject <methods>;
}
-keep class com.zehn.assistant.**_Factory { *; }
-keep class com.zehn.assistant.**_MembersInjector { *; }
-keep class com.zehn.assistant.**_GeneratedInjector { *; }
-keep class com.zehn.assistant.**_HiltModules* { *; }
-keep class com.zehn.assistant.Hilt_* { *; }
-keep class com.zehn.assistant.Dagger* { *; }
-keep class dagger.hilt.internal.aggregateddeps.** { *; }
-keep class hilt_aggregated_deps.** { *; }
-dontwarn dagger.hilt.**
-dontwarn com.google.errorprone.annotations.**

# Keep AndroidX ViewModel constructors used via reflection
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    <init>(...);
}

# =====================================================================
# LiteRT-LM (Gemma) & On-Device Inference Runtimes
# =====================================================================
-keep class com.zehn.assistant.ai.OnDeviceInferenceEngine { *; }
-keep class com.zehn.assistant.ai.LiteRtReflectiveInferenceEngine { *; }
-keep class com.zehn.assistant.ai.OnDeviceLiteRtManager { *; }
-keep class com.zehn.assistant.ai.LiteRtModelSpec { *; }
-keep class com.zehn.assistant.ai.OnDeviceModelState { *; }

-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** {
    <init>(...);
    *;
}
-keep class com.google.ai.edge.litert.** { *; }
-keep class com.google.ai.edge.localagents.** { *; }
-keep class com.google.mediapipe.tasks.genai.** { *; }
-keep class com.google.mediapipe.framework.** { *; }
-keep class com.google.android.gms.tflite.** { *; }
-keep class org.tensorflow.lite.** { *; }

-dontwarn com.google.ai.edge.litertlm.**
-dontwarn com.google.ai.edge.litert.**
-dontwarn com.google.ai.edge.localagents.**
-dontwarn com.google.mediapipe.**
-dontwarn com.google.android.gms.tflite.**
-dontwarn org.tensorflow.lite.**

# =====================================================================
# Retrofit & OkHttp
# =====================================================================
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keep interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# =====================================================================
# Kotlinx Serialization & Enums
# =====================================================================
-dontnote kotlinx.serialization.AnnotationsKt
-keep class kotlinx.serialization.KSerializer { *; }
-keep class kotlinx.serialization.descriptors.SerialDescriptor { *; }
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.zehn.assistant.**$$serializer { *; }
-keepclassmembers class com.zehn.assistant.** {
    *** Companion;
}
-keepclassmembers class com.zehn.assistant.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.zehn.assistant.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class com.zehn.assistant.** { *; }
-keepclassmembers class * extends java.lang.Enum {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
