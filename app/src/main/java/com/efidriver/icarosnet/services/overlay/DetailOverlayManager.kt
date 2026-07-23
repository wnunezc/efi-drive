package com.efidriver.icarosnet.services.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
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
import com.efidriver.icarosnet.vision.RouteLabelOcr
import kotlin.math.max

class DetailOverlayManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val trace: (String) -> Unit
) {
    private var detailOverlay: View? = null

    val hasOverlay: Boolean
        get() = detailOverlay != null

    fun showProfitability(
        result: ProfitabilityResult,
        ocrResult: RouteLabelOcr.OcrResult,
        offerRecommendationPrice: Double?
    ): Boolean {
        remove()

        val displayMetrics = context.resources.displayMetrics
        val overlayWidth = (displayMetrics.widthPixels * 0.60).toInt()
        val overlayHeight = ((if (offerRecommendationPrice != null) 136 else 112) * displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            overlayWidth,
            overlayHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (displayMetrics.widthPixels - overlayWidth) / 2
            y = max((178 * displayMetrics.density).toInt(), 0)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                (14 * displayMetrics.density).toInt(),
                (8 * displayMetrics.density).toInt(),
                (14 * displayMetrics.density).toInt(),
                (8 * displayMetrics.density).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 8 * displayMetrics.density
                setColor(
                    if (result.status == TripStatus.RENTABLE) {
                        Color.parseColor("#E6004D00")
                    } else {
                        Color.parseColor("#E69A1B1B")
                    }
                )
            }
        }

        updateProfitabilityText(container, result, ocrResult, offerRecommendationPrice)

        return try {
            windowManager.addView(container, params)
            detailOverlay = container
            true
        } catch (e: Exception) {
            Log.e(TAG, "DETAIL_PROFITABILITY_OVERLAY_FAILED ${e.message}", e)
            false
        }
    }

    fun showProgress(): Boolean {
        remove()

        val displayMetrics = context.resources.displayMetrics
        val overlayWidth = (displayMetrics.widthPixels * 0.60).toInt()
        val overlayHeight = (72 * displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            overlayWidth,
            overlayHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (displayMetrics.widthPixels - overlayWidth) / 2
            y = max((178 * displayMetrics.density).toInt(), 0)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 8 * displayMetrics.density
                setColor(Color.parseColor("#E6333333"))
            }
        }
        container.addView(TextView(context).apply {
            text = "CALCULANDO..."
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })
        container.addView(TextView(context).apply {
            text = "Leyendo distancia y tiempo"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
        })

        return try {
            windowManager.addView(container, params)
            detailOverlay = container
            trace("DETAIL_PROGRESS_OVERLAY_SHOWN")
            true
        } catch (e: Exception) {
            Log.e(TAG, "DETAIL_PROGRESS_OVERLAY_FAILED ${e.message}", e)
            false
        }
    }

    fun remove() {
        detailOverlay?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (e: Exception) {
                trace("DETAIL_OVERLAY_REMOVE_FAILED message=${e.message}")
            }
        }
        detailOverlay = null
    }

    private fun updateProfitabilityText(
        container: LinearLayout,
        result: ProfitabilityResult,
        ocrResult: RouteLabelOcr.OcrResult,
        offerRecommendationPrice: Double?
    ) {
        val title = when (result.status) {
            TripStatus.RENTABLE -> "RENTABLE REAL"
            TripStatus.NOT_RENTABLE_PICKUP -> "RECOGIDA LEJOS"
            TripStatus.NOT_RENTABLE -> "NO RENTABLE"
        }
        container.addView(TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })
        container.addView(TextView(context).apply {
            text = String.format("%.2f $/km  |  %s", result.expectedUsdPerKm, formatProfitLine(result))
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
        })
        container.addView(TextView(context).apply {
            text = "Tiempo: ${ocrResult.pickup.minutes ?: "-"} + ${ocrResult.destination.minutes ?: "-"} min  |  Total: " +
                String.format("%.1f km", result.totalDistanceKm)
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
        })
        if (offerRecommendationPrice != null) {
            container.addView(TextView(context).apply {
                text = "Ofrecer $" + String.format("%.2f", offerRecommendationPrice) + " para rentable"
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
            })
        }
    }

    private fun formatProfitLine(result: ProfitabilityResult): String {
        val label = if (result.trueProfit < 0.0) "Pierde" else "Gana"
        return "$label: $" + String.format("%.2f", kotlin.math.abs(result.trueProfit))
    }

    private companion object {
        private const val TAG = "EfiTripFlow"
    }
}
