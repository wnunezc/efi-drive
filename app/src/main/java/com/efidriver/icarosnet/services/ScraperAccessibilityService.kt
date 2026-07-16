package com.efidriver.icarosnet.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.efidriver.icarosnet.models.Trip
import com.efidriver.icarosnet.engine.ProfitabilityEngine
import com.efidriver.icarosnet.models.TripStatus
import com.efidriver.icarosnet.models.ProfitabilityResult
import com.efidriver.icarosnet.engine.SettingsManager

class ScraperAccessibilityService : AccessibilityService() {

    private lateinit var settingsManager: SettingsManager
    private lateinit var windowManager: WindowManager
    private val TAG_HUD = "EfiHUD"
    private val TAG_SONDA = "EfiSonda"
    
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
        Log.d(TAG_HUD, "--- SERVICIO ESTABLE v7.2 - HUD PROTEGIDO ---")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: ""
        
        if (packageName == "sinet.startup.inDriver") {
            // 1. HUD Y FILTRO (Producci??n)
            processMainFlow()
            
            // 2. DIAGN??STICO ESTRUCTURAL (Aislado)
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || 
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                try {
                    ejecutarSondaEstructural()
                } catch (e: Exception) {}
            }
        } else {
            if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (packageName != "com.efidriver.icarosnet") {
                    clearAllOverlays()
                }
            }
        }
    }

    private fun processMainFlow() {
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

    // SONDA AGNOSTICA: No busca valores, busca ESTRUCTURA
    private fun ejecutarSondaEstructural() {
        val root = rootInActiveWindow ?: return
        
        // Buscamos cualquier nodo cuyo ID termine en _PointB (el ancla de destino)
        recorrerNodosPorEstructura(root)
    }

    private fun recorrerNodosPorEstructura(node: AccessibilityNodeInfo?) {
        if (node == null) return
        
        val viewId = node.viewIdResourceName ?: ""
        
        // Si encontramos el ancla estructural del destino
        if (viewId.endsWith("_PointB")) {
            Log.e(TAG_SONDA, "ANCLA DETECTADA: $viewId")
            
            // Inspeccionamos al PADRE para ver a sus HERMANOS (donde suele estar el globo de texto)
            val parent = node.parent
            if (parent != null) {
                Log.e(TAG_SONDA, "Inspeccionando entorno del Punto B (Hermanos: \${parent.childCount})")
                for (i in 0 until parent.childCount) {
                    val sibling = parent.getChild(i)
                    if (sibling != null) {
                        val sText = sibling.text?.toString() ?: "NoText"
                        val sDesc = sibling.contentDescription?.toString() ?: "NoDesc"
                        Log.e(TAG_SONDA, "  DATO ENTORNO -> Txt: '\$sText' | Desc: '\$sDesc' | Class: \${sibling.className}")
                        sibling.recycle()
                    }
                }
                parent.recycle()
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            recorrerNodosPorEstructura(child)
            child?.recycle()
        }
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
                    setBackgroundColor(Color.parseColor("#E6004D00"))
                }
                updateHUDText(container, result)
                windowManager.addView(container, params)
                activeOverlays[tripKey] = OverlayRecord(container, container, Rect(bounds))
            }
        } catch (e: Exception) {}
    }

    private fun updateHUDText(container: LinearLayout, result: ProfitabilityResult) {
        container.removeAllViews()
        container.addView(TextView(this).apply {
            text = "RENTABLE"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })
        container.addView(TextView(this).apply {
            text = String.format("%.2f", result.expectedUsdPerKm) + " $/km"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
        })
        container.addView(TextView(this).apply {
            text = "Gana: $" + String.format("%.2f", result.trueProfit)
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
        })

        val footerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        footerContainer.addView(TextView(this).apply {
            text = String.format("%.1f", result.pickupDistanceKm) + " km | "
            setTextColor(Color.WHITE)
            textSize = 10f
        })
        val orangeCircle = View(this).apply {
            val size = (10 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (4 * resources.displayMetrics.density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFA500"))
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
        val iterator = activeOverlays.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            try {
                windowManager.removeView(entry.value.view)
            } catch (e: Exception) {}
            iterator.remove()
        }
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
