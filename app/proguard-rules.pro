# FunkoDex ProGuard rules
# Applied to release builds only (debug builds skip ProGuard entirely)

# ── Couchbase Lite ────────────────────────────────────────────────────────────
# Couchbase Lite uses reflection internally — keep all public API classes
-keep class com.couchbase.lite.** { *; }
-keepclassmembers class com.couchbase.lite.** { *; }
-dontwarn com.couchbase.lite.**

# ── Apache POI (Excel export) ─────────────────────────────────────────────────
# POI uses reflection for XML processing and cell styles
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class org.openxmlformats.schemas.** { *; }
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn org.openxmlformats.schemas.**
-dontwarn javax.xml.**
-dontwarn org.w3c.dom.**

# ── Gson (JSON parsing in FunkoLookupService) ─────────────────────────────────
# Keep all data classes used with Gson fromJson/toJson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.funkodex.network.FunkoLookupService$* { *; }
-keepclassmembers class com.funkodex.network.FunkoLookupService$* {
    <fields>;
}

# ── Hilt ──────────────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# ── OkHttp & Retrofit ─────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── ML Kit barcode ────────────────────────────────────────────────────────────
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ── Coil image loading ────────────────────────────────────────────────────────
-keep class coil.** { *; }
-dontwarn coil.**

# ── Kotlin coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ── General Android ───────────────────────────────────────────────────────────
# Keep Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}
# Keep enum values (used by FunkoItem.Condition, ExportFormat)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# Keep R class
-keepclassmembers class **.R$* {
    public static <fields>;
}
