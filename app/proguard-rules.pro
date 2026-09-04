# Hermes Agent — ProGuard / R8 rules
# Phase 1 (Foundation) of the technical plan.

# --- Kotlin metadata ---
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions

# --- Coroutines ---
-keepclassmembernames class kotlinx.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.flow.**

# --- Hilt / Dagger (handled by plugin, kept defensively) ---
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }

# --- Room ---
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.paging.**

# --- Retrofit / OkHttp ---
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep class retrofit2.** { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Kotlinx Serialization ---
-keepattributes *Annotation*
-keepclassmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.hermes.agent.**$$serializer { *; }
-keepclassmembers class com.hermes.agent.** {
    *** Companion;
}

# --- Hermes app entities / DTOs (serializable) ---
-keep @kotlinx.serialization.Serializable class com.hermes.agent.data.remote.dto.** { *; }

# --- Rhino (core:plugin's sandboxed JS engine for community modules) ---
# Rhino's JSON/reflection helpers reference java.beans.* (java.beans isn't part
# of the Android core library) and javax.lang.model.SourceVersion. Neither path
# is reachable from the interpreted-mode, class-shuttered sandbox this app runs
# Rhino in — safe to silence rather than keep.
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn javax.lang.model.SourceVersion

# --- ONNX Runtime (embeddings) ---
# libonnxruntime4j_jni.so resolves its Java side by name at runtime
# (GetMethodID inside convertToTensorInfo). R8 renaming those classes turns
# that lookup into a null and the JNI layer calls abort(), taking the whole
# process down -- a release-only SIGABRT that never shows up in debug builds
# because minification is off there. Keep the package intact.
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
