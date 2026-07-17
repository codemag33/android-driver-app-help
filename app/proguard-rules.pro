# ─── Socket.IO ────────────────────────────────────────────────────────────────
-keep class io.socket.** { *; }
-dontwarn io.socket.**

# ─── MapLibre ─────────────────────────────────────────────────────────────────
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# ─── Project models (used in JSON serialization) ──────────────────────────────
-keep class com.driver.app.data.** { *; }
-keep class com.driver.app.ui.RideViewModel$LatLngData { *; }
-keep class com.driver.app.ui.RideViewModel$SearchItemUi { *; }
-keep class com.driver.app.ui.RideViewModel$ChatMessage { *; }

# ─── Coroutines ───────────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ─── Keep BroadcastReceiver (PiP actions) ─────────────────────────────────────
-keep class com.driver.app.PipActionReceiver { *; }
