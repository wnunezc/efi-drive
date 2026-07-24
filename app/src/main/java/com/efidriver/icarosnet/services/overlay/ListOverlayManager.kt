package com.efidriver.icarosnet.services.overlay

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
import android.widget.LinearLayout
import android.widget.TextView
import com.efidriver.icarosnet.models.ProfitabilityResult
import com.efidriver.icarosnet.models.TripStatus
import com.efidriver.icarosnet.services.monitoring.TripLifecycleMonitor
import java.util.Collections
import java.util.IdentityHashMap

class ListOverlayManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val lifecycleMonitor: TripLifecycleMonitor,
    private val isRenderingBlocked: () -> Boolean,
    private val isVerbose: () -> Boolean,
    private val trace: (String) -> Unit
) {
    private val activeOverlays = mutableMapOf<String, OverlayRecord>()
    private val activeViews = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
    private var clearGeneration = 0L

    data class ClearResult(
        val removedCount: Int,
        val removedKeys: List<String>,
        val generation: Long,
        val durationMs: Long
    )

    data class RemovalResult(
        val removed: Boolean,
        val removedKeys: List<String>,
        val durationMs: Long
    )

    private data class OverlayRecord(
        val view: View,
        val textContainer: LinearLayout,
        var lastBounds: Rect
    )

    val activeCount: Int
        get() = activeOverlays.size

    val trackedViewCount: Int
        get() = activeViews.size

    val generation: Long
        get() = clearGeneration

    val keys: Set<String>
        get() = activeOverlays.keys

    fun contains(key: String): Boolean {
        return activeOverlays.containsKey(key)
    }

    fun lastBounds(key: String): Rect? {
        return activeOverlays[key]?.lastBounds
    }

    fun sync(tripKey: String, bounds: Rect, result: ProfitabilityResult): Boolean {
        if (isRenderingBlocked()) {
            trace("LIST_OVERLAY_SYNC_SKIPPED key=$tripKey reason=rendering_blocked")
            return false
        }

        // Diagnóstico de coordenadas fuera de pantalla (Culling Test)
        val displayMetrics = context.resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        if ((bounds.top > screenHeight) || (bounds.bottom < 0)) {
            Log.i("EfiDiagnostic", "OFFSCREEN_ROW_SYNC_DETECTED key=$tripKey y=${bounds.top}..${bounds.bottom} screenH=$screenHeight")
        }

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

        return try {
            if (activeOverlays.containsKey(tripKey)) {
                val record = activeOverlays.getValue(tripKey)
                
                updateHUDText(record.textContainer, result)
                applyBackground(record.view, result)
                if (record.lastBounds != bounds) {
                    val deltaY = kotlin.math.abs(record.lastBounds.top - bounds.top)
                    if (deltaY > 50) {
                        Log.i("EfiDiagnostic", "HIGH_DISPLACEMENT key=$tripKey deltaY=$deltaY")
                    }
                    record.lastBounds = Rect(bounds)
                    windowManager.updateViewLayout(record.view, params)
                    lifecycleMonitor.markOverlayUpdated(tripKey, result, "layout_changed", isVerbose())
                    trace("LIST_OVERLAY_UPDATED_LAYOUT key=$tripKey status=${result.status} preview=${result.isPreview} bounds=$bounds")
                } else {
                    lifecycleMonitor.markOverlayUpdated(tripKey, result, "text_changed", isVerbose())
                    trace("LIST_OVERLAY_UPDATED_TEXT key=$tripKey status=${result.status} preview=${result.isPreview} bounds=$bounds")
                }
            } else {
                val container = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                }
                applyBackground(container, result)
                updateHUDText(container, result)
                windowManager.addView(container, params)
                activeOverlays[tripKey] = OverlayRecord(container, container, Rect(bounds))
                activeViews.add(container)
                lifecycleMonitor.markOverlayAdded(tripKey, result, bounds, activeOverlays.size)
                trace("LIST_OVERLAY_ADDED key=$tripKey status=${result.status} preview=${result.isPreview} bounds=$bounds active=${activeOverlays.size}")
            }
            true
        } catch (e: Exception) {
            trace("LIST_OVERLAY_SYNC_FAILED key=$tripKey message=${e.message}")
            false
        }
    }

    fun remove(tripKey: String, reason: String): RemovalResult {
        val startedAt = nowMs()
        var removed = false
        activeOverlays[tripKey]?.let {
            lifecycleMonitor.markOverlayRemoved(tripKey, reason)
            removeView(it.view, "single_remove key=$tripKey reason=$reason")
            trace("LIST_OVERLAY_REMOVED key=$tripKey reason=$reason bounds=${it.lastBounds}")
            removed = true
        }
        activeOverlays.remove(tripKey)
        return RemovalResult(
            removed = removed,
            removedKeys = if (removed) listOf(tripKey) else emptyList(),
            durationMs = nowMs() - startedAt
        )
    }

    fun clear(reason: String, stage: String, blocked: Boolean, sinceCardClickMs: Long?): ClearResult {
        val startedAt = nowMs()
        val viewsToRemove = (activeViews + activeOverlays.values.map { it.view }).distinct()
        val removedKeys = activeOverlays.keys.toList()
        val shouldLogClear = viewsToRemove.isNotEmpty() || isVerbose()
        if (shouldLogClear) {
            Log.d(
                TAG,
                "${TraceText.CLEAR_START} reason=$reason trackedKeys=${activeOverlays.size} trackedViews=${viewsToRemove.size} " +
                    "generation=$clearGeneration stage=$stage blocked=$blocked sinceCardClickMs=${sinceCardClickMs ?: "na"}"
            )
        }
        viewsToRemove.forEach { view -> removeView(view, "clear_all reason=$reason") }
        removedKeys.forEach { key -> lifecycleMonitor.markOverlayRemoved(key, reason) }
        activeOverlays.clear()
        activeViews.clear()
        if (viewsToRemove.isNotEmpty()) {
            clearGeneration += 1
            Log.d(
                TAG,
                "${TraceText.CLEARED} reason=$reason removedViews=${viewsToRemove.size} generation=$clearGeneration keys=${compactKeys(removedKeys)}"
            )
        }
        val durationMs = nowMs() - startedAt
        if (shouldLogClear) {
            Log.d(
                TAG,
                "${TraceText.CLEAR_END} reason=$reason removedViews=${viewsToRemove.size} trackedKeysAfter=${activeOverlays.size} " +
                    "trackedViewsAfter=${activeViews.size} durationMs=$durationMs generation=$clearGeneration"
            )
        }
        return ClearResult(viewsToRemove.size, removedKeys, clearGeneration, durationMs)
    }

    private fun applyBackground(view: View, result: ProfitabilityResult) {
        val color = when {
            result.status == TripStatus.RENTABLE -> "#E6004D00"
            !result.isPreview -> "#E69A1B1B"
            else -> "#E6333333"
        }
        view.setBackgroundColor(Color.parseColor(color))
    }

    private fun updateHUDText(container: LinearLayout, result: ProfitabilityResult) {
        container.removeAllViews()
        container.addView(TextView(context).apply {
            text = when {
                result.isPreview && result.status == TripStatus.RENTABLE -> "PREVIEW"
                result.status == TripStatus.RENTABLE -> "REAL"
                result.status == TripStatus.NOT_RENTABLE_PICKUP -> "REAL LEJOS"
                else -> "REAL NO RENT."
            }
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })
        container.addView(TextView(context).apply {
            text = String.format("%.2f", result.expectedUsdPerKm) + " $/km"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
        })
        container.addView(TextView(context).apply {
            text = formatProfitLine(result)
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
        })

        val footerContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        footerContainer.addView(TextView(context).apply {
            text = String.format("%.1f", result.pickupDistanceKm) + " km | "
            setTextColor(Color.WHITE)
            textSize = 10f
        })
        val orangeCircle = View(context).apply {
            val size = (10 * context.resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (4 * context.resources.displayMetrics.density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFA500"))
            }
        }
        footerContainer.addView(orangeCircle)
        footerContainer.addView(TextView(context).apply {
            text = "Total: " + String.format("%.1f", result.totalDistanceKm) + "? km"
            setTextColor(Color.WHITE)
            textSize = 10f
        })
        container.addView(footerContainer)
    }

    private fun removeView(view: View, reason: String) {
        try {
            windowManager.removeViewImmediate(view)
            trace("LIST_OVERLAY_VIEW_REMOVED reason=$reason mode=immediate")
        } catch (immediateError: Exception) {
            try {
                windowManager.removeView(view)
                trace("LIST_OVERLAY_VIEW_REMOVED reason=$reason mode=normal afterImmediate=${immediateError.message}")
            } catch (removeError: Exception) {
                Log.w(TAG, "LIST_OVERLAY_VIEW_REMOVE_FAILED reason=$reason immediate=${immediateError.message} normal=${removeError.message}")
            }
        } finally {
            activeViews.remove(view)
        }
    }

    private fun formatProfitLine(result: ProfitabilityResult): String {
        val label = if (result.trueProfit < 0.0) "Pierde" else "Gana"
        return "$label: $" + String.format("%.2f", kotlin.math.abs(result.trueProfit))
    }

    private fun compactKeys(keys: Collection<String>): String {
        if (keys.isEmpty()) return "[]"
        val visibleKeys = keys.take(5).joinToString(",")
        return if (keys.size > 5) "[$visibleKeys,+${keys.size - 5}]" else "[$visibleKeys]"
    }

    private fun nowMs(): Long {
        return android.os.SystemClock.elapsedRealtime()
    }

    private object TraceText {
        const val CLEAR_START = "LIST_OVERLAY_CLEAR_START"
        const val CLEARED = "LIST_OVERLAYS_CLEARED"
        const val CLEAR_END = "LIST_OVERLAY_CLEAR_END"
    }

    private companion object {
        private const val TAG = "EfiRuntimeTrace"
    }
}
