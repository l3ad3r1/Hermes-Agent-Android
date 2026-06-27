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
