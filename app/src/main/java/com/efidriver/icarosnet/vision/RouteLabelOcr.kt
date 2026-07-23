package com.efidriver.icarosnet.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

object RouteLabelOcr {

    private const val MAX_PLAUSIBLE_KM_PER_MINUTE = 2.0

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    data class LabelMetrics(
        val minutes: Int?,
        val distanceKm: Double?,
        val rawText: String
    ) {
        val complete: Boolean
            get() = minutes != null && distanceKm != null
    }

    data class OcrResult(
        val pickup: LabelMetrics,
        val destination: LabelMetrics,
        val pickupCandidateIndex: Int = 0,
        val destinationCandidateIndex: Int = 0
    ) {
        val complete: Boolean
            get() = pickup.complete && destination.complete
    }

    fun recognize(
        source: Bitmap,
        detection: RouteLabelDetector.DetectionResult,
        onSuccess: (OcrResult) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val pickupCandidates = detection.pickupCandidates.ifEmpty {
            detection.pickupLabel?.let(::listOf) ?: emptyList()
        }
        val destinationCandidates = detection.destinationCandidates.ifEmpty {
            detection.destinationLabel?.let(::listOf) ?: emptyList()
        }
        if (pickupCandidates.isEmpty() || destinationCandidates.isEmpty()) {
            onFailure(IllegalArgumentException("route_label_bounds_missing"))
            return
        }

        val pending = AtomicInteger(2)
        var pickupMetrics: LabelMetrics? = null
        var destinationMetrics: LabelMetrics? = null
        var failed = false
        var initialPairFinished = false

        fun finishIfReady() {
            if (!failed && pending.decrementAndGet() == 0) {
                initialPairFinished = true
                finishWithAlternates(
                    source = source,
                    pickupCandidates = pickupCandidates,
                    destinationCandidates = destinationCandidates,
                    initialPickup = requireNotNull(pickupMetrics),
                    initialDestination = requireNotNull(destinationMetrics),
                    onSuccess = onSuccess,
                    onFailure = onFailure
                )
            }
        }

        recognizeCandidate(source, pickupCandidates[0].bounds,
            onSuccess = { metrics ->
                pickupMetrics = metrics
                finishIfReady()
            },
            onFailure = { exception ->
                if (!failed && !initialPairFinished) {
                    failed = true
                    onFailure(exception)
                }
            }
        )

        recognizeCandidate(source, destinationCandidates[0].bounds,
            onSuccess = { metrics ->
                destinationMetrics = metrics
                finishIfReady()
            },
            onFailure = { exception ->
                if (!failed && !initialPairFinished) {
                    failed = true
                    onFailure(exception)
                }
            }
        )
    }

    private fun finishWithAlternates(
        source: Bitmap,
        pickupCandidates: List<RouteLabelDetector.LabelCandidate>,
        destinationCandidates: List<RouteLabelDetector.LabelCandidate>,
        initialPickup: LabelMetrics,
        initialDestination: LabelMetrics,
        onSuccess: (OcrResult) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (initialPickup.complete && initialDestination.complete) {
            onSuccess(OcrResult(initialPickup, initialDestination))
            return
        }

        findCompleteCandidate(
            source = source,
            candidates = pickupCandidates,
            startIndex = 1,
            accepted = initialPickup.takeIf { it.complete }
        ) { pickupResult ->
            if (pickupResult == null) {
                findCompleteCandidate(
                    source = source,
                    candidates = destinationCandidates,
                    startIndex = 1,
                    accepted = initialDestination.takeIf { it.complete }
                ) { destinationResult ->
                    onSuccess(
                        OcrResult(
                            pickup = initialPickup,
                            destination = destinationResult?.metrics ?: initialDestination,
                            pickupCandidateIndex = 0,
                            destinationCandidateIndex = destinationResult?.index ?: 0
                        )
                    )
                }
                return@findCompleteCandidate
            }

            findCompleteCandidate(
                source = source,
                candidates = destinationCandidates,
                startIndex = 1,
                accepted = initialDestination.takeIf { it.complete }
            ) { destinationResult ->
                onSuccess(
                    OcrResult(
                        pickup = pickupResult.metrics,
                        destination = destinationResult?.metrics ?: initialDestination,
                        pickupCandidateIndex = pickupResult.index,
                        destinationCandidateIndex = destinationResult?.index ?: 0
                    )
                )
            }
        }
    }

    private data class CandidateOcr(
        val index: Int,
        val metrics: LabelMetrics
    )

    private data class CropVariant(
        val paddingX: Int,
        val paddingY: Int,
        val scale: Int,
        val whiteThreshold: Int
    )

    private val cropVariants = listOf(
        CropVariant(paddingX = 18, paddingY = 12, scale = 3, whiteThreshold = 210),
        CropVariant(paddingX = 28, paddingY = 18, scale = 3, whiteThreshold = 200),
        CropVariant(paddingX = 36, paddingY = 24, scale = 4, whiteThreshold = 195)
    )

    private fun findCompleteCandidate(
        source: Bitmap,
        candidates: List<RouteLabelDetector.LabelCandidate>,
        startIndex: Int,
        accepted: LabelMetrics?,
        onComplete: (CandidateOcr?) -> Unit
    ) {
        if (accepted != null) {
            onComplete(CandidateOcr(0, accepted))
            return
        }

        fun tryIndex(index: Int) {
            if (index >= candidates.size) {
                onComplete(null)
                return
            }

            recognizeCandidate(
                source = source,
                bounds = candidates[index].bounds,
                onSuccess = { metrics ->
                    if (metrics.complete) {
                        onComplete(CandidateOcr(index, metrics))
                    } else {
                        tryIndex(index + 1)
                    }
                },
                onFailure = {
                    tryIndex(index + 1)
                }
            )
        }

        tryIndex(startIndex)
    }

