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
        val greenPixelCount: Int,
        val mode: String,
        val pickupCandidates: List<LabelCandidate> = pickupLabel?.let(::listOf) ?: emptyList(),
        val destinationCandidates: List<LabelCandidate> = destinationLabel?.let(::listOf) ?: emptyList()
    ) {
        val routeLabelsVisible: Boolean
            get() = pickupLabel != null && destinationLabel != null
    }

    fun detect(bitmap: Bitmap): DetectionResult {
        detectFast(bitmap)?.let { return it }
        return detectFullResolution(bitmap)
    }

    private fun detectFast(bitmap: Bitmap): DetectionResult? {
        val sample = 2
        val roi = Rect(
            0,
            (bitmap.height * 0.08).toInt(),
            bitmap.width,
            (bitmap.height * 0.56).toInt()
        )
        val sampledWidth = roi.width() / sample
        val sampledHeight = roi.height() / sample
        if (sampledWidth <= 0 || sampledHeight <= 0) return null

        val pixels = IntArray(roi.width() * roi.height())
        bitmap.getPixels(pixels, 0, roi.width(), roi.left, roi.top, roi.width(), roi.height())

        val blueMatches = BooleanArray(sampledWidth * sampledHeight)
        val greenMatches = BooleanArray(sampledWidth * sampledHeight)
        var blueTotal = 0
        var greenTotal = 0

        for (sy in 0 until sampledHeight) {
            val sourceY = sy * sample
            val sourceRow = sourceY * roi.width()
            val sampledRow = sy * sampledWidth
            for (sx in 0 until sampledWidth) {
                val color = pixels[sourceRow + sx * sample]
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                val index = sampledRow + sx
                if (isPickupBlue(red, green, blue)) {
                    blueMatches[index] = true
                    blueTotal++
                }
                if (isDestinationGreen(red, green, blue)) {
                    greenMatches[index] = true
                    greenTotal++
                }
            }
        }

        val sampledRoi = Rect(
            roi.left / sample,
            roi.top / sample,
            roi.right / sample,
            roi.bottom / sample
        )
        val blueMask = ColorMask(sampledWidth, sampledHeight, blueMatches, blueTotal)
        val greenMask = ColorMask(sampledWidth, sampledHeight, greenMatches, greenTotal)
        val pickupCandidates = findLabelCandidates(blueMask, sampledRoi, sample)
            .map { it.scale(sample) }
        val destinationCandidates = findLabelCandidates(greenMask, sampledRoi, sample)
            .map { it.scale(sample) }
        val pickup = pickupCandidates.firstOrNull()
        val destination = destinationCandidates.firstOrNull()

        return if (pickup != null && destination != null) {
            DetectionResult(
                pickupLabel = pickup,
                destinationLabel = destination,
                bluePixelCount = blueTotal * sample * sample,
                greenPixelCount = greenTotal * sample * sample,
                mode = "fast_sample_${sample}x",
                pickupCandidates = pickupCandidates,
                destinationCandidates = destinationCandidates
            )
        } else {
            null
        }
    }

    private fun detectFullResolution(bitmap: Bitmap): DetectionResult {
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

        val pickupCandidates = findLabelCandidates(blueMask, roi)
        val destinationCandidates = findLabelCandidates(greenMask, roi)

        return DetectionResult(
            pickupLabel = pickupCandidates.firstOrNull(),
            destinationLabel = destinationCandidates.firstOrNull(),
            bluePixelCount = blueMask.totalPixels,
            greenPixelCount = greenMask.totalPixels,
            mode = "full_resolution",
            pickupCandidates = pickupCandidates,
            destinationCandidates = destinationCandidates
        )
    }

    private fun LabelCandidate.scale(factor: Int): LabelCandidate {
        return LabelCandidate(
            bounds = Rect(
                bounds.left * factor,
                bounds.top * factor,
                bounds.right * factor,
                bounds.bottom * factor
            ),
            coloredPixels = coloredPixels * factor * factor,
            density = density
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
        return findLabelCandidates(mask, roi).firstOrNull()
    }

    private fun findLabelCandidates(mask: ColorMask, roi: Rect, sample: Int = 1): List<LabelCandidate> {
        return (
            findByConnectedComponents(mask, roi, sample) +
                findBySlidingWindow(mask, roi, sample)
            )
            .distinctBy { "${it.bounds.left}:${it.bounds.top}:${it.bounds.right}:${it.bounds.bottom}" }
            .sortedWith(
                compareByDescending<RouteLabelDetector.LabelCandidate> { it.coloredPixels }
                    .thenByDescending { it.density }
            )
            .take(5)
    }

    private fun findByConnectedComponents(mask: ColorMask, roi: Rect, sample: Int = 1): List<LabelCandidate> {
        val visited = BooleanArray(mask.matches.size)
        val queue = IntArray(mask.matches.size)
        val candidates = mutableListOf<LabelCandidate>()
        val minPixels = 1_200 / (sample * sample)
        val minWidth = 70 / sample
        val maxWidth = 280 / sample
        val minHeight = 45 / sample
        val maxHeight = 170 / sample
        val padX = (10 / sample).coerceAtLeast(2)
        val padY = (10 / sample).coerceAtLeast(2)
        val padRight = (11 / sample).coerceAtLeast(3)
        val padBottom = (11 / sample).coerceAtLeast(3)

        for (start in mask.matches.indices) {
            if (!mask.matches[start] || visited[start]) continue

            val component = floodFill(mask, visited, queue, start)
            val boundsWidth = component.maxX - component.minX + 1
            val boundsHeight = component.maxY - component.minY + 1
            val area = boundsWidth * boundsHeight
            if (area <= 0) continue

            val density = component.pixels.toDouble() / area.toDouble()
            if (
                component.pixels >= minPixels &&
                density >= 0.35 &&
                boundsWidth in minWidth..maxWidth &&
                boundsHeight in minHeight..maxHeight
            ) {
                val padded = Rect(
                    roi.left + component.minX - padX,
                    roi.top + component.minY - padY,
                    roi.left + component.maxX + padRight,
                    roi.top + component.maxY + padBottom
                )
                padded.intersect(roi)
                val candidate = LabelCandidate(
                    bounds = padded,
                    coloredPixels = component.pixels,
                    density = density
                )
                candidates += candidate
            }
        }

        return candidates
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

    private fun findBySlidingWindow(mask: ColorMask, roi: Rect, sample: Int = 1): List<LabelCandidate> {
        val integral = buildIntegralMask(mask)
        val candidates = mutableListOf<LabelCandidate>()
        val widths = intArrayOf(100, 130, 160, 190, 220).map { (it / sample).coerceAtLeast(20) }
        val heights = intArrayOf(64, 82, 100, 118).map { (it / sample).coerceAtLeast(16) }
        val step = (12 / sample).coerceAtLeast(4)
        val minColored = (1_400 / (sample * sample)).coerceAtLeast(120)

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
                        if (colored >= minColored && density >= 0.28) {
                            val candidate = LabelCandidate(
                                bounds = Rect(roi.left + x, roi.top + y, roi.left + x + width, roi.top + y + height),
                                coloredPixels = colored,
                                density = density
                            )
                            if (candidates.none { overlapsStrongly(it.bounds, candidate.bounds) }) {
                                candidates += candidate
                            }
                        }
                        x += step
                    }
                }
                y += step
            }
        }

        return candidates
    }

    private fun overlapsStrongly(a: Rect, b: Rect): Boolean {
        val intersection = Rect(a)
        if (!intersection.intersect(b)) return false
        val smallerArea = minOf(a.width() * a.height(), b.width() * b.height())
        if (smallerArea <= 0) return false
        return intersection.width() * intersection.height() >= smallerArea * 0.65
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
