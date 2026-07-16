package com.efidriver.icarosnet.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import com.efidriver.icarosnet.models.Trip
import com.efidriver.icarosnet.engine.ProfitabilityEngine
import com.efidriver.icarosnet.models.TripStatus
import com.efidriver.icarosnet.models.ProfitabilityResult
import com.efidriver.icarosnet.engine.SettingsManager

class ScraperAccessibilityService : AccessibilityService() {

    private lateinit var settingsManager: SettingsManager
    private lateinit var windowManager: WindowManager
    private val TAG = "EfiDebug"
    
    private val activeOverlays = mutableMapOf<String, OverlayRecord>()

    private data class OverlayRecord(
        val view: View,
        val textContainer: LinearLayout,
        var lastBounds: Rect
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsManager = SettingsManager(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Log.d(TAG, "--- MONITOR v5.5 (HUD COMPLETO) ---")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: ""
        if (packageName == "sinet.startup.inDriver") {
            processAndIntervene()
        } else if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (packageName != "com.efidriver.icarosnet") {
                clearAllOverlays()
            }
        }
    }

    private fun processAndIntervene() {
        if (!::settingsManager.isInitialized) return
        val rootNode = rootInActiveWindow ?: return
        
        val nodes = rootNode.findAccessibilityNodeInfosByViewId("sinet.startup.inDriver:id/item_order_container")
        val foundKeysInThisScan = mutableSetOf<String>()

        val maxP = settingsManager.maxPickupDistance
        val minU = settingsManager.minUsdPerKm
        val comm = settingsManager.commissionPercent

        for (node in nodes) {
            val trip = extractTripData(node) ?: continue
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
                if (trip.pickupDistance > 0.05) executeDirectHide(node)
            } else {
                val currentBounds = Rect()
                node.getBoundsInScreen(currentBounds)
                syncOverlay(tripKey, currentBounds, result)
            }
        }

        val keysToRemove = activeOverlays.keys.filter { !foundKeysInThisScan.contains(it) }
        keysToRemove.forEach { removeOverlay(it) }
    }

    private fun syncOverlay(tripKey: String, bounds: Rect, result: ProfitabilityResult) {
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
                updateHUDText(record.textContainer, result)
                if (record.lastBounds != bounds) {
                    record.lastBounds = Rect(bounds)
                    windowManager.updateViewLayout(record.view, params)
                }
            } else {
                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.parseColor("#E6004D00")) // Verde m??s oscuro y s??lido
                }
                
                updateHUDText(container, result)
                windowManager.addView(container, params)
                activeOverlays[tripKey] = OverlayRecord(container, container, Rect(bounds))
            }
        } catch (e: Exception) {}
    }

    private fun updateHUDText(container: LinearLayout, result: ProfitabilityResult) {
        container.removeAllViews()
        
        // 1. RENTABLE (Cabecera)
        container.addView(TextView(this).apply {
            text = "RENTABLE"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })

        // 2. [X.XX] $/km
        container.addView(TextView(this).apply {
            text = String.format("%.2f", result.expectedUsdPerKm) + " $/km"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
        })

        // 3. Gana: $Y.YY
        container.addView(TextView(this).apply {
            text = "Gana: $" + String.format("%.2f", result.trueProfit)
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
        })

        // 4. Recogida y Total con el indicador Naranja de Preview
        val footerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        footerContainer.addView(TextView(this).apply {
            text = String.format("%.1f", result.pickupDistanceKm) + " km | "
            setTextColor(Color.WHITE)
            textSize = 10f
        })

        // El círculo naranja [ ]
        val orangeCircle = View(this).apply {
            val size = (10 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (4 * resources.displayMetrics.density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFA500")) // Naranja
            }
        }
        footerContainer.addView(orangeCircle)

        footerContainer.addView(TextView(this).apply {
            text = "Total: " + String.format("%.1f", result.totalDistanceKm) + "? km"
            setTextColor(Color.WHITE)
            textSize = 10f
        })

        container.addView(footerContainer)
    }

    private fun removeOverlay(tripKey: String) {
        activeOverlays[tripKey]?.let {
            try { windowManager.removeView(it.view) } catch (e: Exception) {}
        }
        activeOverlays.remove(tripKey)
    }

    private fun clearAllOverlays() {
        activeOverlays.keys.toList().forEach { removeOverlay(it) }
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
            Trip(name.trim(), parseDoubleSafe(priceT), parseDoubleSafe(distT), from.trim(), "")
        } catch (e: Exception) { null }
    }

    private fun parseDoubleSafe(text: String): Double {
        val isMetro = text.contains("metro", ignoreCase = true)
        val cleaned = text.replace(",", ".").replace(Regex("[^0-9.]"), "")
        val value = cleaned.toDoubleOrNull() ?: 0.0
        return if (isMetro) value / 1000.0 else value
    }

    override fun onDestroy() {
        clearAllOverlays()
        super.onDestroy()
    }

    override fun onInterrupt() {}
}
