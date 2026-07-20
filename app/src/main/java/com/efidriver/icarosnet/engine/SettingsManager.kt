package com.efidriver.icarosnet.engine

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.round

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("efi_prefs", Context.MODE_PRIVATE)

    private fun roundToTwo(value: Float): Double {
        return (round(value * 100.0) / 100.0)
    }

    var maxPickupDistance: Double
        get() = roundToTwo(prefs.getFloat("max_pickup", 2.5f))
        set(value) = prefs.edit().putFloat("max_pickup", value.toFloat()).apply()

    var minUsdPerKm: Double
        get() = roundToTwo(prefs.getFloat("min_usd_km", 0.80f))
        set(value) = prefs.edit().putFloat("min_usd_km", value.toFloat()).apply()

    var previewTripDistanceKm: Double
        get() = roundToTwo(prefs.getFloat("preview_trip_distance_km", 1.0f))
        set(value) = prefs.edit().putFloat("preview_trip_distance_km", value.toFloat()).apply()

    var commissionPercent: Double
        get() = roundToTwo(prefs.getFloat("commission", 15.0f))
        set(value) = prefs.edit().putFloat("commission", value.toFloat()).apply()

    var structuralProbeDebugEnabled: Boolean
        get() = prefs.getBoolean("structural_probe_debug_enabled", false)
        set(value) = prefs.edit().putBoolean("structural_probe_debug_enabled", value).apply()
}
