# ── Hilt / Dagger ─────────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# ── Room ──────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# ── OkHttp / Okio ─────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── org.json ──────────────────────────────────────────────────────────────────
-keep class org.json.** { *; }

# ── DantSu ESCPOS ─────────────────────────────────────────────────────────────
-keep class com.dantsu.escposthermalprinter.** { *; }
-dontwarn com.dantsu.escposthermalprinter.**

# ── ZXing ─────────────────────────────────────────────────────────────────────
-keep class com.google.zxing.** { *; }

# ── Coil ──────────────────────────────────────────────────────────────────────
-keep class coil.** { *; }
-dontwarn coil.**

# ── WorkManager ───────────────────────────────────────────────────────────────
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }

# ── Kotlin coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ── Kotlin serialization / reflection ─────────────────────────────────────────
-keepattributes Signature
-keepattributes InnerClasses
-keep class kotlin.Metadata { *; }

# ── App data classes used in JSON parsing ─────────────────────────────────────
# Keep all data classes in the pos.data package (used in JSONObject parsing).
-keep class com.warungtomyam.pos.data.** { *; }

# ── Suppress common benign warnings ──────────────────────────────────────────
-dontwarn javax.annotation.**
-dontwarn sun.misc.Unsafe

# ── Tink / EncryptedSharedPreferences: ignore compile-only errorprone annotations ─────
# Tink (via androidx.security.crypto) references these annotations, which are compile-time
# only and absent at runtime. Safe to suppress — R8 flagged them as missing classes.
-dontwarn com.google.errorprone.annotations.**
