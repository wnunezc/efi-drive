package com.efidriver.icarosnet.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.efidriver.icarosnet.models.Trip
import com.efidriver.icarosnet.engine.ProfitabilityEngine
import com.efidriver.icarosnet.models.TripStatus
import com.efidriver.icarosnet.engine.SettingsManager

class ScraperAccessibilityService : AccessibilityService() {

    private lateinit var settingsManager: SettingsManager
    private lateinit var windowManager: WindowManager
    private val TAG = "EfiDebug"
    private val FLOW = "DecisionFlow"
    
    private val activeOverlays = mutableMapOf<String, OverlayRecord>()

    private data class OverlayRecord(
        val view: View,
        var lastBounds: Rect,
        var lastSeen: Long
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsManager = SettingsManager(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Log.d(TAG, "--- MONITOR v5.1 (AUDITORIA DE FLUJO - CUALQUIER VIAJE) ---")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName == "sinet.startup.inDriver") {
            processAndIntervene()
        }
    }

    private fun processAndIntervene() {
        if (!::settingsManager.isInitialized) return
        val rootNode = rootInActiveWindow ?: return
        
        val nodes = rootNode.findAccessibilityNodeInfosByViewId("sinet.startup.inDriver:id/item_order_container")
        val currentTime = System.currentTimeMillis()

        val maxP = settingsManager.maxPickupDistance
        val minU = settingsManager.minUsdPerKm
        val comm = settingsManager.commissionPercent

        val foundKeysInThisScan = mutableSetOf<String>()

        for (node in nodes) {
            val trip = extractTripData(node)
            if (trip == null) continue
            
            val tripKey = trip.fingerprint
            foundKeysInThisScan.add(tripKey)

            val result = ProfitabilityEngine.calculate(
                tripPrice = trip.price,
                pickupDistanceKm = trip.pickupDistance,
                maxPickupDistanceKm = maxP,
                minUsdPerKm = minU,
                commissionPercent = comm
            )

            if (result.status != TripStatus.RENTABLE) {
                if (activeOverlays.containsKey(tripKey)) {
                    Log.d(FLOW, "BORRANDO OVERLAY -> " + trip.passengerName + " | Motivo: Cambio de rentabilidad (Calc:" + result.expectedUsdPerKm + " vs Min:" + minU + ")")
                }
                if (trip.pickupDistance > 0.05) {
                    executeDirectHide(node)
                }
            } else {
                val currentBounds = Rect()
                node.getBoundsInScreen(currentBounds)
                syncOverlay(tripKey, currentBounds, currentTime, trip.passengerName)
            }
        }

        activeOverlays.keys.forEach { key ->
            if (!foundKeysInThisScan.contains(key)) {
                Log.d(FLOW, "BORRANDO OVERLAY -> Motivo: Llave '" + key + "' no encontrada en este escaneo.")
            }
        }

        val keysToRemove = activeOverlays.keys.filter { !foundKeysInThisScan.contains(it) }
        keysToRemove.forEach { removeOverlay(it) }
    }

    private fun syncOverlay(tripKey: String, bounds: Rect, currentTime: Long, name: String) {
        val overlayWidth = (bounds.width() * 0.4).toInt()
        val params = WindowManager.LayoutParams(
            overlayWidth,
            bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.right - overlayWidth
            y = bounds.top
        }

        try {
            if (activeOverlays.containsKey(tripKey)) {
                val record = activeOverlays[tripKey]!!
                record.lastSeen = currentTime
                if (record.lastBounds != bounds) {
                    record.lastBounds = Rect(bounds)
                    windowManager.updateViewLayout(record.view, params)
                }
            } else {
                Log.d(FLOW, "CREANDO OVERLAY -> " + name + " | Llave: " + tripKey)
                val overlayView = View(this).apply {
                    setBackgroundColor(Color.parseColor("#4400FF00"))
                }
                windowManager.addView(overlayView, params)
                activeOverlays[tripKey] = OverlayRecord(overlayView, Rect(bounds), currentTime)
            }
        } catch (e: Exception) {}
    }

    private fun removeOverlay(tripKey: String) {
        activeOverlays[tripKey]?.let {
            try {
                Log.d(FLOW, "DESTRUYENDO: Overlay de llave " + tripKey + " eliminado")
                windowManager.removeView(it.view)
            } catch (e: Exception) {}
        }
        activeOverlays.remove(tripKey)
    }

    private fun executeDirectHide(node: AccessibilityNodeInfo) {
        val hideBtn = node.findAccessibilityNodeInfosByViewId("sinet.startup.inDriver:id/item_order_options_container_hide").firstOrNull()
        if (hideBtn != null) {
            hideBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            node.findAccessibilityNodeInfosByViewId("sinet.startup.inDriver:id/item_order_imageview_dots")
                .firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private fun extractTripData(node: AccessibilityNodeInfo): Trip? {
        return try {
            val name = node.findAccessibilityNodeInfosByViewId("sinet.startup.inDriver:id/driver_common_textview_name").firstOrNull()?.text?.toString() ?: return null
            val priceT = node.findAccessibilityNodeInfosByViewId("sinet.startup.inDriver:id/info_textview_stage_price_view").firstOrNull()?.text?.toString() ?: ""
            val distT = node.findAccessibilityNodeInfosByViewId("sinet.startup.inDriver:id/order_info_stage_textview_distance").firstOrNull()?.text?.toString() ?: ""
            val from = node.findAccessibilityNodeInfosByViewId("sinet.startup.inDriver:id/order_info_textview_from_address").firstOrNull()?.text?.toString() ?: ""

            val price = parseDoubleSafe(priceT)
            val dist = parseDoubleSafe(distT)
            
            if (price <= 0.0) return null

            Trip(name.trim(), price, dist, from.trim(), "")
        } catch (e: Exception) { null }
    }

    private fun parseDoubleSafe(text: String): Double {
        val isMetro = text.contains("metro", ignoreCase = true)
        val cleaned = text.replace(",", ".").replace(Regex("[^0-9.]"), "")
        val value = cleaned.toDoubleOrNull() ?: 0.0
        return if (isMetro) value / 1000.0 else value
    }

    override fun onInterrupt() {}
}
