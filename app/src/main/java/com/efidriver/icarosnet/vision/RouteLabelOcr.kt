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
        val destination: LabelMetrics
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
        val pickupBounds = detection.pickupLabel?.bounds
        val destinationBounds = detection.destinationLabel?.bounds
        if (pickupBounds == null || destinationBounds == null) {
            onFailure(IllegalArgumentException("route_label_bounds_missing"))
            return
        }

        val pickupBitmap = cropForOcr(source, pickupBounds)
        val destinationBitmap = cropForOcr(source, destinationBounds)
        val pending = AtomicInteger(2)
        var pickupMetrics: LabelMetrics? = null
        var destinationMetrics: LabelMetrics? = null
        var failed = false

        fun finishIfReady() {
            if (!failed && pending.decrementAndGet() == 0) {
                onSuccess(
                    OcrResult(
                        pickup = requireNotNull(pickupMetrics),
                        destination = requireNotNull(destinationMetrics)
                    )
                )
            }
        }

        recognizer.process(InputImage.fromBitmap(pickupBitmap, 0))
            .addOnSuccessListener { text ->
                pickupBitmap.recycle()
                pickupMetrics = parseLabelMetrics(text.text)
                finishIfReady()
            }
            .addOnFailureListener { exception ->
                pickupBitmap.recycle()
                if (!failed) {
                    failed = true
                    onFailure(exception)
                }
            }

        recognizer.process(InputImage.fromBitmap(destinationBitmap, 0))
            .addOnSuccessListener { text ->
                destinationBitmap.recycle()
                destinationMetrics = parseLabelMetrics(text.text)
                finishIfReady()
            }
            .addOnFailureListener { exception ->
                destinationBitmap.recycle()
                if (!failed) {
                    failed = true
                    onFailure(exception)
                }
            }
    }

    private fun cropForOcr(source: Bitmap, bounds: Rect): Bitmap {
        val expanded = Rect(bounds)
        expanded.inset(-18, -12)
        expanded.intersect(0, 0, source.width, source.height)

        val crop = Bitmap.createBitmap(source, expanded.left, expanded.top, expanded.width(), expanded.height())
        val scaled = Bitmap.createScaledBitmap(crop, crop.width * 3, crop.height * 3, false)
        crop.recycle()

        val output = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until scaled.height) {
            for (x in 0 until scaled.width) {
                val color = scaled.getPixel(x, y)
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                val isWhiteText = red >= 210 && green >= 210 && blue >= 210
                output.setPixel(x, y, if (isWhiteText) Color.BLACK else Color.WHITE)
            }
        }
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
            .replace(Regex("\\s+"), " ")
            .trim()

        val minutes = Regex("""(\d{1,3})\s*m(?:in)?""")
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val distanceMatch = Regex("""(\d{1,4}(?:[,.]\d{1,2})?)\s*(km|k[mn]|metro|metros|m)\b""")
            .findAll(normalized)
            .lastOrNull()

        val distanceValue = distanceMatch
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()

        val distanceUnit = distanceMatch?.groupValues?.getOrNull(2)
        val distanceKm = when {
            distanceValue == null -> null
            distanceUnit == "km" || distanceUnit == "kn" -> distanceValue
            distanceUnit == "metro" || distanceUnit == "metros" || distanceUnit == "m" -> distanceValue / 1000.0
            else -> null
        }

        return LabelMetrics(
            minutes = minutes,
            distanceKm = distanceKm,
            rawText = rawText.replace('\n', '|')
        )
    }
}