    private fun recognizeCandidate(
        source: Bitmap,
        bounds: Rect,
        onSuccess: (LabelMetrics) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        var bestMetrics: LabelMetrics? = null
        var lastFailure: Exception? = null

        fun tryVariant(index: Int) {
            if (index >= cropVariants.size) {
                val best = bestMetrics
                if (best != null) {
                    onSuccess(best)
                } else {
                    onFailure(lastFailure ?: IllegalStateException("ocr_crop_variants_failed"))
                }
                return
            }

            val bitmap = cropForOcr(source, bounds, cropVariants[index])
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                bitmap.recycle()
                    val metrics = parseLabelMetrics(text.text)
                    if (metrics.score > (bestMetrics?.score ?: -1)) {
                        bestMetrics = metrics
                    }
                    if (metrics.complete) {
                        onSuccess(metrics)
                    } else {
                        tryVariant(index + 1)
                    }
            }
            .addOnFailureListener { exception ->
                bitmap.recycle()
                    lastFailure = exception
                    tryVariant(index + 1)
            }
        }

        tryVariant(0)
    }

    private fun cropForOcr(source: Bitmap, bounds: Rect, variant: CropVariant): Bitmap {
        val expanded = Rect(bounds)
        expanded.inset(-variant.paddingX, -variant.paddingY)
        expanded.intersect(0, 0, source.width, source.height)

        val crop = Bitmap.createBitmap(source, expanded.left, expanded.top, expanded.width(), expanded.height())
        val scaled = Bitmap.createScaledBitmap(crop, crop.width * variant.scale, crop.height * variant.scale, false)
        crop.recycle()

        val output = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        for (index in pixels.indices) {
            val color = pixels[index]
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            val isWhiteText = red >= variant.whiteThreshold &&
                green >= variant.whiteThreshold &&
                blue >= variant.whiteThreshold
            pixels[index] = if (isWhiteText) Color.BLACK else Color.WHITE
        }
        output.setPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        scaled.recycle()
        return output
    }

    private fun parseLabelMetrics(rawText: String): LabelMetrics {
        val normalized = rawText
            .lowercase(Locale.US)
            .replace('\n', ' ')
            .replace("í", "i")
            .replace("ı", "i")
            .replace("rn", "m")
            .replace("mim", "min")
            .replace("nin", "min")
            .replace(Regex("\\bk\\s*m\\b"), "km")
            .replace(Regex("\\bk\\s*mn\\b"), "kmn")
            .replace(Regex("\\s+"), " ")
            .trim()

        val minutes = Regex("""(\d{1,3})\s*(?:min|mn|mim|m)\b""")
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val distanceMatch = Regex("""(\d{1,4}(?:[,.]\d{1,2})?)\s*(kmn|kmm|knm|km|kn|ki|metro|metros|mts|mt|m)\b""")
            .findAll(normalized)
            .filterNot { match ->
                val value = match.groupValues.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
                val unit = match.groupValues.getOrNull(2)
                unit.isMeterUnit() && value != null && value < 50.0
            }
            .lastOrNull()

        val distanceValue = distanceMatch
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()

        val distanceUnit = distanceMatch?.groupValues?.getOrNull(2)
        val distanceText = distanceMatch?.groupValues?.getOrNull(1)
        val fuzzyKmUnit = distanceUnit == "kn" ||
            distanceUnit == "ki" ||
            distanceUnit == "kmn" ||
            distanceUnit == "kmm" ||
            distanceUnit == "knm"
        val distanceKm = when {
            distanceValue == null -> null
            fuzzyKmUnit && distanceText?.contains(Regex("[,.]")) != true -> null
            distanceUnit == "km" || fuzzyKmUnit -> distanceValue
            distanceUnit.isMeterUnit() -> distanceValue / 1000.0
            else -> null
        }

        val plausibleDistanceKm = when {
            minutes != null && distanceKm != null && minutes > 0 &&
                distanceKm / minutes > MAX_PLAUSIBLE_KM_PER_MINUTE -> null
            else -> distanceKm
        }

        val fallbackDistanceKm = plausibleDistanceKm ?: parseUnitlessDistanceKm(normalized, minutes)

        return LabelMetrics(
            minutes = minutes,
            distanceKm = fallbackDistanceKm,
            rawText = rawText.replace('\n', '|')
        )
    }

    private val LabelMetrics.score: Int
        get() = (if (minutes != null) 1 else 0) + (if (distanceKm != null) 1 else 0)

    private fun parseUnitlessDistanceKm(normalized: String, minutes: Int?): Double? {
        if (minutes == null) return null
        val numbers = Regex("""\d{1,4}(?:[,.]\d{1,2})?""")
            .findAll(normalized)
            .mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }
            .toList()
        val decimalDistance = numbers
            .firstOrNull { value -> value != minutes.toDouble() && value > 0.0 && value <= 80.0 && value % 1.0 != 0.0 }
        return decimalDistance?.takeIf { it / minutes <= MAX_PLAUSIBLE_KM_PER_MINUTE }
    }

    private fun String?.isMeterUnit(): Boolean =
        this == "metro" || this == "metros" || this == "mts" || this == "mt" || this == "m"
}
