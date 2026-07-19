package com.efidriver.icarosnet.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect

object RouteLabelDetector {

    data class LabelCandidate(
        val bounds: Rect,
        val coloredPixels: Int,
        val density: Double
    )

    data class DetectionResult(
        val pickupLabel: LabelCandidate?,
        val destinationLabel: LabelCandidate?,
        val bluePixelCount: Int,
        val greenPixelCount: Int
    ) {
        val routeLabelsVisible: Boolean
            get() = pickupLabel != null && destinationLabel != null
    }

    fun detect(bitmap: Bitmap): DetectionResult {
        val roi = Rect(
            0,
            (bitmap.height * 0.08).toInt(),
            bitmap.width,
            (bitmap.height * 0.56).toInt()
        )

        val blueMask = buildIntegralMask(bitmap, roi, ::isPickupBlue)
        val greenMask = buildIntegralMask(bitmap, roi, ::isDestinationGreen)

        return DetectionResult(
            pickupLabel = findLabelCandidate(blueMask, roi),
            destinationLabel = findLabelCandidate(greenMask, roi),
            bluePixelCount = blueMask.totalPixels,
            greenPixelCount = greenMask.totalPixels
        )
    }

    private data class IntegralMask(
        val width: Int,
        val height: Int,
        val values: IntArray,
        val totalPixels: Int
    )

    private fun buildIntegralMask(
        bitmap: Bitmap,
        roi: Rect,
        predicate: (Int, Int, Int) -> Boolean
    ): IntegralMask {
        val width = roi.width()
        val height = roi.height()
        val values = IntArray((width + 1) * (height + 1))
        var total = 0

        for (y in 1..height) {
            var rowTotal = 0
            for (x in 1..width) {
                val color = bitmap.getPixel(roi.left + x - 1, roi.top + y - 1)
                val isMatch = predicate(Color.red(color), Color.green(color), Color.blue(color))
                if (isMatch) {
                    rowTotal++
                    total++
                }
                val index = y * (width + 1) + x
                values[index] = values[index - (width + 1)] + rowTotal
            }
        }

        return IntegralMask(width, height, values, total)
    }

    private fun findLabelCandidate(mask: IntegralMask, roi: Rect): LabelCandidate? {
        var best: LabelCandidate? = null
        val widths = intArrayOf(100, 130, 160, 190, 220)
        val heights = intArrayOf(64, 82, 100, 118)
        val step = 12

        for (height in heights) {
            if (height >= mask.height) continue
            var y = 0
            while (y + height <= mask.height) {
                for (width in widths) {
                    if (width >= mask.width) continue
                    var x = 0
                    while (x + width <= mask.width) {
                        val colored = sum(mask, x, y, width, height)
                        val density = colored.toDouble() / (width * height).toDouble()
                        if (colored >= 1_400 && density >= 0.28) {
                            val candidate = LabelCandidate(
                                bounds = Rect(roi.left + x, roi.top + y, roi.left + x + width, roi.top + y + height),
                                coloredPixels = colored,
                                density = density
                            )
                            if (best == null || candidate.coloredPixels > best.coloredPixels) {
                                best = candidate
                            }
                        }
                        x += step
                    }
                }
                y += step
            }
        }

        return best
    }

    private fun sum(mask: IntegralMask, x: Int, y: Int, width: Int, height: Int): Int {
        val stride = mask.width + 1
        val x2 = x + width
        val y2 = y + height
        return mask.values[y2 * stride + x2] -
            mask.values[y * stride + x2] -
            mask.values[y2 * stride + x] +
            mask.values[y * stride + x]
    }

    private fun isPickupBlue(red: Int, green: Int, blue: Int): Boolean {
        return blue >= 150 && green >= 95 && red <= 115 && blue - red >= 70
    }

    private fun isDestinationGreen(red: Int, green: Int, blue: Int): Boolean {
        return green >= 115 && red <= 95 && blue <= 115 && green - red >= 50
    }
}
