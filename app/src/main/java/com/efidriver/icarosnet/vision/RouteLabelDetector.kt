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
        val width = roi.width()
        val height = roi.height()
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, roi.left, roi.top, width, height)

        val blueMask = buildMask(pixels, width, height, ::isPickupBlue)
        val greenMask = buildMask(pixels, width, height, ::isDestinationGreen)

        return DetectionResult(
            pickupLabel = findLabelCandidate(blueMask, roi),
            destinationLabel = findLabelCandidate(greenMask, roi),
            bluePixelCount = blueMask.totalPixels,
            greenPixelCount = greenMask.totalPixels
        )
    }

    private data class ColorMask(
        val width: Int,
        val height: Int,
        val matches: BooleanArray,
        val totalPixels: Int
    )

    private data class Component(
        var minX: Int,
        var minY: Int,
        var maxX: Int,
        var maxY: Int,
        var pixels: Int
    )

    private fun buildMask(
        pixels: IntArray,
        width: Int,
        height: Int,
        predicate: (Int, Int, Int) -> Boolean
    ): ColorMask {
        val matches = BooleanArray(width * height)
        var total = 0

        for (index in pixels.indices) {
            val color = pixels[index]
            if (predicate(Color.red(color), Color.green(color), Color.blue(color))) {
                matches[index] = true
                total++
            }
        }

        return ColorMask(width, height, matches, total)
    }

    private fun findLabelCandidate(mask: ColorMask, roi: Rect): LabelCandidate? {
        return findByConnectedComponents(mask, roi) ?: findBySlidingWindow(mask, roi)
    }

    private fun findByConnectedComponents(mask: ColorMask, roi: Rect): LabelCandidate? {
        val visited = BooleanArray(mask.matches.size)
        val queue = IntArray(mask.matches.size)
        var best: LabelCandidate? = null

        for (start in mask.matches.indices) {
            if (!mask.matches[start] || visited[start]) continue

            val component = floodFill(mask, visited, queue, start)
            val boundsWidth = component.maxX - component.minX + 1
            val boundsHeight = component.maxY - component.minY + 1
            val area = boundsWidth * boundsHeight
            if (area <= 0) continue

            val density = component.pixels.toDouble() / area.toDouble()
            if (
                component.pixels >= 1_200 &&
                density >= 0.35 &&
                boundsWidth in 70..280 &&
                boundsHeight in 45..170
            ) {
                val padded = Rect(
                    roi.left + component.minX - 10,
                    roi.top + component.minY - 10,
                    roi.left + component.maxX + 11,
                    roi.top + component.maxY + 11
                )
                padded.intersect(roi)
                val candidate = LabelCandidate(
                    bounds = padded,
                    coloredPixels = component.pixels,
                    density = density
                )
                if (best == null || candidate.coloredPixels > best.coloredPixels) {
                    best = candidate
                }
            }
        }

        return best
    }

    private fun floodFill(
        mask: ColorMask,
        visited: BooleanArray,
        queue: IntArray,
        start: Int
    ): Component {
        var head = 0
        var tail = 0
        queue[tail++] = start
        visited[start] = true

        val startX = start % mask.width
        val startY = start / mask.width
        val component = Component(startX, startY, startX, startY, 0)

        while (head < tail) {
            val current = queue[head++]
            val x = current % mask.width
            val y = current / mask.width
            component.pixels++
            if (x < component.minX) component.minX = x
            if (y < component.minY) component.minY = y
            if (x > component.maxX) component.maxX = x
            if (y > component.maxY) component.maxY = y

            val left = current - 1
            val right = current + 1
            val up = current - mask.width
            val down = current + mask.width

            if (x > 0 && mask.matches[left] && !visited[left]) {
                visited[left] = true
                queue[tail++] = left
            }
            if (x < mask.width - 1 && mask.matches[right] && !visited[right]) {
                visited[right] = true
                queue[tail++] = right
            }
            if (y > 0 && mask.matches[up] && !visited[up]) {
                visited[up] = true
                queue[tail++] = up
            }
            if (y < mask.height - 1 && mask.matches[down] && !visited[down]) {
                visited[down] = true
                queue[tail++] = down
            }
        }

        return component
    }

    private fun findBySlidingWindow(mask: ColorMask, roi: Rect): LabelCandidate? {
        val integral = buildIntegralMask(mask)
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
                        val colored = sum(integral, mask.width, x, y, width, height)
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

    private fun buildIntegralMask(mask: ColorMask): IntArray {
        val values = IntArray((mask.width + 1) * (mask.height + 1))
        for (y in 1..mask.height) {
            var rowTotal = 0
            for (x in 1..mask.width) {
                if (mask.matches[(y - 1) * mask.width + (x - 1)]) {
                    rowTotal++
                }
                val index = y * (mask.width + 1) + x
                values[index] = values[index - (mask.width + 1)] + rowTotal
            }
        }
        return values
    }

    private fun sum(values: IntArray, maskWidth: Int, x: Int, y: Int, width: Int, height: Int): Int {
        val stride = maskWidth + 1
        val x2 = x + width
        val y2 = y + height
        return values[y2 * stride + x2] -
            values[y * stride + x2] -
            values[y2 * stride + x] +
            values[y * stride + x]
    }

    private fun isPickupBlue(red: Int, green: Int, blue: Int): Boolean {
        return blue >= 150 && green >= 95 && red <= 115 && blue - red >= 70
    }

    private fun isDestinationGreen(red: Int, green: Int, blue: Int): Boolean {
        return green >= 115 && red <= 95 && blue <= 115 && green - red >= 50
    }
}
